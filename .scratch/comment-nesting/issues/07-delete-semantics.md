# 07: 删除语义统一

**What to build:** 删除统一软删 + 楼中楼下的删除语义（grilling Q4/Q10 拍板）。用户删除从物理 DELETE 改为状态转 DELETED（与举报下架同轨，物理删除废弃）。删根评论 = 整楼消失：根转 DELETED、同事务扣减 1 + 楼内可见子回复数、子回复行不动、失效帖子详情缓存；删子回复 = 仅隐藏该条：reply_count -1、总数 -1；删审核中的根评论（作者秒删自己未过审的评论）计数不动（本就未计入）；指向已删评论的标签保留昵称（03 的 JOIN 忽略状态已实现，此处固化回归断言）。前端删除按钮按新语义工作。测试中覆盖"用户删除 / 举报下架 / 审核拒绝"三条路径在软删语义上的一致性。

**Blocked by:** 03, 05

**Status:** resolved

- [x] 用户删除后评论行仍在库（DELETED），任何列表不再返回，计数按规则精确扣减
- [x] 删根：整楼对所有人消失，楼内可见子回复数从总数中一并扣除
- [x] 删审核中根评论：总数不变
- [x] 指向已删评论的"▶ 昵称"仍显示
- [x] 三条删除路径（用户/举报/审核拒绝）软删行为一致
- [x] 前端删除交互（确认、消失、计数联动）端到端可用

## Answer

**后端**：`CommentServiceImpl` 中 `deleteComment`（用户删除，保留权限校验）与 `markDeleted`（举报下架）收敛到私有 `softDelete`：条件 UPDATE 转 DELETED（行保留），计数与状态变更同事务——NORMAL 子回复：总数 -1 + 楼根 reply_count -1；NORMAL 根评论：`countRepliesByRootId` 读楼内可见数，总数扣 1+可见数（详情缓存随 `updatePostCommentCount` 内嵌 evict 失效）；PENDING_REVIEW 转删计数不动；DELETED/REJECTED 终态幂等 no-op（重复删除、下架后帖主再删、驳回后作者再删均成功且计数不重复扣）。物理 `deleteById` 整链废弃（domain 接口 / repository / DAO / Mapper XML）。`listReplies`/`countReplies` 加楼根守卫：根 DELETED → 楼内列表与 total 返回空（删根整楼对所有人消失，含 API 直查）；待审/驳回楼维持 03 现状（守卫若要求 NORMAL 会误杀"作者待审根评论的楼"）。指向昵称 JOIN parent 忽略状态本已实现，测试固化。

**基线缺口修复**：举报下架链路此前无集成测试，首跑揭出 `content_report` 缺 `handler_id`/`handled_at` 列（mapper 读写、init.sql 漏建）→ init.sql 补列 + `sql/migrations/004_content_report_handler.sql` 供联调库执行。

**前端**：`onDeleteComment` 删根 = 本地移除该根 + 清楼状态（整楼消失）；楼内子回复新增删除按钮（`onDeleteReply`：移除该项、thread.total-1、收起态"展开 N 条回复"的 replyCount-1）；确认弹窗文案区分整楼/单条。计数联动改为删除成功后重拉帖子详情同步 `commentCount`——删根扣 1+楼内可见数、待审删除不扣，前端无法精确本地推算，且删除链路已失效详情缓存，重拉即权威值。

**测试**：新增 `CommentDeleteSemanticsIntegrationTest` 7 用例（删根整楼+计数扣 3/行不动、删待审根计数不动、删子回复行保留 DELETED、指向已软删评论昵称保留、重复删除幂等、举报下架走真实 `/api/moderation/reports`+auditor process 与用户删除语义一致+下架后幂等、审核拒绝 REJECTED 行保留+作者再删幂等）；基线 `deleteComment_byAuthor` 物理删 TODO 断言改写为软删、帖主删除补状态断言、楼内删子回复补行保留断言。全量 41 用例绿（9 个测试类）。

**code-review 后修正**：① 软删 SQL 从 `status != 'DELETED'` 改为按前置状态白名单条件翻转 `markDeletedFromStatus(id, fromStatus)`（NORMAL/PENDING_REVIEW）——扣减与否由实际命中的前置状态决定而非 findById 读到的旧态，关闭"审核 approve 与用户删除并发"的计数漂移窗口，同时 REJECTED/DELETED 终态在 SQL 层不可翻（原 service 短路随之删除，幂等内建于翻转未命中）；② 根评论确认弹窗文案按 replyCount 条件化（0 回复不再误示"整楼隐藏"）。

**遗留**：前端浏览器点击链路当时未实测（无浏览器工具，仅 vue-tsc + vite build 通过 + 逻辑走查），后补见下；**浏览器验证补充（2026-09-05，Playwright）**：删除交互已实测——单条/整楼确认弹窗文案区分、删子回复后楼计数与"展开 N 条回复"联动、删根整楼消失、头部 N 按 1+楼内可见数精确扣减、删审核中评论计数不动、▶ 指向已删评论昵称保留、任何列表不再返回已删条目，全部通过（脚本 `.scratch/comment-nesting/browser-e2e/browser-e2e.cjs`）。软删行 DB 保留（DELETED）由联调环境 docker exec 手工查库确认（4 行 DELETED 行在库），行保留的常规覆盖在 `CommentDeleteSemanticsIntegrationTest`。`content_report` 补列（handler_id/handled_at，举报链路集成测试揭出的 init.sql 基线缺口）随本票落地、未拆独立票；既有并发窄窗（非本票引入）：楼根已删后楼内子回复审核通过仍会 +1 计数（approveComment 未检查楼根状态，物理删时代同样存在），如需治理应改 approveComment 条件化计数。
