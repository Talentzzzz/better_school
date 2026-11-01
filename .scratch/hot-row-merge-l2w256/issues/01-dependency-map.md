# 01 · 三条写路径的真实依赖拓扑与可摘除面

Type: research
Status: resolved
Blocked by: —

## Question

把「点赞/浏览计数」与「营销库存扣减」这两条链路今天依赖的每一件基础设施列清，回答三件事：

1. **逐个依赖点名**：这两条链路上，哪些是**专属依赖**（去掉这段代码就没有第二个使用方），哪些是**共享依赖**（别处还在用，摘了会连坐）。至少覆盖：outbox 表与发送器、`bs-post-count` / `bs-product-count` / `bs-inventory-merge` 三个 topic 及其消费组、Redis 桶 Lua、`delay_ms` 延迟消息、`inventoryMergeBackstop` 这个 xxl 任务、以及 xxl-job 在本工程的完整用途清单。
2. **对账读数的去处**：`pendingDeductQty` / `pendingClaimQty` 这类「在途量」读数由哪些 SQL 产出、被谁消费（接口/页面/告警），改造后这些读数还能不能算出来。
3. **测试与压测的锚点**：现有哪些集成测试/单测锁住了这条链路的不变量（类名 + 一句话），`loadtesting/` 那套三策略对照工程能不能直接复用为改造后的基线。

已确认、不必再查的部分：计数侧无 Redis；`PostCountAggregator` 为 200ms 单线程窗口 + 100k msgId LRU；库存侧是 Redis 桶 + 明细 + `cellTime` 两格合并 + 60s 兜底；MQ 共 11 个 topic。

产出写到 `.scratch/hot-row-merge-l2w256/research/dependency-map.md`，本票的 `## Answer` 只留结论指针。

## Answer

全文见 [`../research/dependency-map.md`](../research/dependency-map.md)。三条结论：

1. **专属可摘（三条边 + 三个件）**：`bs-post-count` + `bs-post-count-group`、`bs-product-count` + `bs-product-count-group`、`bs-inventory-merge` + `bs-marketing-inventory-merge-group`；以及 `PostCountAggregator`（含 100k msgId LRU）、`IInventoryBucketPort`（桶 Lua + 去重 key）、`inventoryMergeBackstop` 这个 xxl handler。写入点只有三处：`CommunityRepository.saveCountMessage`、`TradeRepository.saveCountMessage`、`InventoryRepository.insertDeductDetail` 里的合并消息。
2. **必须留的共享件**：outbox 基建与 `outboxMessageScan`（评论审核/搜索同步/聊天/跑腿/中奖积分都在用，共 8 条边）、RocketMQ 本身（还有 9 个 topic）、Redis（详情缓存/WS 票据/抢单滥用/审核在线态/抽奖概率表）、xxl-job（还有 5 个 handler）。**所以「最少依赖」的收益是「这三条链路不再依赖 outbox+MQ（以及库存的 Redis 桶）」，不是「下线中间件」。**
3. **两个连带面**：(a) `pendingDeductQty` 依赖 `state = 0` 的明细，合并消失后恒 0 → 票 08；(b) 应用单实例、Hikari 10 与 ShardingSphere 内层 20 两层池，参考工程「`flushThreads == poolSize`」的硬约束要按 10 还是 20 换算 → 票 06。
