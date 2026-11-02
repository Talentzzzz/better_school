# 社区模块接口

所有接口 base `/api/community`；浏览类 GET 免登录（Security 白名单），发布/评论/点赞/删除需登录（JWT Bearer）。

## 帖子

### GET /posts
时间线 feed：排序（`sort=time|hot`）+ 分页（feed-index-uv 票 04 起走 post_feed_index 索引序 + keyset 游标分页；分类已退役——feed-index-uv 票 06，墙上纯社交内容的发现 = 时间线 + 搜索）。`hot`（热榜）= 点赞数降序，同值按 `feed_time` 降序、再按 `post_id` 降序收敛成确定序（uuid-biz-id 票 03 起：UUID 无序，业务 id 不再是时间代理，同值收敛改「时间 + 业务 id」复合键）；`time` = `feed_time` 降序 + `post_id` 降序。访客视角返回摘要（不暴露浏览量）；摘要含 `content` 截断正文（约前 50 字，票 05，文字小卡渲染用）。封面口径（票 05）：`cover` = 独立封面 ?? 相册第一张，均空 null 即无封面帖。登录用户每行带 `viewed` 是否浏览过（feed-index-uv 票 05 已读灰显，页内批量回查浏览明细；未登录 false）。

### GET /posts/history
我的浏览历史（feed-index-uv 票 05）：按最近浏览时刻倒序，分页。已删/被拒的他人帖过滤（点卡片要能进详情），本人浏览过的待审/驳回/草稿帖照常展示。GET 白名单内匿名可达，匿名一律空页。

### GET /posts/mine | /posts/liked | /posts/commented
我的发布（含待审/草稿/驳回 + 浏览量 + 审核状态）/ 我赞过的 / 我评论过的。均需登录、分页。
`mine` 中 REJECTED 行额外带 `rejectReason`（最新一次拒绝理由，票 07 草稿箱列表直接展示，免二次取详情）；其余行该字段为 null。

### POST /posts
发帖。Body: `title?`, `content`（≤5000）, `images?`（≤9）, `cover?`（≤500，独立封面，票 05；不传回退相册第一张，均无即无封面帖，可传相册外的独立上传图）。先审后发（迁移 011）：同步初筛命中报错「内容包含违规词汇，请修改后重试」且不落库；干净帖落库即 PENDING_REVIEW 进统一人工裁决（作者可见他人不可见），通过才对外可见并同步搜索。返回 postId。
- 匿名参数已移除（v2 票 02）：请求体中的 `anonymous` 作为未知字段被静默忽略，一律实名
- 分类已退役（feed-index-uv 票 06）：请求体中的 `category` 作为未知字段被静默忽略（`fail-on-unknown-properties: false`）

### POST /posts/draft
保存帖子草稿（草稿箱，迁移 012）。Body: `title?`, `content?`, `images?`（≤9）, `cover?`——全部可缺省。不做同步初筛、不做信用分门槛；落库 status=DRAFT、`review_round=0`，仅作者可见（他人访问详情 404，"我的发帖"列表可见 DRAFT 态）。返回 postId。

### PUT /posts/{postId}/draft
编辑草稿（全量覆盖：title/content/images/cover 均可改，Body 同上；不传 `cover` 即清空独立封面，展示回退相册第一张，票 05）。仅 DRAFT 可编辑；待审中报「仅草稿可编辑，待审中的帖子可先撤回再修改」；非作者 403。

### POST /posts/{postId}/submit
提交草稿进待审。与直接发布同门槛：① 完整性校验（正文必填，标题/配图/封面可空，缺项报「草稿内容不完整…」停留 DRAFT）② 信用分门槛 ③ 同步初筛（命中停留 DRAFT，草稿原样保留可继续改）。通过 → PENDING_REVIEW，`review_round +1`（首次提交 0→1），锚定 `review_entered_at`、刷新 30 天过期时间。

### POST /posts/{postId}/withdraw
撤回待审回草稿箱。仅作者、仅 PENDING_REVIEW；撤回后可再编辑重提（轮次继续 +1）。

### POST /posts/{postId}/to-draft
被拒帖转草稿箱（自救路径）。仅作者、仅 REJECTED；转草稿后编辑重提 `review_round +1`，每轮审核记录与拒绝理由独立保留（content_review，拒绝理由取最新一次）。

### GET /posts/{postId}
详情。待审/草稿/驳回帖仅作者可见；浏览量仅作者返回；`viewCount` 是浏览 UV（feed-index-uv 票 02：按用户去重，一人一帖只计一次，重复进详情只刷 `viewed_at` 不加数，匿名访问不计入；落库走异步合并，允许丢）。返回 `cover` 展示封面（独立封面 ?? 相册第一张，票 05）。被拒帖对作者额外返回 `rejectReason`（最新一次拒绝理由，他人不带）；被拒帖转草稿后 `rejectReason` 仍对作者可见（自救时保留回看）。

### DELETE /posts/{postId}
作者软删自己帖子。

### POST / DELETE /posts/{postId}/like
点赞 / 取消点赞（幂等校验：重复赞/未赞报业务错误）。点赞经 PostLikeCase 编排：community 域点赞事务提交后，尽力而为给帖主发互动通知 `POST_LIKED`（自赞不发；同一人赞→取消→再赞只发一条；取消不撤回已发通知）。

### POST / DELETE /comments/{commentId}/like
点赞 / 取消点赞评论（v2-06，根评论与楼内子回复通用）。仅 NORMAL 可见评论可赞（待审/驳回/下架一律 404 或业务错误）；重复赞/未赞报业务错误。点赞记录（`post_comment_like` 唯一键幂等）与 `like_count` ±1 同事务，计数落 `post_comment_like_count` 窄表，根评论另同事务旁带 `post_comment_feed_index` 排序快照（子回复只动窄表），参与"最热"排序。点赞经 CommentLikeCase 编排：点赞事务提交后尽力而为给评论作者发互动通知 `COMMENT_LIKED`（自赞不发；同一触发者对同一评论去重；取消不撤回已发通知）。

## 评论（楼中楼 v2：票 03-06 已合入，07 待做）

### GET /posts/{postId}/comments?sort&page&pageSize
根评论列表（`root_id=0`）：`sort=hot`（like_count 降序，同值按发布时刻 `create_time` 升序、再按 `comment_id` 升序收敛成确定序——uuid-biz-id 票 03 起业务 id 不再代表发布先后；v2-06 起按真实点赞数生效）或 `sort=new`（`create_time` 升序 + `comment_id` 升序），默认最热。每项带 `replyCount`（楼内可见子回复数）、`likeCount`（点赞数）与 `liked`（当前用户已赞态，访客恒 false）。
**hot 档不自见**（comment-count-narrow-table 票 01 起如此，票 05 明写）：最热档只返回**已过审**根评论——热评排序读的是只收可见根评论的排序快照，作者的待审/驳回根评论不在其中，因此不再以 0 赞垫底参与排序。**默认请求（不带 `sort`）走 hot，所以默认视图就是不自见的**：作者发完评论刷新详情页看不到自己待审的根评论，切成 `sort=new` 才见。
作者视角：登录用户额外可见自己 PENDING_REVIEW / REJECTED 的根评论（PENDING 与正常评论展示无差别、无任何标识；REJECTED 额外带 `rejectReason` 拒绝理由，仅作者本人能读到）；他人与访客不可见——**自见行只出现在 `sort=new` 档**。
`total` 为他人可见口径（仅 NORMAL，不含待审/未通过）——`sort=new` 时作者行数可能多于 total（多出自己的审核中/未通过评论）；hot 档不含自见行，行数与 total 同口径。
**对外契约不变**：字段名（`likeCount` / `replyCount` / `liked` / `rejectReason` 等）与数值语义（`likeCount` 是该评论的真实点赞数、`total` 是他人可见根评论数）都与换源前一致，`page`/`pageSize` 的 offset 分页不动，前端零感知。

### POST /posts/{postId}/comments
发表根评论。Body: `content`（≤500）。**先审后发（ADR-0004，票 03）**：先过同步敏感词初筛——命中即报错「内容包含违规词汇，请修改后重试」且不落库；过初筛落库即 PENDING_REVIEW + 同事务写 outbox 待审消息，由 MQ 消费者降级为"通知审核员有评论待审"（不再裁决）。人工裁决通过 → NORMAL + 评论总数 +1 + 互动通知（POST_COMMENTED/COMMENT_REPLIED），同一事务为评论造计数窄表行（根评论另造排序快照行，赞数均 0）；拒绝 → REJECTED 终态、理由必填、作者可见带理由。评论无草稿、无重提、轮次恒 1。响应返回完整新评论 DTO（`commentId`、`postId`、`userId`、`nickname`、`content`、`replyCount=0`、`createdAt`），前端乐观插顶用。

### GET /comments/{commentId}/replies?page&pageSize
楼内子回复平铺：按 `root_id` 归组、id 升序。NORMAL 公开；登录用户额外可见自己 PENDING_REVIEW / REJECTED 的子回复（REJECTED 带 `rejectReason`）。分页默认首页 3 条、之后每页 10 条（显式传 `pageSize` 时常规等距分页）；`total` 为楼内可见子回复数（作者视角含其本人待审/未通过，他人为仅 NORMAL 口径）。
每项字段：`commentId`、`postId`、`parentId`、`userId`、`nickname`、`content`、`likeCount`、`liked`、`createdAt`、`replyToUserId`、`replyToNickname`（取自被回复评论行，忽略其状态）、`rejectReason`（仅作者本人对被拒评论可读，其余为 null）。指向标签（▶ 被回复人昵称）仅当 `parentId ≠ 楼根评论 id` 时由前端展示。

### POST /comments/{commentId}/replies
回复评论（目标可为根评论或楼内子回复，需登录）。Body: `content`（≤500）；请求体中伪造的 `rootId`/`parentId` 作为未知字段被忽略，归属由服务端按 ADR-0001 规则推导：
- 回复根评论 → `root = parent = 根评论 id`
- 回复楼内子回复 → `root = 楼根 id`、`parent = 被回复子回复 id`

发布语义与根评论一致（ADR-0004 先审后发：同步初筛命中报错不落库；过初筛落库即 PENDING_REVIEW + outbox 待审通知，不进楼、不计数；人工裁决通过才进楼并计数）。响应返回完整新回复 DTO（同楼内列表项字段）。目标评论不存在或已驳回/下架 → 404。

### DELETE /comments/{commentId}
评论作者或帖主可删；软删（status=DELETED，v2-07 统一，物理删除废弃），重复删除幂等。无关用户 403。
- 删根评论 = 整楼消失：同事务帖子 `comment_count` 扣 1+楼内可见子回复数，子回复行不动、楼内列表不可达
- 删子回复 = 仅隐藏该条：`comment_count` 与楼根 `reply_count` 各 -1
- 删审核中（PENDING_REVIEW）评论：计数不动；REJECTED/DELETED 终态再删为幂等 no-op

## 链路（发帖/评论/点赞）

发帖：Controller → PostPublishCase（信用分门槛 / 同步敏感词筛查）→ domain service（同事务：落库 + outbox 搜索同步消息）。
草稿箱（迁移 012）：保存/编辑 Controller → PostPublishCase → community 域（无初筛无门槛）；提交 Controller → PostPublishCase（完整性 + 信用分 + 同步初筛，命中/缺项停留 DRAFT）→ 域 submitDraft 条件 UPDATE（DRAFT→PENDING_REVIEW，round+1）；撤回/被拒转草稿 Controller → community 域条件 UPDATE（状态守卫 + 归属校验）。DRAFT 进 `post` 状态机（迁移 012 放开 content 可空；分类 feed-index-uv 票 06 起退役），审核队列仍只选 PENDING_REVIEW，裁决轮次由业务表 `review_round` 锚定。
评论：Controller → CommentPublishCase（同步敏感词初筛，命中报错不落库）→ domain service（同事务：落库 PENDING_REVIEW + outbox 待审消息 `bs-comment-review`）→ CommentReviewConsumer → CommentReviewCase（降级为"给 ADMIN/AUDITOR 发待审提醒 AUDIT_PENDING"，不再裁决）。人工裁决走 ReviewCase.decide(COMMENT)：通过 → `approveComment` 条件 UPDATE + 计数 + 详情缓存失效 + 互动通知 POST_COMMENTED/COMMENT_REPLIED（自评/自回跳过，仅首次通过触发）；拒绝 → `rejectComment` 终态 + 理由必填 + 作者 AUDIT_RESULT 通知（计数与缓存不动）。评论无草稿无重提、轮次恒 1。
帖子点赞：Controller → PostLikeCase → community 域 likePost（事务：点赞流水 + outbox 计数消息）→ 事务提交后尽力而为 POST_LIKED 通知（chat 域，dedup_key 去重，失败吞日志不回滚）。
评论点赞：Controller → CommentLikeCase → community 域 likeComment（事务：post_comment_like 流水唯一键幂等 + like_count 同事务 ±1 落计数窄表 `post_comment_like_count`，根评论旁带 `post_comment_feed_index` 快照）→ 事务提交后尽力而为 COMMENT_LIKED 通知（同上）。列表明细的 `likeCount` 由页内 id 集批量回查窄表填充（缺行按 0，存量语义），「最热」档的**顺序**来自排序快照——两者不同源，快照刷偏只动顺序不动数字。点赞类互动通知落 `notification` 表带 `sender_id` + `dedup_key`（唯一键，同一触发者对同一目标只发一条）；评论/回复类互动通知（POST_COMMENTED/COMMENT_REPLIED，05 票）暂不带这两列。

## 按用户查询（作者主页，票 06）

作者主页的「笔记 / 赞过 / 评论」三个 tab 一律「看**别人**」，故新增一组**公开只读**接口（区别于本人视角的 `/posts/mine|liked|commented`，后者带私有状态）。全部**免登录**，`page`/`pageSize`（≤50），按时间倒序。

### GET /users/{userId}/posts

该用户「笔记」tab。

- 过滤：`post.status='NORMAL'`；**本人看自己主页也走此口径**（草稿/草稿箱/待审/驳回只在「我的」页出现，保住「作者主页」语义单一）
- 响应：`PageResult<PostSummaryResponseDTO>`

### GET /users/{userId}/liked

该用户「赞过」tab。

- 过滤：该用户点过赞（JOIN `post_like`）且**源帖 `status='NORMAL'`**；按**点赞时间**倒序
- **隐私开关**：`bs_user.show_liked=0` 且请求者 **≠** 主页主人 → 返回空列表（前端渲染「该用户已隐藏此内容」）；请求者＝主人不受影响
- 响应：`PageResult<PostSummaryResponseDTO>`

### GET /users/{userId}/comments

该用户「评论」tab——**评论列表**形态（不是「评论过的帖子列表」）。

- 过滤：该用户 `post_comment.status='NORMAL'` **且源帖 `status='NORMAL'`**；按**评论时间**倒序
- 每项：`{ commentId, content, likeCount, createdAt, postId, postTitle, postCover }`——`postTitle`/`postCover` JOIN `post` 取（评论行本身不存标题/封面）
- 前端：点「来自笔记 · 标题」→ `/post/{postId}?commentId={commentId}`，详情页据 `commentId` 定位高亮（若不在首页已加载范围，落地时按 `commentId` 拉取定位）
- **隐私开关**：`bs_user.show_comments=0` 且请求者 ≠ 主人 → 空列表 + 「该用户已隐藏此内容」
- **「我的-评论」tab 共用本接口**（传自己 `userId`）；旧 `/posts/commented` 保留给 `SearchView` 的 `scope=comments`（搜索我评论过的帖子，语义不同）
