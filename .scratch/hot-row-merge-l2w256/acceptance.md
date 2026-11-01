# 验收表：热点行写入换进程内合并（L2W256）

Label: wayfinder:ticket（票 10 的实现件）
Date: 2026-10-23
Scope: `backend/better-school/`（社区计数）+ `backend/marketing/`（库存配额）

> 本文件是 `.scratch/hot-row-merge-l2w256/issues/10-acceptance.md` 的 `## Answer` 落地件：
> 把 02 / 05 / 06 / 07 / 08 / 09 已定的语义翻成**指标 / 基线 / 目标 / 用什么测**，并为落地轮次给出
> **现有测试的处置清单**（保留 / 改写 / 退休）。不新增判据。

## 0. 一句话结论

**不压测。验收 = 三组断言 + 一步人工抽样**：引擎不变量（两份实现各一份单测）、库存守恒（集成测试）、
计数精确（集成测试），加票 09 第 8 步的切换后抽样对账。吞吐与时延数字**只作讨论锚点，不作判据**。

## 1. 为什么没有吞吐 / p99 判据

1. **基线不可比**：`4,079`（单热点逐笔写物理天花板）/ `12,267` / `15,356`（`merge` 策略）来自
   `loadtesting/REPORT.md` 的另一套 app 与表；`25,563` 来自参考工程独立服务（池 20）。本仓库池是
   **共享的 `hikari.maximum-pool-size = 10` + 虚拟线程**（票 06 §3），任何阈值都是编的。
2. **平均吞吐会骗人**：合并器的收益只在「同一行的并发」；长尾（1 万帖/秒各一赞）下合并比 ≈1，
   合并器是净成本（票 06 §2）。用平均 TPS 做判据，恰好把这条代价藏起来。
3. **参数未扫参**（票 06 §3）：窗口 5ms / 批 200 / 分片 256 全是模型推导。压测本该回答的问题，
   本次诚实标为未知 —— 所以判据只能落在**结构性质**（同事务、恰好一条 UPDATE、无 MQ）上。

## 2. 验收表

| # | 组 | 指标 | 基线（今天的形态） | 目标（判据） | 用什么测 |
|---|---|---|---|---|---|
| A1 | 引擎 | 不丢件、恰一个终态 | 参考工程 `StockMergeServiceImplTest.concurrentDeductNoLoss` | `ACCEPTED` 的件数 == 终态件数，每件至多一个终态 | `everyAcceptedItemGetsExactlyOneTerminalState`：8 线程 × N 件 |
| A2 | 引擎 | 单飞 | 参考工程 `ShardBufferTest.singleFlight` | 同一分片最大并发 flush == 1，件数一件不少 | `singleFlightNeverTwoConcurrentFlushes`（落库阻塞） |
| A3 | 引擎 | 锁不跨 DB | 参考工程同形态（`ReentrantLock`，临界区只碰内存） | 落库阻塞 300ms 期间 `submitAsync` 仍毫秒级返回 | `flushBlockingDoesNotBlockSubmit` |
| A4 | 引擎 | 停机三步 | 参考工程 `rejectAfterShutdown` | 停收 → 排空 → 关池；`close()` 后提交拿 `CLOSED`；排空失败计数 > 0 | `closeStillDrainsBufferedItems` / `closeRejectsNewSubmissions` / `drainFailureIsCounted` |
| A5 | 引擎 | 背压有界 | 参考工程 `rejectsWhenBufferFull` | 满时 `offer` 立即 false；已入队件不受影响；合并器自己不丢件 | `rejectsWhenFullWithoutTouchingQueue` / `acceptedItemsSurviveRejection` |
| A6 | 引擎 | 批上限与保序 | 参考工程 `batchCappedAtMaxBatch` | 单批 ≤ `maxBatch`；同分片内入队序 == 出队序 | `batchNeverExceedsMaxBatch` / `sameShardKeepsSubmissionOrder` |
| A7 | 引擎 | 参数构造即校验 | 参考工程 `MergeConfigVOTest`（4 条） | `shardCount` 非 2 的幂 / `waitTimeoutMs ≤ windowMs` / `shardCapacity < maxBatch` 构造即失败 | `MergeConfigTest`（两份实现各一份） |
| B1 | 库存 | 零超卖 | Redis 桶 Lua 预扣（两阶段） | `surplus = k` 时并发 N 笔恰好 k 笔成功，终值 0，任何时刻不为负 | 集成：`InventoryQuotaConservationIntegrationTest#noOversellUnderConcurrency` |
| B2 | 库存 | 三列守恒 | 票 09 第 1 步的初值快照 | 逐 inv：`surplus + waiting + issued == 初值` | 集成：同一类的 `ledgerSumEqualsInitialSnapshot` |
| B3 | 库存 | 明细等式 | 票 08 §4（今天恒真，但只靠运行时读数） | `waiting + issued == SUM(quantity) WHERE type = 1`；**含「探一次再切」分支**（`k <` 批内笔数时仍相等） | 集成：`detailSumEqualsWaitingPlusIssued`（含部分成功用例） |
| B4 | 库存 | 预扣幂等 | 现状 `uk_biz_type` 撞键回放 | 同 `recordId` 重放：明细仍 1 行、净扣 1 份 | 改写 `PhysicalQuotaIntegrationTest` 的三条 replay |
| B5 | 库存 | `waiting` 不足不静默 | 现状：整格回滚留 `state = 0`，`pendingClaimQty > 0` 等对账 | 整组回滚（含确认明细）+ 抛可重试；不留半组、不落状态 | 改写 `mergeClaim_waitingShortage_keepsCellUnmerged` → `claimWaitingShortage_rollsBackWholeGroup` |
| B6 | 库存 | 确认即转移 | 现状：落 `type=2` + 2s 延迟消息，消费后翻账 | 确认返回时 `waiting -= c, issued += c` 已提交，无第二步 | 改写 `RaffleShippingConsumerIntegrationTest#ship_physicalPrize_*` |
| C1 | 计数 | 点赞计数 == 关系行数 | 现状：outbox → MQ → 200ms 聚合，允许滞后（票 09 第 3 步要人工重算） | 点赞接口返回成功后，`COUNT(post_like WHERE post_id)` == `post_like_count.like_count`，**无需等待** | 改写 `PostCountAggregationIntegrationTest` 全 3 条 |
| C2 | 计数 | 评论点赞精确 | 现状精确（同事务） | 不变 ±1、唯一键幂等、可取消再赞 | `CommentLikeIntegrationTest` 现有 6 条**保持绿** |
| C3 | 计数 | 评论总数精确 | 票 04：审核动作不是热点 | 不变（+1 / 删除 -1） | `PostCommentBaselineIntegrationTest` 现有 7 条**保持绿** |
| C4 | 计数 | 浏览量（唯一允许丢） | 现状 200ms 聚合，失败并回窗口 | **不断言等于请求数**：只断言单调不减 + 丢弃可读数（`droppedViews`） | 新用例 `viewCountIsMonotonicAndDropIsCounted` |
| C5 | 计数 | 详情缓存不存计数 | 现状：缓存带三个数 + 刷库后 `evict` | 详情页三个数恒等于库值（缓存命中路径下也成立） | 新用例 `detailCountsNeverComeFromCache`（先赞 → 再读详情） |
| C6 | 计数 | 无 msgId 去重 | 现状 100k LRU + `synchronized` | 去重删除；重复点赞由唯一键裁决（第二次不重复计） | `CommentLikeIntegrationTest#点赞幂等` 已覆盖；加一条「重复点赞不重复计」 |
| D1 | 人工 | 库存守恒抽样 | 票 09 第 8 步 SQL① | 返回 **0 行** | 切换后执行（线上库） |
| D2 | 人工 | 计数抽样 | 票 09 第 8 步 SQL② | `like_count` 与 `COUNT(*)` **逐行相等**（取 `update_time` 最新 20 行） | 切换后执行（线上库） |
| D3 | 人工 | 合并比 / 降级 | 无（新读数） | `BUFFER_FULL` 降级 == 0、`drainFailed` == 0；记录 `flush 次数 / 请求数`，作为第一次观测基线 | `MergeMetrics` 快照，抄进发布记录 |

**A 组的「基线」是形态基线不是数字基线**：参考工程已有等价断言，本次照抄用例语义（票 06 §9「用例名也照 §5 抄」），
目的是让两份实现的漂移在自己工程里先红。

## 3. 时延预算（预算，不是实测目标）

只有「不超时」是判据，p99 数字不测。

| 路径 | 基线 | 预算 | 依据 |
|---|---|---|---|
| 点赞 / 取消赞 / 评论点赞 | 一次 HTTP + 一次事务（无窗口） | `windowMs(5) + flush`；flush ≈ 0.5ms + 0.04ms × 行数（200 行 ≈ 8ms） | 票 06 §2、§3 |
| 抽奖预扣（`preDeduct`） | Redis 预扣 ≈ 1ms，无 DB 等待 | 同上；硬上限 `waitTimeoutMs = 100`，超时即 `TIMEOUT_UNKNOWN` | 票 07 §6 |
| 兑换确认（MQ 消费者侧） | 一次同步 SQL | 同上；`TIMEOUT` 与 `FAILED` 合并成一个可重试出口（幂等键兜住） | 票 07 §5 |
| 帖子 / 商品浏览 | 200ms 聚合 | **立即返回**（fire-and-forget，不进等待） | 票 02 |

判据写法：链路演示里 `timedOut == 0`；一旦非 0，先看 `maxFlushNanos` 与 `waitTimeoutMs` 的关系，再调参数（不改结构）。

## 4. 回归清单（现有测试的处置）

统计口径：`@Test` 计数为当前文件实际值。

### 4.1 整份退休（测的是被删掉的机制）

| 测试类 | 用例数 | 理由 |
|---|---|---|
| `InventoryMergeEngineIntegrationTest` | 12 | 两格工单、`cellTime`、`state = 1`、兜底 `backstopOnce` / `mergeKey` 全部退休（票 05 / 08 §6） |
| `InventoryMergeDeliveryLatencyTest` | 1 | 测「定时消息 2.0~3.2s 投递」，消息本身不存在 |
| `InventoryMergeTriggerIntegrationTest` | 7 | 「扣成功才发消息 / `delay_ms = 2000` / 扫描重发 / 去重 key / outbox 回滚」整组失去对象；其中「明细与 outbox 同事务」的价值由 B3 的「一组一事务」承接 |

### 4.2 部分退休 / 改写

| 测试类 | 用例数 | 处置 |
|---|---|---|
| `InventoryFaultIntegrationTest` | 3 | 2 条 `redisUnavailable_*` 退休（桶没了，「Redis 不可用」不是故障）；`mergeResume_convergesLedger_evenWithRedisDown` 改写成 `flushFailureReturnsFailedThenRetryDoesNotDoubleDeduct` |
| `VirtualInventoryIntegrationTest` | 10 | `bucketKey_usesSegmentCodeInPath` / `preDeduct_updatesRedisAndDetail_butNotLedger` / `audit_redisDrift_reportsFalseWithoutRepair` 退休；其余改写为「预扣即 `surplus -= N, issued += N` + 明细一行，台账同事务可见」；`backstopOnce_*` 三条改名去掉 backstop 语义 |
| `PhysicalQuotaIntegrationTest` | 13 | 「预扣后台账未变、合并后才 `waiting` 增」整组改写为「预扣返回即 `waiting` 增」（B2/B4）；`confirmIssued_landsClaimDetail_thenMergeTransfers` 改为「返回即转移」（B6）；`mergeClaim_waitingShortage_*` 改为抛（B5）；`audit_unifiedIdentity_separatesPendingClaim` 改写为 4 字段读数（票 08 §4） |
| `RaffleShippingConsumerIntegrationTest` | 7 | 实物档 3 条改写（B6）；`ship_physicalPrize_eventBeatsMerge_stillConfirmsAndSettlesInOrder` 退休（顺序不再由 `cellTime` 决定，由同分片单飞决定）；两条幂等用例保留 |
| `RaffleShippingOutboxIntegrationTest` | 7 | 中奖事件 outbox（`bs-lottery-drawn`）保留；`delayLevel_isPersistedAndSharedSenderKeepsRaffleAtZero` 里「库存合并消息 `delay_level = 1`」那半条退休，中奖事件那半条保留 |
| `PostCountAggregationIntegrationTest` | 3 | 整份重写成 `PostCountMergeIntegrationTest`：「点赞 → 窄表即 1（不等窗口）」「取消赞 → 回 0」「浏览 → `view_count` 递增（`awaitTrue` 等 fire-and-forget）」；删除 msgId 重投断言 |
| `InventoryMergeTestSupport` | — | 改写成「触发一次 flush / 等待排空」的工具；`SwitchableInventoryBucketPort` 删除 |

### 4.3 应保持绿（外部行为不变，是这次改对的旁证）

| 测试类 | 用例数 | 为什么不该红 |
|---|---|---|
| `CommentLikeIntegrationTest` | 6 | 断言的是 HTTP 结果与计数 ±1、通知去重、最热排序，不碰内部链路 |
| `PostCommentBaselineIntegrationTest` | 7 | 评论总数**不动**（票 04） |
| `CommentThreadingIntegrationTest` / `CommentRootListV2IntegrationTest` | — | `reply_count` 不动 |
| `DrawCountBucketIntegrationTest` | — | `draw_count_bucket` 是用户资产，白名单外（票 09 §6） |
| `RaffleWarmupIntegrationTest` | 3 | 概率表预热保留（map 的 out of scope） |
| `PointsDeductIntegrationTest` 等积分域 | — | 本次不动 |

**红了的含义**：4.3 里任何一条红，先怀疑实现（同事务形状写错 / 计数被缓存），不要改断言。

## 5. 未扫参声明与人工观测（票 09 第 8 步 = 本表 D1~D3）

- 参数默认值（票 06 §3）：计数 `256 / 5ms / 200 / 100ms / 5000 / 4 线程`，库存 `64 / 5ms / 200 / 100ms / 2000 / 2 线程`。
  **全部未经实测**；调参依据只有 `MergeMetrics` 的 `flushed ÷ batches`（合并比）与 `timedOut` / `rejectedFull`。
- 人工验收只做三件事：跑 D1、D2 两条 SQL（期望 0 行 / 逐行相等），记 D3 的读数进发布记录。
- 差额与守恒**没有自动信号**（本工程无 metrics 端点、`alarm_email` 为空，票 08 §3）——D 组是这件事唯一的补偿。

## 6. 明确不做

- 不往 `loadtesting/` 加 `l2w256` 策略，不改那套 app；不给 TPS / p99 阈值。
- 不建对账 job、不建只读巡检（票 08 §3）。
- 不新增「校准」逻辑：点赞有唯一键、库存有守恒断言、浏览允许丢 —— 三条都不需要 `COUNT(*)` 校准口子。
