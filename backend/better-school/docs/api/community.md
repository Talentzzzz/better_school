# 社区模块接口

所有接口 base `/api/community`；浏览类 GET 免登录（Security 白名单），发布/评论/点赞/删除需登录（JWT Bearer）。

## 帖子

### GET /posts
时间线 feed：排序（`sort=time|hot`）+ 分页（feed-index-uv 票 04 起走 post_feed_index 索引序 + keyset 游标分页；分类已退役——feed-index-uv 票 06，墙上纯社交内容的发现 = 时间线 + 搜索）。`hot`（热榜）= 点赞数降序，同值按 `feed_time` 降序、再按 `post_id` 降序收敛成确定序（uuid-biz-id 票 03 起：UUID 无序，业务 id 不再是时间代理，同值收敛改「时间 + 业务 id」复合键）；`time` = `feed_time` 降序 + `post_id` 降序。访客视角返回摘要（不暴露浏览量）；摘要含 `content` 截断正文（约前 50 字，票 05，文字小卡渲染用）。封面口径（票 05）：`cover` = 独立封面 ?? 相册第一张，均空 null 即无封面帖。登录用户每行带 `viewed` 是否浏览过（feed-index-uv 票 05 已读灰显，页内批量回查浏览明细；未登录 false）。

### GET /posts/history
我的浏览历史（feed-index-uv 票 05）：按最近浏览时刻倒序，分页。已删的他人帖过滤（点卡片要能进详情），本人浏览过的草稿帖照常展示。GET 白名单内匿名可达，匿名一律空页。

### GET /posts/mine | /posts/liked | /posts/commented
我的发布（含草稿 + 浏览量 + 状态）/ 我赞过的 / 我评论过的。均需登录、分页。

### POST /posts
发帖。Body: `title?`, `content`（≤5000）, `images?`（≤9）, `cover?`（≤500，独立封面，票 05；不传回退相册第一张，均无即无封面帖，可传相册外的独立上传图）。发布即对外可见：落库即 NORMAL 并同步搜索。返回 postId。
- 匿名参数已移除（v2 票 02）：请求体中的 `anonymous` 作为未知字段被静默忽略，一律实名
- 分类已退役（feed-index-uv 票 06）：请求体中的 `category` 作为未知字段被静默忽略（`fail-on-unknown-properties: false`）

### POST /posts/draft
保存帖子草稿（草稿箱，迁移 012）。Body: `title?`, `content?`, `images?`（≤9）, `cover?`——全部可缺省。不做信用分门槛；落库 status=DRAFT，仅作者可见（他人访问详情 404，"我的发帖"列表可见 DRAFT 态）。返回 postId。

### PUT /posts/{postId}/draft
编辑草稿（全量覆盖：title/content/images/cover 均可改，Body 同上；不传 `cover` 即清空独立封面，展示回退相册第一张，票 05）。仅 DRAFT 可编辑；非作者 403。

### POST /posts/{postId}/submit
提交草稿发布。与直接发布同门槛：① 完整性校验（正文必填，标题/配图/封面可空，缺项报「草稿内容不完整…」停留 DRAFT）② 信用分门槛。通过 → NORMAL（发布即对外可见）并刷新 30 天过期时间。

### GET /posts/{postId}
详情。草稿帖仅作者可见；浏览量仅作者返回；`viewCount` 是浏览 UV（feed-index-uv 票 02：按用户去重，一人一帖只计一次，重复进详情只刷 `viewed_at` 不加数，匿名访问不计入；落库走异步合并，允许丢）。返回 `cover` 展示封面（独立封面 ?? 相册第一张，票 05）。

### DELETE /posts/{postId}
作者软删自己帖子。

### POST / DELETE /posts/{postId}/like
点赞 / 取消点赞（幂等校验：重复赞/未赞报业务错误）。点赞经 PostLikeCase 编排：community 域点赞事务提交后，尽力而为给帖主发互动通知 `POST_LIKED`（自赞不发；同一人赞→取消→再赞只发一条；取消不撤回已发通知）。

### POST / DELETE /comments/{commentId}/like
点赞 / 取消点赞评论（v2-06，根评论与楼内子回复通用）。仅可见评论可赞（已删评论一律 404 或业务错误）；重复赞/未赞报业务错误。点赞记录（`post_comment_like` 唯一键幂等）与 `like_count` ±1 同事务，计数落 `post_comment_like_count` 窄表，根评论另同事务旁带 `post_comment_feed_index` 排序快照（子回复只动窄表），参与"最热"排序。点赞经 CommentLikeCase 编排：点赞事务提交后尽力而为给评论作者发互动通知 `COMMENT_LIKED`（自赞不发；同一触发者对同一评论去重；取消不撤回已发通知）。

## 评论（楼中楼 v2：票 03-06 已合入，07 待做）

### GET /posts/{postId}/comments?sort&page&pageSize
根评论列表（`root_id=0`）：`sort=hot`（like_count 降序，同值按发布时刻 `create_time` 升序、再按 `comment_id` 升序收敛成确定序——uuid-biz-id 票 03 起业务 id 不再代表发布先后；v2-06 起按真实点赞数生效）或 `sort=new`（`create_time` 升序 + `comment_id` 升序），默认最热。每项带 `replyCount`（楼内可见子回复数）、`likeCount`（点赞数）与 `liked`（当前用户已赞态，访客恒 false）。
`total` 为根评论总数（仅可见口径，DELETED 不计）；hot 档与 new 档行数与 total 同口径。
**对外契约**：字段名（`likeCount` / `replyCount` / `liked` 等）与数值语义（`likeCount` 是该评论的真实点赞数、`total` 是可见根评论数）保持稳定，`page`/`pageSize` 的 offset 分页不动。

### POST /posts/{postId}/comments
发表根评论。Body: `content`（≤500）。发布即对外可见：落库即 NORMAL + 评论总数 +1 + 互动通知（POST_COMMENTED/COMMENT_REPLIED），同一事务为评论造计数窄表行（根评论另造排序快照行，赞数均 0）。评论无草稿。响应返回完整新评论 DTO（`commentId`、`postId`、`userId`、`nickname`、`content`、`replyCount=0`、`createdAt`），前端乐观插顶用。

### GET /comments/{commentId}/replies?page&pageSize
楼内子回复平铺：按 `root_id` 归组、id 升序，公开可见。分页默认首页 3 条、之后每页 10 条（显式传 `pageSize` 时常规等距分页）；`total` 为楼内可见子回复数（DELETED 不计）。
每项字段：`commentId`、`postId`、`parentId`、`userId`、`nickname`、`content`、`likeCount`、`liked`、`createdAt`、`replyToUserId`、`replyToNickname`（取自被回复评论行，忽略其状态）。指向标签（▶ 被回复人昵称）仅当 `parentId ≠ 楼根评论 id` 时由前端展示。

### POST /comments/{commentId}/replies
回复评论（目标可为根评论或楼内子回复，需登录）。Body: `content`（≤500）；请求体中伪造的 `rootId`/`parentId` 作为未知字段被忽略，归属由服务端按 ADR-0001 规则推导：
- 回复根评论 → `root = parent = 根评论 id`
- 回复楼内子回复 → `root = 楼根 id`、`parent = 被回复子回复 id`

发布语义与根评论一致：落库即 NORMAL、进楼并计数。响应返回完整新回复 DTO（同楼内列表项字段）。目标评论不存在或已删除 → 404。

### DELETE /comments/{commentId}
评论作者或帖主可删；软删（status=DELETED，v2-07 统一，物理删除废弃），重复删除幂等。无关用户 403。
- 删根评论 = 整楼消失：同事务帖子 `comment_count` 扣 1+楼内可见子回复数，子回复行不动、楼内列表不可达
- 删子回复 = 仅隐藏该条：`comment_count` 与楼根 `reply_count` 各 -1
- DELETED 终态再删为幂等 no-op

## 链路（发帖/评论/点赞）

发帖：Controller → PostPublishCase（信用分门槛）→ domain service（同事务：落库 NORMAL + outbox 搜索同步消息）。
草稿箱（迁移 012）：保存/编辑 Controller → PostPublishCase → community 域（无门槛）；提交 Controller → PostPublishCase（完整性 + 信用分，缺项停留 DRAFT）→ 域 submitDraft 条件 UPDATE（DRAFT→NORMAL）。DRAFT 进 `post` 状态机（迁移 012 放开 content 可空；分类 feed-index-uv 票 06 起退役）。
评论：Controller → CommentPublishCase → domain service（同事务：落库 NORMAL + 评论计数 + outbox 搜索同步消息）。
帖子点赞：Controller → PostLikeCase → community 域 likePost（事务：点赞流水 + outbox 计数消息）→ 事务提交后尽力而为 POST_LIKED 通知（chat 域，dedup_key 去重，失败吞日志不回滚）。
评论点赞：Controller → CommentLikeCase → community 域 likeComment（事务：post_comment_like 流水唯一键幂等 + like_count 同事务 ±1 落计数窄表 `post_comment_like_count`，根评论旁带 `post_comment_feed_index` 快照）→ 事务提交后尽力而为 COMMENT_LIKED 通知（同上）。列表明细的 `likeCount` 由页内 id 集批量回查窄表填充（缺行按 0，存量语义），「最热」档的**顺序**来自排序快照——两者不同源，快照刷偏只动顺序不动数字。点赞类互动通知落 `notification` 表带 `sender_id` + `dedup_key`（唯一键，同一触发者对同一目标只发一条）；评论/回复类互动通知（POST_COMMENTED/COMMENT_REPLIED，05 票）暂不带这两列。

## 按用户查询（作者主页，票 06）

作者主页的「笔记 / 赞过 / 评论」三个 tab 一律「看**别人**」，故新增一组**公开只读**接口（区别于本人视角的 `/posts/mine|liked|commented`，后者带私有状态）。全部**免登录**，`page`/`pageSize`（≤50），按时间倒序。

### GET /users/{userId}/posts

该用户「笔记」tab。

- 过滤：`post.status='NORMAL'`；**本人看自己主页也走此口径**（草稿/草稿箱只在「我的」页出现，保住「作者主页」语义单一）
- 响应：`PageResult<PostSummaryResponseDTO>`

### GET /users/{userId}/liked

该用户「赞过」tab。

- 过滤：该用户点过赞（`post_like` 单表主查）且**源帖 `status='NORMAL'`**（源帖由仓储按 id 集回查后 Java 过滤）；按**点赞时间**倒序
- **隐私开关**：`bs_user.show_liked=0` 且请求者 **≠** 主页主人 → 返回空列表（前端渲染「该用户已隐藏此内容」）；请求者＝主人不受影响
- 响应：`PageResult<PostSummaryResponseDTO>`

### GET /users/{userId}/comments

该用户「评论」tab——**评论列表**形态（不是「评论过的帖子列表」）。

- 过滤：该用户 `post_comment.status='NORMAL'` **且源帖 `status='NORMAL'`**；按**评论时间**倒序
- 每项：`{ commentId, content, likeCount, createdAt, postId, postTitle, postCover }`——`postTitle`/`postCover` 由仓储回查 `post` 拼装（评论行本身不存标题/封面）
- 前端：点「来自笔记 · 标题」→ `/post/{postId}?commentId={commentId}`，详情页据 `commentId` 定位高亮（若不在首页已加载范围，落地时按 `commentId` 拉取定位）
- **隐私开关**：`bs_user.show_comments=0` 且请求者 ≠ 主人 → 空列表 + 「该用户已隐藏此内容」
- **「我的-评论」tab 共用本接口**（传自己 `userId`）；旧 `/posts/commented` 保留给 `SearchView` 的 `scope=comments`（搜索我评论过的帖子，语义不同）
