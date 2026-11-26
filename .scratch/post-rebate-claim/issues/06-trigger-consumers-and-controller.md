# 06: trigger — 领取/入账消费者 + 发帖接口返回结构化响应 + 旧消费者退役

**What to build:** 新增 `PostRebateClaimConsumer`（重放领取任务）与 `PostRebateCreditConsumer`（异步入账），发帖/草稿提交接口返回 `PostPublishResponseDTO`；旧 `PostRebateConsumer`、旧 topic、旧消息类与旧 msgId 构造器退役。

**Blocked by:** 01, 05

**Status:** ready-for-agent

## 交付物

### 1. `trigger/listener/PostRebateClaimConsumer`

- `@RocketMQMessageListener(topic = Constants.POST_REBATE_CLAIM_TOPIC, consumerGroup = "bs-monolith-post-rebate-claim-group")`，`RocketMQListener<String>`。
- 解析 `PostRebateClaimTaskMessage`；解析失败直接丢弃（重试也不会成功）。
- `postId` / `userId` 为空 → 记日志丢弃；`periodKey` 允许为空（旧载荷兜底，由 case 按当天处理）。
- 调 `postRebateCase.replayClaim(message)`；不 catch 业务异常（上抛交 broker 重试；领取幂等）。

### 2. `trigger/listener/PostRebateCreditConsumer`

- `@RocketMQMessageListener(topic = Constants.POST_REBATE_CREDIT_TOPIC, consumerGroup = "bs-monolith-post-rebate-credit-group")`，`RocketMQListener<String>`。
- 解析 `PostRebateCreditMessage`；解析失败丢弃。
- `claimId` / `userId` / `postId` 任一为空 → 记日志丢弃。
- 调 `postRebateCase.credit(message)`；不 catch 业务异常（broker 重试；桶唯一键 + markGranted 幂等）。

### 3. `PostController`

- `POST /api/community/posts`：`Response<PostPublishResponseDTO>`。取 `PostPublishCase.publishPost(...)` 返回的 `RebateClaimResultVO`，映射：
  - `postId = result.getRefId()`
  - `rebateState = result.getApiState().name()`（CLAIMED / DENIED / NO_REBATE / PENDING）
  - `pointsRebate` / `drawCountRebate` 取结果金额（DENIED/NO_REBATE/PENDING 为 0）
- `POST /api/community/posts/{postId}/submit`：`Response<PostPublishResponseDTO>`，同一映射。
- `POST /posts/draft`、`PUT /posts/{postId}/draft` 保持原样（分别返回 postId、Void）。
- 私有方法 `toPostPublishResponse(RebateClaimResultVO)` 收敛映射。

### 4. 退役

- 删除 `trigger/listener/PostRebateConsumer.java`。
- 删除 `types/common/PostRebateTaskMessage.java`、`Constants.POST_REBATE_TOPIC`、`PostRebateTaskKeys.of(String)`。
- 全仓不得再有 `bs-post-rebate` 旧 topic / `PostRebateConsumer` / `PostRebateTaskMessage` / `grantPostRebate` 引用（含注释）。

## 验收

- [ ] 两个新消费者 group 与 topic 与常量一致，解析失败丢弃、业务失败上抛
- [ ] 发帖与草稿提交接口返回 `{postId, rebateState, pointsRebate, drawCountRebate}`
- [ ] 四种 API 状态均可由 HTTP 响应直接观察
- [ ] 旧消费者/旧 topic/旧消息类全仓零残留
