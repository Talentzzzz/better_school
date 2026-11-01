# 04 · 推荐读路径切换与游标分页（五档排序）

Type: task
Status: resolved
Blocked by: 03

## Task

市集两域推荐读路径从直查切到索引表，照帖子票 04 的先例：

1. **五档排序全走索引序扫描**：热度（view_count 降）、时间降序、时间升序（同索引反向扫描）、价格升、价格降；排序谓词 `(排序键, id) < / > (?, ?)` 同构走 range scan。跑腿大厅现状 `create_time DESC` 无索引，顺带收益。
2. **keyset 游标分页**：出参带 cursor、total 索引路径降口径 -1；游标编解码照 `FeedCursorVO` 先例（雾项：泛化复用还是各域各写，倾向各域域内编解码，票内定）；**排序变更校验**防换 tab 复用/篡改游标（照帖子）。
3. **筛选降级**：带分类（商品 6 类）或价格区间（minPrice/maxPrice）的请求走旧直查（保留不删），两路公开口径对齐；索引异常 warn 降级旧直查。
4. **切换范围只限推荐页**：市集闲置 tab（`listProducts` 默认路径）+ 跑腿大厅（`listOrders` 默认路径）；我的发布/他人主页在售/收藏/我的订单/作者主页保持 offset 直查一行不动（out of scope）。
5. 越尾游标返回空页（不降级不重发，照帖子）。
6. 集成测试：五排序 × 游标翻页不重不漏、换 tab 游标拒绝、越尾空页、筛选走直查、降级路径可用。

## Answer

### 交付物

- **索引序扫描一条 SQL ×2**（`IProductDao.selectFeedIndex` / `IErrandDao.selectFeedIndex`，与票 03 的索引写语句同 DAO 同 mapper）：`WHERE state = 'ON_SALE' / 'PUBLISHED'` + 五档排序共用一条语句——hot 走 `(state, view_count, 业务ID)`、time / time_asc 走 `(state, feed_time, 业务ID)` 的反向 / 正向扫描、price_asc / price_desc 走 `(state, price, 业务ID)`；续页谓词与排序方向同构（降序 `<`、升序 `>`）并与 ORDER BY 列序同向，才走得到 range scan。部分列直映域 VO（`ProductFeedIndexItemVO` / `ErrandFeedIndexItemVO`），排序键随行带出供编游标（与 `selectReviewTimeoutPosts` 同款）。跑腿那侧 `errand_order_id` **别名成 `order_id`**——下划线转驼峰会映射成 `errandOrderId`，VO 上是 `orderId`，不对齐就是空锚点（首跑 11 用例全红即此因：orderId=null → 游标编成 `time|null` 且 `listByIds([null])` 空页）。
- **游标各域各写**（雾项定稿）：`ProductFeedCursorVO` / `ErrandFeedCursorVO` record，对外 Base64url 不透明串，内部 `域标签|sort|排序键|业务ID`；解码校验域标签 + 排序一致——换 tab 复用旧游标、跨域复用（闲置游标喂跑腿）、篡改内容一律 PARAM_ERROR，不静默错位；锚点工厂 `anchorOf` 收拢五档分支、`normalizeSort` 归一未知值（未知 / `time_desc` 之类同义拼写落 default = 最新）。域标签是本票相对帖子先例的加固：两域游标格式同构，不钉域就会互相解成合法串。
- **读路径编排**（`ProductServiceImpl.listProducts` → `ProductFeedPageVO{products, nextCursor, indexPath}`；`ErrandOrderServiceImpl.listOrders` → `ErrandFeedPageVO`）：索引路径多探一行探下页、`listByIds` 批量回查、按扫描序重排（索引行缺业务行只跳过不补位，游标锚在扫描序上）；扫描为空 = 越过末尾返回**空页**（不重发第一页、不降级）；游标解码在 try 之外（非法游标是调用方错误，不是降级信号）。
- **筛选降级**：商品带 category / minPrice / maxPrice → 旧直查。跑腿除票面口径外**把 type 筛选与非 PUBLISHED 状态一并划进旧直查**：索引表无 type 列，索引可见集也只有 PUBLISHED，`type=` 若仍走索引就等于静默丢掉用户筛选、`status=` 空串若走索引会把「全部公开状态」收窄成 PUBLISHED——同性质问题一个口径解决。索引查询异常 `catch (Exception)` → warn → 旧直查（indexPath=false、不发游标，前端按 page 续页）。
- **total 降口径**：索引路径不再 COUNT，total = -1（调用方按 indexPath 判定），筛选 / 降级路径仍精确。
- **两路公开口径对齐**：legacy `selectPage` 补齐五档（商品原有 time / price_asc / price_desc，加 hot、time_asc；跑腿原来只有 `create_time DESC` 一条，补全五档），热度的两路键都是浏览 UV（商品读 `product_view_count`、跑腿 `LEFT JOIN errand_view_count`——该 JOIN 现在无条件带上，因为列表要展示浏览量，见下条）；商品 `price_asc` 的 tie-break 由 `id DESC` 改成 `id ASC` 与索引序同向（否则同价并列的先后在两路之间不一致）。
- **大厅列表浏览量**：跑腿索引路径把索引热度快照写回实体（票 02 留的「feed 索引快照属票 04」口子），旧直查路径改读计数窄表（`ErrandOrderPO` 补 `view_count` 冗余列 → `toEntity` 回填）——两条路数值天然一致（同一合并事务同时推进索引旁路与计数窄表），筛选不会再让浏览量掉成 0；详情仍走 `viewCountOf` 精确值。商品侧不动：`selectByIds` 早已 JOIN 计数窄表，索引列只当排序键。
- **接口契约**：`/api/trade/products` 加 `cursor`，`/api/errand/orders` 加 `sort`（默认 time）+ `cursor`；`PageResult.cursor` 复用帖子先例字段（4 参老构造保留）。前端 `useWaterfall` 的 fetchPage 本就收 `(page, cursor)`，`MarketView` 两个 tab 补透传——不补会一直拿第一页（索引路径忽略 page）；`SortOrder` 扩成五档 API 词汇（chips 与灰显留票 05）。
- **domain 口径**：CONTEXT.md 补「热度」词条（= 浏览量降序、不加权、无时间窗衰减，与主页 feed 的「最热」区分）；docs/api `trade.md` + `errand.md` 同步读路径与入参。

### 已知口径注记

- **时间键两路不同源**：索引路径（最新 / 最早）排序键是 `feed_time`（进入当前可见轮的通过时刻，票 01 定稿口径），旧直查（筛选 / 降级）只能用主表 `create_time`——降级本就发生在「索引不可用」时，拿不到 feed_time。与帖子先例同一处已知差异，两处 mapper 都写明了。
- **tie-break 列**：索引尾列是业务 ID，旧直查尾列是物理自增 `id`；本库两者恒等（sql.md 主键口径：业务 ID 与物理自增同值，`bindBizId` 写回），实际同一列。同秒 feed_time、同价、同热度的并列都靠它收敛成确定序，测试期望序一律取自库内索引序，不假设排序键互异。
- **索引路径忽略 `page`**：带 cursor 时 page 只作回显（照帖子先例）；降级 / 筛选路径才按 page 续页。
- **游标失效不报错**：排序键漂移（点赞式旁路更新、商品改价）只影响边界附近行，不重不漏由业务 ID 唯一锚点保证——与帖子同语义。
- **两域 VO 与编排的重复是刻意的**：map 已定「各域各写」，不把游标 / 扫描编排泛化到 types 或其他域（跨域泛化会把两域耦合进一个口径里）。

### 验证

- 新增 `ProductFeedCursorPagingIntegrationTest`（trade 包，5 用例）：① 五档排序 × 游标翻页不重不漏（25 商品 3 页，整序 = 库内索引序，total=-1、page/pageSize 兼容回显，夹具刻意造价格四档循环 / 热度三档循环 / feed_time 五分钟循环钉 tie-break）；② 换排序复用旧游标 + 非法游标 + **跨域游标** 一律 400；③ 越过末尾返回空页（手工构造锚在末行的游标，不重发不降级）；④ 分类 / 价格区间筛选走旧直查（精确 total、无游标）；⑤ 索引查询异常降级直查（白盒：mock 只覆盖 `scanFeedIndex`、其余委托真实仓储）。
- 新增 `ErrandFeedCursorPagingIntegrationTest`（errand 包，6 用例）：同上五档翻页 + 跨域/换排序游标 + 越尾空页 + 降级，另加两条本域特有的：**大厅列表带索引热度快照**（列表浏览量接线）、**筛选 / 非 PUBLISHED 口径走旧直查**（精确 total、无游标，且筛选路径浏览量读计数窄表 = 5）。
- 存量夹具对齐既有事实：`TradePublishPendingReviewIntegrationTest.insertProductRow` 补插索引行（存量 ON_SALE 必有索引行，真实存量由票 01 回填脚本造；不补等于造线上不可能存在的漂移数据）——这是本票唯一改动的既有测试，spec 轴确认属正确修复而非迁就实现。
- 新测 11/11 绿；全量 bs-app 套件 **386 tests, 0 failures**（BUILD SUCCESS，375 存量 + 本票净增 11）；前端 `vue-tsc --noEmit` 过。
- code-review 双轴：standards 轴 0 硬违例（「两域 VO / 编排重复」是 map 定过的判断项、「switch 五档散落 mapper」是 SQL 映射固有形态，均照帖子先例保留）；spec 轴抓到两点已修——跨域游标可被解成合法串（加域标签，补跨域拒绝用例）、大厅两条路浏览量 0 与非 0 不一致（旧直查补计数窄表 JOIN），另把「时间键 / tie-break 两路不同源」落成本节注记。

## Comments
