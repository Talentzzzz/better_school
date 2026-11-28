# 03: 帖子域 — 发布/提交返回结果对象 + 落领取任务 outbox + 标记已处理

**What to build:** 发帖与草稿提交在各自事务内落一条 PENDING 的「领取任务」outbox（不发送），按数据库时钟读回发布时间并据此定死 `periodKey`；发布/提交返回 `postId + 发布时间` 结果对象；新增按确定性 msgId 标记领取任务已处理的方法。旧 `bs-post-rebate` 消息不再由发帖仓储发送。

**Blocked by:** 01

**Status:** resolved

## 契约

### domain/post/model/valobj/`PostPublishResultVO`

```java
private String postId;
private LocalDateTime publishTime; // 数据库时钟：直接发布取插入行的 update_time；草稿提交取提交语句后的 update_time
```

### `IPostRepository` 变更

```java
/** 新建并发布：写 post + feed 索引 + search-sync outbox + 领取任务 outbox；提交后只同步发送 search-sync */
PostPublishResultVO savePublished(PostEntity postEntity);

/** 提交草稿：DRAFT → NORMAL + feed 索引 + 两条 outbox；状态已变化返回 null；提交后只发送 search-sync */
PostPublishResultVO submitDraftAsPublished(String userId, String postId);

/** 把该帖子的领取任务 outbox 按确定性 msgId 标记已发送（state=1）；幂等 */
void markRebateClaimTaskProcessed(String postId);
```

### `IPostService` 变更

```java
PostPublishResultVO publishPost(String userId, String title, String content, List<String> images, String cover);
/** 提交草稿；状态已变化时仍抛原来的 BusinessException（"草稿状态已变化，请刷新后重试"） */
PostPublishResultVO submitPostDraft(String userId, String postId);
```

`savePostDraft` / `updatePostDraft` 不变（草稿不触发返利、不占名额）。

## 实现要点（`infrastructure/.../post/PostRepository`）

- 事务内：
  1. 插入 post（直接发布）或条件 UPDATE 状态（提交草稿）；
  2. `upsertFeedIndexOnApprove`；
  3. 新增 DAO 查询读回 `update_time`（建议 `IPostDao.selectPublishTime(String postId)` + `PostMapper.xml` 单表 `SELECT update_time FROM post WHERE post_id = #{postId}`），作为 `publishTime`；
  4. `periodKey = RebatePeriodKeys.of(publishTime)`；
  5. 插 search-sync outbox（现有 `postSearchSyncMessage`，随机 msgId 不变）；
  6. 插领取任务 outbox：
     `MqOutbox.pending(PostRebateTaskKeys.claimOf(postId), Constants.POST_REBATE_CLAIM_TOPIC, new PostRebateClaimTaskMessage(postId, userId, periodKey), objectMapper)`。
- **领取任务不在发帖仓储内发送**（保持 PENDING，等 case 领取后标记；异常时交 OutboxScanJob 重放到 claim topic）。
- 事务提交后只对 search-sync outbox 调 `sendOutbox`；删除旧返利 outbox 的插入与发送。
- 删除 `PostRebateTaskMessage` / `Constants.POST_REBATE_TOPIC` / `PostRebateTaskKeys.of` 的本文件引用（类本身留票 06 删）。
- `submitDraftAsPublished` 返回 null 时，`PostServiceImpl.submitPostDraft` 抛原异常。
- `markRebateClaimTaskProcessed(postId)` → `mqOutboxDao.markSentByMsgId(PostRebateTaskKeys.claimOf(postId))`，无需显式事务（单条条件 UPDATE）。
- `savePublished` 仍设置 `postId = UUID.randomUUID().toString()` 并在插入前写入实体；`createdAt` 仍由 SQL `NOW()` 落库。

## 验收

- [x] 直接发布与草稿提交均落一条 `msg_id = post-rebate-claim:{postId}`、`destination = bs-post-rebate-claim` 的 outbox，且提交后不发它
- [x] 保存/编辑草稿不落任何领取任务 outbox
- [x] 草稿提交以「提交时刻」而非草稿创建时刻计算 `periodKey`（读 `update_time`）
- [x] 发布/提交返回值带 `postId` 与数据库发布时间
- [x] search-sync 行为不变（照旧提交后同步发送、失败留 PENDING）
- [x] `markRebateClaimTaskProcessed` 重复调用无副作用

## Comments

**2026-11-26** — 已实现并静态自检（本机无 JDK21/Maven，未编译）。

改动文件：

- `bs-domain/.../post/model/valobj/PostPublishResultVO.java`（新）：`postId` + `publishTime`，`@Data @AllArgsConstructor @NoArgsConstructor`。
- `bs-domain/.../post/adapter/repository/IPostRepository.java`：`savePublished`/`submitDraftAsPublished` 改返回 `PostPublishResultVO`（草稿提交 null = 状态已变化），新增 `markRebateClaimTaskProcessed`；注释改为两段式语义。
- `bs-domain/.../post/service/post/IPostService.java`、`PostServiceImpl.java`：`publishPost`/`submitPostDraft` 改返回 `PostPublishResultVO`；`submitPostDraft` 对仓储 null 仍抛原 `BusinessException("草稿状态已变化，请刷新后重试")`；`savePostDraft`/`updatePostDraft` 未动。
- `bs-infrastructure/.../adapter/repository/post/PostRepository.java`：发布/提交事务内落 `post-rebate-claim:{postId}`（`Constants.POST_REBATE_CLAIM_TOPIC`，`PostRebateClaimTaskMessage(postId, userId, periodKey)`，PENDING、不发送）；`periodKey = RebatePeriodKeys.of(update_time)`（直接发布=插入行、草稿提交=条件 UPDATE 后的数据库时钟）；search-sync 改走既有私有 `saveOutbox`（事务内落行、afterCommit 同步发送，失败留 PENDING）；`markRebateClaimTaskProcessed` 直接 `mqOutboxDao.markSentByMsgId(claimOf(postId))`。删除 `PostRebateTaskMessage` / `Constants.POST_REBATE_TOPIC` / `PostRebateTaskKeys.of` 引用与「两条 outbox 一起发」的循环。
- `bs-infrastructure/.../dao/post/IPostDao.java` + `bs-app/.../mapper/PostMapper.xml`：新增 `LocalDateTime selectUpdateTime(postId)`，单表 `SELECT update_time FROM post WHERE post_id = #{postId}`（无表别名、无 AS；不复用 `selectById`，其列片段不含 `update_time`）。

决策与说明：

- 草稿提交用 `return null` 表示条件 UPDATE 未命中；`postDetailCache.evict(postId)` 仍在事务后执行（与旧行为一致，null 路径也 evict）。
- 领取任务 outbox 保持 PENDING，不在发帖仓储内发送；异常/宕机由 `outboxScan` 重放到 claim topic。
- `msgId` 前缀 18 + UUID 36 = 54，小于 `mq_outbox.msg_id VARCHAR(64)`。
- 票面建议方法名 `selectPublishTime`，按本次任务口径落为 `selectUpdateTime`。
- `IPostService` 未新增 `markRebateClaimTaskProcessed` 委托（本票契约只要求仓储方法；票 05 的 case 调用需要时再在服务层补一个透传方法）。
- 已知跨票影响：`PostPublishCase.publishPost` 仍声明 `String` 并 `return postService.publishPost(...)`，本票改签名后该行需由票 05 改写；`submitPostDraft` 的调用忽略返回值，不受影响。`PostServiceImpl` 自身与新返回类型一致。
