# 05: 评论审核异步化 + 乐观插顶

**What to build:** 评论发布链路按 ADR-0002 改造为"零同步审核 + 异步削峰"。发布接口不再做任何同步审核：落库即 PENDING_REVIEW + 同事务写 outbox 审核消息 + 响应返回完整新评论 DTO；前端发布成功后乐观插顶（无标识，刷新后按 04 的作者视角查询自然归位）。MQ 消费者异步执行敏感词检查：通过 → 条件 UPDATE 转 NORMAL（幂等，复用既有审核通过机制）+ 帖子评论总数 +1 + 失效帖子详情缓存 + 发互动通知（"帖被评"通知帖主、"评被回"通知 parent 评论作者；自己评论/回复自己不发；通知在审核通过后才发）；不通过 → 转 REJECTED + 给作者发一条通知，评论对所有人消失，计数不变、缓存不失效。人工审核队列与既有机制不动；帖子发布链路（同步敏感词）不动。测试直接驱动消费者入口，不等 outbox 扫描定时器。

**Blocked by:** 03, 04

**Status:** resolved

- [x] 发布响应不受敏感词结果影响（异步后才体现），响应含完整 DTO
- [x] 消费者通过：他人可见、总数 +1、详情缓存失效、两类通知按规则发出（含自评/自回跳过）
- [x] 消费者不通过：对所有人（含作者）消失、作者收到一条通知、计数与缓存不动
- [x] 重复消费幂等：计数只加一次
- [x] 前端乐观插顶端到端：发布秒见、刷新后作者仍可见、他人审核通过后才可见

## Answer

**后端发布链路**：`CommentPublishCase`（同步敏感词编排）整体删除，Controller 直调 `ICommunityCommentService`。`addComment/addReply` 签名改为返回 `PostCommentEntity`（不再收 `initialStatus`，恒 PENDING_REVIEW）；事务内 `saveComment + saveReviewMessage(new CommentReviewMessage(commentId))`（仓储层 `PostCommentRepository.saveReviewMessage` 经 `OutboxMessageStore` 写 `bs-comment-review` topic，提交后立即异步发送一次）；NORMAL 计数分支删除（计数职责全部收敛到 approveComment，人工审核同走此路径）。响应 DTO：`ICommunityService.addComment → Response<PostCommentResponseDTO>`、`addReply → Response<CommentReplyResponseDTO>`，昵称/指向字段由 domain 返回前补齐（写入路径无 JOIN，读用户仓储补 nickname；reply 的指向取自被回复行）。

**消费者**：`CommentReviewConsumer`（bs-trigger/listener，topic `bs-comment-review`）→ case 层新编排 `CommentReviewCase.review(commentId)`：按 id 回查评论，非 PENDING_REVIEW（重复消费/人工先处理/待审期被作者物理删除）直接跳过；敏感词命中 → `rejectComment` + 作者一条 `AUDIT_RESULT`（"内容包含违规词汇"），不动计数不失效缓存；未命中 → 复用 `approveComment`（条件 UPDATE 幂等 + 评论数/reply_count 同事务 +1 + `updatePostCommentCount` 内嵌详情缓存失效）+ 互动通知。通知为 case 层编排（跨域不共事务，衍生数据可接受瞬时缺失）：`POST_COMMENTED` 通知帖主（自评不发，refType=POST/refId=postId）、`COMMENT_REPLIED` 通知 parent 作者（自回不发，refType=COMMENT/refId=parent id）；`NotificationTypeVO` 新增两枚举值。子回复也触发帖主的帖被评通知（字面口径：帖被评与评被回独立判定）。

**坑**：`PostRepository.findById` 本身走 cache-aside 回填——消费者通过分支里"读帖主"会将刚失效的缓存用**新数据**回填，缓存断言只能以"回源结果是否读到新计数"为准，不能断言 bucket 不存在。

**前端**：`createComment` 返回类型 `number → PostComment`（`createReply` 同步返回 `CommentReply`）；`submitComment` 根评论分支改为响应 DTO 直接 `unshift` 插顶（无标识），不再整列表重载；发布/回复的 `commentCount++` 乐观自增删除——头部 N 恪守他人可见口径（CONTEXT.md 评论总数定义），审核通过后随详情缓存刷新自然更新；回复分支保持既有重放刷新语义。已验证 `PostComment` 接口与后端响应字段逐一匹配。

**测试**：新增 `CommentAsyncReviewIntegrationTest` 5 用例（发布零同步审核+完整 DTO+outbox 落库；通过分支可见/计数/缓存/帖被评；子回复通知规则含自回跳过；驳回全消失+一条通知+计数缓存不动+重复消费通知不重复；通过分支重复消费计数只加一次），直接 `@Autowired` 消费者 `onMessage` 驱动。基座 `CommunityApiTestBase` 新增 `driveCommentReview(commentId)`（按 outbox 载荷定向投喂，兼当 outbox 行存在性断言）与 `driveAllCommentReviews()`；`@BeforeEach` 增加 Redis 详情缓存清理（帖子 id 每用例重排，Redis 不随 TRUNCATE 清空，防跨用例脏读）。既有 4 个评论测试类适配异步语义（需要可见/计数断言的用例先驱动消费者；人工审核入口用例保留敏感词回复不走消费者）。全量 28 用例绿。

**冒烟**：真实 docker compose（含 RocketMQ broker）+ 8080 新 jar，python 脚本 10 项断言全过（发布 DTO/发布后 0 可见/异步通过可见/计数 +1/敏感词发布成功/驳回全消失/计数不变/两类通知落库）；见 `.scratch/comment-nesting/smoke05.py`。vite 代理链路验证过 API 契约（GET comments 经 5173 代理返回完整 DTO 字段）；插顶的浏览器点击行为当时未验证（无浏览器工具，仅 vue-tsc 构建通过 + 逻辑走查），后补见下节。

**浏览器验证补充（2026-09-05，Playwright）**：插顶点击链路已实测——发布秒见插顶（断言插顶行内无"审核中"类标识）、刷新后作者仍可见、异步过审（真实 outbox→MQ 消费）后他人刷新可见，全部通过。过审延迟极短（提交后立即异步发送），"他人发布后立即可见=否"的强断言存在竞态，以轮询到可见为准。脚本见 `.scratch/comment-nesting/browser-e2e/browser-e2e.cjs`。

**遗留给后续票**：
- 楼内子回复查询无作者视角（作者自己的待审子回复在楼内不可见，审核通过后才出现）；06/07 若涉及可顺带补 viewerId
- 头部 N 在审核通过后依赖下次进详情页才更新（无实时推送），与浏览量同级的最终一致
- 通知"帖被评/评被回"文案与聚合策略（同一帖子多条评论合并）未做，属通知体验优化
