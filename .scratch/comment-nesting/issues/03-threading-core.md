# 03: 楼中楼核心——树模型 + 回复写读

**What to build:** 评论区 v2 的数据模型一次到位 + 楼中楼的写与读 + 前端楼展示。评论表新增 `root_id`、`parent_id`、`like_count`、`reply_count` 及 `idx_root (root_id, status)` 索引（like_count 本票只加列不启 API：04 最热排序与 06 点赞依赖此列）。新增两个端点：回复评论（`root`/`parent` 由后端按 ADR-0001 规则计算，拒绝客户端伪造）与楼内子回复查询（按 `root_id` 平铺、id 升序、默认 3 条之后每页 10 条；"▶ 被回复人昵称"JOIN parent 行取、忽略其状态）。`reply_count` 仅统计可见（NORMAL）子回复，同事务维护。前端：每条评论的"回复"入口与输入、根评论下"展开 N 条回复"、展开后平铺 + ▶ 标签 + "展开更多/收起"。发布语义本票沿用现状（同步敏感词），05 票统一异步化。

树模型语义（ADR-0001）：根评论 root=0、parent=0；回复根评论 root=parent=根评论 id；回复楼内子回复 root=楼根 id、parent=被回复子回复 id，依此类推；展示永远两级平铺，仅 parent≠root 时显示指向标签。

**Blocked by:** 02

**Status:** resolved

- [x] 三种回复场景的 root/parent 计算正确（回复根 / 回复楼内子回复 / 更深层级），客户端伪造值被服务端覆盖
- [x] 楼内分页 3/10 正确；reply_count 与可见子回复数一致（未通过/已删不计入）
- [x] parent 指向的评论处于任何状态时指向昵称仍返回
- [x] 前端端到端：回复任意评论 → 展开楼 → 平铺 + ▶ 指向 → 展开更多/收起
- [x] TDD：trigger 层 seam 红绿覆盖以上行为；01 基线保持绿

## Answer

**后端**：`post_comment` 加 4 列 + `idx_root`（`sql/migrations/002_comment_threading.sql` 供联调库增量执行；存量评论 root/parent 默认 0 自然成根）。端点落在 PostController：`POST /comments/{id}/replies`（root/parent 在 domain 服务 `addReply` 内由被回复评论推导，请求体伪造字段作为未知字段被 Jackson 忽略）、`GET /comments/{id}/replies`（平铺 id 升序，默认首页 3 条/之后每页 10 条，显式传 pageSize 走常规等距分页；指向昵称 JOIN parent 行、不带状态过滤）。reply_count 同事务维护五条路径：NORMAL 落库 +1、审核通过 +1、用户删除 -1、举报下架 -1、待审从未计数；评论总数口径（根+子回复）在 addReply NORMAL 分支同事务对帖子 comment_count +1。根列表查询加 `root_id=0` 过滤并返回 replyCount。发布语义未动（同步敏感词）。

**前端**：PostDetailView 按根评论 id 维护楼状态（expanded/replies/page/total）；回复目标栏（含取消）+ 输入占位切换；展开后平铺、parent≠楼根且昵称非空才渲染 ▶ 标签；"展开更多"按 3/10 翻页；回复成功后刷新根列表并**重放已加载页数**刷新所在楼（不丢用户翻过的页）。

**测试**：`CommentThreadingIntegrationTest` 8 用例（红→绿）覆盖验收项全表；审核通过走真实 `/api/moderation/review` 入口（注意：角色在登录时写入 JWT，测试需改库后重新走验证码登录）。全量 19 用例绿（01 基线 + 02 匿名移除 + 迁移脚本用例）。

**遗留给后续票**：
- 删除仍是物理 DELETE（删根评论楼内子回复成为孤儿行），07 票软删统一；指向已物理删除评论的昵称为空，前端已守卫不渲染空标签
- GET replies 对不存在的评论 id 返回空页 200 而非 404（spec 未要求，前端只对列表内根评论调用）

**浏览器验证补充（2026-09-05，Playwright）**：前端链路已实测——回复入口→"回复 @昵称"目标栏→占位切换→取消/发送（UI 写链路）、展开楼首页 3 条、"展开更多"补齐 5 条、深层回复 ▶ 指向标签、回复根评论无标签、收起还原，全部通过。脚本见 `.scratch/comment-nesting/browser-e2e/browser-e2e.cjs`。
