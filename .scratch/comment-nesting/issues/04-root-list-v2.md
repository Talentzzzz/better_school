# 04: 根列表 v2——最热/最新 + 作者视角 + 总数

**What to build:** 根评论列表升级。`sort=hot`（like_count 降序、同数 id 升序——本票点赞未上线恒为 0，退化行为即 id 升序，测试按退化行为断言）与 `sort=new`（id 升序），默认最热；查询包含作者自己的 PENDING_REVIEW 评论（与正常评论展示无差别、无任何标识；他人不可见）；修复列表 total 恒 0 的既有缺陷（真实 COUNT）。前端：评论区头部"全部评论(N)"（N 取帖子详情的评论总数，他人可见口径）+"最热/最新"切换 tab，根评论分页接真实 total。

已知怪癖（grilling 拍板接受）：作者视角列表行数可能多于总数 N（多出自己的审核中评论），测试固化该口径。

**Blocked by:** 03

**Status:** resolved

- [x] 两种排序端到端可切换，默认最热，退化排序（全 0 赞 = 时间序）有测试
- [x] 作者刷新后可见自己审核中的评论且无标识；其他用户不可见；总数 N 不含审核中评论
- [x] total 返回真实总数，01 票的 TODO 断言替换为正确行为
- [x] 前端 tab 切换与"全部评论(N)"头部可用

## Answer

**后端**：`PostCommentMapper.xml` 的根列表 SQL 改为 `selectRootPageByPostId(postId, viewerId, sort, offset, limit)`——`root_id=0` 且（`status='NORMAL'` 或 viewerId 非空时 `status='PENDING_REVIEW' AND user_id=viewerId`）；`<choose>` 按 sort 分支：hot = `ORDER BY like_count DESC, id`（点赞未上线恒 0 退化为 id 升序），new = `ORDER BY id`。新增 `countVisibleRootsByPostId`（仅 NORMAL 根评论 COUNT，他人可见口径）。链路 `IPostCommentDao` → `IPostCommentRepository.pageRootByPostId/countVisibleRootsByPostId` → `ICommunityCommentService.listComments(postId, viewerId, sort, ...)`（sort 归一化，非 "new" 一律 hot）/`countVisibleComments` → `PostController.listComments`（`sort` 缺省 hot，viewerId 取 `SecurityUtils.currentUserId()`，total 用真实 COUNT 替换原硬编码 0）。

**前端**：`listComments(postId, page, pageSize, sort)` 加 sort 参数（默认 hot）；PostDetailView 评论区头部改"全部评论(N)"（N = 帖子详情 commentCount）+ 最热/最新 tab（`switchCommentSort` 切换重置重载）；"加载更多"接真实 total（`comments.length < total`，作者待审行数多于 total 时自然停）。

**测试**：新增 `CommentRootListV2IntegrationTest` 4 用例（默认=最热+退化排序一致、作者可见待审无标识/他人与访客不可见、total 不含待审+作者行数多于 total 怪癖、分页真实 total）。01 票基线两处 `TODO(v2-04)` 断言改写：作者视角可见待审、total=15；物理删除的 TODO 标签修正为 v2-07。评论相关 19 用例绿；全量 mvn test 通过；8081 新 jar 冒烟 + vite 代理链路验证（guest/stranger 3 行 total 3、作者 4 行 total 3、default==hot）。

**遗留给后续票**：评论点赞（06）上线后 hot 排序脱离退化行为，需补非退化排序断言；发布待审评论后前端头部 N 乐观 +1 与他人可见口径的偏差属 05 票异步化一并处理。

**浏览器验证补充（2026-09-05，Playwright）**：默认最热 tab、退化排序（全 0 赞=id 升序）、点赞后"最热"按真实 like_count 置顶、"最新" id 升序、"全部评论(N)"头部、"加载更多"接真实 total（23 条根评论跨页翻页）均实测通过。脚本见 `.scratch/comment-nesting/browser-e2e/browser-e2e.cjs`。
