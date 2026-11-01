# 04: bs 浏览换 fire-and-forget，并摘掉计数侧 MQ 骨架

**Spec:** `.scratch/hot-row-merge-l2w256/spec.md` §3.2、§3.4、§5.1、§5.4；判据见 `acceptance.md` C4

**What to build:** 帖子浏览量、商品浏览量从「outbox → MQ → 200ms 聚合」换成入队即返回、窗口过后一条 `UPDATE … view_count = view_count + N`。浏览没有关系行、没有真相表可对账——**允许丢**，失败只记丢弃数，不告警、不回补、不影响请求结果。

降级：缓冲满 / 停机中也只做一次独立 `UPDATE … + 1`，再失败就丢弃并计数；等超时这条不存在（本来没等）。

计数侧两条链路搬完之后，MQ 骨架成为死代码，本票一并摘掉：两个 Consumer、聚合器（含 100k LRU 与 `synchronized`）、两个 topic 常量。同时删掉 msgId 环形去重与「刷盘失败并回窗口」——重投源头已消失，点赞幂等由唯一键承担，浏览本无幂等；**至多一次**，失败只记 `failed`。

**Blocked by:** 01, 03（计数 Consumer 只有在点赞也不再走消息之后才无引用）

**Status:** resolved

- [x] 帖子浏览、商品浏览走 `submitAsync`，入队即返回，HTTP 时延不随窗口变化
- [x] 窗口后一条 `UPDATE … view_count = view_count + N`，`shardKey` 分别取 `postId` / `productId`
- [x] C4：不断言「等于请求数」——只断言 `view_count` 单调不减，且丢弃可读数（`droppedViews`），新增用例 `viewCountIsMonotonicAndDropIsCounted`
- [x] 降级可观测：缓冲满 / 停机中 → 一次独立 `UPDATE … + 1`，再失败丢弃并计数；不返回错误给用户
- [x] 计数侧消息骨架清零：`PostCountConsumer`、`ProductCountConsumer`、`PostCountAggregator`、两个 topic 常量全删；启动日志里不再出现聚合器
- [x] msgId 环形去重与「失败并回窗口」删除，`failed` 计数承担全部失败可见性
- [x] 长尾代价写进代码注释或提交说明：单帖热点的合并比随速率涨，长尾下合并比 ≈ 1，此时合并器是净成本（多一次排队）——这是选「统一一条路」的代价，不是待优化项
- [x] 浏览的合并 `UPDATE` 与「审核通过 / 软删 / 编辑」抢同一把行锁这一已知窗口不改（列挪窄表不在本次范围），只在注释里留一句指向 spec §9.3

## 落地记录

- **`ViewMergeWriter`（`infrastructure/adapter/merge`）是浏览的唯一入口**：`submitAsync` 入队即返回；
  `BUFFER_FULL` / `CLOSED` → **调用方线程**同步 `flushGroup(action, rowId, [event])`（组大小恒 1）；
  那一次也失败 → `droppedViews++`，`log.debug`，**绝不抛**给用户。丢弃的读数是
  `ViewMergeWriter#droppedViews()`（case 层），与引擎的 `MergeMetrics.failed / drainedFailed`（已入队后
  落库失败 / 停机排空超时）分开读，两者相加才是「丢了多少次浏览」。
- **一个合并器、两个域的落库方法 ⇒ 新增 `CountFlushDispatcher`（`@Primary`）**：按**动作**分发
  （`POST_LIKE / POST_UNLIKE / COMMENT_LIKE / POST_VIEW` → `CommunityRepository.flushGroup`，
  `PRODUCT_VIEW` → `TradeRepository#flushViewGroup`）。`TradeRepository` 刻意**不**实现
  `ICountFlushHandler`：那个接口签名带基础设施的 `CountEvent`，而 `ITradeRepository` 是域接口。
- **浏览落库只有一条 SQL，不套 `TransactionTemplate`**（单条 UPDATE 自身即原子）；但必须回一组
  **等长、非 null** 的占位结果（`Collections.nCopies(n, LikeState.APPLIED)`）——引擎把「null / 缺位」
  当漏回填补 `FAILED`，那会让 `flushed` 漏算、合并比与「accepted − flushed」两个读数同时失真。
  占位值是新增枚举 `LikeState.APPLIED`（评审把初版的 `LIKED` 占位改掉了）：点赞路径拿到它会直接抛
  （`CountMergeWriter#toDomain`），占位值不可能混进等结果的那条路；约定写进了
  `ICountFlushHandler` 与 `LikeState` 的文档。
- **一处对 spec §2.6 矩阵的有意修订，以票为准**：spec 矩阵写「停机中 → 直接丢弃」，票 04 明说
  「缓冲满 / 停机中**都**只做一次独立 `UPDATE … + 1`」——实现照票走（优雅停机时连接池还活着，
  调用方线程补一次单行 UPDATE 便宜且能救回一件；两条降级路径同形也少一个分支）。
- **删掉的东西**：`PostCountConsumer`、`ProductCountConsumer`、`PostCountAggregator`、
  `PostCountMessage`、`ProductCountMessage`、`Constants.POST_COUNT_TOPIC` / `PRODUCT_COUNT_TOPIC`、
  `ICommunityRepository.submitCountMessage / saveCountMessage / saveViewCountMessage`、
  `ITradeRepository.updateCountDelta / saveCountMessage / saveViewCountMessage`。
  msgId 环形去重与「刷盘失败并回窗口」随聚合器一起消失；失败可见性只剩引擎读数 + `droppedViews`。
  端口的对外形状换成 `recordPostView(postId)` / `recordProductView(productId)`（域接口不出现任何合并类型）。
- **新用例**：`ViewMergeWriterTest`（6 条：入队不阻塞、线程归属、`BUFFER_FULL` / `CLOSED` 降级各
  一次独立 +1、降级失败丢弃计数、非浏览动作拒收）；`PostCountMergeIntegrationTest`
  的 `viewCountIsMonotonicAndDropIsCounted`（C4）+ `一组浏览件合并成一条Update加N`；
  `ProductViewMergeIntegrationTest`（2 条，验「动作 → 表」路由没走错）。
  `ConsumeHandlerVirtualThreadIntegrationTest` 的消费者清单 14 → 12（两个计数 Consumer 下线）。
- **长尾代价（不是待优化项）**：合并的收益只来自「同一行的并发」。单热点下合并比随速率涨；
  **长尾下合并比 ≈ 1，合并器在那条路上是净成本**（多一次入队 + 5ms 窗口）。这是选「统一一条路」
  （点赞与浏览共用一份分片与线程池，spec §2.2）的代价——真要优化就得为浏览单开直写路径，那会把两份参数、
  两份观测、两个降级口径一起带回来。
- **已知窗口照旧**：`view_count` 在主表行上，合并 UPDATE 与「审核通过 / 软删 / 编辑」抢同一把行锁；
  5ms 窗口把频率压到每帖 ≤ 200 次/秒，可接受，列挪窄表不在本次范围（spec §9.3）。
- **文档**：`better-school/docs/db/community.md` 的写路径一段（不再有 200ms 聚合 / msgId 去重）、
  `backend/docs/testing.md` 的测试清单（指向改名后的 `PostCountMergeIntegrationTest`）。
