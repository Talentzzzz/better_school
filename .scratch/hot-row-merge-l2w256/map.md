# map：热点行写入换成进程内合并（L2W256）

Label: wayfinder:map

**Status: 目的地达成（2026-10-23）** —— `spec.md` 定稿、`docs/adr/0013-in-process-row-merge.md` 落盘、`acceptance.md` 出验收表。本 map 关闭；落地见「交接」。

## Destination

`.scratch/hot-row-merge-l2w256/spec.md` 定稿 + 一份 ADR：把**帖子点赞、评论点赞、帖子浏览量、商品浏览量、营销库存扣减**这五条「逐笔打同一行」的写，统一换成**进程内分片缓冲 + 定时窗口 + 共享固定执行池 + 合并成一条 UPDATE**（样板见 `inventory-l2w256/`），并把这些链路上专属的 **outbox 延迟消息 / MQ 消费组 / Redis 桶 / xxl 兜底 job** 整条摘掉。

语义底线：**库存「成功 = 已提交」不放宽**；计数分三档（票 02 / 04 已定）——**点赞数与关系行同事务、精确**；**浏览量异步、允许丢**；**评论总数与子回复数不动**（仍精确、与审核动作同事务）。

**本 map 只出决策与 spec，不写业务代码**；落地另起 `.scratch/hot-row-merge-l2w256-impl/`。

## Notes

- **范围**：`backend/better-school/`（community 域计数、trade 域商品浏览）+ `backend/marketing/`（inventory 域配额扣减）。参考件在仓库根 `inventory-l2w256/`（独立可跑，7 模块 DDD；合并器住在 `l2w256-domain` 里，自带 `@Service`）。
- **先纠前提（重要）**：用户最初的判断是「点赞/评论/库存都走 redis+mq」。逐行核对后，事实分两半：
  - **计数侧今天没有 Redis**。帖子点赞是「`post_like` 唯一键 INSERT + 同事务落 outbox 消息 → `bs-post-count` topic → `PostCountConsumer` → `PostCountAggregator`（200ms、单线程、100k msgId LRU）→ 窄表 `post_like_count` / `post.view_count` 合并 UPDATE」。商品浏览同理走 `bs-product-count`。**冗余在「outbox + MQ + 消费线程」这一段，不在 Redis。**
  - **库存侧才是真 Redis+MQ**：Redis 桶 Lua 预扣（`InventoryBucketPort`）→ 预扣明细 → 同事务落 outbox（`delay_ms = 2000`）→ `bs-inventory-merge` → `InventoryMergeServiceImpl.mergePair` 按时间格合两格 → `inventoryMergeBackstop` 60s 兜底（xxl-job）。
  - 所以「摘掉 MQ 依赖」只对这两条链路成立：MQ 还承载 11 个 topic（评论审核、搜索同步、聊天推送、跑腿超时、中奖/积分…），**RocketMQ 不会因此下线**；Redis 也不会（详情缓存、WS 票据、抢单滥用计数、审核在线态、抽奖概率表预热）。本次摘的是**这两条链路专属的那几个依赖**，不是中间件本身。收益要按这个口径算，别按「下线 Redis/MQ」算。
- **用户已定的口径**（本次不再重开）：
  1. 先定方案，代码另起实现轮次。
  2. ~~三块共用一套合并引擎（不是各写一份）~~ —— **已收窄**：共用的是**同一份合并语义**（算法 + 5 条不变量 + 参数名），代码不跨工程共享；工程内部（计数三件事）仍是一份实现。
  3. 范围含浏览量：帖子 + 商品浏览一起换。
  4. 库存「成功 = 已提交」：请求在进程内等 future，落库提交后才返回成功；崩溃则调用方拿不到成功，凭 `bizNo/bizId` 幂等重试。
  5. ~~评论总数也改成近似~~ —— **已被票 04 推翻**：评论总数与子回复数不动，保持精确（审核动作不是热点）。
  6. ~~引擎做成独立构件，两个工程各自依赖~~ —— **已被用户推翻（2026-10-22）**：不共享构件，两个工程各自在自己的 Spring Boot 里实现一份，跟着各自的 `@Component` / `@Transactional` / `@PreDestroy` 走。见票 06 的 `## Answer` 与 `## Comments`。
  7. 部署目前单实例；多实例用 key 哈希路由，本期只留 shade key 口子，不实现路由。
- **基线数字**（来自 `loadtesting/REPORT.md`，同机同表实测）：单热点行 DB 逐笔写的物理天花板 **≈4,079 TPS**（200 线程、Avg 49ms，加线程只加延迟）；`redis_mq` 受理 12.3k/s 但落库 5.9k/s、收尾要 130s；`merge` 受理 15.4k/s、零积压、台账只 UPDATE 996 次。参考工程 `inventory-l2w256` 单热点 500 并发 25,563 TPS。**这些只是「讨论合并收益」的锚点——本次不做压测验收**（用户 2026-10-22 明确，见票 10）。
- **参考工程里被本次判定为「对、但要改」的点**：`failAllIfSoldOut` 的二分拆批只适用于「批内数量不等」的场景，抽奖每笔恒为 1 件 ⇒ 改成「扣不动再探再切」；`REJECTED` 背压对点赞与抽奖都不适用（点赞降级逐笔写、抽奖降级单条同步扣）；`DeductStatus.TIMEOUT_UNKNOWN` 只对库存有意义（点赞同步成功/失败，浏览压根不等结果）。
- 用到的 skill：口径票 `grilling` + `domain-modeling`；事实票 `research`。

## Decisions so far

<!-- 索引：每个已关闭票一行；细节在该票 ## Answer 里 -->

- [01 · 三条写路径的真实依赖拓扑与可摘除面](issues/01-dependency-map.md)：专属可摘的是 3 个 topic/消费组 + `PostCountAggregator` + Redis 桶 + 兜底 job；outbox、RocketMQ、Redis、xxl 本身都还有别的使用方，**中间件不会因此下线**。
- [02 · 计数合并的失败语义](issues/02-counter-failure-semantics.md)：**点赞走「同步等结果 + 同事务批量 INSERT + 一条 UPDATE」，计数精确**；**浏览量走 fire-and-forget**；msgId 环形去重删除；缓冲满时点赞降级逐笔写、浏览降级单条 UPDATE 或丢弃。
- [03 · 计数的读路径](issues/03-counter-read-path.md)：窗口统一 5ms；**详情缓存不存计数**（内容进缓存、三个数每次单独查），`PostCountAggregator` 的 `evict` 整条删除。
- [04 · 计数口径的词条改写：评论总数要不要降级](issues/04-comment-count-approximate.md)：**评论总数与子回复数不动**（审核动作不是热点）；`CONTEXT.md` 的「互动计数」拆成「点赞计数（精确）」与「浏览量（近似）」，词条已落盘。
- [05 · 库存扣减去掉 Redis 桶后的等价模型](issues/05-inventory-merge-model.md)：**桶整层废除**；一批 = 一个事务（`INSERT IGNORE` 明细 + 一条 `UPDATE` 台账）；**扣不动再探再切**（不二分）；台账三列保留、明细删 `state/bucket_id` 与两条合并索引；缓冲满降级为单条同步扣减。
- [06 · merge-engine 构件的形态与不变量](issues/06-engine-spec.md)：**不做共享构件**（用户同日修正，推翻提问里「独立构件、无 Spring」的前提）——两个工程各自在自己的 Spring Boot 里实现一份，跟着各自的 `@Component` / `@Transactional` / `@PreDestroy` 走，只靠「算法 + 5 条不变量 + 参数名」对齐；**两个入口**（`submitAsync` / `submitAndWait`，否决 `waitMode` 参数），计数侧点赞与浏览共用一份分片与线程、按「动作类型 × shardKey」二次分组、**一组一个事务**；分片键取**行主键**、路由改成 `(key ^ key>>>32) & mask`（高位折叠，`key < 2^32` 时与 `& mask` 等价）；**`flushThreads == poolSize` 被推翻**——本仓库池是共享的（两 app 均 10 + 虚拟线程），改成 `≤ poolSize − 2`，计数 4 / 库存 2；5 条不变量（不丢件 / 单飞 / 锁不跨 DB / 停机三步 / 背压有界）全部保留；**没有任何场景把 `REJECTED` 返回给用户**，降级必复用同一个落库方法；长尾下合并比 ≈ 1，合并器在那条路上是净成本、收益只在热点，这点写进了 spec 而不是靠压测美化。
- [07 · 兑换确认去掉时间格合并后](issues/07-claim-transition.md)：**确认进合并器**（同分片 `invId`、与预扣共用一套落库形状，`waiting -= c, issued += c`），**幂等键仍是 `type = 2` 明细的 `uk_biz_type`**（票 05 的「`type` 暂留」结案为「常留」），**`waiting` 不足不再静默**——整组回滚 + 抛可重试异常 → MQ 重投 → 死信；确认只存在于实物段，所以方法**不带 `segment`**；预扣的 `TIMEOUT_UNKNOWN` 落成「不切兜底、不落库、抛可重试」。另纠一条前提：确认不是「用户点兑换」，是**每笔实物中奖自动确认一次**、与预扣同频。
- [08 · 对账读数与兜底](issues/08-reconcile-and-backstop.md)：**60s 兜底整条删掉，不建替代 job**——ADR-0011 列的三种丢失（消息丢 / Redis 扣了事务失败 / 崩在两步之间）在内存合并下一种都不剩，且兜底只按明细搬账、从不做业务核对（孤儿预扣它照实搬进 `waiting` 就没了下文）；**对账读数 7 → 4 个字段**（`pendingDeductQty` / `pendingClaimQty` 恒 0，`mergedDeductQty` / `ledgerConsistent` / `redisConsistent` 随桶或同事务退休，留 `invId / surplus / waiting / issued`），恒等式降级为集成测试断言；**xxl 用一条 `DELETE ... IN ('inventoryMergeBackstop','inventoryBucketWarmup')` 收口，先重灌 seed 再发版**。残留风险只剩「孤儿预扣」（预扣提交、`draw_record` 没落），旧兜底也管不了它，附人工 SQL 进 spec。
- [09 · 迁移与回滚](issues/09-cutover.md)：**不设开关（一个 PR 换掉）**——这次不是立场而是物理做不到：旧路径踩在将被删的列/表/桶/注册上，`merge.enabled=false` 启动即报错，且新逻辑不维护桶 ⇒ 双跑买不到「一次配置回滚」；**计数（bs）先切、库存（marketing）后切，单位是进程**（「只让商品浏览先跑一周」不存在，它在同一个 jar 里，会变成双跑），间隔 = 一个日间高峰 + 一次抽样对账，不是一周；**切流窗口只做可逆动作**：`state = 0` 用一次性 SQL 合完（单实例没有「停流不停进程」的中间态，旧兜底随进程停）、桶「先合完→再对账→最后清」（差额只记不修，仓库无流水表→进发布记录）、outbox 按 topic 删而非等排空；**不可逆点推到 D9 收口**（窗口内只 `ALTER … SET DEFAULT 1` + xxl `trigger_status = 0`，删列/删表/删注册 D9 做），于是回滚 = 停新 jar + 回滚 jar + 重灌桶 + 放开注册，**不需要反向 DDL、不需要恢复备份**；并修正票 08 一句：回滚形态里 `inventoryMergeBackstop` 必须回来（消息丢 ⇒ `surplus` 不降 ⇒ 超卖）。
- [10 · 验收](issues/10-acceptance.md)：**不压测**（用户 2026-10-22）。吞吐锚点（4,079 / 12,267 / 15,356 / 25,563 TPS）只作讨论收益，不作判据；验收改由测试断言承担，判据与现有测试的处置落在 [`acceptance.md`](acceptance.md)（A 组引擎不变量 / B 组库存守恒 / C 组计数精确 / D 组人工抽样）。

## Not yet specified

<!-- 见「Fog of war」：还在雾里、尚不能成票的 in-scope 问题 -->

- **搜索索引里的计数**：`PostSearchDocVO` 带 `likeCount`。点赞现在精确，但索引回写走的是 `bs-search-sync`（按内容变更触发，不按计数）⇒ 帖子被赞到爆时索引里的点赞数会长期不更新。要不要接受，或改成定期刷。
- **同类热点行是否纳入同一构件**：`post_comment.reply_count`、`user` 获赞聚合、通知计数器、商品 `sold_count` 等目前都是逐笔写；这次不动，但构件要能直接复用。
- **Redis 剩余角色是否收敛**：详情缓存 / WS 票据 / 抢单滥用 / 审核在线态 / 抽奖概率表——本次不动，但「最少依赖」的账本要写清还剩哪些、各自是否还有替代方案。

## Out of scope

<!-- 见「Out of scope」：被明确划到本 effort 终点之外的工作；已关闭，永不毕业 -->

- **点赞关系表结构**（`post_like`、评论点赞表的唯一键）：关系表是事实源，本次不动。
- **抽奖概率表预热与责任链**：Redis 概率表是决策侧前提，必须保留；本次只动库存配额那一段。
- **评论审核链路、通知链路、聊天推送**：仍走 outbox + MQ。
- **分库分表 / 读写分离**：本次不引入，合并后的单行写入仍是单库单表。
- **跨实例 key 路由的实现**：只留 shade key 口子，不实现网关/一致性哈希。
- **中间件下线**：RocketMQ / Redis / xxl-job 都还在，只是这三条链路不再依赖其中的某几件。
- **压测验收**（[票 10](issues/10-acceptance.md)）：本次不做吞吐/时延压测、不给验收阈值；正确性靠单测锁不变量 + 集成测试断言守恒，参数按模型推导、未扫参。收益数字只作讨论锚点。

## 交接（给下一个窗口）

**交付物已出（2026-10-23），effort 的目的地达成：**

| 产物 | 位置 | 说明 |
|---|---|---|
| spec | [`spec.md`](spec.md) | 10 节：目的地与边界 / 两份合并器形态 / 计数读写 / 库存落库与确认 / 删什么 / 上线与回滚 / 验收 / 测试与文档 / 残留风险 / 落地票索引 |
| ADR | [`docs/adr/0013-in-process-row-merge.md`](../../docs/adr/0013-in-process-row-merge.md) | 取代 0011 / 0012（两份历史记录不改） |
| 验收 | [`acceptance.md`](acceptance.md) | 票 10 的落地件：验收表 A/B/C/D + 现有测试的回归清单 |

**下一件事是落地，另起 `.scratch/hot-row-merge-l2w256-impl/`**，票序与依赖见 spec §10（01 bs 引擎 / 02 marketing 引擎可并行 → 03 bs 计数 / 04 marketing 库存 → 05 删兜底与读数 → 06 DDL 与脚本 → 07 全量回归）。

**写 spec / 落地前必须知道的七条已定前提**（都在对应票的 `## Answer` 里，别重开）：

1. **确认进合并器、明细常留**：`type = 2` 的兑换确认仍落 `physical_marketing_quota_detail`，`uk_biz_type (type, biz_type, biz_id)` 是幂等唯一裁决者；`flushGroup` 里预扣组与确认组各一个事务（不同批不同 `UPDATE`），确认组是 `waiting -= c, issued += c`。
2. **`waiting` 不足 = 不变量破坏，不是业务失败**：整组回滚 + `error` + 抛可重试 ⇒ MQ 重投到死信。不再有「静默留给对账」这条路。
3. **对账读数已瘦成台账三列**：`pendingDeductQty` / `pendingClaimQty` 恒 0（删），`mergedDeductQty` / `ledgerConsistent` / `redisConsistent` 随桶或同事务退休；`waiting + issued == SUM(type = 1)` 是**测试断言**，不是运行时读数。
4. **60s 兜底整条退休，不建替代 job**：合并器在进程内、调用方等 future、`state` 列已删。残留风险只有「孤儿预扣」，附人工 SQL（见票 08 §2），旧兜底本来也管不了。
5. **xxl 注册表：`inventoryMergeBackstop` / `inventoryBucketWarmup` 走「切流窗口 `trigger_status = 0` 停用 → D9 `DELETE`」两段**（票 09 §4/§5/§6 对票 08「一条 DELETE」的时序细化：最终形态一样，只是不可逆动作推到观察期后）；`inventoryMerge` 那条既有 `DELETE` 保留。
6. 库存侧合并器的既有形状不再松动：只有 `submitAndWait`（没有 `submitAsync`）、分片键 `invId`、`flushThreads = 2`、5 条不变量、参数未扫参（票 10 已划出压测）。
7. **回滚形态 ≠ 新形态**：回滚（停新 jar → 回滚 jar → 重灌桶 → 放开注册）里 `inventoryMergeBackstop` **必须在**（消息丢 ⇒ `surplus` 不降 ⇒ 超卖），这是票 09 对票 08 那句「回滚时这两个注册没什么需要回来的」的收窄——那句只成立于「只回滚 jar、表结构不回去」这个已被否决的形态。

**仓库里已经改过的文件**：`CONTEXT.md`（「互动计数」拆成「点赞计数」+「浏览量」两个词条，并修正了「评论总数」里的交叉引用）、`docs/adr/0013-in-process-row-merge.md`（新增），以及 `.scratch/hot-row-merge-l2w256/` 下的票、map、`spec.md`、`acceptance.md`。**代码一行未动。**

**落地时还要改的词条**（本期没动，因为它们描述的是尚未存在的形态）：`CONTEXT.md` 的「库存配额」「预扣」「时间格合并」仍然写着「Redis 只是余量面」「时间格合并」；「奖品类型」里「实物档的兑换确认是**异步**的：落一条 `type = 2` 的明细 + 一条定时 2 秒的合并消息」也被票 07 改掉了（现在是进合并器、等提交、`waiting` 不足要抛）。落库改造完成后要一起改写或删除，否则词条会指向已删的机制。

**新窗口开场白建议**：`照 .scratch/hot-row-merge-l2w256/spec.md §10 开落地轮次：先起 impl 的票 01（bs 版 CountMerger + A 组单测）`。
