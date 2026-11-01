# 库存配额 schema（bs_marketing 的 virtual_inventory_quota_* / physical_marketing_quota_* 表集）

> 业务：发放侧库存（营销域 `backend/marketing/`）；归属域：`marketing.domain.inventory`
> 结构与根目录 `sql.md` §2 逐表一致（唯一事实源）。DDL 在本工程 `sql/schema.sql`。
> hot-row-merge-l2w256 起（ADR-0013 取代 0011/0012）：**进程内合并，无 Redis 桶、无合并消息、无兜底**。

**两套 quota 并存，不是一张表**：实物 `physical_marketing_quota*`、虚拟 `virtual_inventory_quota*`，
各 2 张共 4 张。两者是**同一套模型的两个目标**（预扣明细 + 进程内合并 + 台账三列），代码上由
`InventorySegmentVO`（`QUOTA` / `VIRTUAL`，code 为 `quota` / `virtual`）选边，不再有孪生方法。
命名不对称是有意的：实物侧叫「营销配额」（配额即投放池），虚拟侧叫「虚拟库存配额」。

| 表名 | 说明 |
| --- | --- |
| `virtual_inventory_quota` | 虚拟配额台账（`type` = POINTS 积分 / DRAW_COUNT 抽奖次数；`surplus_qty` -1 = 不限量；`value` = 单次发放面额） |
| `virtual_inventory_quota_detail` | 虚拟预扣明细（无 `type`：虚拟只有一段流转） |
| `physical_marketing_quota` | 实物营销配额台账（`surplus → waiting → issued` 两段式） |
| `physical_marketing_quota_detail` | 实物流转明细（`type = 1` 预扣结转 / `type = 2` 兑换确认） |

旧的一对「出入库流水」表、两张 `*_bucket` 分桶表（Redis 对账副本 `redis_qty`）**均已删除**：
桶的「把并发摊开」职责由合并器的分片缓冲承担，台账三列是唯一真相，没有第二个余量面要对齐。

## 明细表：业务身份 + 唯一键

两张明细表**形状一致**，只有两处差异（`type` 列、唯一键形状）：

| 列 | 虚拟 | 实物 | 口径 |
| --- | --- | --- | --- |
| `inv_id` | ✓ | ✓ | 分片键（合并按 `invId` 路由，预扣与确认共用） |
| `quantity` | ✓ | ✓ | 本次预扣量 / 本次流转量 |
| `type` | — | ✓ | `1` 预扣结转（进 `waiting_qty`）、`2` 兑换确认（`waiting → issued`）。**只有实物有**：虚拟没有第二段流转，多一列只会多一个恒为 1 的死值 |
| `biz_type` | ✓ | ✓ | `LOTTERY_DRAW`（预扣）/ `LOTTERY_CLAIM`（兑换确认，仅实物） |
| `biz_id` | ✓ | ✓ | 抽奖与兑换确认都是 `draw_record.record_id` |
| `create_time` | ✓ | ✓ | INSERT 时由 DB 的 `NOW()` 写，只作审计时间，不再参与任何合并定位 |

- **唯一键就是业务幂等**：虚拟 `uk_biz (biz_type, biz_id)`、实物 `uk_biz_type (type, biz_type, biz_id)`。
  实物必须带 `type`——抽奖预扣行与兑换确认行共用 `biz_id = recordId`，只用 `(biz_type, biz_id)` 会互撞。
  **撞键不是错误**：撞键 = 这条流转已落过，按成功回放处理。
- 旧的两态 `state` 列、`bucket_id` 列、`idx_key_cell` / `idx_pending` 两条索引**已删**
  （`schema.sql` 写最终形态；存量库由 `sql/cutover/d9-finalize.sql` 在 D9 收口删，过渡期见下「切换与 D9」）。

## 落库：进程内合并，一组一个事务

预扣与兑换确认都走 `QuotaMergeBuffer.submitAndWait`（两个重载，**不合成一个方法**——两个失败域的语义不同）：
请求等 `windowMs`（5ms）窗口，同分片的件合并成**一组一个事务**：

```sql
-- 预扣（实物；虚拟直接 surplus → issued）
INSERT IGNORE INTO <segment>_detail (inv_id, quantity, biz_type, biz_id) VALUES (...), (...);
UPDATE <segment>_ledger
   SET surplus_qty = surplus_qty - N, waiting_qty = waiting_qty + N
 WHERE inv_id = ? AND surplus_qty >= N;      -- 守卫与扣减同一事务 ⇒ 结构上不可能超卖

-- 兑换确认（逐行 INSERT IGNORE type=2 明细，转移量 = 新增行的份数和）
INSERT IGNORE INTO physical_marketing_quota_detail (inv_id, quantity, type, biz_type, biz_id) VALUES (...);
UPDATE physical_marketing_quota SET waiting_qty = waiting_qty - c, issued_qty = issued_qty + c WHERE inv_id = ?;
```

- **成功 = 已提交**：调用方等到的是 `COMMIT` 之后的结果。三个出口：成功 / `INSUFFICIENT`（确定没扣，切兜底奖品位）/
  `TIMEOUT_UNKNOWN`（大概率会扣，不切兜底、抛可重试，用户凭同 token 重放）。
- **扣不动再探再切**（不二分）：`affected = 0` 时回读 `surplus_qty`，`k = min(剩余, 批内笔数)`；
  `k > 0` 扣 `k`、前 `k` 笔成功其余 `INSUFFICIENT`（第二事务路径里明细与台账仍平）；`k = 0` 整批 `INSUFFICIENT`。
- **兑换确认的 `waiting` 不足 = 不变量破坏，不是业务失败**：整组回滚（连确认明细一起）+ `error` 日志 + 抛可重试
  ⇒ MQ 重投 ⇒ 死信。不做部分确认，没有「确认时配额不足」这类同步业务失败态。
- **合并器永不把 `REJECTED` 返回给用户**：缓冲满 / 停机中由调用方线程同步落库（组大小 1，**复用同一个落库方法**）；
  降级观测看 `MergeMetrics`（`rejectedFull` / `rejectedClosed` / `timedOut` / `drainFailed`）。
- 旧的触发链整条退休：`bs-inventory-merge` topic 与消费组、`delay_ms = 2000` 定时消息、时间格
  （`create_time` 秒键 + 固定合两格 + `affected` 判重）、Lua 桶脚本与去重 key、60 秒 `inventoryMergeBackstop`
  兜底、`inventoryBucketWarmup` 预热——全部删除，不建替代 job（ADR-0011 列的三种丢失在内存合并下一种都不剩）。

## 对账（`IInventoryAuditService.audit(segment, invId)`，可重复执行）

读数瘦成**台账三列直读**（`InventoryAuditVO` 4 字段：`invId / surplusQty / ledgerWaitingQty / ledgerIssuedQty`）：
没有第二份状态可对，`pendingDeductQty` / `pendingClaimQty` / `mergedDeductQty` / `ledgerConsistent` /
`redisConsistent` 全删。守恒等式降级为集成测试断言（B2 / B3），不再有运行时读数：

```text
实物： waiting_qty + issued_qty == SUM(quantity) WHERE type = 1
虚拟： issued_qty               == SUM(quantity)
```

- 实物 `type = 2` 不进等式：它在两个列之间搬（`waiting -c / issued +c`），和不变。
- **生产上账不平零自动信号**（无 metrics 端点、`alarm_email` 为空）；可用的路只有死信队列、
  `draw_record` 停在待发货、与下面这条人工 SQL。
- **不自动修数据**：差值只暴露，人工核。

## 孤儿预扣（人工 SQL，不建 job、不建巡检）

预扣提交、`draw_record` 没落库（抽奖不是一个大事务）⇒ 用户没成功但库存已扣。能自愈的靠同 token 重试
撞 `uk_biz_type` 回放；用户放弃的部分永久挂账，只有人工 SQL 能找到：

```sql
-- 有预扣明细、但没有对应 draw_record 的行（type = 1，biz_id = record_id）
SELECT d.inv_id, d.biz_id, d.quantity, d.create_time
  FROM physical_marketing_quota_detail d
  LEFT JOIN draw_record r ON r.record_id = d.biz_id
 WHERE d.type = 1 AND r.record_id IS NULL
 ORDER BY d.create_time;

-- 虚拟段同构（无 type 条件）
SELECT d.inv_id, d.biz_id, d.quantity, d.create_time
  FROM virtual_inventory_quota_detail d
  LEFT JOIN draw_record r ON r.record_id = d.biz_id
 WHERE r.record_id IS NULL
 ORDER BY d.create_time;
```

## 切换与 D9（存量库）

切换包在 `sql/cutover/`：运行手册 [cutover-runbook.md](../../sql/cutover/cutover-runbook.md)
（D0 切 bs → D1 晚高峰后抽样 → D2 切 marketing → D9 收口；8 步 + 回滚 4 步 + D9 3 步）、
`cutover-catchup.sql`（在途 `state = 0` 明细按旧逻辑合完，含 `type = 2`）、
`cutover-cleanup.sql`（outbox 收尾 + 逐 inv 差额只记不修 + 清桶）、`rollback-warmup.sh`（回滚重灌桶）、
`window-ddl.sql`（`state SET DEFAULT 1`）、`d9-finalize.sql`（删列删索引 / 白名单删两张桶表 / 删 xxl 注册，**不可逆**）。

## 与主链路相接

抽奖主链路经 `IInventoryDeductPort` 调本域的预扣（实物 `preDeductWithFallback(primary, fallback)`、
虚拟 `preDeduct`）；预扣返回即台账已动（`surplus → waiting` / `issued`），目录查询
（`GET /prizes` 的 `remaining` / `total`）只读台账。兑换确认由发货消费驱动
（`IQuotaConfirmPort` → `QuotaMergeBuffer` 确认重载），返回即 `waiting → issued` 已提交。
预扣成功而 `draw_record` 没落库的窗口**不补偿**：明细在库、台账已扣，靠「孤儿预扣」人工 SQL 暴露。
