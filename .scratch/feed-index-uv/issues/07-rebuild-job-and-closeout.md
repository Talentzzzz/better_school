# 07 · 全量重建 job 与收口

Type: task
Status: resolved
Blocked by: 02, 03, 04

## Task

1. 照 `SearchIndexRebuildJob` 样板建 feed 索引重建 job：从 post 表 + `post_like_count` + 审核记录重灌 `post_feed_index`（先删后写，幂等可重跑），顺带校准 `post_view_count` = `user_view_history` 行数
2. 集成测试：重建后索引行与窄表/明细表严格一致；人工制造 0 行更新边角（直接 UPDATE 窄表）后重建修复
3. 存量收口：确认停写期无残留写入后，`post.category` / `post.view_count` 删列（迁移脚本收尾）
4. 全链路回归：feed 双排序、详情计数、浏览历史、已读灰显、搜索（category 移除后）
5. 回填 map 的 Decisions so far，每票 Answer 补完

## Answer

### 交付物

- **重建 job**（照 SearchIndexRebuildJob 样板）：`bs-trigger/.../job/FeedIndexRebuildJob.java`（`@XxlJob("feedIndexRebuildJob")`）→ `ICommunityPostService.rebuildFeedIndex()` → `CommunityRepository.rebuildFeedIndex()`，**四条语句一个事务**（`clearFeedIndex` → `rebuildFeedIndexProjection`；先校准后清孤儿：`recalibrateViewCounts` → `deleteViewCountsMissingHistory`）——读者要么看到重建前、要么重建后的完整索引，不见空窗。SQL 落 `PostMapper.xml`：
  - 索引重灌行集 = **NORMAL 帖 ∪ 有过审批通过的帖**（运行期语义：approve 事务才诞生行、之后 state 跟随流转行不消失；从未通过审的待审/驳回/草稿帖不凭空进索引）。feed_time = content_review 里最近一次 APPROVE 时刻（与票 01 回填脚本同口径），查不到审批流水的存量老帖取 create_time 兜底；like_count 从窄表抄入（无行 = 0）。
  - `post_view_count` 校准 = `user_view_history` 行数投影：ODKU 补缺行/刷漂移/幂等落回同值 + 清掉无明细的孤儿行（懒建行口径）。
  - 清索引用 DELETE 不用 TRUNCATE（TRUNCATE 隐式提交，会毁掉事务原子性）。
- **调度策略（雾项收敛）**：手动触发、默认不调度（xxl-job-tasks.sql 注册 trigger_status=0）——同步是同事务直写、常态无漂移，重建是重活；漂移信号 = 赞合并旁路 `updateFeedIndexLikeCount` 命中 0 行的 warn，出现时到 xxl-job-admin 手动执行。
- **集成测试** `FeedIndexRebuildIntegrationTest`（2 用例，经 trigger handler 调真实链路）：
  - 索引一致性：真实链路摆通过/通过后删除/待审/存量直插四态 + 人工制造**0 行边角**（先删索引行再点赞——赞旁路 UPDATE 命中 0 行、窄表照涨）与**窄表直改漂移**（UPDATE post_like_count = 99），重建后逐行扫断言（state == post.state、like_count == 窄表 COALESCE 0、每 NORMAL 帖有行）全部收敛，重跑是幂等不动点。
  - 浏览校准：重复浏览只刷 viewed_at 不加数的前提下，直接改加速列 999 / 误删计数行 / 给无浏览帖插孤儿行，重建后 `post_view_count` ≡ 明细行数投影（一行不多、一行不少、值恒等）。
- **存量收口**：`post.category` / `post.view_count` / `idx_category_created` 从 schema.sql 删除（删列注记指回脚本）；新增 `sql/backfill/feed-index-uv-drop-columns.sql`（线上一次性 ALTER，头注释含停写期确认口径与执行序：先跑完初版回填搬数、再删列；不幂等，MySQL 8.0 不支持 DROP COLUMN IF EXISTS）；backfill 脚本 ③ 搬数段随删列退役移除（引用的列已不存在），`FeedIndexUvBackfillIntegrationTest` 同步去搬数断言与夹具。

### 口径备忘

- 重建投影的 feed_time 取 **MAX(APPROVE)**（回填同口径），与运行期 ODKU「只在 INSERT 侧落 feed_time」在「撤回→重提→再通过」序列上存在理论差（运行期保留首次通过时刻、重建取最近一次）——两者都满足「feed_time = 某次对外可见轮的通过时刻」，且重提再通过的正常流走 INSERT 侧、无差。不为此加运行期刷新：visible 轮的排序基准在重建后即对齐，无外部可观察分歧。
- 回填脚本整体随本票退役（只服务于仍带旧列存量的更早线上库）；开发/测试库由 schema.sql 重建，永远不需要跑两个脚本。

### 验证

- 新增 2 用例绿；`FeedIndexUvBackfillIntegrationTest` / `PostFeedIndexSyncIntegrationTest` 回归绿；全量 bs-app 359 绿（feed 双排序、详情计数、浏览历史、已读灰显、搜索随全量回归覆盖，票 06 已摘净 category；收口时顺带清了两处残留夹具——`AuthorProfileIntegrationTest` 直插 post 带 category、`PostCountMergeIntegrationTest` 的 post.view_count 停写冻结断言，随删列退役）。
