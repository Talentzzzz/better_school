# 06: 预热 + 对账合一

**Spec:** `.scratch/marketing-inventory-refactor/spec.md` §7.1（`warmup` / `audit` 新形状）、
§8（统一恒等式与报告字段）、§9（预热参数）、§10（故障测试）

**What to build:** 把 05 留下来的两个孪生入口收掉：预热一条 `segment` 路径、对账一条 `segment` 路径，
对账口径统一成**一条恒等式**（两段同表达，差别只在 `waiting` 是否恒 0），并把 `pendingClaimQty`
作为独立读数暴露出来——它是「异步确认没落地」的唯一可观测信号。

**Blocked by:** 05

**Status:** resolved

**预算：≈100k / 200k。** 代码量小，主要是口径要写对：只需盯住「实物只算 `type=1`、Redis 只算 `type=1`」。

## 验收

- [x] `IInventoryWarmupService.warmup(segment, invId)` + `warmupAll()`；删两个孪生方法。
      `inventoryBucketWarmup` 参数改成 `[quota|virtual:]invId`（空 = 两段全量），仍是 `SET` 语义、
      在途未合并时严禁重灌的告警保留
- [x] `IInventoryAuditService.audit(segment, invId)`；删两个孪生方法
- [x] `InventoryAuditVO` 换成 spec §8 的字段：`pendingDeductQty` / `pendingClaimQty` / `mergedDeductQty` /
      `ledgerWaitingQty` / `ledgerIssuedQty` / `ledgerConsistent` / `redisConsistent`（`null` = Redis 不可读）
- [x] 恒等式：实物 `waiting + issued == SUM(quantity) WHERE state=1 AND type=1`；
      虚拟 `issued == SUM(quantity) WHERE state=1`（虚拟 `waiting` 恒 0）
- [x] Redis 恒等式只算 `type=1`：`每桶余额 == redis_qty - SUM(quantity WHERE state=0 AND type=1)`
- [x] `InventoryFaultIntegrationTest` 换服务名与新字段；补「Redis 挂了 → 预扣抛、明细不落、对账可查」
- [x] 全量集成测试绿

## 实现要点

（2026-10-18 落地。只收 05 刻意留下的两个孪生入口与对账口径：触发 / 合并算法 / 确认语义 / schema 均未动。）

**聚合 SQL 走哪条索引**
- 四条对账读数（合计 7 条 SQL）全部是单表 `WHERE inv_id = ?[ AND bucket_id = ?] AND state = ?[ AND type = ?]`，
  走 `idx_pending (state, inv_id, bucket_id, create_time)`，三列全等值；`type` 不在索引里，作为回表后的
  过滤（单库存明细量级小，实物表 `type` 只有 1/2，不另加索引——schema 票 03 已定稿）。
- Redis 恒等式右端按桶单查：`sumPendingDeductQtyByBucket(segment, invId, bucketId)`，由审计遍历
  `listBuckets` 逐个比较。**没有引入 `GROUP BY` 的结果投影类**：`dao/po` 保持「PO 与表一一对应」
  （backend/CLAUDE.md §3），审计是手动/运维读，桶数很少，多几条索引等值查询换掉一个非表 PO 是划算的。
- 两个 mapper XML 共 7 条：虚拟 3 条（pendingDeduct / mergedDeduct / byBucket），实物 4 条（+ pendingClaim）；
  实物的 pendingDeduct 与 pendingClaim 共用 `<sql id="sumUnmergedByType">` 片段（SQL 只差调用方传的
  `type` = 1 / 2）。

**`ledgerWaitingQty` 在虚拟段怎么填**
- 05 起仓储的 `toVirtualLedger` 已显式补 `waitingQty = 0`，本票不再在审计里做 `waiting + issued` 的合并：
  直接读台账两列，恒等式写成 `ledgerWaitingQty + ledgerIssuedQty == mergedDeductQty`，
  虚拟段自然退化成 `issued == mergedDeduct`——**两段同一条表达式**，没有 if/else 分支。
- `mergedDeductQty` 在实物段只算 `state=1 AND type=1`：`type=2` 合并时只是在 `waiting → issued` 之间搬，
  两边和不变，所以它既不进台账等式、也不进 Redis 等式。
- `pendingClaimQty` 虚拟段由仓储直接返回 `0L`（虚拟表没有 `type` 列、也没有兑换确认语义），
  不查一条永远为空的 SQL。

**故障用例的注入方式**
- 继续用 `SwitchableInventoryBucketPort`（`@Primary` 桩，委托真实现）：`switchToUnavailable()` 让
  `tryDeduct / release / warmup / getBalance` 都抛 `BucketUnavailableException`，`@AfterEach` 复位。
- 故障测试扩成两段各一条：虚拟段沿用原用例（预扣抛、无明细、台账不动、`redisConsistent = null`），
  实物段新增一条同样注入方式的用例（预扣抛、明细不落、`waiting/issued/surplus` 不动、对账七字段可查）。
- `redisConsistent` 三态都有用例：true（正常 / `type=2` 不干扰）、false（直接改 Redis 桶余额制造漂移，
  断言只暴露差、不修数据）、null（上面的故障注入）。

**`pendingClaimQty` 的验收对照（票 11 前）**
- 11 之前没有生产代码会写 `type=2`：`PhysicalQuotaIntegrationTest` 手工 INSERT 一行
  `state=0 / type=2` 模拟「异步确认没落地」，断言 `pendingClaimQty = 2`、且它不进台账恒等式与 Redis 恒等式；
  随后手工把它置 `state=1` 并做 `waiting -2 / issued +2`，断言 `pendingClaimQty` 归 0、`mergedDeductQty`
  仍只算 `type=1`、两列之和不变——把 spec §8「type=2 在两列之间搬、和不变」这条也钉住。

**形状与参数**
- `InventoryAuditVO.ledgerConsistent` 保持 `boolean`（恒等式永远有结论），`redisConsistent` 是
  `Boolean` 三态（`null` = Redis 不可读，不下结论）。
- `InventorySegmentVO.fromCode(code)` 成为 code → 段的唯一反查入口（job 参数现在用；10 解析合并消息载荷
  也用它）；`inventoryBucketWarmup` 只接受 `quota:` / `virtual:` 前缀，裸 invId 直接 fail，失败只有一个分支。
  `sql/xxl-job-tasks.sql` 的 `job_desc` 参数说明同步改，`docs/` 留给票 12。

**验证**
- `mvn -o -f backend/marketing/pom.xml test` → **96 tests, 0 failures**（93 + 实物段故障 1 +
  `type=2` 对账 1 + Redis 漂移三态 1）。
- **环境噪声（既有，不属于本票）**：dev xxl-admin 里 `inventoryMerge` 是 `0/1 * * * * ?`（每秒扫全表），
  会扫到测试库、与用例抢合并时机；本票两轮全量各被它掀翻一次（shipping 的 `waiting` 转移、
  draw 的「配额未合并明细一条」）。验证时临时把该 job 的 `trigger_status` 置 0（跑完恢复 1）→
  96 绿、job 执行 0 次。票 08 起该 job 由「消息 + 兜底」取代（spec §4），噪声随之消失；
  07 合参数化测试时若仍偶发，先停 job 再跑。

## 注意

- **对账不自动修数据**：差值只暴露，人工核（既有口径）。
- `pendingClaimQty` 本票恒 0（`type=2` 还不存在），但字段和读数要先有——11 才有值，用它做验收对照。
- 预热只改参数解析，不要动「预热 = `SET`」和「在途不重灌」的告警；这两条是行为边界，不是实现细节。
