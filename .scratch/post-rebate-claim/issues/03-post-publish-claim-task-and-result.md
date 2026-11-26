# 03: 帖子域 — 发布/提交返回结果对象 + 落领取任务 outbox + 标记已处理

**What to build:** 发帖与草稿提交在各自事务内落一条 PENDING 的「领取任务」outbox（不发送），按数据库时钟读回发布时间并据此定死 `periodKey`；发布/提交返回 `postId + 发布时间` 结果对象；新增按确定性 msgId 标记领取任务已处理的方法。旧 `bs-post-rebate` 消息不再由发帖仓储发送。

**Blocked by:** 01

**Status:** ready-for-agent

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

- [ ] 直接发布与草稿提交均落一条 `msg_id = post-rebate-claim:{postId}`、`destination = bs-post-rebate-claim` 的 outbox，且提交后不发它
- [ ] 保存/编辑草稿不落任何领取任务 outbox
- [ ] 草稿提交以「提交时刻」而非草稿创建时刻计算 `periodKey`（读 `update_time`）
- [ ] 发布/提交返回值带 `postId` 与数据库发布时间
- [ ] search-sync 行为不变（照旧提交后同步发送、失败留 PENDING）
- [ ] `markRebateClaimTaskProcessed` 重复调用无副作用
