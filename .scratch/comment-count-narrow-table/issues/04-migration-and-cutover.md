# 04 · 建表与删列序（2026-10-27 更名：原「迁移回填与删列序」，回填段随「不管存量」拍板退役）

Type: grilling
Status: resolved
Blocked by: 02

## Question

**2026-10-27 两次修订**：票 02 拍板「不管存量」（无回填，存量历史点赞丢弃显示 0，用户确认接受后果）；票 03 拍板照 feed 形态加 `post_comment_feed_index`（重建 job 照帖子样板）。本票从「迁移回填与删列序」缩水成**建两张表 + 删一列的交付序**。要收的口：

1. **交付节奏**：单 PR 一步到位（schema.sql 两张新表 + 存量库 DDL 脚本 + 删列 + 重建 job 全在一个 effort）还是分两步（先建表切码、观察后删列）？无停写窗口（旧代码不碰新表；不管存量）。market-feed-uv 票 06 是单 effort 内做完含删列的先例。
2. **存量库脚本形状**：独立脚本三条 DDL（建窄表 + 建索引表 + 删 like_count 列，不幂等 + 头注释执行序，照 market-feed-uv 先例）还是只改 schema.sql 让存量库手工对齐？
3. **重建 job 落地形态**：照市集票 06 样板——XXL-Job handler（commentFeedIndexRebuildJob）+ xxl-job-tasks.sql 注册 trigger_status=0 手动执行 + CommunityRepository.rebuildCommentFeedIndex（清空索引 → 从可见根评论重灌、like_count 取窄表真值）。确认照抄即可还是有评论侧特有口。
4. **schema.sql 与 mapper 同步面**：`post_comment` 删 `like_count` 列（**reply_count 保留**）后，`PostCommentMapper.xml` 的 baseColumns、insert 语句、各 SELECT 列清单、`PostCommentPO` 字段、测试 fixture（AuthorProfileIntegrationTest.insertComment、LikeCountFlushHandlerIntegrationTest fixture）逐处列出（依赖触点清单票），防漏。
5. **回滚形态**：旧 jar 期望旧列在——删列前可无脑回滚（新表多余但无害）；删列后回滚需反向 DDL（加回列，历史点赞数据本就丢弃）。market-feed-uv 把不可逆点推到删列的做法照抄给结论。

## Answer

2026-10-27 grilling 收敛，三问全照推荐：

1. **交付节奏：单 PR 一步到位**——schema.sql 两张新表 + 存量库 DDL 脚本 + 删列 + 重建 job 在本 effort 内一次做完；无停写窗口（旧代码不碰新表、不管存量）、无双读观察期（不设开关文化）。
2. **存量库脚本：独立脚本** `sql/backfill/comment-like-count-cutover.sql`，不幂等，头注释写执行序：① 建 `post_comment_like_count` ② 建 `post_comment_feed_index` ③ 起新 jar（新 jar 启动前提是两表已在）④ 删 `post_comment.like_count`。头注释同时写明：不管存量、存量评论点赞显示 0、删列后回滚需手工反向 DDL 且历史点赞不回来。
3. **回滚：不可逆点 = 删列**（market-feed-uv 先例照抄）。删列前旧 jar 可无脑回滚（新表多余但无害）；删列后回滚需反向 DDL，历史点赞数据丢弃（本就不管存量）。
4. **重建 job 落地**（照市集票 06 样板）：`CommentFeedIndexRebuildJob`（`@XxlJob("commentFeedIndexRebuildJob")`）+ `xxl-job-tasks.sql` 注册 trigger_status=0 手动执行 + `CommunityRepository.rebuildCommentFeedIndex`——单事务：清空索引（DELETE 非 TRUNCATE，保原子性）→ 重灌投影（`INSERT...SELECT` 行集 = post_comment 中 `root_id=0 AND state='NORMAL'`，like_count 取窄表真值 IFNULL 缺行补 0）。评论侧无快照多源问题（索引仅 like_count 一列、真值源唯一指向窄表——窄表自身 0 行命中只落 warn 不自愈，见票 02 ②），重建纯兜 0 行 warn 的漂移。
5. **mapper / PO / fixture 同步面清单**（依赖触点票逐处核对）：
   - `PostCommentMapper.xml`：baseColumns（:5-8）去 `like_count`（reply_count 留）；`insert`（:10-14）去列；R1/R2/R3/R4 四条 SELECT 列清单去 `c.like_count`；`updateLikeCount`（:170-175）主表版本退役、换窄表 UPDATE；R1 hot 分支改造照票 03（索引序扫描 + 回查）。
   - `PostCommentPO.likeCount` 字段保留、批量回查投影填充；`CommunityRepository.toCommentEntity` / PostController 组装 / DTO 零改动。
   - 新增：窄表 upsert/UPDATE 语句、索引 INSERT/DELETE/±N/重建四类语句（票 02/03 定的形状）。
   - 测试 fixture：`AuthorProfileIntegrationTest.insertComment`（:260-266）去 `like_count` 列；`LikeCountFlushHandlerIntegrationTest` fixture（:77-79）去列；`CommentLikeIntegrationTest.commentLikeCountOf`（:218）与 `LikeCountFlushHandlerIntegrationTest.commentLikeCount()`（:119）直查 SQL 换 `post_comment_like_count`。
