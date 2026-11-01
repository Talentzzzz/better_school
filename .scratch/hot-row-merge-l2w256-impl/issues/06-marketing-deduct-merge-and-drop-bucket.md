# 06: marketing 预扣换进程内合并，删桶层

**Spec:** `.scratch/hot-row-merge-l2w256/spec.md` §4.1–§4.4、§4.6、§5.2；判据见 `acceptance.md` B1–B4

**What to build:** 抽奖预扣从「Redis 桶 Lua 预扣 → 明细 → 同事务 outbox（`delay_ms = 2000`）→ 合并消费 → 时间格两两合 → 60s 兜底」换成「请求等 `windowMs` → 一组一事务：明细 `INSERT IGNORE` + 台账一条 `UPDATE` → 返回」。**成功 = 已提交**（等 future，`COMMIT` 之后才说成功）。

落库按「动作类型 × segment」二次分组，一组一个事务——不是一批一个事务：一组一事务让锁集最小、失败只回滚一组、能精确回填到件；一批一事务会让两个分片以不同顺序锁同两行，多一个死锁面。实物段 `surplus → waiting`，虚拟段 `surplus → issued`。

扣不动时不二分：`affected = 0` 就查剩余，`k = min(剩余, 批内笔数)`；`k > 0` 则扣 `k`、前 `k` 笔回成功其余 `INSUFFICIENT`（**第二事务路径必须让明细与台账仍然平——插进去的是 k 行不是 N 行**）；`k = 0` 则整批 `INSUFFICIENT`。

三出口照旧：成功 / `INSUFFICIENT`（确定没扣，改发兜底档）/ `TIMEOUT_UNKNOWN`（还在缓冲或落库中，大概率会扣 ⇒ 不切兜底、不落库、抛可重试）。把 `waitTimeoutMs` 抬到「绝不超时」是被否决的。

桶层随之整层消失：`inventory_bucket` 两张表在代码侧的映射、端口与 VO、桶 Lua、去重 key、脚本管理器里的桶脚本。台账三列成为唯一真相，不再有「Redis 余量面」要对齐。

**Blocked by:** 02

**Status:** resolved

- [x] 预扣成功即台账已动：返回时 `surplus` 已减、实物段 `waiting` 已增 / 虚拟段 `issued` 已增，明细恰好 N 行，**没有第二步**
- [x] B1 零超卖：`surplus = k` 时并发 N 笔恰好 k 笔成功，终值 0，任何时刻不为负
- [x] B2 三列守恒：逐 inv `surplus + waiting + issued == 初值`（初值取切换前快照）
- [x] B3 明细等式：`waiting + issued == SUM(quantity) WHERE type = 1`，**含「探一次再切」分支**（`k <` 批内笔数时仍相等）
- [x] B4 预扣幂等：同 `recordId` / `bizId` 重放，明细仍 1 行、净扣 1 份；整批撞 `uk_biz_type` ⇒ 扣 0、直接提交、按成功回放；部分撞键 ⇒ 回查命中行并从本次扣减量剔除
- [x] 事务失败 ⇒ 整体回滚 ⇒ 调用方 `ERROR`，凭 `bizId` 重试不双扣
- [x] `TIMEOUT_UNKNOWN` 不被当成失败：不切兜底、不落库、抛可重试；调用方凭同 token 重放（`recordId` 由「用户 + token」确定性映射）
- [x] 降级：缓冲满 / 停机中 ⇒ 调用方线程同步扣一批（组大小 1），**复用同一个落库方法**
- [x] 桶层删除干净：`IInventoryBucketPort` / `InventoryBucketPort` / `InventoryBucketVO` / 桶 Lua / `inventory:merge:dedupe:*` / 脚本管理器的桶方法；`BucketUnavailableException` 及其异常码一并删
- [x] `shardKey` 取 `invId`，预扣与确认共用同一分片（撞片无害）；合并器不解析请求体
- [x] 本票**不删**兜底 job / 合并消费者 / 读数（那是 08），但 `preDeduct` 已不再发库存合并消息
- [x] 中间态被明确标注为「不可进生产」：新代码不写 `state`（当前列默认 0），旧兜底会把这些行当成未合并再合一次账 ⇒ 06 / 07 / 08 必须落在同一次部署（marketing 侧是「一个 PR 换掉」，不设开关）
- [x] 本票范围内测试同批处置（先改测试再改实现），模块构建绿：`InventoryMergeTriggerIntegrationTest` 中「扣成功才发消息 / `delay_ms = 2000` / 扫描重发 / 去重 key / outbox 回滚」几类用例退休（对象在本票消失）；`PhysicalQuotaIntegrationTest` 与 `VirtualInventoryIntegrationTest` 的「预扣后台账未变、合并后才 `waiting` 增」整组改写为「预扣返回即台账已动」

## Answer（落地记录）

- 落库方法落在 `InventoryRepository`（一域一仓储 + `TransactionTemplate`，实现 02 的 `IQuotaMergeFlushHandler`）：
  一组一事务 = 批量 `INSERT IGNORE` + 一条带余量守卫的台账 `UPDATE`；`affected = 0` 探一次再切
  （第二事务只插 k 行只扣 k 份）；整批撞 uk 按 0 扣提交、按成功回放；批内同 bizId（同 token 双击落进
  同一组）由内存判重剔除。撞键快照**先于**批量 INSERT 取（同事务自插行不算「已落过」），快照与插入之间
  被并发重放抢先时整组回滚重取一次（至多两轮，再不齐即抛 = 整组 FAILED，宁失败不双扣）。
- 域层经新端口 `IQuotaMergePort` 进合并器（CLAUDE.md：domain 不注入 infrastructure）；
  降级（`BUFFER_FULL` / `CLOSED`）由服务层同步调 `IInventoryRepository.flushDeductGroup`（组大小 1），
  与合并器共用同一落库方法。`TIMEOUT_UNKNOWN` / `FAILED` 抛 `AppException(SYSTEM_ERROR)` 可重试。
- 桶层删除面：`IInventoryBucketPort` / `InventoryBucketPort` / `InventoryBucketVO` / `DeductAttemptVO` /
  桶 Lua 与脚本管理器 / 桶 PO 与 `listBuckets` / `Constants` 的桶 key / 去重 key / Lua 返回码 /
  `BucketUnavailableException` / `IInventoryWarmupService` + 桶预热 job（xxl handler 6 → 5）。
  审计的 `redisConsistent` 失去数据源，恒 null（字段留给 08 一并瘦身）。
- 兜底扫描 `selectMergeKeys` 加 `bucket_id IS NOT NULL` 过滤：新预扣行不写桶号且账已动，
  旧时间格合并只对切换前的存量行有意义（这也挡掉了旧兜底对新行 `bucketId = null` 的 NPE）。
- `schema.sql`：明细表 `bucket_id` 置可空（新行不写，列保留到 D9 收口删）；桶表与 `state` 列保留——
  旧合并机制（08 删）与切换前存量行还依赖它们；spec §5.3 的最终形态 schema 由 09 落。
- 测试：新增 `InventoryQuotaConservationIntegrationTest`（B1 零超卖并发 / B2 三列守恒 / B3 明细等式
  含探一次再切 / B4 整批与部分撞键、回滚重试不双扣）与 `InventoryFlushFailureIntegrationTest`
  （落库抛 → `FAILED` → 可重试 → 重试净扣一份）；Physical / Virtual 预扣组改写为「预扣返回即台账已动」；
  Trigger / Fault / DeliveryLatency 三个测试类与 `SwitchableInventoryBucketPort` 整份退休（对象在本票消失）；
  抽奖链路用例的 Redis 桶断言改为台账断言。中间态：06 / 07 / 08 必须同一次部署。
