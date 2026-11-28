# 奖品库存 schema（award 域：单一奖品库存台账；已并入单体库 better_school）

> 业务：发放侧库存（营销域；monolith-restructure 票 07 起并入单体）；归属域：`domain.award`
> 结构与根 `sql.md` §2 逐表一致（唯一事实源）。DDL 在单体 `better-school/sql/schema.sql` 尾段。
> hot-row-merge-l2w256 起（ADR-0013 取代 0011/0012）：**进程内合并，无 Redis 桶、无合并消息、无兜底**。
> award 域重构起：**只有一张奖品库存台账**——原「配额池 + 分配」那套（`virtual_inventory_quota*` /
> `physical_marketing_quota*` / `inventory_allocation_flow`）已整条删除，运营直接维护 `award_inventory`。

## 模型（先对齐再看表）

```
运营投放（ADMIN）                      抽奖链路
      │                                  │
      ▼ 直接 upsert（一条 SQL，低频）       ▼ 预扣/确认（合并器，打的就是这张台账）
┌──────────────────────────────────┐
│ award_inventory                  │
│   1 奖品位 1 账户（uk_award_id）   │
│   type = PHYSICAL：两段式          │
│          surplus / waiting / issued│
│   type = VIRTUAL ：surplus→issued  │
│ award_inventory_detail            │
│   flow_type = 1 预扣 / 2 兑换确认   │
└──────────────────────────────────┘
```

| 表名 | 层 | 说明 |
| --- | --- | --- |
| `award_inventory` | 奖品 | 奖品库存台账：1 奖品位 1 账户（`uk_award_id`），运营投放/改量与抽奖扣减的**唯一账户** |
| `award_inventory_detail` | 奖品 | 奖品库存流转明细：预扣（`flow_type = 1`）/ 兑换确认（`flow_type = 2`）两类共用，`uk_award_biz (award_id, biz_type, biz_id)` 是唯一幂等裁决者 |

关键语义（相对旧「池 + 分配」模型）：

| 旧 | 新 |
|---|---|
| 配额池 `virtual_inventory_quota*` / `physical_marketing_quota*` + 分配流水 `inventory_allocation_flow` | 全部删除，无池、无分配、无转出流水 |
| 分配 `QuotaAllocateCase`（`POST /api/marketing/inventory/allocate`）把池量转进奖品账户 | `AwardInventoryCase`（`POST /api/marketing/award/inventory`）直接 upsert 奖品账户 |
| `InventorySegmentVO`（VIRTUAL/QUOTA）选两张台账表 | `AwardInventoryTypeEnum`（VIRTUAL/PHYSICAL）是统一台账里的一列 |
| 抽奖 `invId` = 池台账行主键 | 抽奖按 `award_id` 定位账户；`draw_record.inv_id` 快照记 **award_id**（见 [draw.md](draw.md)） |
| 积分面额经 award_id → inv_id → 池配置两步查 | 积分面额直接读 `award_inventory.value_qty` |
| 明细 `flow_type` 0 分配转入 / 1 预扣 / 2 确认 | 明细 `flow_type` 只剩 1 预扣 / 2 兑换确认 |

## 奖品库存账户：列口径

`award_inventory`：

| 列 | 口径 |
| --- | --- |
| `award_id` | 奖品位 id（`draw_strategy_award.award_id`），`uk_award_id` = 1 奖品位 1 账户；抽奖按它定位账户 |
| `type` | `PHYSICAL`（两段式）/ `VIRTUAL`（`waiting` 恒 0）：**列**而不是分表——SQL/Java 按 type 分支；类型由运营投放时直接给出 |
| `surplus_qty` | 可扣余量；`-1 = 不限量`（守卫放行、不减 surplus） |
| `waiting_qty` | 已预占未确认（仅 PHYSICAL；VIRTUAL 恒 0） |
| `issued_qty` | 已发放（VIRTUAL 是直接发放；PHYSICAL 是兑换确认后的正式占用） |
| `value_qty` | 单次发放面额（仅 VIRTUAL：积分 / 抽奖次数；实物 NULL）——目录 `pointsAmount` 与中奖记录面额都直接读它 |
| `state` | TINYINT `0 open / 1 exhausted / 2 closed`（surplus 恰好扣空自动置 exhausted；投放维护时按入参覆盖） |

`award_inventory_detail` 的 `flow_type`：`1` 预扣（PHYSICAL 进 waiting，VIRTUAL 直接进 issued）/
`2` 兑换确认（仅 PHYSICAL，waiting → issued）。
`biz_type` 取值只剩 `LOTTERY_DRAW` / `LOTTERY_CLAIM`（`AwardBizTypes`），两个业务键都是
`draw_record.record_id`；`flow_type` 与 `biz_type` 存在派生关系（LOTTERY_DRAW⇒1 / LOTTERY_CLAIM⇒2），
保留两列是为了对账 SQL 直接按流转类型过滤，不做强一致约束。

## 运营投放（直接维护，一个事务，低频）

入口：case 层 `AwardInventoryCase`（HTTP `POST /api/marketing/award/inventory`，ADMIN）→
域服务校验奖品位存在（跨域只读经 `IDrawRepository#existsAward`）→ 仓储 `IAwardRepository#upsertInventory`：

```sql
INSERT INTO award_inventory
    (award_id, type, surplus_qty, waiting_qty, issued_qty, value_qty, state, create_time, update_time)
VALUES (?, ?, ?, 0, 0, ?, ?, NOW(), NOW())
ON DUPLICATE KEY UPDATE
    type        = VALUES(type),
    surplus_qty = VALUES(surplus_qty),
    value_qty   = VALUES(value_qty),
    state       = VALUES(state),
    update_time = NOW();
```

- **无户建户、有户覆盖**：`award_id` 唯一键决定 upsert 形态；`waiting_qty / issued_qty` 是既有流转进度，
  **不被覆盖**（投放改量只动可扣余量与配置）。
- 参数校验在 case 层：`type` ∈ `PHYSICAL/VIRTUAL`，`surplus_qty >= -1`，`value_qty >= 0`（可空），
  `state` ∈ `0/1/2`（空 = open）；奖品位不存在直接 400。
- 低频运营操作，**不进合并器**，同步一事务，返回即已提交。
- 余额语义：`surplus_qty = -1` = 不限量；`state = exhausted` 由抽奖扣空自动置位，
  运营改量/重开按入参 `state` 覆盖（`closed` 不自动重开，需显式传 `state = 0`）。

## 守恒口径（§0）

```text
奖品库存（PHYSICAL 行）： waiting_qty + issued_qty == SUM(detail.flow_type = 1)
奖品库存（VIRTUAL  行）： issued_qty               == SUM(detail.flow_type = 1)
确认行（flow_type = 2）：在 waiting/issued 两列之间搬，和不变，不进上面等式
```

对账轮只校验上面两条 + 正反双向配对（`B3_CONSERVATION` / `FORWARD_PAIRING` / `REVERSE_PAIRING`，
见 [recon.md](recon.md)）；原 B1 台账守恒依赖分配转入明细（`flow_type = 0`），随池与分配一起删除。

## 落库：进程内合并，一组一个事务

预扣与兑换确认都走仓储的单件入口（`IAwardRepository#deduct` / `#claim`）。
合并组件（`infrastructure/merge` 的通用引擎 `MergeEngine`：库存侧写入组件
`AwardInventoryMergeWriter` → 落库函数 `AwardInventoryFlushService`；设计与接入方式见
[`../architecture/merge.md`](../architecture/merge.md)）是**实现细节**：域只看到「一件扣没扣成」，
不知道这一件是并进一批落的还是单件落的。两个动作仍是两个落库方法（**不合成一条 SQL**——两个失败域语义不同）：

```sql
-- 预扣（PHYSICAL 行；VIRTUAL 行 surplus → issued，无 waiting）
INSERT IGNORE INTO award_inventory_detail (award_id, flow_type=1, ...) VALUES (...), (...);
UPDATE award_inventory
   SET surplus_qty = surplus_qty - N, waiting_qty = waiting_qty + N
 WHERE award_id = ? AND type = 'PHYSICAL' AND (surplus_qty < 0 OR surplus_qty >= N);

-- 兑换确认（逐行 INSERT IGNORE flow_type=2 明细，转移量 = 新增行的份数和）
INSERT IGNORE INTO award_inventory_detail (award_id, flow_type=2, ...) VALUES (...);
UPDATE award_inventory
   SET waiting_qty = waiting_qty - c, issued_qty = issued_qty + c
 WHERE award_id = ? AND type = 'PHYSICAL' AND waiting_qty >= c;
```

- 两条 UPDATE 都带守卫，且 surplus 恰好扣空时同一条 SQL 把 `state` 置 exhausted。
- **成功 = 已提交**：域的入口仍是三条——`DEDUCTED` / `INSUFFICIENT`（确定没扣，切兜底奖品位）/
  `RETRY_LATER`（结果未知或确定未落：不切兜底、抛可重试，用户凭同 token 重放）；
  「未知」与「失败」的区分只留在基础设施（`MergeResult.StateEnum.TIMEOUT` / `FAILED`）与日志里；
- **扣不动再探再切**（不二分）：`affected = 0` 时回读 `surplus_qty`，`k = min(剩余, 批内笔数)`；
  `k > 0` 扣 `k`、前 `k` 笔成功其余 `INSUFFICIENT`；`k = 0` 整批 `INSUFFICIENT`；
- **撞键快照按 `uk_award_biz (award_id, biz_type, biz_id)` 回查**：主档与兜底是两个账户（两个 awardId
  前缀），互不撞键；
- **兑换确认的 `waiting` 不足 = 不变量破坏，不是业务失败**：整组回滚（连确认明细一起）+ `error` 日志
  + 抛可重试 ⇒ MQ 重投 ⇒ 死信。不做部分确认；
- **合并器永不把 `REJECTED` 返回给用户**：缓冲满 / 停机中由调用方线程同步落库（组大小 1，
  复用同一个落库方法）。

## 明细幂等：唯一键就是业务幂等

- 奖品明细 `uk_award_biz (award_id, biz_type, biz_id)`：预扣行（1）/ 确认行（2）共用；
  **撞键不是错误**：撞键 = 这条流转已落过，按成功回放处理。
- 没有池明细表、没有分配流水，也没有 `flow_type = 0` 的写入面。
- 存量数据：未上线，清库重建，无迁移 SQL。

## 孤儿预扣（对账 job 自动收敛，fund-consistency 票 08）

预扣提交、`draw_record` 没落库（抽奖不是一个大事务）⇒ 用户没成功但库存已扣。能自愈的靠同 token 重试
撞 `uk_award_biz` 回放；用户放弃的部分由**孤儿预扣对账 job**（`orphanPrereserveScan`，xxl cron `0/30`）在
一个扫描周期内收敛：扫台账流水 `flow_type=1 AND biz_type='LOTTERY_DRAW'` 且 `biz_id`（= record_id）在
`draw_record` 无行、超过补偿阈值（10 分钟）的孤儿，登记进状态表 `draw_orphan_prereserve_order` 后两条路径——
决策可重放（活动仍有效）→ 以原幂等键重放抽奖落库后半段（不重复扣次数、不重复预扣；决策重跑奖品可能漂移，
漂移时原奖品预扣回补）；决策不可重放（活动已结束/下线）→ 库存回补（PHYSICAL `waiting → surplus` 条件转移 /
VIRTUAL `issued → surplus` 条件回退）+ 抽奖次数退还（`ORPHAN_REFUND`，桶 `uk_user_reason_ref` 幂等）。
重试条件更新抢占、指数退避（1/2/4/8/16 分钟），耗尽进 `DEAD`（只 ERROR 日志 + 人工 SQL 重置）。排查用 SQL 保留：

```sql
-- 有预扣明细、但没有对应 draw_record 的行（flow_type = 1，biz_id = record_id）
SELECT d.award_id, d.biz_id, d.quantity, d.create_time
  FROM award_inventory_detail d
  LEFT JOIN draw_record r ON r.record_id = d.biz_id AND r.inv_id = d.award_id
 WHERE d.flow_type = 1 AND r.record_id IS NULL
 ORDER BY d.create_time;
```

逐笔收敛之上另有每日全量对账轮（`drawReconScan`，03:00）做总量兜底：预扣守恒 + 正反双向配对轧账、
差错二次核对、平账分流，见 [recon.md](recon.md)。

## 与主链路相接

抽奖主链路经 `IInventoryDeductPort` 调本域的预扣（实物 `preDeductWithFallback(primary, fallback)`、
虚拟 `preDeduct`），**预扣锚在奖品位 id（奖品库存账户）**；奖品位配置 `draw_strategy_award.inv_id`
只剩「不限量哨兵」一个用途（字符串 `'0'`，落库快照无库存来源置 `NULL`）。预扣返回即账户台账已动
（`surplus → waiting` / `issued`），目录查询（`GET /prizes` 的 `remaining` / `total`）读奖品库存账户，
积分面额直接读同一行的 `value_qty`。兑换确认由发货消费驱动（`IQuotaConfirmPort` → 仓储 `claim` → 合并组件），
返回即 `waiting → issued` 已提交。预扣成功而 `draw_record` 没落库的窗口由孤儿预扣对账 job 在一个扫描周期内
收敛（补落库或回补+退还，见上节「孤儿预扣」）。
