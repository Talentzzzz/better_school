# 依赖拓扑：三条热点写路径的现状（票 01 事实底稿）

调查时间 2026-10-22，基线 `backend/` 当时 HEAD。只列事实与证据，不含建议。

## 0. 一句话结论

- **计数侧（点赞 / 浏览）今天没有 Redis**：走的是「业务行同事务写 `outbox_message` → RocketMQ → 消费线程 → JVM 内存窗口 → 合并 UPDATE」。要摘的是 **outbox + MQ + 消费线程**这一段。
- **库存侧才是真 Redis+MQ**：Redis 桶 Lua 预扣 → 明细 → outbox 定时消息（`delay_ms=2000`）→ 消费按时间格合两格 → 60s xxl 兜底。
- **两个中间件都不会因此下线**：RocketMQ 还有 9 条别的边，Redis 还有 5 处别的用途，xxl 还有 5 个别的任务。本次摘的是「这几条链路专属」的那几个件。

## 1. 计数侧的无条件证据：没有 Redis

`grep -rln "Redisson\|RedisTemplate\|redisson" backend/better-school` 全部命中里**没有一个**是点赞/评论/浏览链路：

| 文件 | 用途 |
|---|---|
| `bs-infrastructure/.../adapter/cache/post/PostDetailCache.java` | 帖子详情缓存（`post:detail:{id}`），**是计数的读者**，不是写入者 |
| `bs-infrastructure/.../adapter/port/chat/WsTicketPort.java` | WS 连接票据（一次性 60s） |
| `bs-infrastructure/.../adapter/port/errand/ErrandGrabAbusePort.java` | 抢单滥用滑动计数 |
| `bs-infrastructure/.../adapter/port/moderation/ReviewDispatchMetrics.java` | 审核派单计数 |
| `bs-infrastructure/.../adapter/port/moderation/ReviewPresencePort.java` | 审核员在线态 |
| `bs-infrastructure/.../adapter/repository/user/UserRepository.java` | 用户名预留/注册去重 |

## 2. 点赞与浏览量的完整链路

### 2.1 帖子点赞

1. `PostController` → `PostLikeCase.like` → `PostServiceImpl.likePost` → `CommunityRepository.likePostWithCountMessage`（`CommunityRepository.java:322`）。
2. 同一事务内两件事：
   - `addPostLike`：`INSERT` 到 `post_like`（`PostMapper.xml`，唯一键幂等，重复即抛「请勿重复点赞」）；
   - `saveCountMessage(postId, 1, 0)`：`outboxMessageStore.save(POST_COUNT_TOPIC, PostCountMessage.of(...))`（`CommunityRepository.java:245`）。
3. 提交后 outbox 异步发到 **`bs-post-count`**（`bs-types/.../Constants.java:14`），消费组 `bs-post-count-group`。
4. `PostCountConsumer`（`bs-trigger/.../listener/PostCountConsumer.java:20`）解析 JSON，把 postId 还原成 `PostCountMessage`，交给 `PostCountAggregator.submit`。
5. `PostCountAggregator`（`bs-infrastructure/.../repository/post/PostCountAggregator.java`）：
   - `FLUSH_INTERVAL_MILLIS = 200`（`:30`），单线程 `post-count-flusher`（`:49`）；
   - `synchronized` 的 `LinkedHashMap` 做 **msgId 环形去重，容量 100k**（`:33`、`:42`）；
   - `ConcurrentHashMap<Long, long[]>` 累加 `[likeDelta, viewDelta]`，刷盘时 `remove` 整槽；
   - 刷盘 SQL：`upsertLikeCount(postId, delta)` → `INSERT … ON DUPLICATE KEY UPDATE like_count = GREATEST(0, like_count + #{delta})`（`PostMapper.xml:306`）；`increaseViewCount` → `post.view_count` 同形态（`PostMapper.xml:316`）；
   - 点赞增量刷库后 `postDetailCache.evict(postId)`，纯浏览不失效（`:95`）。
6. **取消赞**走 `unlikePostWithCountMessage`，同样落 `-1` 消息；**没有** case 层（`PostLikeCase` 只有 `like`，unlike 在 controller 里直调 service）。

### 2.2 浏览量

- 帖子：`PostServiceImpl:188` → `saveViewCountMessage` → `saveCountMessage(postId, 0, 1)`，同一个 `bs-post-count`。
- 商品：`ProductServiceImpl:256` → `TradeRepository.saveViewCountMessage` → `PRODUCT_COUNT_TOPIC`（`bs-product-count`）→ `ProductCountConsumer` → 同一套窗口聚合（商品侧有自己的 agg/flush）。
- `post.view_count` / `product.view_count` 是**主表列**（不是窄表），与状态变更共用同一行。

## 3. 评论链路

### 3.1 评论总数 `post_comment_count`

- **发布评论不计数**。`saveCommentWithReviewMessage` 只插 `post_comment` + 落审核消息 + 入审核池（`CommunityRepository.java:362`）。
- **审核通过**才 `+1`：`approveComment` → `updatePostCommentCount(postId, +1)`，与状态变更同事务，SQL 是 `INSERT … ON DUPLICATE KEY UPDATE comment_count = GREATEST(0, comment_count + #{delta})`（`PostCommentMapper.xml:173`）。
- **软删/驳回**：`deleteComment` 按「删除的可见子回复数」扣减（不是恒 -1），同一 SQL。
- 计数变更后会 `postDetailCache.evict`（`CommunityRepository.updatePostCommentCount` 内）。
- 读：`PostMapper.xml:11` 用 `LEFT JOIN post_comment_count` 投影；`hot` 排序 `PostMapper.xml:160` 用 `like_count + comment_count*2`。

### 3.2 评论点赞 `post_comment.like_count`

- `PostController` → `CommentLikeCase.like` → `CommentServiceImpl.likeComment`（`:180`）→ `CommunityRepository.likeCommentWithCount`（`:506`）。
- 同事务：`INSERT` 评论点赞关系（唯一键） + `UPDATE post_comment SET like_count = GREATEST(0, like_count + #{delta})`（`PostCommentMapper.xml:158`）。
- **排序源与展示源同一个**：`ORDER BY c.like_count DESC, c.id`（`PostCommentMapper.xml:42`）。
- 单帖热评区一次刷新会把该帖评论的 `like_count` 全部读出来（`selectRootPageByPostId`）。

## 4. 营销库存（真正的 Redis + MQ）

```
Requirement: 抽奖主链路 → IInventoryQuotaService.preDeductWithFallback
  ├─ queryLedger(segment, invId)                        // 台账不存在 → 参数错误
  ├─ listBuckets(segment, invId)                        // 一 inv 多桶
  ├─ for bucket: InventoryBucketPort.tryDeduct( bucketKey, mergeDedupeKey, qty )
  │     Lua: 余量够 → DECRBY + 首个请求 SET NX PX 1000 决定 notifyMerge（固定窗口 throttle）
  ├─ inventoryRepository.insertDeductDetail(...)         // 同一事务：明细 INSERT + 可选 outbox（delay_ms=2000）
  └─ 撞 uk_biz_type → release(桶回补) + 按成功回放
```

- 桶 key：`inventory:bucket:{segment}:{invId}:{bucketId}`；去重 key：`inventory:merge:dedupe:{segment}:{invId}:{bucketId}`（`marketing-types/.../inventory/Constants.java`）。
- 消息：topic **`bs-inventory-merge`**，消费组 `bs-marketing-inventory-merge-group`（`InventoryMqConstants`）。
- 消费：`InventoryMergeConsumer` → `IInventoryMergeService.mergePair(segment, invId, bucketId, cellTime)`，固定合 `cellTime` 与 `cellTime+1s` 两格（ADR-0012）。
- 落账：`InventoryRepository` 内一个格一个事务，`UPDATE … WHERE redis_qty >= ?` 守卫 + 明细 `state` 翻转 + 行数校验。
- 兑换确认：`confirmIssued` 落 `type=2` 明细 + 一条 `cellTime` 消息，转移由合并完成（`InventoryQuotaServiceImpl.java:150`）。
- 兜底：`InventoryMergeBackstopJob`（`@XxlJob("inventoryMergeBackstop")`，60s，两段各 200 key）。
- 台账列：`surplus_qty` / `waiting_qty` / `issued_qty` / `redis_qty`（+ 多桶副本）；明细：`state`(0/1)、`uk_biz_type(type,biz_type,biz_id)`。
- 预热：`inventoryBucketWarmup`（当前 `trigger_status=0` 停用，且 `WarmupCustomer.triggerType=0` 时不插 xxl 行）。

## 5. 共享依赖清单（摘了会连坐）

### 5.1 RocketMQ：11 个 topic，本次只涉及 3 个

专属（本次目标是整条摘掉）：`bs-post-count`、`bs-product-count`、`bs-inventory-merge`。

其余（必须保留）：`bs-user-register`、`bs-errand-pay-timeout`、`bs-errand-paid`、`bs-notification-broadcast`、`bs-search-sync`、`bs-comment-review`、`bs-chat-push`、`bs-chat-read`、`bs-chat-recall`、`bs-lottery-drawn`、`bs-points-changed`。

### 5.2 outbox：基础设施是共享的，写入点是专属的

- `OutboxMessageStore` + 扫描 job `outboxMessageScan`：评论审核、搜索同步、聊天推送、跑腿超时/已付、中奖/积分、发货闭环都在用 → **基建必须留**。
- 本次要删的是三处调用点：`CommunityRepository.saveCountMessage`（`:245`）、`TradeRepository.saveCountMessage`（`:267`）、`InventoryRepository.insertDeductDetail` 内的合并消息写入。
- `outbox_message.delay_ms` 列：只有库存合并用得着；删掉合并后这一列是否留（`RaffleShippingConsumerIntegrationTest` 用它做过回归）见票 09。

### 5.3 Redis：5 处别的用途 + 抽奖概率表

详情缓存、WS 票据、抢单滥用、审核派单/在线态、抽奖概率表预热（`RaffleRepository`）。**Redis 本身不会下线**；本次只删 `IInventoryBucketPort` 的桶读写与去重 key。

### 5.4 xxl-job：6 个 handler，只涉及 1 个

`inventoryMergeBackstop`（本次目标）、`inventoryBucketWarmup`（停用）、`lotteryAwardWarmup`、`lotteryStrategyWarmup`、`outboxMessageScan`、`aggregateSchedulerJob`。

## 6. 对账读数的产出链

`InventoryAuditVO`：
- `pendingDeductQty`：`SELECT SUM(quantity) FROM …_inventory_detail WHERE inv_id=? AND state=0`（`queryPendingDeductQty`）；
- `pendingClaimQty`：`waiting_qty` 未转 issued 的部分（`queryPendingClaimQty`）；
- 消费方：库存审计服务 / 集成测试（`InMemoryAuditTestDao` 里两个方法）。

**去掉合并后 `state=0` 不再存在 ⇒ `pendingDeductQty` 恒 0**，这个读数的命名/去留是票 08 的题。

## 7. 锁住不变量的测试（改动的回归面）

| 测试 | 锁住什么 |
|---|---|
| `PostCountAggregationIntegrationTest` | 消费端聚合后窄表计数正确；outbox 表会收到消息 |
| `PostCommentBaselineIntegrationTest` | 评论审核通过 +1、软删按子回复数扣减 |
| `CommentLikeIntegrationTest` | 评论点赞唯一键幂等 + `like_count` 同步 ±1 |
| `PostCountAggregationIntegrationTest`（商品侧同名） | 商品浏览量窗口聚合 |
| `VirtualInventoryIntegrationTest` / `PhysicalQuotaIntegrationTest` | 两段的预扣/确认/台账守恒 |
| `InventoryMergeEngineIntegrationTest` | 时间格合并、两段分叉、翻转行数校验 |
| `InventoryMergeTriggerIntegrationTest` | outbox 消息 → 消费 → 落账 的端到端 |
| `InventoryFaultIntegrationTest` | 故障路径（桶扣了明细失败、消息丢失等） |
| `InventoryMergeDeliveryLatencyTest` | 定时消息投递延迟上界 3.2s（需 `-Dbs.inventory.mq-e2e=true`） |
| `InventoryRepositoryTest` / `OutboxMessageStoreTest` / `InventoryMergeMessageTest` | 仓储事务、outbox 扫描、载荷格式 |

## 8. 部署与容量事实

- 应用**单实例**：`scripts/run-app.sh` 起一个 jar；`docker-compose.yml` 只有中间件（nacos/mysql/redis/rocketmq/xxl/minio/es），没有 app 服务。
- MySQL 9.7，`docker-compose.yml` 未改隔离级别 → InnoDB 默认 **REPEATABLE READ**。
- 连接池两层：`spring.datasource.hikari.maximum-pool-size=10`，ShardingSphere 内层 `maxPoolSize=20`。参考工程的关键约束「`flushThreads == poolSize`」在这里要先换算清楚（票 06）。
- 压测工程 `loadtesting/`：三策略（`db` / `redis_mq` / `merge`）+ JMeter + 报告；基线数字 4,079 / 12,267 / 15,356 TPS。
