# map：市集（闲置 + 跑腿）推荐索引表 + 浏览 UV 体系

Label: wayfinder:map

## Destination

市集两个域的推荐读路径照主页帖子的样板切换到 MySQL 异构索引表（五档排序键 + keyset 游标分页），浏览体系照帖子 UV 口径落地（明细表 = 事实源 + 加速列，匿名不计入），商品存量 PV 清零重启；`/me/history` 保持帖子专属，闲置/跑腿的浏览记录体现在市集页。**实现型 effort：执行发生在票内**，每票一个会话可收口，含集成测试。全部票 resolved、CONTEXT.md 与 docs 同步、全量 bs-app 绿，即到达。

## Notes

### 桌上已定的设计（2026-10-25 会话用户拍板，本节是权威摘要）

- **两域对称，照帖子样板**：闲置（product）与跑腿（errand）各落一张异构索引表（命名票内定稿，倾向 `product_feed_index` / `errand_feed_index`），同步触发点同事务直写、读路径索引序扫描 + keyset 游标、异常降级旧直查——全套照 `.scratch/feed-index-uv/` 已收口的先例。
- **五档排序（用户原话：浏览量显示为热度）**：热度（view_count 降）、时间降序、时间升序、价格升、价格降。跑腿的价格 = 悬赏 reward。时间升序用同索引的反向扫描，不必单列索引。
- **浏览 UV 口径照帖子**：明细表一人一对象一行 = 事实源，加速列恒等于明细行数；INSERT 成功才算一次浏览，重复进详情只刷 viewed_at；**匿名不计入**——商品语义变更（现状匿名也计、纯累加 PV 近似值），CONTEXT.md「浏览量」词条必须改写。
- **商品存量 view_count 清零重启**（用户拍板）：PV 无法换算 UV，从上线起按 UV 精确累计，老 PV 数据废弃。跑腿本来就没有浏览量，从 0 起。
- **浏览记录的呈现分工（用户拍板）**：`/me/history` 页保持帖子专属、不动；闲置/跑腿的浏览记录体现在市集页——倾向照帖子 feed 的已读灰显（viewed 整卡降不透明度），具体形态在票 05 与用户对齐。
- **表形态倾向分域新增**（`product_view_history` / `errand_view_history` / 各自计数表），不泛化、不改动 post 既有三表；分域 vs 泛化的最终定夺在票 01。
- **读路径切换范围只限推荐页**：市集闲置 tab + 跑腿大厅（`MarketView` 两个 tab）。我的发布、他人主页在售、收藏、我的订单、作者主页、ES 搜索等非推荐列表保持现状直查不动（照帖子先例：只切主页 feed）。
- **筛选不索引化**：商品分类 + 价格区间筛选照帖子分类过滤的先例走旧直查降级路径。

### 关键现状事实（读码所得）

- **闲置**：`ProductMapper.xml` 直查 `WHERE state='ON_SALE'`，offset 分页（`(page-1)*pageSize`，ProductServiceImpl.java:244），排序最新/价格升降；先审后售状态机 `ProductStatusEnum`（DRAFT/PENDING_REVIEW/ON_SALE/LOCKED/SOLD/REJECTED/DELETED），流转全是条件 UPDATE；浏览 = `TradeRepository.recordProductView`（不带 userId）→ ViewMergeWriter 合并 → `flushViewGroup` 一条 `UPDATE product SET view_count += N`；DTO `ProductResponseDTO`（列表详情共用，无 cursor）。
- **跑腿**：`ErrandMapper.xml selectPage` 直查 `WHERE state NOT IN ('DRAFT','DELETED')`（大厅默认传 PUBLISHED），offset 分页，仅 `create_time DESC` 排序且该排序无索引；13 态状态机 + 先付后审 + 锁单轮次已落地（errand-lock-review-hub-impl / errand-order-review 已收口）；**无浏览量列、无打点、无历史**；DTO `ErrandOrderResponseDTO` 无 viewCount。
- **帖子样板（照抄出处）**：`post_feed_index`（state/feed_time/like_count + 两索引）、`user_view_history`（PK (user_id,post_id)）、`post_view_count` 加速列；`viewPosts` 合并器三步（INSERT IGNORE 明细 → 全组刷 viewed_at → 计数 += INSERT 影响行数）；`FeedCursorVO` 域内编解码；`GET /posts/history`；重建 job `feedIndexRebuildJob` 样板。
- **前端**：`MarketView.vue` 双 tab 都走 `useWaterfall`（已支持 cursor keyset 协议，响应带 cursor 才走 keyset）；帖子侧灰显 = `PostCard` 整卡降不透明度 + DTO viewed 字段（仅 feed 填充、匿名 false）。
- **计数基建**：ViewMergeWriter 窗口合并 → CountFlushDispatcher 分发 → 各域仓储 flushXxxGroup；商品浏览组现成（PRODUCT_VIEW），跑腿需新增 action。

### 与既有图的关系

- `.scratch/feed-index-uv/`（已全部 resolved）：本图样板出处，只消费不复改；其票 02 已把「商品浏览仍是累加近似值」显式划出帖子口径，本图就是把这一划出项收进来。
- `.scratch/interaction-counter-model/`：旧图，方向已被 feed-index-uv 取代，与本图无关。

### skill

- 实现票为 `task` 型；涉及口径改写（浏览量词条、热度）时对照 `domain-modeling` 更新 CONTEXT.md 词条。

## Decisions so far

<!-- 索引：每个已关闭票一行；细节在该票 ## Answer 里 -->

- 票 01 resolved：分域六表定稿（product/errand × feed_index/view_history/view_count，errand 业务键沿用 errand_order_id）；计数走独立窄表、product.view_count 走退役（票 02 停读、票 06 删列）；跑腿大厅行集口径 = PUBLISHED（前端恒传核实过）；PV 处置 = 回填抄入索引 + 清零独立一次性脚本，执行时机拍板「票 02/03 切流量之前」（先清后切，理由见票内 Answer）。
- 票 02 resolved：两域浏览落库换 UV 口径（ViewMergeWriter 三域同规强制带人，匿名在仓储层跳过不计入——商品语义变更；落库三步照 viewPosts 镜像，商品落 TradeRepository、跑腿新增 ERRAND_VIEW 落 ErrandRepository，Dispatcher 加分发分支）；读路径换源：商品六处查询 baseColumns LEFT JOIN product_view_count（主表旧 PV 列停读写、删列随票 06），跑腿 DTO 补 viewCount 仅详情接线（列表恒 0，feed 索引快照属票 04）；CONTEXT.md 浏览量词条改写为三域 UV 口径 + docs/api 同步。
- 票 03 resolved：两域索引同步触发点收进仓储本体——商品 14 个流转点 gated 带写 + 在售改价 price 同步；跑腿拍板行常驻 + state 快照（行只在审核通过事务诞生），15 个流转点带写、过审前流转点不带写；浏览合并事务第 4 步旁带索引 view_count ±N（0 行 warn 交票 06）；商品 approve ODKU 刷 feed_time/price（偏离票面「只在 INSERT 侧落」的帖子复制文字，跟 schema 注释「重提再通过刷新」，spec 评审确认正确）；原先裸调 DAO 的两域方法顺带补齐仓储内事务。
- 票 04 resolved：两域推荐页读路径切到索引表——五档排序（hot / time / time_asc / price_asc / price_desc）共用一条扫描语句，全走 `(state, 排序键, 业务ID)` 索引序 range scan（时间升序 = 同索引正向扫描、降序反向；价格的 tie-break 必须与排序同向才走得到 range scan，故 legacy `price_asc` 的 `id DESC` 对齐成 `id ASC`）；**游标各域各写**（`ProductFeedCursorVO` / `ErrandFeedCursorVO`，雾项就此定：不泛化），编解码域内、换排序复用 / 篡改一律 PARAM_ERROR；出参 `cursor` + 索引路径 `total = -1`，带筛选与降级路径精确 total 且恒无 cursor；筛选降级除商品分类 / 价格区间外**带上跑腿 type 与非 PUBLISHED 口径**（同性质：索引表无 type 列，索引可见集只有 PUBLISHED）；大厅列表 `viewCount` 取索引热度快照（票 02 留的口子），详情仍走计数窄表；前端只补 cursor 透传（`useWaterfall` 已支持 keyset），灰显与排序 chips 留票 05。
- 票 05 resolved：市集前端收口 + 浏览记录呈现定稿——两 tab 各五档排序 chips（页内一份 `MARKET_SORTS` 两域共用，图标落成 ↑/↓ 字形）、切排序走 `watch → reset → load`（reset 清 cursor，两域同一条 keyset 协议，跑腿补 `sort` 透传）；已读灰显落地为「列表组装后按页内 id 批量 IN 查明细 → DTO `viewed` 只在两域推荐列表填、匿名恒 false、详情与其他列表 null」，卡片整卡 0.55 照 `PostCard`；**HITL 用户拍板：市集页浏览记录止步于已读灰显**——不做历史列表入口 / 角标 / 「只看没看过」筛选，不毕业新票，雾项出雾，`/me/history` 一行不动；跑腿卡片与详情补浏览量、与闲置卡片同文案同位置（0 不展示：ES 结果不投影 UV），热度档下排序依据可见；CONTEXT「浏览量」词条写明呈现分工（历史页帖子专属、市集页只有灰显）。
- 票 06 resolved：两域重建 job + 收口——`MarketFeedIndexRebuildJob` 两域各一个 handler（`productFeedIndexRebuildJob` / `errandFeedIndexRebuildJob`，`trigger_status=0` 默认不调度；不做「一个 job 带域参数」：数据源与索引价格列/驻留口径都不同）→ 域服务 → 仓储 `rebuildFeedIndex` **四条语句一个事务**（计数窄表重投影 ODKU 补缺行/刷漂移 → 清孤儿 → 清空索引（DELETE 非 TRUNCATE）→ 重灌投影），读者不见空窗；重灌行集 = 商品 ON_SALE ∪ 有过 APPROVE / 跑腿 PUBLISHED ∪ 有过 APPROVE（行常驻），feed_time = MAX(APPROVE) 兜底 create_time，view_count **抄计数窄表**（顺带追平「过审前被浏览、旁路 0 行」的漏计）。**`product.view_count` 删列**（票 01 拍板的退役走到终点）：schema 删列 + mapper insert 去列 + `market-feed-uv-drop-columns.sql`；票 01 的清零脚本整脚本退役（唯一作用是归零这张将被删的列 + 索引热度键，原文留进删列脚本头注释，照帖子 ③ 段先例）；回填脚本 ① 的 PV 搬数段退役、② 的 ODKU 不再碰 view_count——脚本只服务切码前存量库，切码后修索引走重建 job。docs 四处同步，CONTEXT 热度/浏览分工词条已齐只把「旧 PV 列退役」改准为「已删列」。测试 4 用例（严格一致扫描 + 缺行/跨表直改/漏计修复 + 幂等不动点；计数校准），全量 bs-app 393 绿。

## Not yet specified

<!-- 见「Fog of war」：还在雾里、尚不能成票的 in-scope 问题 -->

- ~~闲置/跑腿浏览记录在市集页的**具体呈现形态**~~：票 05 已与用户对齐定稿——**止步于已读灰显**（整卡降不透明度），不做历史列表入口 / 卡片角标 / 「只看没看过」筛选，不毕业新票，此条出雾。
- 游标协议~~是否把 `FeedCursorVO` 泛化成跨域复用还是各域各写一个~~：票 04 已定**各域各写**（`ProductFeedCursorVO` / `ErrandFeedCursorVO`），此条出雾。

## Out of scope

<!-- 见「Out of scope」：被明确划到本 effort 终点之外的工作；已关闭，永不毕业 -->

- **点赞/评论**：闲置和跑腿明确不加（用户原话）；热度排序键 = 浏览量，不引入 like/comment。
- **post 侧既有体系返工**：`post_feed_index` / `user_view_history` / `post_view_count` 与帖子读写路径一行不改；票 01 若拍板泛化明细表，也只在新表层吸收，post 语义不动。
- **ES 搜索链路**：`bs_errand` / `bs_post` mapping 与搜索读写语义不改。
- **非推荐列表的读路径**：我的发布、他人主页在售、收藏、我的订单、作者主页、搜索结果页保持现状直查 offset 分页。
- **热度时间窗衰减**（近 24h 热）：累计口径，产品未提，不做。
- **商品收藏机制**：不动。
- **分类/价格筛选的索引化**：走旧直查降级路径即可，索引表不加筛选列。
- **分库分表落地**：只随帖子先例预留触发点位置，不迁移。
