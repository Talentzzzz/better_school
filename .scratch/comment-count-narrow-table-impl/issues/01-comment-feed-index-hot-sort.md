# 01 · 热评投影索引端到端

Type: task
Status: resolved
Blocked by: 无（可立即开始）

## What to build

根评论热评排序从「主列 filesort」换成「照帖子 feed 形态的投影索引索引序扫描」，并让 hot 档不再返回作者自见的待审/驳回评论。**本票不动计数真源**：`post_comment.like_count` 照旧被写，索引快照与它同步推进——这是本票能独立成立、全套现有测试保持绿的原因。

四件事一条链路：

1. **建表**：`post_comment_feed_index`（形态见 spec §2.2）进 `schema.sql`：PK = `comment_id`、`post_id`、`like_count` 快照、`create_time`/`update_time`、`KEY idx_post_like (post_id, like_count, comment_id)`。**无 state 列**——行集就是当前可见根评论：过审事务 INSERT、软删事务物理 DELETE（评论软删是终态无恢复路径，帖子侧「行常驻 + state 镜像」在评论这里永远只有 NORMAL，不维护）。子回复不入索引（楼内不按赞排）。

2. **行诞生**：`approveCommentWithCount` 事务内，根评论（`rootId == 0`）追加一条索引行，赞数必为 0（待审态不可被赞，点赞前置检查锁 NORMAL）。子回复过审只走 reply_count 与帖子评论总数，索引零动作。

3. **旁带同步**：点赞合并落库与取消赞两个入口，在同一事务内旁带 `UPDATE post_comment_feed_index SET like_count = GREATEST(0, like_count + delta)`，命中 0 行落 warn（照帖子 `syncFeedIndexLikeCount` 同款文案，交重建 job）。**子回复只动主列、不动索引**——写路径只拿到 commentId，需自行判定根/子；子回复的 UPDATE 必然落 0 行，绝不能当漂移告警。根评论软删时同事务 `DELETE` 索引行。

4. **读路径**：`sort=hot` 的根评论列表改为两步——索引序扫描 `(post_id, like_count DESC, comment_id)` 配 `LIMIT offset, limit` 取出 comment_id 序 → 按 id 集回查评论行（保留 bs_user / content_review JOIN）→ 按扫描序重排。hot 档去掉 viewerId 自见分支（索引只收已过审根评论），默认档（`ORDER BY c.id`）自见照旧，offset 分页与 total 契约不动。

`reply_count`、帖子评论总数、楼内平铺、作者主页 tab 全部零改动。

**票间窗口说明**：本票的 0 行 warn 指向的重建 job 在 03 落地；整个 effort 是单 PR 一步到位，该窗口不对外。

## Acceptance criteria

- [x] 根评论过审后索引行存在且 `like_count = 0`；子回复过审不产索引行；待审/驳回不产行
- [x] 点赞与取消赞后索引 `like_count` 与主列数值一致；子回复点赞不触碰索引、不产生 0 行 warn
- [x] 根评论软删后索引行消失、楼内列表与帖子评论总数照常；子回复软删索引无动作
- [x] hot 档按赞数降序、同数按 `comment_id` 升序；offset 跨页不重不漏（含同数跨页）
- [x] hot 档不再出现作者自见的待审/驳回评论；默认档自见照旧；列表 total 口径不变
- [x] 索引 `like_count` 被人工刷偏后，hot 序跟随索引值（证明排序真的读索引而非主列）
- [x] 既有 hot 排序与软删语义用例（`CommentLike` / `CommentDeleteSemantics` / `CommentRootListV2`）按新口径调整后绿；全量 bs-app 套件绿

## Answer

**落点**

- `sql/schema.sql`：`post_comment_feed_index`（spec §2.2 逐列照抄，含 PK 与 `idx_post_like`），表注释写明「行集 = 当前可见根评论、无 state 列、子回复不入索引」的理由；主键口径（业务键直接当 PK）按仓库惯例登记进文件头的主键例外清单，不留一条「照头部读即违例」的表。
- `PostCommentMapper.xml`：新增 `selectRootFeedCommentIds`（索引序扫描，拿 comment_id 序）、`selectNormalRootsByIds`（id 集回查 NORMAL 根行）、`insertFeedIndexOnApprove`、`updateFeedIndexLikeCount`、`deleteFeedIndex`、`selectRootId`（根/子判定）；`selectRootPageByPostId` 退化成纯默认档——去掉 `sort` 参数与 hot 分支（留着就是一条永远走不到的排序分支，还会让后来人以为 hot 还在这条 SQL 里）。两档的列清单 + 两个展示 JOIN 抽成 `rootCommentColumns` / `rootCommentJoins` 片段共用：同一档的列表项字段不该随取数路径漂移，票 04 删列也只改这一处。
- `CommunityRepository`：`approveCommentWithCount` 按 `rootId` 分流（根 → 索引行诞生，子 → 只加 reply_count）；`likeComments`（合并器落库）与 `unlikeCommentWithCount` 同事务调 `syncCommentFeedIndexLikeCount`；`softDeleteCommentWithCount` 的根评论分支同事务 `deleteFeedIndex`；`pageRootByPostId` 的 hot 分支走 `pageHotRootsByPostId` 两级取数（扫描 → 回查 → 按扫描序重排，缺行跳过不补位）。
- **先判根/子再刷索引**（`selectRootId` 一次点查），且三态分开：评论行不存在 → 落 warn（不该发生的漂移，留痕不猜）；子回复 → 静默返回（它没有索引行，UPDATE 必然 0 行，不是漂移）；根评论 → 走 UPDATE，命中 0 行落 warn，文案与帖子 `syncFeedIndexLikeCount` 同款（commentId + delta），交重建 job。
- 接口层注释同步改口：`ICommunityRepository`（hot 不自见 + 索引序、审核通过造行、软删删行、点赞旁带）、`IPostCommentDao`（两条新 SQL 的口径）、`CommentServiceImpl`（「最热排序直接读它」改准为「排序键是索引快照」）、`PostController.listComments`（「作者行数可能多于 total」只对 `sort=new` 成立）。

**排序口径（EXPLAIN 已核，写进 mapper 注释）**：SQL 与索引列都照 spec §2.2 / §4.1 原文（`KEY idx_post_like (post_id, like_count, comment_id)` + `ORDER BY like_count DESC, comment_id`）。前置条件是把扫描锁在单帖，但**同数 `comment_id` 升序与 `like_count` 降序方向相反，索引序给不出这个混合方向**，实际执行是 covering index lookup（range = 本帖）+ Sort，排序落在本帖几十行的窄范围内。spec §4.3 接受的是「单帖楼数量级 + offset 分页」，并没有专门讨论「索引序能否直接给出这个混合方向」——这条后果如实写进注释，不再往 spec 上归因。若要真·索引序扫描，需把索引改成 `(post_id, like_count DESC, comment_id)`（MySQL 8 起支持降序索引），那是对 §2.2 DDL 的改动，留给后续决定。

**自见口径的连带事实**：API 默认排序就是 hot（`PostController` `@RequestParam(defaultValue = "hot")`），所以「热评档不自见」的实际表现是——作者发完待审评论后**刷新默认视图看不到它**，要显式切 `sort=new`（「最新」）才看得到；`sort=new` 的自见照旧。docs/api 与词条（票 05）应按「默认请求即不自见」写，不要写成「只有选了最热才不自见」。

**测试**

- 新增 `CommentFeedIndexIntegrationTest` 9 用例：行诞生（根有/子回复与待审驳回无）、赞与取消赞索引快照与主列同步且子回复只动主列、根软删索引行退场而楼内与总数照常、子回复软删零动作、hot 赞数降序 + 同数 comment_id 升序且页大小 2 跨页拼接不重不漏、hot 不自见而默认档自见照旧、索引刷偏后 hot 序跟随索引值（同时断言展示值仍取主列 → 排序确实不读主列）、**索引行缺失时点赞照常成功且落 warn、不补行**（白盒抓落库线程日志）、**子回复点赞不产生 0 行 warn**（白盒，钉死「不是漂移」这条语义）。
- `CommentRootListV2IntegrationTest`：`total_excludesPending_authorRowsMayExceedTotal` 按新口径拆断言——hot 作者行数 = total（2），new 作者行数多出自己的待审（4）。
- `CommentReviewHumanizedIntegrationTest` / `PostCommentBaselineIntegrationTest`：作者自见的前置断言改用 `sort=new`（默认档），hot 档不自见另由上面新类钉死。
- `CommunityApiTestBase` 隔离清单补 `post_comment_feed_index`（逐用例 TRUNCATE）。
- 结果：新类 9/9 绿、community 包 104/104 绿；全量 bs-app **402 tests, 0 failures**（分区跑，见下）。

**环境注记**：本机 MySQL 9.7.1 容器在一次全量跑的途中反复崩在 `TRUNCATE TABLE timeout_task_*`（InnoDB 断言 `row0sel.cc:2304 field->col->mtype == type` / `dict0dict.cc:3707`，signal 6），崩溃带走 mysqld 并连带 nacos-test 段错误——与本票改动无关（崩的是超时中心那 16 张分表；DROP + 重建两库这 16 张表后仍复现）。最终改为按包分区跑（unit / community / chat / errand / trade / moderation / misc），每区前重启栈并用真实鉴权查询确认就绪：24 + 104 + 43 + 72 + 61 + 71 + 27 = 402 全绿。

## Comments
