# 08: 时间格合并引擎

**Spec:** `.scratch/marketing-inventory-refactor/spec.md` §5.1–§5.6（时间格 / 单格事务 / 排干循环 / 失败与不变量 /
被否的替代）、§4.6 的 key 选取 SQL、§8 的 `type=1` 口径

**What to build:** 把合并从「每秒全表扫 `state=0` → 内存分组 → 按 id 清单翻转」换成
**「一个 key 的最老封口秒格 + `affected == cnt` 判重」**：只合 `create_time < NOW()` 的格、
一次最多 10 格、只有最老一格，失败即停。触发本票**仍是每秒**——`inventoryMerge` job 内部改成调
`backstopOnce()`（按段取 200 个 key → 逐 key `mergeKey`）；换触发在 09 / 10。
本票实现 `type` 分组读，但只结算 `s1`（`type=2` 的写方在 11 才有）。

**Blocked by:** 05, 07

**Status:** resolved

**预算：≈150k / 200k。** 核心是仓储里的 `mergeCell` 事务（聚合 → 翻转 → 桶守卫 → 结算），
其余是循环与 key 选取。若 `affected != cnt` 的用例难造，可以停在「条件翻转 + 整格回滚已实现、
用例只覆盖单线程重复合并」，把竞态用例留给 10 的续命/兜底用例一起验。

## 验收

- [x] 仓储新增 `mergeCell(segment, invId, bucketId, t0)`，**一个事务**内：
      ① `MIN(create_time) WHERE state=0 AND create_time < NOW()`（取格由服务做，这里只做单格）→
      ② 按 `type` 聚合 `SUM(quantity) / COUNT(*)` → ③ 条件翻转（`state=0 AND create_time=t0`，
      `affected == c1 + c2` 否则整格回滚）→ ④ 桶守卫递减（只 `s1`，`s1=0` 跳过，0 行 = 回滚）→
      ⑤ 台账结算（`s1` 进 `waiting_qty` / 虚拟进 `issued_qty`，`surplus` 同步递减、减到 0 置 `exhausted`）
- [x] `IInventoryMergeService.mergeKey(segment, invId, bucketId) : MergeKeyResultVO(cells, details)`：
      循环 ≤10 格、只合已封口的最老格、任一步失败即停（**不续命**）
- [x] `backstopOnce() : MergeBackstopResultVO`：每段按 spec §4.6 的 SQL 取 200 个 key（按 `MIN(create_time)` 升序），
      逐 key 直调 `mergeKey`；`inventoryMerge` job 本票改为每秒调它
- [x] 删旧路径：`mergeOnce`、`listUnmerged*` 全表扫、`mark*DetailsMerged(id 清单)`、`merge*BucketGroup`
- [x] mapper 里旧的「全表扫 + 按组 id 清单」SQL 删干净；`idx_key_cell` / `idx_pending` 被新查询用上
      （跑一次 `EXPLAIN` 看执行计划，不回表）
- [x] 新用例：只合已封口格（未封口格不合）、只合最老一格、`affected != cnt` 整格回滚且行留 `state=0`、
      10 格上限、桶守卫不过时 `log.error` 且不抛（等待守卫 `s2` 属票 11，本票没有写方）
- [x] 全量集成测试绿（**102 tests**；96 + 6）

## 实现要点

（2026-10-18 落地。**注意：07 尚未落地**，故新用例暂放 `InventoryMergeEngineIntegrationTest`，
并更新三个既有库存测试 + `RaffleShippingConsumerIntegrationTest`；07/10 收口时再迁进唯一的参数化类。）

**单格事务（`InventoryRepository.mergeCell`）**
- 事务由仓储 `TransactionTemplate`（REQUIRED）包住，**一格一个事务**；域服务只逐格调一次。
- SQL 顺序严格按 spec §5.2：聚合读（`GROUP BY type`）→ 条件翻转 → 桶守卫递减 → 台账结算。
  先翻转 = 先抢单；④⑤ 都在翻转成功之后，任何一步回滚都整格退掉。
- `affected` 与 `cnt` 的比较落点在翻转之后：`flipUnmergedCell` 的返回行数 != 聚合出的 `c1 + c2`
  就 `setRollbackOnly` + `log.info` + 返回 0（竞态，唯一会自愈的一种）。`cnt = 0`（聚合前已被合走）
  同样返回 0。
- 桶守卫 0 行 / 台账结算 0 行：`setRollbackOnly` + `log.error` + 返回 0（不变量破坏，不抛）。
- 返回 `int`（本格合掉的明细行数）；**0 = 这一格没合**，不是「没有行」。
- 本票只结算 `s1`；`type = 2` 的 `s2`（waiting → issued）留票 11 在同一事务里补分支。
  聚合投影因此只保留 `s1` 的量与 `c1 / c2` 的计数（`c2` 用于 `affected` 比对），`s2` 的量等到 11 再加。

**`mergeKey` 排干循环**
- 每轮现查 `MIN(create_time) WHERE state=0 AND create_time < NOW()`；null 即结束。
- 最多 `Constants.MERGE_MAX_CELLS_PER_KEY = 10` 格（types.inventory，跨票可见）。
- 仓储返回 0 → `log.warn` 并立即结束（**不续命**）；成功格累加 `cells` / `details`。

**`backstopOnce` 的 key 选取与批大小**
- 每段 `Constants.MERGE_BACKSTOP_KEY_LIMIT = 200` 个 key；SQL 就是 spec §4.6 的
  `GROUP BY inv_id, bucket_id ORDER BY MIN(create_time) LIMIT ?`，逐 key **直调** `mergeKey`（不发消息）。
- 加 `FORCE INDEX (idx_pending)`：MySQL 9.7 在空表 / 小表上会改选 `idx_key_cell` 的 skip scan
  （同样不回表），但 spec 把这条全局扫描的落点钉在 `idx_pending`；FORCE 让执行计划跨版本稳定。
- 返回 `MergeBackstopResultVO(quotaKeys, virtualKeys, details)`；`inventoryMerge` job 改为每秒调它。

**索引验证（EXPLAIN，5000 行样本）**
- key 选取：`key = idx_pending`，`Extra = Using index; Using temporary; Using filesort` → **不回表**。
- 最老格：`Select tables optimized away`（MIN 走 `idx_key_cell`）。
- 聚合 / 翻转：`idx_key_cell`；聚合要读 `quantity` / `type`，必然回表读格内几行——spec 明确
  「不为覆盖查询再加列」，这是预期形状，不是漏索引。

**删掉的旧路径**
- 接口 / 实现：`mergeOnce`、`listUnmergedDetailRows`、`mergeBucketGroup`、`markDetailsMerged`
  （实物 `markQuotaDetailsMerged`），及 `MergeResultVO` / `MergeGroupVO` / `UnmergedDetailVO`。
- DAO / mapper：`selectUnmergedDetails` / `selectUnmergedQuotaDetails` 与两条 `WHERE id IN (...)` 翻转。

**测试**
- 新增 `InventoryMergeEngineIntegrationTest` 6 例：未封口格不合、最老优先且桶守卫不过即停（捕获 `log.error`）、
  `affected != cnt` 整格回滚、10 格上限、重复合并幂等、`backstopOnce` 两段取 key。
- `affected != cnt` 的造法：`@SpyBean` 在「聚合之后、翻转之前」用独立连接提交一行翻转，再用
  `sqlSessionTemplate.getMapper(...)` 执行真实翻转——不是伪造返回值；整格回滚后该行确为 `state=0`。
  该类的 spy 会拉起第二个 Spring 上下文，故用 `@MockBean XxlJobSpringExecutor` 避开进程级 handler 注册冲突。
- 既有测试改动：票 08 起「预扣后立刻合并」不再成立（`create_time < NOW()`）。测试不 sleep，
  用 `InventoryMergeTestSupport.sealUnmergedDetails(jdbcTemplate)` 把未合行前移 2 秒（等价于这一秒已过去）。
- 全量：`mvn -o test`（backend/marketing）→ **102 tests, 0 failures**。

## 注意

- **`create_time < NOW()` 不要写成 `<=`**：当前这一秒还在长新行，合它必然对不上 `cnt`。
- **只合最老一格**是 `type = 2` 的 `waiting_qty` 守卫能成立的前提（`type=1` 的行必然更早）；
  不要改成并行合多格，也不要按 id 清单翻转。
- **不抛、不续命**：不变量破坏（桶守卫 / 等待守卫不过）只 `log.error` 并让行留在 `state=0`，
  由兜底反复撞 + 对账读数暴露。
- 本票结束时 `mergeOnce` 与「每秒全表扫」都应消失；`inventoryMerge` 这个 handler 名**还在**（10 才删）。
