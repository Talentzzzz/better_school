# marketing 全域：合并、事务下沉、config 只读改 port

Type: task
Status: resolved
Blocked by:

## Question

1. **raffle**：4 个仓储接口（`IRaffleConfigRepository` / `IRaffleQueryRepository` / `IDrawRecordRepository` / `IDrawRecordShippingRepository`，都建在同一个 `IRaffleDao` 上）→ `IRaffleRepository` + `RaffleRepository`，实现体原样搬；原 `DrawRecordRepository:29`、`DrawRecordShippingRepository:36` 的仓储内事务保持原样。
2. **inventory**：`IVirtualInventoryRepository` + `IPhysicalQuotaRepository` → `IInventoryRepository`；`PhysicalQuotaServiceImpl:104`、`InventoryMergeServiceImpl:91`（每组一事务的循环，逐组调一次仓储方法）原样搬。
3. **points / drawcount**：`PointsAccountServiceImpl:45/69/93`、`DrawCountAccountServiceImpl:66/105` 原样搬进各自仓储。
4. **config 跨域只读 3 处**（`PointsAccountServiceImpl:36`、`DrawCountAccountServiceImpl:33`、`RaffleQueryServiceImpl:26`）→ 消费方域自有 port，实现放 marketing-infrastructure。
5. **`RaffleExchangeServiceImpl:37` 的跨域事务**：raffle 域服务开事务、事务内调 points 扣分 + drawcount 入账两个 port。默认按全仓既有跨域口径收敛：**case 层顺序调用两个域方法（各自域内原子）+ 幂等重试**，不再在一个事务里跨域。若你要「兑换意图先行 + 单向推进」（多一张表、可对账），开工前说一声。

判据：marketing 侧编译过；`RaffleDrawPipelineIntegrationTest`、`RaffleShippingOutboxIntegrationTest`、`RaffleShippingConsumerIntegrationTest`、`PointsDeductIntegrationTest`、`DrawCountBucketIntegrationTest`、`VirtualInventoryIntegrationTest`、`PhysicalQuotaIntegrationTest`、`InventoryFaultIntegrationTest` 绿；marketing 每域一个仓储；points/drawcount/raffle 不 import `domain.config.adapter.repository`，相关 service 不出现 `TransactionTemplate`。

## Answer

**1. raffle 四仓储合一**

- 四个接口（65 + 42 + 17 + 27 行）→ `IRaffleRepository`（123 行 / 24 方法），实现 `RaffleRepository` 持 `IRaffleDao` + `RedissonClient` + `OutboxMessageStore` + `TransactionTemplate`，四个被合并实现类的方法体原样搬入。**PO / mapper XML / DAO 一律未动**（`raffle/dao`、`RaffleMapper.xml` 零 diff）。
- 落位沿用这四张面原本所在的 `infrastructure/raffle/adapter/repository/raffle/`（票面明写「实现体原样搬」，不动落位）。
- 两个接口都有的 `queryRecordByRecordId` 合成一个；`RaffleQueryRepository` 的包内静态 `toRecordEntity` 改回普通私有方法（同包共用前提消失）。
- 仓储内两处事务（`insertDrawRecord` 记录 + 明细 + 中奖事件 outbox、`advanceState` 状态推进 + 动作明细）**连 lambda 一起原样搬**，REQUIRED 传播不变 —— `OutboxTransactionProbe` 的「探针事务回滚 → 记录与事件一起消失」仍绿。
- 四张 `types.raffle.Constants` 的 Redis key 口径在本类内以「canonical 前缀 + 后缀」重建（javac 在本类里解析不了同名 `Constants` 的静态导入：同文件还有 `types.common.Constants`，两者都要用）。

**2. inventory 两仓储合一**

- `IVirtualInventoryRepository`(51) + `IPhysicalQuotaRepository`(68) → `IInventoryRepository`（140 行）；实现 `InventoryRepository` 持 `IVirtualInventoryDao` + `IPhysicalQuotaDao` + `TransactionTemplate`，两个被合并实现类的方法体原样搬入，PO / XML / DAO 零 diff。
- 虚拟侧 10 个方法加 `Virtual` 限定消歧（`queryVirtualInventory` / `listVirtualBuckets` / `insertVirtualDetail` / `listUnmergedVirtualDetailRows` / `deductVirtualBucketRedisQty` / `settleVirtualInventory` / `markVirtualDetailsMerged` / `listOpenVirtualInventories` / `sumUnmergedVirtualQty` / `sumMergedVirtualQty`）；实物侧沿用既有 `Quota` 限定。另把 `queryInventory`（physical_inventory 出入库台账）改叫 `queryPhysicalInventory`，与 `queryVirtualInventory` 各指一物。
- 「一组一事务」搬成仓储方法 `mergeVirtualBucketGroup` / `mergeQuotaBucketGroup`（守卫递减 0 行 → `setRollbackOnly` + false），域服务逐组调一次、只在返回 false 时打跳过日志；`confirmIssued` 整段搬（waiting 转移 → 出库扣减 → 落 OUT 明细，任一步 0 行即在 lambda 内抛并整笔回滚）。

**3. points / drawcount 事务下沉**

| 原调用点 | 新仓储方法 |
| --- | --- |
| `PointsAccountServiceImpl:45` 中奖入账 | `IPointsRepository.insertBucketWithChanged(userId, pointsAmount, LOTTERY_WIN, recordId)` |
| `:69` 返利入账 | `insertBucketWithChanged(userId, rebateValue, REBATE, refId)` |
| `:93` 兑换扣分 | `IPointsRepository.deduct(userId, amount, reasonType, refId)` |
| `DrawCountAccountServiceImpl:66` 扣次 | `IDrawCountRepository.deductForDraw(userId, recordId)` |
| `:105` 入账共享体 | `IDrawCountRepository.creditBucket(userId, amount, reasonType, refId)` |

- 全部连 lambda 一起搬，REQUIRED 不变：`deduct` 的「幂等预检 → FIFO 扣减 → 明细撞 uk 则 `setRollbackOnly` 返回 null → 事务外重读真实余额按成功返回」整段照搬；`deductForDraw` 的「已扣过 → true」「无桶 → false」「撞 uk → 回滚」三分支照搬。
- 时间戳与有效期都改在**事务内**取（造桶搬进仓储，`expire_at = now + 有效期配置`，端口注入落在仓储实现）：这是票 03 复查对同类问题的既定口径（`createOrderWithLock` 的 `payTimeoutMinutes` 先例）。域服务不再注入 `TransactionTemplate`，也不再持 config port 之外的配置读。
- 「幂等跳过」的日志统一回域服务（仓储只返回 boolean），与 `addForLotteryWin` / `creditForRebate` 同形。

**4. config 跨域只读 → 消费方域 port（3 个）**

- `IPointsConfigPort`（`pointsValidDays` / `findOpenRebateValue`）、`IDrawCountConfigPort`（`drawCountValidDays` / `findOpenRebateValue`）、`IRaffleConfigPort`（`drawCountValidDays`），接口落各域 `domain/<域>/adapter/port/config/`，实现在 `infrastructure/<域>/adapter/port/config/` 纯委托 `IConfigRepository`（判据留在消费方）。
- 入账算 `expire_at` 的那次「读有效期」随造桶一起落进 points / drawcount 仓储实现（仍经各域自己的 port）；`drawCountValidDays` 的展示口径留在 `RaffleQueryServiceImpl`。

**5. 兑换边跨域事务收敛（票面默认口径）**

- `RaffleExchangeServiceImpl` 只剩积分半边：扣分（`IPointsDeductPort`，积分域内原子）→ 返回新的 `RedeemIntentVO(redeemOrderId, pointsCost)`；不足即抛 `LOTTERY_INSUFFICIENT_POINTS`（一分不扣、一桶不加）。
- 新增 case 边 `RaffleExchangeCase`：顺序调 `IRaffleExchangeService.exchangeDrawCount` → `IDrawCountAccountService.creditForRedeem` → 读余额，返回 `RedeemResultVO(redeemOrderId, drawCount)`（HTTP 契约 `{redeemOrderId, drawCount}` 不变）。**case 直调两个域方法（不是抽奖域自己的 port）**，即票面「case 层顺序调用两个域方法」原文。
- 两步共用同一 `hash(userId, token)` 单号作幂等键（积分流水唯一键 / 次数桶唯一键），中间态靠重试收敛；没有采用「兑换意图先行 + 单向推进」替代方案。
- 调用方改 `LotteryController` → `RaffleExchangeCase`。

**6. 文档同步**

- `marketing/docs/api/lottery.md`：`GET /activity` 流程图的两处仓储名；`POST /exchange/draw-count` 的「事务边界」段改写为顺序调用 + 幂等重试。
- `backend/docs/architecture/marketing-services.md`：仓储举例改 `IRaffleRepository`；「票 13 把兑换折叠成进程内调用」段补票 05 的两域不再同事务。
- `marketing-api/IPointsService`、`marketing-app/config/TransactionConfig` 的过期描述（「两域同库同事务」「域服务内用 transactionTemplate」）一并改。
- `backend/CLAUDE.md` 的 §1 只读例外与 §2/§3 事务落位按 map 归票 06，本票不动。

**判据结果**

- `mvn -o test-compile`：BUILD SUCCESS（全量 8 模块）。
- 全量 `mvn -o test`：**83 项 0 失败 0 错误**；票面点名的 8 个类全绿（`RaffleDrawPipeline` 11 / `RaffleShippingOutbox` 5 / `RaffleShippingConsumer` 7 / `PointsDeduct` 5 / `DrawCountBucket` 6 / `VirtualInventory` 5 / `PhysicalQuota` 5 / `InventoryFault` 2）。
- marketing 每域恰一个仓储：`ICommunityRepository` 式检查在本工程为 points / drawcount / config / raffle / inventory 五对，各自唯一。
- `grep` 判据：`marketing-domain` 内 `TransactionTemplate` 仅出现在 javadoc（0 处注入 / 0 处调用）；points / drawcount / raffle 对 `domain.config.adapter.repository` 0 命中（`ConfigServiceImpl` 用它自己的 `IConfigRepository` 属本域，不算跨域）。

**执行中的偏差（有意，非票面原文）**

- 合并必然要消歧的方法名：inventory 虚拟侧 10 个带 `Virtual`、`queryInventory`→`queryPhysicalInventory`；raffle 的 `IRaffleConfigRepository.SLOT_*` 两个常量并入 `IRaffleRepository`（调用点随之改）。
- `RaffleExchangeCase` 走 `IDrawCountAccountService`（域方法）而非抽奖域已有的 `IDrawCountPort`：票面第 5 条明写「case 层顺序调用两个域方法」，且该 port 的语义是「抽奖域 → 次数域」（主链路扣次 / 读余额），兑换入账本就该由 case 直调。
- 「造桶」搬进仓储（含时点与有效期读）——原因见 §3，是复查口径而非票面字面。
- 新增两个用例（`RaffleQueryIntegrationTest.exchangeDrawCount_retryAfterDeductOnly_converges`、`RaffleWarmupIntegrationTest.warmupState_isPerStrategy`）：前者覆盖本票新引入的「扣分已提交、入账未发生」中间态，后者锁住下面那个 bug。

**code-review 两轴复查发现并已修掉的问题**

1. **（真实 bug，Spec 轴抓到）`RaffleRepository` 的预热态 key 丢了 `strategyId`**：`cacheWarmupState` / `isWarmed` 少了 `+ strategyId`，导致所有策略共用一个 key、`isWarmed(strategyId)` 参数失效（A 预热会让 B 被报成已预热，而该标记正是主链路降级判据）。已按 `RaffleConfigRepository:141/147` 原样改回 `RAFFLE_WARMUP_STATE_KEY + strategyId`，并补 `warmupState_isPerStrategy` 用例（同时断言「不得存在不带 strategyId 的共用 key」）。原有用例只断言布尔返回、又每用例只用一个策略，因此抓不到——这正是 `InventoryFault` 那类「测试绿但行为变」的翻版。
2. **（Standards 轴抓到）时点 / 有效期算在事务外**：首版把造桶留在域服务（`LocalDateTime.now()` 与端口读都早于事务边界），已改回事务内（见 §3）。
3. **（Standards 轴抓到）过期文档四处**：`marketing-services.md` 兑换段、`lottery.md`、`IPointsService`、`TransactionConfig` 仍写「两域同库同事务 / 域服务内用 transactionTemplate」，已一并改。
4. **（Standards 轴抓到）`creditBucket(bucket, reasonType)` 参数冗余且与 points 侧不同形**：改为 `creditBucket(userId, amount, reasonType, refId)`（与 `insertBucketWithChanged` 同形），「幂等跳过」日志统一回域服务。
5. **（Standards 轴抓到）`queryInventory` 命名歧义**：改为 `queryPhysicalInventory`。

**已知但不在本票处理（复查记录）**

- `RaffleRepository` 落位 `adapter/repository/raffle/` 使「域段」出现两次（`.../raffle/adapter/repository/raffle/`），与 §3 的单层「领域包」不一致；但这是票 11 / 13 起既有落位、票面又要求原样搬，故未动（`InventoryRepository` 按 §3 单层落位）。
- `merge*BucketGroup` 与两个 `toBucketEntity` 的同形重复：map 的「执行口径从简」明确禁止趁机抽公共骨架，只记录不改。
- 九个事务半步方法（`deductVirtualBucketRedisQty` / `settleVirtualInventory` / `markVirtualDetailsMerged` 及实物孪生 / `transferWaitingToIssued` / `issueInventory` / `insertInventoryDetail`）在合并后只有本类内部调用，却仍公开在域接口上；票面要求「原样搬」，故保留。
