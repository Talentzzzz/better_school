# map — 评论楼中楼重构（评论区 v2）

spec: `.scratch/comment-nesting/spec.md`

## Decisions-so-far

- [01 测试设施 + 现状基线](issues/01-test-infrastructure-baseline.md)（resolved）：集成测试设施落在 `bs-app` 的 `com.betterschool.integration` 包，seam = trigger 层 HTTP + 数据库可观察状态，不 mock 内部；细节与踩坑见票内 Answer 与 `backend/docs/testing.md`。后续功能测试沿用此先例。
- [02 匿名整体移除](issues/02-remove-anonymous.md)（resolved）：帖+评论匿名字段从 schema/API/前端全删，init.sql 原地更新 + 增量 ALTER 供联调库；细节见票内 Answer。
- [03 楼中楼核心](issues/03-threading-core.md)（resolved）：root/parent 树模型（ADR-0001）+ 回复写读 + 前端楼展示，reply_count 同事务维护；002 迁移；细节与遗留见票内 Answer。
- [04 根列表 v2](issues/04-root-list-v2.md)（resolved）：最热/最新排序 + 作者视角待审可见 + 真实 total（修 total 恒 0 缺陷）；`CommentRootListV2IntegrationTest`；细节见票内 Answer。
- [05 评论审核异步化](issues/05-async-review.md)（resolved）：发布零同步审核 + outbox→MQ 消费者（ADR-0002），通过才可见才计数，前端乐观插顶；`CommentAsyncReviewIntegrationTest` + `smoke05.py`；细节见票内 Answer。
- [06 评论点赞 + 通知补全](issues/06-comment-likes.md)（resolved）：点赞端点（唯一键幂等）+ like_count 同事务 ±1 + 四类互动通知（sender+目标去重，通知表加 sender_id）；003 迁移；`CommentLikeIntegrationTest` + `smoke06.py`；细节见票内 Answer。
- [07 删除语义统一](issues/07-delete-semantics.md)（resolved）：物理删除废弃，软删 DELETED 按前置状态白名单条件翻转，删根整楼消失；004 迁移（content_report 补列）；`CommentDeleteSemanticsIntegrationTest`；细节与并发遗留见票内 Answer。
