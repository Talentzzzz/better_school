# 02 · 评论点赞计数真源换到窄表

Type: task
Status: resolved
Blocked by: 01

## What to build

把评论点赞数的**真源**从主表列搬到专用窄表，写路径与四条读路径同时换源，并让主列从此停写。**这一票不能再拆**：主列一停写，列表计数与 hot 排序立刻没有读来源；反过来先换读也没有行可读——写与读必须同一票落地。

1. **建表**：`post_comment_like_count`（形态见 spec §2.1）进 `schema.sql`：`id` 自增主键 + `UNIQUE KEY uk_comment (comment_id)`，`like_count`、`create_time`（= 审核通过时刻）、`update_time`，**无二级索引**（取数全走唯一键点查，热评排序走 01 的索引表）。全量评论都入表，根评论与子回复一样。

2. **行诞生**：`approveCommentWithCount` 事务内，根与子回复都执行 `INSERT IGNORE` 窄表行（赞数 0）。affected = 0 落 warn——裸 INSERT 撞上漂移产生的重复键会让整个审核事务失败，IGNORE 只可能吞重复键。**不做 ODKU 补行**：那会把不变式从「行诞生于审核通过」改成「行诞生于第一次点赞」。

3. **写路径换落点**：点赞合并落库（`likeComments`）与同步取消赞（`unlikeCommentWithCount`）改 `UPDATE post_comment_like_count SET like_count = GREATEST(0, like_count + delta)`。0 行命中（窄表行缺失）落 warn 并写清 commentId 与 delta——交手工对账，不兜底补行。主行 `update_time` 不再被计数写刷（主行没有排序键依赖它）。

4. **读路径去列 + 批量回查**：根列表、楼内平铺、作者主页 tab、单行四条 SQL 的列清单去掉 `like_count`（`reply_count` 留）；主查询返回后，页内评论 id 集一次 `IN` 回查窄表，在 Java 侧填充 `PostCommentPO.likeCount`，缺行补 0。四条读路径同一通路，单行即单元素回查。**不用 SQL JOIN**（与 feed 形态的 `listByIds` 同思路，且单行/批量统一）。hot 档沿用 01 的索引序扫描，顺序不动，计数由这条回查填充。

5. **链路零改动验证**：`PostCommentPO.likeCount` 字段保留为投影填充列，`toCommentEntity` → `PostCommentEntity` → PostController 三个组装方法 → 三个 ResponseDTO 的字段名与搬运全不动；`replyCount` 全链不动。接口层注释里「主表行 like_count（没有窄表）」「最热排序直接读它」一类说法同步改口。

主列仍在（留 04 删），但自此无写入、无读方。

## Acceptance criteria

- [x] 根评论与子回复过审后窄表行存在且为 0；待审/驳回不产行；根评论另有一行索引（01 已覆盖）
- [x] 点赞 N 次后窄表 `like_count = N`、取消后回退，且主列值不再变化；子回复同样落窄表
- [x] 窄表行被人工删除后点赞：落 warn、不补行、点赞事务不中断
- [x] 四条读路径返回的 `likeCount` 均来自窄表；窄表缺行时显示 0（存量语义）
- [x] hot 档顺序与 01 一致（赞数降序 + `comment_id` 升序），默认档/楼内/主页时间序不回查
- [x] `PostCommentPO.likeCount` / entity / DTO 字段名与组装方法零改动；`replyCount` 相关断言零改动
- [x] 直查主列的测试辅助改查窄表（`CommentLike` 与 `LikeCountFlushHandler` 两处）；全量 bs-app 套件绿

## Answer

**落点**

- `sql/schema.sql`：`post_comment_like_count` 逐列照 spec §2.1（`id` 自增 + `UNIQUE KEY uk_comment`、无二级索引），表注释写明「行诞生于审核通过事务、点赞 0 行只 warn 不 ODKU 补行」以及「like_count 索引给不出单帖内赞数序」这条不建索引的理由；`post_comment.like_count` 的列注释改口为「票 02 起停写停读，留待票 04 删除」，让后来人不必读代码就能看出这是死列。
- `PostCommentMapper.xml`：四条读语句去 `like_count`、`reply_count` 留——`rootCommentColumns`（默认档与最热档回查共用）、`selectRepliesByRootId`、`selectPageByUser`、`selectById`；`baseColumns` 同步去列（该片段当前无 include 引用，spec §5.4 仍要求一并改，留着一条「照头部读即违反停写」的列清单比删它更容易误导）。新增 `insertLikeCountOnApprove`（`INSERT IGNORE`，注释写明为何不能裸 INSERT、为何不做 ODKU）与 `selectLikeCountsByIds`（页内 id 集 `IN` 回查，缺行由调用方补 0）；`updateLikeCount` 整体换落点改写窄表（`GREATEST(0, like_count + delta)`），主行 `update_time` 不再被计数写刷。**没有任何一条语句再写 `post_comment.like_count`**。
- `IPostCommentDao` + `PostCommentLikeCountPO`：两条新语句的契约（`insertLikeCountOnApprove` 返回 0 = 行已存在；`selectLikeCountsByIds` 返回行数可能少于入参）+ 回查结果 PO（`commentId` / `likeCount`）。
- `CommunityRepository`：`approveCommentWithCount` 在同一事务里对**根与子回复都** `INSERT IGNORE` 窄表行，affected = 0 落 warn 留痕、不覆盖既有计数；`updateLikeCount`（`likeComments` 与 `unlikeCommentWithCount` 共用的唯一入口）落窄表，0 行落 warn 且文案带 commentId 与 delta 交手工对账；新增 `toCommentEntities` 作为**唯一**点赞数填充通路——主查询只拿评论行，页内 id 集一次 `IN` 回查、把值投影进 `PostCommentPO.likeCount`（缺行补 0），根列表两档 / 楼内平铺 / 作者主页 tab / 单行四条读路径全部走它（单行即单元素，`findCommentById` 的 null 早退是该通路的必要分支，原实现靠 `toCommentEntity(null)` 返回 null）。`toCommentEntity`、`replyCount` 全链、PO/Entity/DTO 字段名与三个组装方法零改动。
- 接口层注释改口：`ICommunityRepository`（审核通过造窄表行 + 索引行、合并写落窄表且索引只服务排序、`updateLikeCount` 的落点与 0 行语义）、`CommentServiceImpl`（「评论计数在主表行上，没有窄表」改准为窄表 + 「返回的是点赞前快照」）、`CountAction`（`COMMENT_LIKE` 落点）、`IPostCommentDao`（`insertCommentLikes` 的影响行数喂给窄表）。
- 测试：`CommentLike` / `CommentFeedIndex` 的 `commentLikeCountOf` 直查换窄表、并各加一条「主列已停写恒 0」断言；`LikeCountFlushHandler` 夹具去主列列、同造窄表行（不造的话 UPDATE 命中 0 行，这个类就测不到它要测的东西了）并断言主列 0；`AuthorProfile` 的 `insertComment` 夹具去列；`CommunityApiTestBase` 隔离清单补 `post_comment_like_count`（逐用例 TRUNCATE）。新增 `CommentLikeCountNarrowTableIntegrationTest` 5 用例：行诞生（根 + 子回复有行且 0、待审/驳回无行）、赞与取消赞落窄表且主列与主行 `update_time`（先钉成 2020 年再点赞）都不动、窄表行被人工删除后点赞照常成功且落 warn（commentId + delta）不补行、四条读路径取窄表值（把窄表刷成 7：关系行只有 2 条、主列是 0，两条歧路都会露馅）、窄表缺行时四条读路径显示 0。白盒日志捕获收进基类 `captureWarnLogs(...)`（try-with-resources，两个测试类共用，不再各写一份 appender 生命周期）。
- 结果：新类 **5/5 绿**；社区四个受影响类 **24/24 绿**；全量 bs-app **407 tests, 0 failures**（票 01 后 402 + 净增 5）。
- code-review 双轴：standards 0 硬违例，采纳并修两条——`rootCommentColumns` 注释「列增删也只该改这一处」与「楼内/主页/单行各有一份列清单」的事实不符、两处测试类重复的白盒日志助手收进基类；未采纳三条并记理由——`PostCommentPO.likeCount` 就地投影填充（spec §4.2/§4.4 明文要求「Java 侧填充 PO 字段」，且 `toCommentEntity` 零改动本身是验收项）、`baseColumns` 死片段保留（spec §5.4 点名要改）、两处「0 行 → warn」不合并（一处是审核建行撞重复键、一处是计数行缺失，语义与文案不同）。spec 轴：§2.1 DDL / §3.1-3.3 写路径 / §4.1-4.4 读路径与 PO 链逐条核对一致，另补了 `CommentLikeIntegrationTest` 类注释里遗留的「主表 like_count ±1」旧口径。
- **口径注记（写给后续票）**：验收第 5 条「默认档/楼内/主页时间序不回查」按 §4.2 理解为「**排序键**不回查」——三条列表仍走同一次批量 `IN` 回查取展示值，只是其中只有最热档的顺序来自索引；否则默认档的 likeCount 没有来源。热评档的顺序仍只由 01 的索引快照决定（索引刷偏只动顺序不动数字，票内用例钉死）。

## Comments
