# 03 · feed 索引表同步触发点

Type: task
Status: resolved
Blocked by: 01

## Task

在三个既有事务位置带索引行更新，全部同事务直写：

1. 状态流转（CommunityRepository 六个入口）：
   - 审核通过：`INSERT ... ON DUPLICATE KEY UPDATE state='NORMAL', feed_time=NOW()`（行诞生/翻回，feed_time 只在 INSERT 侧落——注意 ON DUPLICATE 分支不刷 feed_time）
   - 驳回 / 撤回 / 被拒转草稿 / 软删 / 强删：`UPDATE state`（行不可见化）
2. 赞合并事务：`upsertLikeCount` / `decreaseLikeCount` 旁带 `UPDATE post_feed_index SET like_count = like_count ± N WHERE post_id = ?`；**纯 UPDATE 不 upsert**（能被赞的帖子行必存在），命中 0 行落日志交重建 job
3. 不触发：评论数、浏览、正文（帖子发布后不可编辑）
4. 集成测试：通过→可见、撤回重提再通过→可见且 feed_time 为第二次通过时刻、驳回/删除→索引行不可见、点赞→热榜位次变化

## Answer

### 交付物

- **三条新 SQL**（`IPostDao` + `PostMapper.xml`，索引语句与 post 语句同 DAO 同 mapper）：
  - `upsertFeedIndexOnApprove`：审核通过的行诞生/翻回。INSERT 侧落 `state='NORMAL', feed_time=NOW(), like_count=0`；ODKU 分支只翻 `state='NORMAL'` + `update_time`，**不刷 feed_time、不碰 like_count**（like_count 由赞合并事务独占维护）。ODKU 命中属纯防御分支：状态机里 NORMAL 不可重回待审，正常流到不了「行已存在再通过」，唯一触达是双审并发的幂等重放；feed_time 口径（= 最近一次通过）由票 01 回填脚本侧测试钉住。
  - `updateFeedIndexState(postId, state)`：驳回/撤回/被拒转草稿/软删共用的不可见化，state 快照镜像 post 的新状态（REJECTED / DRAFT / DELETED）。行不存在（从未通过审，这三个隐藏路径的常态）= 0 行静默，不 upsert。
  - `updateFeedIndexLikeCount(postId, delta)`：赞合并事务旁路 ±N，`GREATEST(0, …)` 兜负与窄表 `decreaseLikeCount` 同口径。纯 UPDATE 不 upsert；命中 0 行 = 索引行缺失的存量异常，`syncFeedIndexLikeCount` 落 warn 日志交票 07 重建 job，不在写路径凭空补行。
- **六入口带写（CommunityRepository）**：approve / reject / withdraw / rejectToDraft / 软删（`deletePostById`，用户删除与管理侧强删共用）/ 强删同轨。approve、reject、deletePostById 本次补齐了方法内 `transactionTemplate.execute`（REQUIRED，加入 `approveWithSearchSync` 等外层事务）——多写后事务护栏收进仓储本体，不再依赖调用方；rejectToDraft 原先无事务，一并补上。所有方法签名不变，纯实现内改动。
- **不触发清单核实**：评论数（`updatePostCommentCount`）、浏览（`viewPosts`）、正文（无已发布编辑端点）均无索引写。

### 已知口径注记

- 票面「驳回/删除→索引行不可见」在黑盒测试里的可观察形态是**驳回/转草稿不产行**（驳回只发生在待审态，行必不存在）、「删除后翻 DELETED」（删除发生在通过后，行存在）。「不可见化 UPDATE」本体由删除用例钉住；驳回路径的 0 行静默不 upsert（未通过过的帖子不凭空进索引）也一并断言。
- 「撤回重提再通过→feed_time 为第二次通过时刻」运行期唯一可达形态是「撤回 → 重提 → 首次通过」（行此前不存在，INSERT 侧落 feed_time = 重提轮的通过时刻）；「行已存在的再通过」不可达（见上 ODKU 防御分支注记），测试按可达形态断言 feed_time ≥ 重提时刻。
- 强删（`forceDeleteWithSearchSync`）无触发层 HTTP 暴露，黑盒测不到；与用户软删共用 `deletePostById` 同一段索引写，不设独立用例。

### 验证

- 新增 `PostFeedIndexSyncIntegrationTest`（4 用例）：① 通过诞生索引行（state/feed_time=通过时刻/like_count=0）+ 待审不产行 + 浏览不碰索引；② 撤回重提再通过（撤回后无行、通过后可见、feed_time ≥ 重提时刻）；③ 驳回/转草稿不产行 + 删除翻 DELETED 且不在 NORMAL 集；④ 点赞 ±N 推进索引 like_count（与窄表真源同值）+ 热榜位次 (like_count DESC, post_id DESC) 变化含同分反超。
- code-review 双轴：standards 轴发现 approve/reject 多写后未在方法内收事务（违反 backend/CLAUDE.md §1/§3「事务落位在仓储内」）——已修正（三方法补 transactionTemplate 护栏）；spec 轴其余发现均为注记级（强删无独立测试、驳回断言口径），已落入上方注记。
- 新测试类 4/4 绿；全量 bs-app 套件 **347 tests, 0 failures**（BUILD SUCCESS）。中途 bs-mysql 容器 mysqld 在 TRUNCATE 时崩溃（exit 2，环境事故非代码回归），重启后重灌跑批全绿。
