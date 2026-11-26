# 05: case 编排 — `PostRebateCase` + `PostPublishCase` 发布→领取→返回

**What to build:** 新增 `PostRebateCase`（同步领取、领取重放、入账编排）；`PostPublishCase` 改为「发布 → 领取 → 标记任务 → 返回领取结果」，领取异常返回 PENDING；旧 `grantPostRebate` 退役。

**Blocked by:** 02, 03, 04

**Status:** ready-for-agent

## 契约

### `com.betterschool.usecase.post.PostRebateCase`（`@Service`）

依赖：`IRebateService`、`IPostService`、`IPointsAccountService`、`IDrawCountAccountService`。

```java
/** 同步领取：periodKey 由帖子发布时间（Asia/Shanghai）格式化；正常返回后把领取任务标记已处理；返回领取结果 */
RebateClaimResultVO claimForPost(String userId, String postId, LocalDateTime publishTime);

/** 领取任务重放（claim consumer 调用）：periodKey 缺失按 RebatePeriodKeys.today() 兜底；领取后标记任务已处理 */
void replayClaim(PostRebateClaimTaskMessage message);

/** 入账编排（credit consumer 调用）：积分/次数各自按快照金额入账（>0 才调），全部完成后 rebateService.markGranted(claimId) */
void credit(PostRebateCreditMessage message);
```

要点：
- `claimForPost`：`rebateService.claimActionRebate(RebateActionType.POST.getCode(), userId, postId, RebatePeriodKeys.of(publishTime))`；正常返回（CLAIMED/DENIED/NO_REBATE）后 `postService.markRebateClaimTaskProcessed(postId)`；标记失败只 `log.warn`，不影响领取结果（任务留 PENDING 由 OutboxScanJob 重放，重放幂等）。
- `replayClaim`：`periodKey = (message.getPeriodKey() == null || blank) ? RebatePeriodKeys.today() : message.getPeriodKey()`；调用与标记同上（标记用 message.getPostId()）。
- `credit`：先校验 claimId/userId/postId；`pointsValue > 0` 调 `pointsAccountService.creditRebate(userId, pointsValue, postId)`，`drawCountValue > 0` 调 `drawCountAccountService.creditRebate(userId, drawCountValue, postId)`；两者都完成后 `rebateService.markGranted(claimId)`。异常上抛交 broker 重试；两个域各自唯一键幂等，单边失败重试不会重复。

### `PostPublishCase` 变更

```java
/** 发布 → 领取 → 返回领取结果；refId 即新 postId */
RebateClaimResultVO publishPost(String userId, String title, String content, List<String> images, String cover);

/** 提交草稿 → 领取 → 返回领取结果 */
RebateClaimResultVO submitPostDraft(String userId, String postId);
```

流程：
1. 现有信用分门槛校验保留；
2. `PostPublishResultVO result = postService.publishPost(...)` / `postService.submitPostDraft(...)`；
3. `return postRebateCase.claimForPost(userId, result.getPostId(), result.getPublishTime())`；
4. 捕获领取阶段异常（`try/catch (Exception)`）：`log.warn` 后返回 `RebateClaimResultVO.pending(result.getPostId())`——帖子已发布，任务保持 PENDING，绝不再上抛给用户。
   - 注意：发布本身抛的业务异常（信用分/草稿状态）照旧上抛，不要吞。
   - 实现上把「发布」放在 try 外、只包「领取」。
5. 删除 `grantPostRebate` 与不再需要的 `IPointsAccountService` / `IDrawCountAccountService` / `RebateActionType` 直接依赖（这些改由 `PostRebateCase` 持有）。
6. `savePostDraft` / `updatePostDraft` 不变。

## 验收

- [ ] 发布成功后必定返回 CLAIMED / DENIED / NO_REBATE / PENDING 之一，`refId` = postId
- [ ] 领取抛异常时响应 PENDING、任务仍 PENDING（未标记）
- [ ] CLAIMED / DENIED / NO_REBATE 均把领取任务标记已处理
- [ ] 入账重复投递：两个桶各只有一条 REBATE 记录，`markGranted` 幂等
- [ ] 旧 `grantPostRebate` 与相关 import 已删除
