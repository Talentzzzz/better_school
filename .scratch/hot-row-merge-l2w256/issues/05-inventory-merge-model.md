# 05 · 库存扣减去掉 Redis 桶后的等价模型

Type: grilling
Status: resolved
Blocked by: —

## Question

目标形态照抄参考工程的 `StockRepository.deductMerged`：**一个事务 = 插一批明细（唯一键裁决幂等）+ 一条 `UPDATE … SET qty = qty - N WHERE id = ? AND qty >= N`**，调用方在进程内等这次提交。现在的形态是**两阶段**（Redis 桶先扣出余量 → 明细 → 后台按时间格翻进台账），要逐项决定哪些概念留、哪些废：

1. **桶（bucket）废不废**：`inventory_ledger` 下挂 `inventory_bucket` 多行，`preDeduct` 逐个桶试扣。合并器的分片已经承担了「把同一 inv 的并发收成一条 UPDATE」，桶还有存在意义吗？废除后 `bucket_id` 列（明细/台账/`queryDeductBucketId`）怎么处置——删列、还是保留恒 0 只做历史兼容？
2. **台账三列语义**：实物段 `surplus → waiting → issued`、虚拟段 `surplus → issued`。预扣在合并事务里直接改成 `surplus -= N, waiting += N`（实物）/ `surplus -= N, issued += N`（虚拟）？还是先把 `surplus -= N`、`waiting` 交给后面的确认动作？
3. **扣不动的判据与拆批**：参考工程是「`affected = 0` → 售罄短路（一次 SELECT 比最小请求量）→ 仍可能不够则二分拆批 → 单条仍不够才判 INSUFFICIENT」。库存段沿用这套，还是简化成「整批不够就整批失败」（抽奖单笔 qty=1，批内 qty 相同，二分基本用不上）？**给出取舍**。
4. **失败语义的三种出口**：`INSUFFICIENT`（明确没扣）/ `TIMEOUT_UNKNOWN`（大概率会扣，对外必须说「处理中」）/ `REJECTED`（背压）。抽奖主链路现在拿到 `false` 就改发兜底档——新语义下「处理中」这个态在抽奖编排里怎么落（等第二次查询？还是把等待上限设到绝不超时）？
5. **明细表的新职责**：去掉 `state`（未合并/已合并）后，明细只剩「幂等键 + 审计」。`uk_biz_type (type, biz_type, biz_id)` 是否原样保留、`type=2`（兑换确认）是否还走明细。
6. **存量收口的一句话**：Redis 桶里还剩的余量与台账 `surplus` 对不上时，切换那一刻以谁为准（建议以台账为准并一次性清桶，差额记账）。

答案要能直接改成 DDL 变更清单 + 一段 `deductMerged` 的伪代码。

## Answer

（2026-10-22 定）

**一句话：从「两阶段（Redis 桶预扣 → 明细 → 时间格翻账）」变成「一次合并事务（批量插明细 + 一条 UPDATE 台账）」，桶这一层整层废除。**

### 1. 落库事务（每批一个）

```java
// 分片键 = (segment, invId)：同一 inv 恒落同一分片，批内只碰一行
BEGIN
  INSERT IGNORE INTO <segment>_detail (inv_id, quantity, biz_type, biz_id) VALUES (...),(...);
  UPDATE <segment>_ledger
     SET surplus_qty = surplus_qty - N,
         waiting_qty = waiting_qty + N            -- 实物段
         -- issued_qty = issued_qty + N          -- 虚拟段直接进 issued
   WHERE inv_id = ? AND surplus_qty >= N;
COMMIT
```

- **扣不动（affected = 0）时：扣不动再探再切**。`SELECT surplus_qty` → `k = min(剩余, 批内笔数)`；`k > 0` 则 `UPDATE` 扣 `k`，**前 k 笔回成功、其余回 `INSUFFICIENT`**；`k = 0` 整批 `INSUFFICIENT`。正常路径只有一条 UPDATE，临界点最多 2 次事务，不误杀、不二分。
- **重放**：整批撞 `uk_biz_type` → 扣减量 0、直接提交、按成功回放；部分撞键 → 回查命中行、从本次扣减量里剔除（同参考工程 `listDetailsByBizNos`）。
- **事务失败**：整体回滚，调用方拿 `ERROR`，凭 `bizId` 重试（唯一键兜底，不会双扣）。

### 2. 桶整层废除

删：`inventory_bucket` 两张表（实物 / 虚拟）、台账的 `redis_qty` 列、`IInventoryBucketPort` 与 `inventory_bucket_deduct.lua`、`inventory:merge:dedupe:*` key、`inventoryBucketWarmup` handler、`InventoryMergedConsumer` 与 `bs-inventory-merge` topic。分片取代了桶的「把并发摊开」职责。

### 3. 台账三列保留

`surplus / waiting / issued` 全留：预扣直接推进（实物 `surplus→waiting`，虚拟 `surplus→issued`），兑换确认 `waiting→issued`（票 07）。审计、对账、兜底改发都还要读这三列——它们现在是唯一真相，不再有「Redis 余量面」需要对齐。

### 4. 明细表保留，字段精简

- 留：`inv_id / quantity / biz_type / biz_id / create_time`，`uk_biz_type (type, biz_type, biz_id)` 仍是幂等唯一裁决者。
- 删：`state`（未合并/已合并）、`bucket_id`、`idx_pending`、`idx_key_cell` —— 这三个只服务于时间格合并。
- `type` 列暂留：`type = 2` 的兑换确认是否还落明细，由票 07 决定（**票 07 已定：常留**——确认走合并器，明细行承载幂等与审计，`uk_biz_type` 原样）。

### 5. 失败语义三出口

| 出口 | 含义 | 调用方 |
|---|---|---|
| 成功 | 已提交，账已动 | 继续 |
| `INSUFFICIENT` | 确定没扣 | 改发兜底档 |
| `TIMEOUT_UNKNOWN` | 还在缓冲/落库中，**大概率会扣** | 不能报失败，按「处理中」处理（票 07 已定：不切兜底、不落库、抛可重试，用户凭同 token 重放） |

缓冲区满时**降级为单条同步扣减**（调用方线程自己走上面那个事务），不返回 `REJECTED`——抽奖不该因为「合并器忙」就随机丢奖。

### 6. 存量收口

切换时以**台账 `surplus` 为准**，把桶余量一次性清空并记一条差额流水；`state = 0` 的未合明细先由旧逻辑合完再切。具体步骤归票 09。
