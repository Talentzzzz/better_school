# 库存配额 schema（bs_marketing 的 award-inventory 两层模型：配额池 + 奖品库存账户）

> 业务：发放侧库存（营销域 `backend/marketing/`）；归属域：`marketing.domain.inventory`
> 结构与根目录 `sql.md` §2 逐表一致（唯一事实源）。DDL 在本工程 `sql/schema.sql`。
> hot-row-merge-l2w256 起（ADR-0013 取代 0011/0012）：**进程内合并，无 Redis 桶、无合并消息、无兜底**。
> award-inventory 两层模型起（本票）：**抽奖不再直接扣配额池**——配额池（外界投放）只被「分配」转移，
> 抽奖的预扣/确认全部落在奖品库存统一台账上。

## 两层模型（先对齐再看表）

```
外界投放（运营）                     抽奖链路
      │                                │
      ▼ 分配（即转移，一事务）           ▼ 预扣/确认（合并器，只打奖品库存账户）
┌─────────────────────────┐   ┌──────────────────────────────────┐
│ 配额池（不再被抽奖扣）     │   │ draw_strategy_award_inventory     │
│ virtual_inventory_quota  │──▶│   type = VIRTUAL：surplus→issued  │
│ physical_marketing_quota │   │   type = PHYSICAL：两段式          │
│ + 各自 detail 表（保留）   │   │   surplus / waiting / issued      │
│ + inventory_allocation_  │   │ draw_strategy_award_inventory_    │
│   flow（分配流水）         │   │   detail（统一明细，flow_type 区分）│
└─────────────────────────┘   └──────────────────────────────────┘
```

| 表名 | 层 | 说明 |
| --- | --- | --- |
| `virtual_inventory_quota`(+detail) | 池 | 虚拟投放池（`type` = POINTS/DRAW_COUNT；`value` = 单次面额配置）：只被「分配」转出 surplus，抽奖不直接扣减 |
| `physical_marketing_quota`(+detail) | 池 | 实物投放池（两段式列保留）：同上 |
| `inventory_allocation_flow` | 池 | 分配流水（池侧转出流水；`uk_alloc_biz` 分配单号幂等，池 surplus 对账依据） |
| `draw_strategy_award_inventory` | 奖品 | 奖品库存统一台账：1 奖品位 1 账户（`uk_award_id`），抽奖直接扣减的账户 |
| `draw_strategy_award_inventory_detail` | 奖品 | 奖品库存统一流转明细：预扣/确认/分配转入三类共用，`uk_award_biz (award_id, biz_type, biz_id)` 是唯一幂等裁决者 |

关键语义：

| 旧 | 新 |
|---|---|
| `InventorySegmentVO`（VIRTUAL/QUOTA）选两张台账表 | `AwardInventoryType`（VIRTUAL/PHYSICAL）是统一台账里的一列；段枚举收窄为「池段」（池查询/分配用） |
| 抽奖 `invId` = 池台账行主键 | 抽奖 `invId` = 奖品库存账户维度（`draw_record.inv_id` 快照记 **award_id**，见 [draw.md](draw.md) 开放点①） |
| 池台账被预扣直接 UPDATE | 池台账只被「分配」转移；预扣/确认全部打奖品库存台账 |
| 实物/虚拟 = 两套 DAO/PO/明细表 | 一套 DAO/PO/明细表，`type`（台账）/ `flow_type`（明细）分支 |

## 奖品库存账户：列口径

`draw_strategy_award_inventory`：

| 列 | 口径 |
| --- | --- |
| `award_id` | 奖品位 id（`draw_strategy_award.award_id`），`uk_award_id` = 1 奖品位 1 账户；抽奖按它定位账户，不再需要池 `inv_id` |
| `type` | `PHYSICAL`（两段式）/ `VIRTUAL`（`waiting` 恒 0）：**列**而不是分表——两张 DAO 合一套，SQL/Java 按 type 分支；账户类型由来源池段唯一决定（实物池→PHYSICAL、虚拟池→VIRTUAL） |
| `surplus_qty` | 可扣余量；`-1 = 不限量`（守卫放行、不减 surplus，沿用旧台账口径） |
| `waiting_qty` | 已预占未确认（仅 PHYSICAL；VIRTUAL 恒 0） |
| `issued_qty` | 已发放 |
| `value_qty` | 单次发放面额（仅 VIRTUAL：POINTS 积分 / DRAW_COUNT 次数；实物 NULL）——分配时从虚拟池 `value` 列一并带给账户；POINTS/DRAW_COUNT 的**区分**仍留在池配置（读侧面额拼装按 award_id → inv_id → 池 type，开放点②不冗余类型列） |
| `state` | TINYINT `0 open / 1 exhausted / 2 closed`（沿用原台账语义；分配转入时 exhausted 自动回 open，closed 不自动重开） |

`draw_strategy_award_inventory_detail` 的 `flow_type`：`0` 分配转入（`biz_type = AWARD_ALLOCATE`，`biz_id` = 分配单号）/
`1` 预扣（PHYSICAL 进 waiting，VIRTUAL 直接发放）/ `2` 兑换确认（仅 PHYSICAL）。
`flow_type` 与 `biz_type` 存在派生关系（LOTTERY_DRAW⇒1 / LOTTERY_CLAIM⇒2 / AWARD_ALLOCATE⇒0），
保留两列是为了对账 SQL 直接按流转类型过滤，不做强一致约束。

## 分配（分配即转移，一个事务，低频运营）

入口：case 层 `QuotaAllocateCase`（HTTP `/api/marketing/inventory/allocate`，ADMIN）→
域服务 `IInventoryAllocationService`（校验池 open + 奖品位存在）→ 仓储 `allocate` 一个事务：

```sql
INSERT IGNORE 奖品侧 AWARD_ALLOCATE 明细（award_id, flow_type=0, biz_id=分配单号）;   -- 幂等裁决先行
INSERT IGNORE 池侧分配流水（alloc_biz_id=分配单号）;                                  -- 双侧流水，同锚
-- 两边任一撞键 = 本单已执行 → 提交空事务，按成功回放
UPDATE <池>   SET surplus_qty = IF(surplus_qty < 0, surplus_qty, surplus_qty - N)
 WHERE inv_id = ? AND state = 'open' AND (surplus_qty < 0 OR surplus_qty >= N);
UPDATE draw_strategy_award_inventory                                  -- upsert：无户建户、有户累加
   SET surplus_qty = surplus_qty + N ...（exhausted 自动回 open）
 WHERE award_id = ?;
```

- **分配不走合并器**（低频运营操作），同步一事务，返回即已提交；
- 撞分配单号 = 本单已执行，按成功回放（两侧流水唯一键同锚：奖品侧 `uk_award_biz` + 池侧 `uk_alloc_biz`）；
- 不限量池（surplus = -1）放行、不减 surplus，流水照记；
- 分配**不写**池 detail 表（它不产生 waiting/issued 流转），池侧守恒靠 `inventory_allocation_flow`；
- 池 surplus 恰好分空**不置 exhausted**（exhausted 表示「被抽奖扣空」的旧语义；投放收口走 closed / 改投放量）。

## 守恒口径（§0）

```text
奖品库存（PHYSICAL 行）： waiting_qty + issued_qty == SUM(detail.flow_type = 1)
奖品库存（VIRTUAL  行）： issued_qty               == SUM(detail.flow_type = 1)
确认行（flow_type = 2）：在 waiting/issued 两列之间搬，和不变，不进上面等式
分配行（flow_type = 0）：不进上面两个等式（它只在 surplus 与池之间搬运）
池侧：surplus 转出量 == SUM(inventory_allocation_flow.quantity)（不限量池不减、流水照记）
```

守恒等式由集成测试断言锁住（`InventoryQuotaConservationIntegrationTest` 的 B0–B3），
不留在运行时读数里；`IInventoryAuditService.audit(segment, invId)` 仍为池台账三列直读。

## 落库：进程内合并，一组一个事务（口径不变，目标表换成统一台账）

预扣与兑换确认都走仓储的单件入口（`IInventoryRepository#deduct` / `#claim`）。
合并组件（`infrastructure/merge` 的通用引擎 `MergeEngine`：库存侧写入组件
`InventoryQuotaMergeWriter` → 落库函数 `InventoryQuotaFlushService`；设计与接入方式见
[`../architecture/merge.md`](../architecture/merge.md)）是**实现细节**：域只看到「一件扣没扣成」，
不知道这一件是并进一批落的还是单件落的。两个动作仍是两个落库方法（**不合成一条 SQL**——两个失败域语义不同）：

```sql
-- 预扣（PHYSICAL 行；VIRTUAL 行 surplus → issued，无 waiting）
INSERT IGNORE INTO draw_strategy_award_inventory_detail (award_id, flow_type=1, ...) VALUES (...), (...);
UPDATE draw_strategy_award_inventory
   SET surplus_qty = surplus_qty - N, waiting_qty = waiting_qty + N
 WHERE award_id = ? AND type = 'PHYSICAL' AND (surplus_qty < 0 OR surplus_qty >= N);

-- 兑换确认（逐行 INSERT IGNORE flow_type=2 明细，转移量 = 新增行的份数和）
INSERT IGNORE INTO draw_strategy_award_inventory_detail (award_id, flow_type=2, ...) VALUES (...);
UPDATE draw_strategy_award_inventory
   SET waiting_qty = waiting_qty - c, issued_qty = issued_qty + c
 WHERE award_id = ? AND type = 'PHYSICAL' AND waiting_qty >= c;
```

- **成功 = 已提交**：域的入口仍是三条——`DEDUCTED` / `INSUFFICIENT`（确定没扣，切兜底奖品位）/
  `RETRY_LATER`（结果未知或确定未落：不切兜底、抛可重试，用户凭同 token 重放）；
  「未知」与「失败」的区分只留在基础设施（`MergeResult.State.TIMEOUT` / `FAILED`）与日志里；
- **扣不动再探再切**（不二分）：`affected = 0` 时回读 `surplus_qty`，`k = min(剩余, 批内笔数)`；
  `k > 0` 扣 `k`、前 `k` 笔成功其余 `INSUFFICIENT`；`k = 0` 整批 `INSUFFICIENT`；
- **撞键快照按 `uk_award_biz (award_id, biz_type, biz_id)` 回查**：主档与兜底是两个账户（两个 awardId
  前缀），互不撞键——旧「主档与兜底跨表不撞」的口径在同一张表上由 award_id 前缀自然延续；
- **兑换确认的 `waiting` 不足 = 不变量破坏，不是业务失败**：整组回滚（连确认明细一起）+ `error` 日志
  + 抛可重试 ⇒ MQ 重投 ⇒ 死信。不做部分确认；
- **合并器永不把 `REJECTED` 返回给用户**：缓冲满 / 停机中由调用方线程同步落库（组大小 1，
  复用同一个落库方法）。

## 明细幂等：唯一键就是业务幂等

- 奖品明细 `uk_award_biz (award_id, biz_type, biz_id)`：预扣行（1）/ 确认行（2）/ 分配转入行（0，
  `biz_id` = 分配单号）共用；**撞键不是错误**：撞键 = 这条流转已落过，按成功回放处理。
- 池侧两张明细表**保留**（历史数据 / 旧对账 SQL 仍可跑），但**不再有新写入面**——
  分配不写池明细，抽奖不写池明细。
- 存量数据：未上线，清库重建（开放点④），无迁移 SQL。

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
  FROM draw_strategy_award_inventory_detail d
  LEFT JOIN draw_record r ON r.record_id = d.biz_id AND r.inv_id = d.award_id
 WHERE d.flow_type = 1 AND r.record_id IS NULL
 ORDER BY d.create_time;
```

## 与主链路相接

抽奖主链路经 `IInventoryDeductPort` 调本域的预扣（实物 `preDeductWithFallback(primary, fallback)`、
虚拟 `preDeduct`），**预扣锚在奖品位 id（奖品库存账户）**；奖品位配置 `draw_strategy_award.inv_id`
收窄为「投放池配置」：它是分配的转出来源，也是 `inv_id = '0'` 的不限量哨兵。预扣返回即账户台账已动
（`surplus → waiting` / `issued`），目录查询（`GET /prizes` 的 `remaining` / `total`）读奖品库存账户，
积分面额读池配置。兑换确认由发货消费驱动（`IQuotaConfirmPort` → 仓储 `claim` → 合并组件），
返回即 `waiting → issued` 已提交。预扣成功而 `draw_record` 没落库的窗口由孤儿预扣对账 job 在一个扫描周期内
收敛（补落库或回补+退还，见上节「孤儿预扣」）。
