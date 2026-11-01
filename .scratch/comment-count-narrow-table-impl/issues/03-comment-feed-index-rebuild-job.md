# 03 · 评论索引重建 job

Type: task
Status: resolved
Blocked by: 02

## What to build

给 01/02 留下的「旁带 UPDATE 命中 0 行 → 落 warn 交重建 job」一个真正可执行的自愈手段：照市集票 06 与帖子 `feedIndexRebuildJob` 的样板，加一个手动触发的清空重灌任务。

- **handler**：`@XxlJob("commentFeedIndexRebuildJob")`，照样板薄封装到社区仓储的重建方法；`sql/xxl-job-tasks.sql` 注册一条，`trigger_status = 0`（手动执行，不自动调度）。
- **重建语义**：单事务两条语句——`DELETE FROM post_comment_feed_index`（**不用 TRUNCATE**，隐式提交会毁掉原子性，读者要么看到重建前、要么看到重建后的完整索引）→ `INSERT ... SELECT` 重灌投影，行集 = `root_id = 0 AND state = 'NORMAL'` 的根评论，`like_count` 取 `IFNULL(窄表.like_count, 0)`，时间列按 01 的口径落。
- **只兜索引、不兜窄表**：窄表是真值源，重建不校准它。窄表缺行的根评论在重灌时按 0 处理，点赞一次即回正——这条口径要写进方法注释，别让后人误以为重建能修窄表。

切码前存量库没有索引行、由新 jar 运行期自然积累，**不需要**首建重建（照 market-feed-uv「切码前存量库 / 切码后走重建」二分）。

## Acceptance criteria

- [x] job 注册进 `xxl-job-tasks.sql`，`trigger_status = 0`，可被 xxl-job-admin 手动触发
- [x] 人工制造漂移（删索引行 / 把 `like_count` 刷成漂值 / 索引里留下已软删的根评论）后执行重建，索引与 `NORMAL` 根评论 1:1、`like_count` 与窄表一致
- [x] 重建是幂等不动点：连跑两次结果相同（行数、内容、hot 序都一致）
- [x] 重建只影响索引表：窄表数值不被改写，缺窄表行的根评论重灌为 0
- [x] 重建经 handler 走真实链路验证；全量 bs-app 套件绿

## Answer

**落点**

- `PostCommentMapper.xml`：`clearFeedIndex`（`DELETE` 非 `TRUNCATE`）+ `rebuildFeedIndexProjection`（`INSERT ... SELECT`，行集 = `root_id = 0 AND state = 'NORMAL'`，`like_count` 从左连的计数窄表抄入、无行 = 0，`create_time` 取 `content_review` 最近一次 `APPROVE` 时刻、查不到审批流水的存量行取评论 `create_time` 兜底，`update_time` = `NOW()`）。注释写清 state 判据的前提（评论软删是终态、无恢复路径——将来开恢复路径这张表的行集语义要跟着改）、以及 `update_time` 是技术时间戳不参与幂等比对。
- `IPostCommentDao`：两条语句的契约（清空 / 重灌行集与时间列口径）。
- `ICommunityRepository.rebuildCommentFeedIndex` + `CommunityRepository` 实现：`executeWithoutResult` 里清空 → 重灌，`log.info` 重灌行数（帖子那边同款）。方法注释按票面要求写明**只兜索引、不兜窄表**。
- `ICommunityCommentService.rebuildCommentFeedIndex` + `CommentServiceImpl` 转发（帖子 `ICommunityPostService.rebuildFeedIndex` 同形；trigger 只依赖 case/api/domain，不注入仓储）。
- `bs-trigger/job/CommentFeedIndexRebuildJob`：`@XxlJob("commentFeedIndexRebuildJob")` 薄封装，`rebuild(param)` 返回 `success`（与 `FeedIndexRebuildJob` / `MarketFeedIndexRebuildJob` 一致）。
- `sql/xxl-job-tasks.sql`：按 `executor_handler` 判重插入一条，`trigger_status = 0`，注释写清触发信号（评论 `updateFeedIndexLikeCount` 命中 0 行的 warn、索引行被绕过直改、软删漏删索引行）。

**口径注记（三处，写给后续票）**

1. **票面「点赞一次即回正」经核对不成立，注释按事实写**：票 02 的点赞只 `UPDATE` 窄表，命中 0 行仅落 warn、不 ODKU 造行（票 02 用例 `missingNarrowRow_warnsAndDoesNotBackfill` 已钉死），所以窄表缺行的根评论重灌为 0 之后，**点赞也补不回窄表行**——要真回正得先手工补窄表行、再重跑重建。方法注释与 `ICommunityRepository` 的 javadoc 都按这个事实写，用途不变（别把重建当窄表的对账手段）。票面这句建议票 05 顺带勘误。
2. `IFNULL` → `COALESCE`：窄表 `like_count` 是 `NOT NULL DEFAULT 0`，两者等价；帖子 / 商品 / 跑腿三处重建 SQL 都是 `COALESCE`，同形优先于字面照抄。
3. **幂等的「内容一致」排除 `update_time`**：它按样板每次重建刷 `NOW()`，是技术时间戳、非排序键。用例比对的是行集 + `comment_id/post_id/like_count/create_time` + hot 序，两次完全一致；`update_time` 的变化已在 mapper 注释里点明。

**测试**

新增 `CommentFeedIndexRebuildIntegrationTest` 3 用例，全部经 trigger 层 handler（`commentFeedIndexRebuildJob.rebuild(null)`）走真实链路：

- **严格一致 + 四类漂移**：一条帖子铺 6 根评论 + 1 子回复，走真实链路攒出正常态（过审 / 赞 2 次 / 赞 1 次 / 软删），再人工造漂移——① 删索引行 ② `like_count` 直改 99 ③ 软删后插回残行 ④ 待审根评论凭空进索引；重建后断言帖内索引行集恰为三条 NORMAL 根评论（残行 / 凭空行 / 驳回 / 子回复都不在）、全表扫「非 NORMAL 根评论的索引行」与「缺行的 NORMAL 根评论」皆空、`like_count` 逐行等于窄表（刷偏的回到 1）、hot 序跟随重建后快照。
- **时间列口径**：把评论行 `create_time` 倒回 2020 再重建，断言索引 `create_time` = 审核通过时刻而非评论行时间；另直插一条**无审批流水**的存量 NORMAL 根评论（无窄表行），断言其 `create_time` 走 `c.create_time` 兜底、`like_count` = 0。
- **只动索引表**：窄表被直改成 7、窄表行被删各一条，重建后索引分别追平 7 / 落 0，而窄表**整表快照（行集 + 数值）一字不动**、缺失行不被补回。
- **幂等不动点**：先造一条漂移（确保首跑真在改东西），连跑两次，行集 / 逐行内容 / hot 序完全相同。
- 结果：新类 **3/3 绿**；全量 bs-app **410 tests, 0 failures**（票 02 后 407 + 净增 3）。

**AC 第 1 条的实测**：`sql/xxl-job-tasks.sql` 实际灌进本地 `xxl_job` 库核验——`commentFeedIndexRebuildJob` 落一行、`trigger_status = 0`，脚本重复执行不重复插（按 handler 判重）。xxl-job-admin 容器未起，故「可被 admin 手动触发」以 handler 直调 + 注册行两点核验。

**环境注记**：全量首跑 1 例失败——`FeedCursorPagingIntegrationTest.热榜排序游标翻页不重不漏` 的发帖点赞返回 `999`（计数合并器等待超时，`LikeStateVO.SYSTEM_ERROR` 的可重试态）。单跑该类 6/6 绿、整包重跑绿；与本票改动无关（点赞路径一行未碰），归因本机负载下的合并器超时。另注：`mvn test -pl bs-app` 不带 `-am` 会用本地仓库的旧兄弟模块 jar，缺类导致上下文起不来——必须带 `-am`（文档已写，这里踩了一次）。

## Comments
