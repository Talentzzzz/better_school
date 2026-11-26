# 06: trigger — 领取/入账消费者 + 发帖接口返回结构化响应 + 旧消费者退役

**What to build:** 新增 `PostRebateClaimConsumer`（重放领取任务）与 `PostRebateCreditConsumer`（异步入账），发帖/草稿提交接口返回 `PostPublishResponseDTO`；旧 `PostRebateConsumer`、旧 topic、旧消息类与旧 msgId 构造器退役。

**Blocked by:** 01, 05

**Status:** resolved

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

- [x] 两个新消费者 group 与 topic 与常量一致，解析失败丢弃、业务失败上抛
- [x] 发帖与草稿提交接口返回 `{postId, rebateState, pointsRebate, drawCountRebate}`
- [x] 四种 API 状态均可由 HTTP 响应直接观察
- [x] 旧消费者/旧 topic/旧消息类全仓零残留

## Comments

### 2026-11-26

**Files**

- 新增 `bs-trigger/.../trigger/listener/PostRebateClaimConsumer.java`：`POST_REBATE_CLAIM_TOPIC` + `bs-monolith-post-rebate-claim-group`，`RocketMQListener<String>`，解析 `PostRebateClaimTaskMessage`；解析失败 / postId·userId 空丢弃；`periodKey` 空交由 case 兜底；业务异常不 catch。
- 新增 `bs-trigger/.../trigger/listener/PostRebateCreditConsumer.java`：`POST_REBATE_CREDIT_TOPIC` + `bs-monolith-post-rebate-credit-group`，解析 `PostRebateCreditMessage`；解析失败 / claimId·userId·postId 空丢弃；业务异常不 catch。
- 修改 `bs-trigger/.../trigger/http/PostController.java`：发帖与草稿提交改返回 `Response<PostPublishResponseDTO>`，私有 `toPostPublishResponse(RebateClaimResultVO)` 收口映射（`refId → postId`、`apiState.name() → rebateState`、快照金额）；保存 / 编辑草稿保持 `Response<String>` / `Response<Void>`；顺带去掉重复的 `IPostService` import。
- 删除 `bs-trigger/.../trigger/listener/PostRebateConsumer.java`、`bs-types/.../types/common/PostRebateTaskMessage.java`。
- 修改 `bs-types/.../types/common/Constants.java`：删除 `POST_REBATE_TOPIC` 及其 topic 注释。
- 修改 `bs-types/.../types/common/PostRebateTaskKeys.java`：删除 `of(String)` 与旧 `PREFIX`，保留 `claimOf` / `creditOf`，头注释同步去掉旧前缀长度。
- 测试 fixture 只改 publish 响应解析（`data` 为对象后取 `data.postId`）：`PostApiTestBase`（保留 UUID 断言）、`AnonymousRemovalIntegrationTest`、`PostCoverFeedSummaryIntegrationTest`（发布两处；保存草稿两处 `save.path("data")` 未动）、`ViewHistoryIntegrationTest`。未新增测试。

**Decisions**

- 空值守卫按 `null || isBlank()` 处理（比票面「为空」更严，空白串同样视为残缺载荷丢弃）——与 `LotteryShippingConsumer` 现有风格一致。
- `Constants` 直接删掉整段旧 topic Javadoc，避免留下 `bs-monolith-post-rebate-group` 旧消费组文字；`PostRebateTaskKeys` 删除后已无 `PREFIX` 引用，一并清理避免 unused 常量。
- `PostController` 只新增两个 import；未引入 `RebateActionType` 等新依赖。

**Verification（无编译环境）**

- 仓库无 Maven / JDK21，未执行编译与测试；已逐文件通读并核对签名：`PostRebateCase.replayClaim/credit`、`PostPublishCase.publishPost/submitPostDraft` 均返回 `RebateClaimResultVO`，`RebateClaimResultVO` 提供 `getRefId/getApiState/getPointsValue/getDrawCountValue`，`PostPublishResponseDTO` 有 4 参构造。
- `backend/ front/ docs/` 全量检索：`PostRebateConsumer`、`PostRebateTaskMessage`、`POST_REBATE_TOPIC`、`grantPostRebate`、`PostRebateTaskKeys.of(`、旧 topic 字面量 `bs-post-rebate`（排除 `-claim` / `-credit`）、旧消费组 `bs-monolith-post-rebate-group` 均 0 命中。
- `.scratch/**` 的票面/spec 仍按历史叙述提到旧符号（含本票自身验收描述），属追踪文档而非代码，归票 08 的文档一致性清理。
