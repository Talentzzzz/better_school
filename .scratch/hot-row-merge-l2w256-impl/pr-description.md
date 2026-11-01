# PR 描述：热点行写入换进程内合并（L2W256）

> 本文件是本次 effort 的 PR 描述兼 review 清单（票 10 收口件）。
> 判据来源：`.scratch/hot-row-merge-l2w256/acceptance.md`；形态来源：同目录 `spec.md`（ADR 0013）。

## 变更一句话

五条「逐笔打同一行」的写（bs 点赞/取消赞/评论赞、bs+商品浏览、marketing 库存预扣/确认）
换成「进程内分片缓冲 + 5ms 定时窗口 + 共享固定执行池 + 合并成一条 UPDATE」，两工程各实现一份
合并器（`CountMerger` / `QuotaMergeBuffer`），删掉 outbox→MQ→聚合、Redis 两段桶、时间格兜底整套旧机制；
切换包在 `backend/marketing/sql/cutover/`（DDL / 三脚本 / 8 步运行手册 / D9 收口）。

## Review 清单（reviewer 必查四条）

| # | 规则 | 在代码里的落点 | 核验结果 |
|---|---|---|---|
| 1 | **合并器不得从 payload 推导 key**：分片键必须是调用方显式传入的行主键，引擎/缓冲区不解析事件内容 | bs：`CountMergeWriter.write(action, rowId, userId)` 的 javadoc 明写「也是分片键，合并器不解析事件来推导它」；`CountMerger.submitAndWait(rowId, event)`。marketing：`QuotaMergeBuffer` 构造处校验 `IllegalArgumentException: shardKey(%s) 必须等于 request.invId(%s)`——键不一致直接拒收 | ✅ 两侧成立 |
| 2 | **同一业务行所有调用点给同一个 shardKey** | bs 三个调用点：`CommunityRepository` L283（POST_VIEW→postId）、L338/L343（LIKE/UNLIKE→postId）、L627（COMMENT_LIKE→commentId）、`TradeRepository` L275（PRODUCT_VIEW→productId）——同一行永远是同一个值；marketing 侧 `shardOf(invId)` 且预扣/确认共用 invId | ✅ 两侧成立 |
| 3 | **降级必须复用同一个落库方法**：不许另写一套「同步版」SQL | bs：`CountMergeWriter.degrade()` 调 `flushHandler.flushGroup(...)`（与引擎 flush 同一个 `ICountFlushHandler`，即 `CommunityRepository` 同一事务方法）；marketing：`QuotaMergeBuffer` 拒收后调用方走同一个 `flushDeductGroup` / `flushClaimGroup` | ✅ 两侧成立 |
| 4 | **丢弃必须计数**：任何「没接住就丢」的路径都要有可读数 | 引擎侧 `MergeMetrics.rejectedFull / rejectedClosed / failed / drainedFailed`；浏览 case 层 `ViewMergeWriter.droppedViews()`（与引擎读数分开，各自可读）；C4 用例 `viewCountIsMonotonicAndDropIsCounted` 断言丢弃数可读 | ✅ 两侧成立 |

补充一条结构事实（spec.md §9.7）：两份实现漂移从「编译器保证」变成「review 要看见」，压住手段是
同名单测（A1–A7 用例名逐字一致）+ 本清单四条。

## 未扫参声明（保留在案，调参不改结构）

窗口 / 批 / 分片 / 线程**全部是模型推导值，未经实测**：

- 计数侧（bs `application.yml`）：256 分片 / 5ms 窗口 / 批 200 / 分片容量 5000 / 等待 100ms / 4 线程；
- 库存侧（marketing `application.yml`）：64 分片 / 5ms 窗口 / 批 200 / 分片容量 2000 / 等待 100ms / 2 线程。

上线后**只按 `MergeMetrics` 调参**：`flushed ÷ batches`（合并比，≈1 说明长尾、合并器是净成本）、
`timedOut`（非 0 先看 `maxFlushNanos` 与 `waitTimeoutMs` 的关系）、`rejectedFull`（降级次数，容量信号）。
两份 yml 里都写明「调参不改代码结构」。D3 读数位在 `cutover-record-template.md` §5。

## 验收结果（票 10 全表核对）

### A 组 · 引擎不变量（两份各一份，用例名逐字一致）

| 票 | bs（`CountMergerTest`） | marketing（`QuotaMergeBufferTest`） |
|---|---|---|
| A1 | `everyAcceptedItemGetsExactlyOneTerminalState` | 同名 |
| A2 | `singleFlightNeverTwoConcurrentFlushes` | 同名 |
| A3 | `flushBlockingDoesNotBlockSubmit` | 同名 |
| A4 | `closeStillDrainsBufferedItems` / `closeRejectsNewSubmissions` / `drainFailureIsCounted` | 同名 |
| A5 | `rejectsWhenFullWithoutTouchingQueue` / `acceptedItemsSurviveRejection` | 同名 |
| A6 | `batchNeverExceedsMaxBatch` / `sameShardKeepsSubmissionOrder` | 同名 |
| A7 | `MergeConfigTest` + `CountMergeConfigTest`（7+5 条） | `MergeConfigTest` + `QuotaMergeConfigTest`（7+5 条，方法名逐字一致） |

`ShardBuffer` 两侧方法名逐项一致：`offer / bringForward / scheduleNext / runFlush / takeBatch /
failRemaining / failAllLocked / pendingSize / isIdle`（marketing 版 `Pending<?>` 泛型化 + 库存侧
`insufficientIsABusinessResultNotALoss` / `timeoutIsReportedAsUnknownResultNotFailure` /
`deductAndClaimNeverShareAGroup` / `rejectsShardKeyThatDiffersFromRequestInvId` 四条为库存特有出口）。
`MergeMetrics` 十字段两侧逐字一致（pending/accepted/flushed/batches/rejectedFull/rejectedClosed/timedOut/failed/drainedFailed/maxFlushNanos）。

### B 组 · 库存守恒（集成，全绿）

- B1 `InventoryQuotaConservationIntegrationTest#noOversellUnderConcurrency`
- B2 同类 `ledgerSumEqualsInitialSnapshot`
- B3 `detailSumEqualsWaitingPlusIssued_partialSuccess`（含「探一次再切」部分成功）
- B4 `wholeBatchDuplicate_replaysAsSuccessWithoutSecondDeduction` / `partialDuplicate_excludesExistingFromDeduction` / `transactionFailure_rollsBackAndRetryDoesNotDoubleDeduct` + `PhysicalQuotaIntegrationTest` 三条 replay
- B5 `claimWaitingShortage_rollsBackWholeGroup`（整组回滚 + 抛可重试，不留 `state = 0`）
- B6 `confirmIssued_transfersImmediately` + `RaffleShippingConsumerIntegrationTest#ship_physicalPrize_confirmTransfersImmediately`

### C 组 · 计数精确（全绿）

- C1/C5/C6/C4 落在重写后的 `PostCountMergeIntegrationTest`：`点赞成功即精确且重复赞不重复计` /
  `detailCountsNeverComeFromCache` / `取消赞后计数回落到关系行数且不为负` / `viewCountIsMonotonicAndDropIsCounted`
  —— C4 **只断言单调不减 + 丢弃可读 + 排空后至少一次增量**，明确无「浏览数 == 请求数」断言（javadoc 与断言注释都写明）。
- C2 `CommentLikeIntegrationTest` 6 条 + 新增「重复点赞不重复计」（acceptance §2 C6 授权的增量）；
  C3 `PostCommentBaselineIntegrationTest` 7 条。

### §4 处置清单落地

- **4.1 整份退休（3 类 20 条）**：`InventoryMergeEngineIntegrationTest`(12) / `InventoryMergeDeliveryLatencyTest`(1) / `InventoryMergeTriggerIntegrationTest`(7) 源码已不存在。
- **4.2 改写（6 类）**：`InventoryFaultIntegrationTest` → `InventoryFlushFailureIntegrationTest#flushFailureReturnsFailedThenRetryDoesNotDoubleDeduct`；`VirtualInventoryIntegrationTest` 4 条改写（`preDeduct_commitsLedgerImmediately` 等）；`PhysicalQuotaIntegrationTest` 改写（`preDeduct_commitsWaitingImmediately` / `confirmIssued_transfersImmediately` / `claimWaitingShortage_rollsBackWholeGroup` / `audit_readsLedgerColumnsDirectly` 四字段读数）；`RaffleShippingConsumerIntegrationTest` 实物档 3 条改写、`eventBeatsMerge` 退休、两条幂等保留；`RaffleShippingOutboxIntegrationTest` 库存 delay_level 半条退休、中奖事件保留（`delayLevel_raffleEventStaysZero`）；`PostCountAggregationIntegrationTest` → `PostCountMergeIntegrationTest` 整份重写；`InventoryMergeTestSupport` 改成 `awaitDrained / pendingOf`，`SwitchableInventoryBucketPort` 已删（全仓无引用）。
- **4.3 保持绿（7 类）**：`CommentLikeIntegrationTest`(6+1) / `PostCommentBaselineIntegrationTest`(7) / `CommentThreadingIntegrationTest` / `CommentRootListV2IntegrationTest` / `DrawCountBucketIntegrationTest` / `RaffleWarmupIntegrationTest`(3) / `PointsDeductIntegrationTest` 等积分域——本次 effort 全程未改断言（`git diff 5847e2c..HEAD` 仅 CommentLike +27 行 = C6 授权的新增一条）。

### D 组 · 切换后人工（SQL 已演练，执行留给窗口）

- D1（库存守恒 0 行）与 D2（like_count 与 COUNT(*) 逐行相等，取最新 20 行）两条 SQL 已在本地预发栈（`backend/docker` 的 bs-mysql 两库）实际执行通过——无 `Unknown column` / `Table doesn't exist`；D1 本地返回 0 行。正式预发/线上执行留给切换窗口。
- D3 读数位在 `cutover-runbook.md` 第 8 步 + `cutover-record-template.md` §5：`BUFFER_FULL` 降级 = 0、`drainFailed` = 0、`timedOut`（非 0 先看 `maxFlushNanos` vs `waitTimeoutMs`）、`flush 次数 / 请求数`（第一次观测基线）。
- `timedOut == 0` 判据链路演示随切换窗口执行，读数抄进切换记录。

### 全量构建与测试

- marketing：`mvn -o test` 全 reactor **BUILD SUCCESS，116 条 0 失败 0 错误**。
- bs：`mvn -o clean test -pl bs-app -am` 全 reactor **BUILD SUCCESS，341 条 0 失败 0 错误**（8 模块全 SUCCESS）。
- 测试库先 `scripts/test-env.sh reset-test-db` 重建为最终形态 schema 再跑（避免假绿）。
- 启动验证清单（无 `Unknown column` / `Table doesn't exist` / 「找不到执行器任务」）在
  `cutover-runbook.md` 第 7 步，本地测试栈上下文全量启动通过（同一套配置）。

> 过程记录：首轮 bs 全量时 Docker 宿机抖动把整个测试栈容器打断（`bs-nacos-test` Exited 255），
> 49 条报错全部是 `publishSharedConfig` 的 `ConnectException`——纯环境故障；栈恢复 + 测试库重建后重跑。

### 明确不做（acceptance §6）

不压测、不加 l2w256 策略、不建对账 job / 巡检、不加 COUNT(*) 校准口子。
