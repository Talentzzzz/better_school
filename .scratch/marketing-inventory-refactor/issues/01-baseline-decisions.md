# 票 01 · 口径基线（charting 阶段 grill 定）

Type: grilling
Status: resolved
Blocked by: —

## Question

库存扣减重构的目标形状是什么：表怎么收、明细加什么、合并由谁触发、并发安全靠什么。

## Answer

### 1 目的地

只出 spec + 拆票，不写业务代码。落地另起 effort（`.scratch/marketing-inventory-refactor-impl/`）。

### 2 表：两套 quota 并存

| 现在 | 之后 |
|---|---|
| `physical_inventory` / `physical_inventory_detail` | **删除**（第二本出入库账） |
| `physical_marketing_quota` / `_bucket` / `_detail` | **保留**（实物：`surplus → waiting → issued`） |
| `virtual_inventory` | → `virtual_inventory_quota`（虚拟：`surplus → issued`） |
| `virtual_inventory_bucket` | → `virtual_inventory_quota_bucket` |
| `virtual_inventory_detail` | → `virtual_inventory_quota_detail` |

- 「所有库存都是 quota」= 两段都用同一套 quota 模型（Redis 桶 + 明细 + 合并），不是同一张表。
- **`confirmIssued` 只对实物**：只做 `waiting_qty → issued_qty`，不再动 `surplus_qty`（合并那步已经扣过，再扣就是双扣），也不再写出库行。
- 虚拟段没有兑换确认，`issued_qty` 在合并时直接加。
- 出库的单品追溯靠 `draw_record` + `quota.issued_qty`（总数）。`physical_inventory_detail` 的 OUT 行今天**没有任何生产读路径**。

### 3 明细

- 两套 `_detail` 都加 `biz_type` / `biz_id`：
  - 虚拟：`UNIQUE (biz_type, biz_id)`
  - 实物：另加 `type`（`1 = 预扣结转`、`2 = 兑换确认`），`UNIQUE (type, biz_type, biz_id)`——抽奖行与兑换行共用 `recordId`，只用两列会撞键。
- 撞键 = 这条流转已落过 → 按成功回放 + 释放本次 Redis 扣减（沿用现有 catch-and-release 分支）。
- 顺带修掉既有 bug：预扣在 `draw_record` 事务之外，同一 token 双击会让两个请求各扣一次 Redis、各插一条明细，另一份没人退。虚拟段同病。
- `biz_id` 从抽奖域穿透进 `IInventoryDeductPort → IInventoryService → IInventoryRepository`（破坏性签名变更）。

### 4 触发：两段共用一套

- 扣减 Lua：`DECRBY` + `SET bs:inventory:merge:<segment>:<invId>:<bucketId> 1 PX 1000 NX`，返回「扣没扣成 + 要不要发消息」。去重放 Redis，不放 DB——DB 唯一键会让同一桶同一秒的并发扣减在重复键检查上排队。
- 拿到「要发」才在**同一个事务**里插 detail + 插 outbox；消息带 `segment`（`quota` / `virtual`）+ `invId` + `bucketId`。
- 延迟级别 1（**≈1.0–1.1s**；`Message.setDelayTimeLevel` + 5 参 `asyncSend`，见票 02）。重投不保留这个延迟（首次重投 10s 后）。
- **保留 30–60s 的兜底扫描**：延迟消息是延迟优化，不是唯一唤醒源。
- 去重语义：**throttle（固定窗口，从第一笔算）**，不是 debounce。
- **兑换路径（实物 `type=2`）不走 SETNX**：低频，每条都发；走去重会被窗口吞掉、押到兜底。

### 5 合并：时间格 + 最老一格 + affected 判重（两段共用骨架）

分格键用 `create_time`（插入时 DB 写的 `NOW()`，秒精度），不新增 token 列。

```sql
-- 单次唤醒内循环，每次一个事务、一格
SELECT MIN(create_time) FROM <seg>_detail
 WHERE inv_id = ? AND bucket_id = ? AND state = 0
   AND create_time < NOW();                              -- 当前这一秒还在长，不碰

-- 实物按 type 聚合（两行）；虚拟只有一条流转，直接 SUM
SELECT type, SUM(quantity), COUNT(*) FROM physical_marketing_quota_detail
 WHERE inv_id = ? AND bucket_id = ? AND state = 0 AND create_time = ?t0
 GROUP BY type;

UPDATE <seg>_detail SET state = 1, update_time = NOW()
 WHERE inv_id = ? AND bucket_id = ? AND state = 0 AND create_time = ?t0;
-- affected 必须等于 cnt 之和；否则整笔回滚

UPDATE <seg>_bucket SET redis_qty = redis_qty - ?s1
 WHERE inv_id = ? AND bucket_id = ? AND redis_qty >= ?s1;

-- 实物：type=1 进 waiting，type=2 从 waiting 转 issued（后者只有实物有）
UPDATE physical_marketing_quota
   SET waiting_qty = waiting_qty + ?s1, surplus_qty = IF(surplus_qty < 0, surplus_qty, surplus_qty - ?s1)
 WHERE inv_id = ?;
UPDATE physical_marketing_quota
   SET waiting_qty = waiting_qty - ?s2, issued_qty = issued_qty + ?s2
 WHERE inv_id = ? AND waiting_qty >= ?s2;

-- 虚拟：直接进 issued
UPDATE virtual_inventory_quota
   SET issued_qty = issued_qty + ?s1, surplus_qty = IF(surplus_qty < 0, surplus_qty, surplus_qty - ?s1)
 WHERE inv_id = ?;
```

四条约束：

1. **`create_time < NOW()`**：当前这一秒还在收新行，合它会让 `affected` 与 `cnt` 对不上；过完的秒不会再长新行。
2. **只合最老的一格**：`MIN` 保证实物 `type=1` 永远不落到 `type=2` 后面；丢消息、乱序、晚提交都靠它自愈。
3. **`affected == cnt`**：并发消费者的去重。票 02 查实同 key 的两条消息可以并行处理；先翻的赢，后到的对不上、整笔回滚。这一格已封口，cnt 是精确的。
4. **翻转先于结算**：抢不到就什么都不做；结算失败则整笔回滚，翻转一并退掉。

一次唤醒循环合多格（上限 10，每格一个事务）：只合一格的话，丢一条消息就永久落后一格。

被否掉的替代：id 清单翻转（要把几百行拉回应用）、不带秒边界的 `WHERE state=0`（快照读 vs 当前读）、`id <= MAX(id)`（分配序 ≠ 提交序）、认领态/批次令牌（每行多写一次）、分布式锁（挡不住并发 insert，且让 Redis 成为合并的前置）。

### 6 逻辑收口

- 删掉的是**出入库那套逻辑**（`physical_inventory*`），两套 quota 逻辑都在。
- `preDeductWithFallback` 退化成「主档扣不出 → 换个 inv_id 再扣」。
- 合并骨架本来就是共用一套（`InventoryMergeServiceImpl` 挂两个目标），保持。
- 对账：实物 `waiting + issued == merged`，虚拟 `issued == merged`；Redis 恒等式只算 `type = 1`。
- Redis key：`bs:inventory:quota:bucket:`（实物）/ `bs:inventory:virtual:quota:bucket:`（虚拟）。

### 7 本次不做

分布式锁、认领态（`state=2` 瞬态）、`FOR UPDATE`、DB 唯一键去重、入库 / 出库流水表。
