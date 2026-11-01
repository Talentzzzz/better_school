# map：feed 异构索引 + 浏览 UV 体系 + 帖子分类退役

Label: wayfinder:map

## Destination

主页 feed 的排序查询从 DB 直查切换到 MySQL 异构索引表（`post_feed_index`），浏览体系重构为 UV 口径（`user_view_history` 明细即事实源 + `post_view_count` 加速列），帖子 `category` 从领域整体退役（商品的 category 不动）。**实现型 effort：执行发生在票内**，每票一个会话可收口，含集成测试。全部票 resolved、CONTEXT.md 词条同步改写、集成测试绿，即到达。

## Notes

### 桌上已定的设计（2026-10-23 会话收敛，本节是权威摘要）

- **选型：MySQL 异构索引，不用 ES。** feed 只需要排序+过滤，ES 表达力用不上；索引表与 post 同库可同事务直写；ES 留给站内搜索。换 ES 的触发条件：个性化推荐/多路召回/排序组合爆炸——届时只换索引层实现，读路径（索引出 ID → 回查组装）不动。
- **三张新表**：
  - `post_feed_index(post_id PK, state, feed_time, like_count, update_time)`，一帖一行 1:1 投影。索引：`(state, feed_time, post_id)` 最新、`(state, like_count, post_id)` 热榜。feed_time = 首次对外可见时刻（approve 事务写入，此后不刷新；与现状按 post.create_time 排序有细微口径差异，已拍板取 feed_time）。排序键永远不用 update_time（技术时间戳，会被浏览回写等无关写污染——`increaseViewCount` 现状就是证据）。
  - `user_view_history(user_id, post_id, viewed_at)`，PK `(user_id, post_id)`，二级索引 `(user_id, viewed_at)`。UV 事实源 + 浏览历史 + feed 已读灰显的数据源。用户维度，将来按 user_id 分片友好。
  - `post_view_count(post_id, view_count, create_time, update_time)`，加速列 = 明细行数，精确 UV。
- **浏览口径变更为 UV（读法 B）**：INSERT 成功才算一次浏览，重复进详情只刷 `viewed_at` 不加数。落库形态 = `likePosts` 的镜像：合并器一组一个事务，`INSERT IGNORE` 明细（affected = 新增 UV 数）→ `UPDATE viewed_at`（全量刷新，无害）→ 计数 `+= 新增数`。不用 upsert 计数（affected rows 会被同秒重复刷 viewed_at 打乱）。**CONTEXT.md「浏览量」词条必须改写**：近似值/纯累加 → 精确 UV、真相源 = 明细表。`post.view_count` 列退役，`increaseViewCount` 删除，详情读路径 `selectCountsByPostId` 的 view 段换源。
- **热榜 = 纯 like_count**：去掉 `like_count + comment_count*2` 权重公式与 hot_score 概念。评论数不参与 feed 排序（评论是重动作、天然限流，窄表直写不合并是对的，不对称保留）。想恢复权重时索引表加列即可。
- **帖子 category 退役**：交易内容在独立市集模块（商品/跑腿各有列表筛选），墙上纯社交内容的发现 = 时间线 + 搜索。波及：DTO×4、`listPosts` 参数、domain/case 签名、mapper 条件、ES `bs_post` mapping（searchPosts 本就不用它过滤）、community 集成测试 fixture、前端分类 tab。老客户端发的 category 字段靠 Jackson 忽略未知字段兼容。商品的 category 是真需求，一行不动。
- **同步触发点**（同事务直写，全库内）：① 状态流转事务（approve INSERT...ON DUPLICATE KEY 诞生行；驳回/撤回/转草稿/软删 UPDATE state；重提再通过翻回 NORMAL）② 赞合并事务（`upsertLikeCount`/`decreaseLikeCount` 旁带索引行 ±N，纯 UPDATE 不 upsert，0 行落日志交重建 job）③ 浏览合并事务（上表两条）。评论数、浏览量、正文编辑不触发索引。帖子发布后不可编辑（编辑只在草稿态），无内容同步入口。
- **分库预案**：触发点位置不变，运输层换 outbox + RocketMQ（管道现成）。索引表独立部署，读路径不变。
- **游标分页**：`WHERE (排序键, post_id) < (?, ?)` 续页，弃 offset。

### 与既有图的关系

- `.scratch/interaction-counter-model/`（Decisions so far 为空、票 01 claimed 其余 open）：其票 02「最热排序读哪个源」、票 03「浏览量的真相」的方向被本图取代。**处置留给用户**：close 旧图或在其 Decisions 里标注 superseded。
- `.scratch/hot-row-merge-l2w256/`：合并器基建的出处，本图只消费不复改。

### 关键现状事实（读码所得）

- feed 直查：`PostMapper.selectPage`，热榜 `(like_count + comment_count*2)` filesort，最新 `create_time DESC`。
- 计数窄表：`post_like_count` / `post_comment_count`，票 05 拆投影，详情读已拆两段（`selectCountsByPostId`）。
- 赞合并：`CommunityRepository.likePosts` —— INSERT IGNORE 关系行 → `upsertLikeCount(+inserted)`，不碰 post 行。本图浏览落库照此复刻。
- 重建 job 样板：`SearchIndexRebuildJob`（xxl-job 全量重建，删索引重灌）。
- 帖子发布后不可编辑（`updateDraft` 仅 DRAFT 生效，无编辑已发布帖端点）。

### skill

- 实现票为 `task` 型；涉及口径改写时对照 `domain-modeling` 更新 CONTEXT.md 词条。

## Decisions so far

<!-- 索引：每个已关闭票一行；细节在该票 ## Answer 里 -->

- 票 01（DDL 与存量迁移）resolved：三表按定稿落地（主键 = 业务键、时间列收窄，sql.md 主键/命名两处口径段已声明例外）；回填脚本 `backend/better-school/sql/backfill/feed-index-uv-backfill.sql`（ODKU 重投影可重跑追平窗口漂移；**票 02 上线后禁整脚本重跑**——③ 段会用停写的 post.view_count 覆盖 UV 真值）；user_view_history 不回填（无按人明细可搬）；category/view_count 只停写未删。集成测试直跑脚本文件钉住语义。详见该票 ## Answer。
- 票 02（浏览合并器接入与详情读路径换源）resolved：浏览落库 = `likePosts` 的镜像（`viewPosts` 一组一个事务三步：INSERT IGNORE 明细 → 全组刷 viewed_at → 加速列 += INSERT 影响行数）；事件链路带人（`recordPostView(postId, userId)`，ViewMergeWriter 对 POST_VIEW 强制 userId > 0）；**匿名浏览不计入**（明细主键一半是 user_id，无主无处可落——「INSERT 成功才算浏览」的直接推论）；`increaseViewCount` 删除，`post.view_count` 停写且停读（列表/详情投影 view 段全换 post_view_count，列保留到收口票）；CONTEXT.md「浏览量」词条改写为精确 UV（商品浏览显式划出、仍为累加近似值）。全量套件 343 绿。详见该票 ## Answer。
- 票 03（feed 索引表同步触发点）resolved：三条新 SQL（approve ODKU 行诞生/翻回，feed_time 只在 INSERT 侧落、ODKU 不刷不碰 like_count；隐藏路径共用一条 state 快照 UPDATE，行不存在 0 行静默；赞合并旁路 ±N 纯 UPDATE、0 行落 warn 交票 07 重建 job）。六入口全带写且事务护栏收进仓储本体（approve/reject/deletePostById 补方法内 transactionTemplate、rejectToDraft 补事务）；ODKU 命中在当前状态机不可达（NORMAL 不可重回待审），属双审并发防御分支。新增 PostFeedIndexSyncIntegrationTest 4 用例；全量 bs-app 347 绿。详见该票 ## Answer。
- 票 04（feed 读路径切换与游标分页）resolved：listPosts 无分类走 post_feed_index 索引序扫描（`(排序键, post_id) DESC` 同构谓词走 range scan）+ keyset 游标分页（`FeedCursorVO` 域内编解码，排序校验防换 tab 复用/篡改；`PageResult` 加可空 cursor，total 索引路径降口径 -1）；listByIds 批量回查按扫描序重排，liked/摘要后处理不动；索引异常 warn 降级旧 selectPage（热榜口径同步对齐纯 like_count）；带分类过滤走旧直查（索引无 category 列，票 06 去参前暂存）；前端 useWaterfall/HomeView 一次切换（total<0 不参与 finished）。越尾游标返回空页（不降级不重发）。新增 FeedCursorPagingIntegrationTest 7 用例；全量 bs-app 353 绿。详见该票 ## Answer。
- 票 05（浏览历史接口与 feed 已读灰显）resolved：历史接口 `GET /posts/history` 走 idx_user_viewed 倒序 JOIN 分页（可见性谓词在 SQL 内——offset 翻页与 COUNT 同口径；**雾项收敛：已删/被拒帖过滤不展示失效卡片**，口径 = 详情可见性 NORMAL 或本人非删除态）；feed 已读灰显 = listPosts 组装后批量 IN 查浏览明细（主键前缀），DTO 加 viewed（仅 feed 填充、匿名 false），PostCard 整卡降不透明度；前端 `/me/history` + ProfileView hero 第三卡；匿名 GET history 白名单内 200 空页。新增 ViewHistoryIntegrationTest 4 用例。详见该票 ## Answer。
- 票 06（帖子 category 退役）resolved：DTO×3（PostSummary 本就没有）去字段、PostCategoryVO 删除、签名链（Controller→Case→domain→repo→dao）全线摘参；listPosts 无条件走索引路径，旧直查保留作降级（分类条件一并去，两路同口径）；PostMapper 投影/insert/updateDraft 停读写 `p.category`（删列放票 07）；ES bs_post mapping 去字段（`clearIndex` 删索引后 ensureIndices 新 mapping 重建，线上跑一次重建 job 即生效，时机随票 07）；前端分类 tab 与发帖页分类选择下线；`AnonymousRemovalIntegrationTest` 旧客户端用例保留 category 字段作 Jackson 忽略未知字段的活体回归。全量 bs-app 357 绿。详见该票 ## Answer。
- 票 07（全量重建 job 与收口）resolved：重建 job `feedIndexRebuildJob`（xxl-job 手动触发、默认不调度——同步是同事务直写常态无漂移，重建是重活，漂移信号 = 赞旁路 0 行 warn 时再手动跑）：清空重灌 `post_feed_index`（行集 = NORMAL ∪ 有过审批通过的帖、feed_time 取审核记录最近一次 APPROVE 与回填同口径、like_count 从窄表抄入）+ `post_view_count` 校准成 `user_view_history` 行数（ODKU 补缺行/刷漂移 + 清孤儿），四条语句一个事务（读者不见空窗）；调度策略雾项就此收敛（人工触发，参照 searchIndexRebuildJob 样板）。存量收口：`post.category` / `post.view_count` / `idx_category_created` 删列删索引（schema.sql 出形状、`sql/backfill/feed-index-uv-drop-columns.sql` 处置存量库；backfill 脚本 ③ 搬数段随删列退役移除，其测试同步去搬数断言）。新增 FeedIndexRebuildIntegrationTest 2 用例（严格一致扫 + 0 行缺行/窄表直改漂移/孤儿计数行/缺计数行的修复 + 幂等不动点）；全量 bs-app 359 绿。详见该票 ## Answer。

## Not yet specified

<!-- 见「Fog of war」：还在雾里、尚不能成票的 in-scope 问题 -->

（无——重建 job 的调度策略已随票 07 收敛：手动触发、默认不调度。）

## Out of scope

<!-- 见「Out of scope」：被明确划到本 effort 终点之外的工作；已关闭，永不毕业 -->

- **商品的 category**：市集真需求，一行不动。
- **ES 搜索功能链路**：只随 category 退役去 `bs_post` mapping 一个字段，搜索读写语义不改。
- **点赞/评论计数窄表机制**：已验证正确（合并器 + 窄表隔离锁），不复改。
- **Redis 缓冲计数**：热点行阶梯的第二级，合并器吞吐远未触顶，不做。
- **热榜时间窗衰减**（近 24h 热）：累计口径是现状语义，产品未提，不做。
- **分库分表落地**：只做预案（触发点预留），不迁移。
- **个性化推荐 / 多路召回**：那是索引层换 ES 的触发条件，属于下一个 effort。
