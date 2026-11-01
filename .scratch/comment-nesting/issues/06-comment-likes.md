# 06: 评论点赞 + 通知补全

**What to build:** 评论点赞端到端 + 通知补全到四类。新增评论点赞表（评论 id + 用户 id 唯一键幂等）；点赞/取消端点（仅对可见评论有效）；评论行 like_count 同事务 ±1（03 已加列）；通知表新增 sender_id。评论被赞通知走 case 层编排（domain 禁互注：community 域点赞事务先提交，chat 域通知尽力而为、失败只吞不回滚），按（sender + 目标）去重，取消赞不撤回已发通知；帖子被赞通知在现有点赞用例上补同样编排。自己赞自己不发。前端：每条评论/回复的点赞按钮、数字、已赞态。本票合入后 04 的"最热"排序开始按真实点赞数生效。

**Blocked by:** 03

**Status:** resolved

- [x] 重复点赞幂等（唯一键），计数精确 ±1，取消后可再赞
- [x] 同一人对同一目标"赞→取消→再赞"只产生一条通知；不同人各一条；取消不撤回
- [x] 帖子被赞产生通知（同一去重规则）；自赞不发
- [x] 对 PENDING_REVIEW/REJECTED/DELETED 状态的评论点赞被拒绝
- [x] "最热"排序按真实点赞数生效的端到端测试

## Answer

**实现**：commit `7a64317`（2026-09-05）。评论点赞端点（`POST/DELETE /community/comments/{id}/like`，仅可见评论有效，唯一键幂等）、`post_comment.like_count` 同事务 ±1、`notification` 表加 `sender_id`、四类互动通知补全（帖被赞/评论被赞走 case 层编排，按 sender+目标去重，取消赞不撤回），集成测试 `CommentLikeIntegrationTest` 覆盖以上全部行为；冒烟脚本 `smoke06.py`（两用户点赞/取消再赞/通知去重）；迁移脚本 `sql/migrations/003_comment_likes.sql`。

**浏览器验证补充（2026-09-05，Playwright）**：前端点赞 UI 已实测——根评论与楼内回复条目的按钮数字 0→1、`.liked` 已赞态、第二用户叠加计数、点赞后"最热"排序按真实点赞数生效（2 赞评论置顶）、"最新"仍 id 升序，全部通过。脚本见 `.scratch/comment-nesting/browser-e2e/browser-e2e.cjs`。
