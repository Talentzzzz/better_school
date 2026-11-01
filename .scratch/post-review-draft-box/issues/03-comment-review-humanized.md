# 03: 评论审核人工化

**What to build:** 评论（根评论与子回复）对齐 ADR-0004 的新口径：发布时做同步敏感词初筛，命中报错不落库；通过初筛落库即待审，由人工裁决——通过才计入评论总数、才对外可见、才发互动通知；拒绝为终态，作者本人可见并能看到拒绝理由。评论发布链路的 outbox→MQ 消费者保留但降级为"通知审核员有新评论待审"，不再做任何敏感词裁决。评论无草稿、无重提、轮次恒为一。

**Blocked by:** 01: 帖子审核新语义——先审后发 + 同步初筛报错 + 审核轮次

**Status:** ready-for-human

- [x] 评论命中敏感词：报错、不落库；通过初筛落库即待审，作者可见、他人不可见
- [x] MQ 消费者不再裁决，改为发"进入人工审核"通知；不通过初筛的内容不会产生该通知
- [x] 人工裁决通过 → 计数 +1、对外可见、互动通知照发；拒绝 → 终态、理由必填
- [x] 被拒评论作者本人可见且带拒绝理由，对外不展示
- [x] 现有评论异步审核集成测试按新语义改写，覆盖初筛报错、人工裁决计数口径、通知路径

## Comments

- 2026-09-08 实现完成（待提交）：发布走 CommentPublishCase 同步初筛（命中报错不落库，与 PostPublishCase 同口径）+ 落库即待审；CommentReviewConsumer→CommentReviewCase 降级为给 ADMIN/AUDITOR 发 AUDIT_PENDING 待审提醒（dedup_key 幂等，不再裁决）；ReviewCase 评论通过 → 计数 +1 + 互动通知（仅首次通过触发，approveComment 改返回 boolean）、拒绝 → 理由必填（去掉默认文案兜底）；根/楼内列表对作者带出本人 REJECTED 评论并以 content_review JOIN 回显 rejectReason；POST /comments 同步初筛错误码对齐帖子。新增 CommentReviewHumanizedIntegrationTest 7 用例，删除旧 CommentAsyncReviewIntegrationTest；全量 158 集成测试绿。联调库无需迁移（仅 notification.type 新增 AUDIT_PENDING 值，schema 无需变更）。
