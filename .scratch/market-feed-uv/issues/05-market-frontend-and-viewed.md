# 05 · 市集前端整合与浏览记录呈现

Type: task
Status: resolved
Blocked by: 04

## Task

前端跟进 + 浏览记录呈现（含一处与用户的 HITL 对齐）：

1. **排序 chips 重做**：闲置与跑腿两 tab 各五档（热度/最新/最早/价格升/价格降，文案与图标票内定）；切筛选 reset 重拉逻辑接游标协议（`useWaterfall` 已支持 cursor，响应带 cursor 即走 keyset）。
2. **已读灰显**：两域列表接口组装后批量 IN 查浏览明细（主键前缀，照帖子 listPosts 先例），DTO 加 viewed（仅列表填充、匿名 false），MarketView 卡片整卡降不透明度照 `PostCard`。
3. **HITL 对齐（本票的 grilling 环节）**：与用户确认市集页浏览记录的呈现是否止步于已读灰显——若用户还要历史列表入口等别的形态，从本票毕业出独立票（map 雾项已预记）。
4. **`/me/history` 保持帖子专属**：一行不动（用户拍板）。
5. **跑腿 viewCount 展示**：跑腿卡片/详情补浏览量展示（商品已有）；热度排序下两域卡片浏览量展示口径对齐。
6. 匿名兼容：市集两 tab 匿名可浏览，viewed=false、游标可用；对照 `AnonymousRemovalIntegrationTest` 样板补用例。

## Answer

### HITL 结论（用户拍板，本票 grilling 环节）

市集页的浏览记录**止步于已读灰显**：只做 `viewed` 整卡降不透明度，不做历史列表入口、不加卡片角标、不加「只看没看过」筛选。因此本票**没有**毕业出新票，map「Not yet specified」里那条「呈现形态」雾项就此出雾。`/me/history` 保持帖子专属、一行不动。

### 交付物

- **五档排序 chips（前端）**：`MarketView` 两个 tab 各一行五档（热度 / 最新 / 最早 / 价格↑ / 价格↓），页内一份 `MARKET_SORTS` 常量供两 tab 共用——两域文案一字不差，才不会切 tab 时「价格↓」变「悬赏↓」。**图标按「票内定」落成 ↑ / ↓ 字形**，不用 SVG：24px 的 chip 里五个按钮都放得下，也不跟档位文案抢视觉。
- **切排序接游标协议**：跑腿侧此前不传 `sort`（只有固定时间序），本票补 `errandSort` 并 `watch([errandType, errandSort])` → `reset()` + `load()`；reset 清 `page`/`cursor`/`list`，响应带 `cursor` 即 keyset 续页——两域同一条协议，未改 `useWaterfall` 一行。
- **filter-bar 改两行**：分类 / 类型 chips 一行 + 排序 chips 一行（活跃项加浅色胶囊底），sticky 的 `top` 仍由入口行实测高度下发，不新增魔数。
- **已读灰显（后端）**：`ProductResponseDTO` / `ErrandOrderResponseDTO` 加 `Boolean viewed`；两域新增批量回查 `IProductDao.selectViewedProductIds` / `IErrandDao.selectViewedOrderIds` + mapper——明细表主键前缀 `(user_id, 业务ID)` 先钉人再页内 IN，一页一次、不做 N+1；仓储 → 域服务 → 用例层各一层转发（跑腿 controller 只注入 `ErrandOrderCase`，故经它转发）。两个**推荐列表**接口组装后 `viewed = 命中集合.contains(id)`；未登录查出空集 ⇒ 恒 `false`（不是缺字段）。**只在推荐列表接口填**，详情与其他列表（我的发布 / 他人主页 / 收藏 / 搜索）保持 `null`，照帖子 `listPosts` 先例。
- **已读灰显（前端）**：`ProductCard` / `ErrandCard` 整卡 `opacity: .55`（照 `PostCard`）；`types/api.ts` 两域补 `viewed`。
- **浏览量展示**：`ErrandCard` 底部 meta 补「浏览 N」、`ErrandDetailView` 信息卡补「浏览」一行；`ProductCard` 同步补「浏览 N」——两域卡片**同文案、同位置、同 `formatCount`**，热度档下「排序依据看得见」。0 不展示（ES 搜索结果不投影 UV，0 是占位不是真值：宁可缺省也不给错数）。后端不改：大厅列表 `viewCount` 票 04 已接线（索引快照 / 计数窄表），详情本就是精确值。
- **domain 口径**：CONTEXT.md「浏览量」词条把「市集页浏览痕迹」落成「市集两域推荐页已读灰显」，并写明「我的浏览历史页保持帖子专属、闲置与跑腿不另设历史列表入口」；`docs/api/trade.md` + `errand.md` 补 `viewed` 语义（含「仅列表填充 / 匿名 false」）与列表 `viewCount` 两路口径，顺带修正 errand.md 里「降级 / 筛选直查路径恒 0」的过期表述（票 04 已改成读计数窄表）。

### 验证

- 新增 `ProductViewedIntegrationTest`（trade，2 用例）：① 登录浏览详情 → 列表该商品 `viewed=true`、未浏览 `false`、**筛选直查路径同样带标记**、详情为 `null`；② 匿名列表 `viewed` 恒 `false`（断言字段是 boolean 而不是缺字段）、游标续页不重不漏（3 条 / pageSize 2 → 两页）。
- 新增 `ErrandViewedIntegrationTest`（errand，2 用例）：① 登录浏览详情 → 大厅该单 `viewed=true`、未浏览 `false`、**带 type 筛选走旧直查同样带标记**、详情 `null`；② 匿名大厅 `viewed` 恒 `false` + 列表 `viewCount` 可读（卡片展示的数据源）+ 游标续页不重不漏。
- 新测 4/4 绿；全量 bs-app **390 tests, 0 failures**（386 存量 + 本票净增 4）；前端 `vue-tsc --noEmit` 过。
- code-review 双轴：standards 轴 0 硬违例——「trigger 直调域服务」「跑腿 case 纯转发」两条都是既有结构（`ProductController`/`PostController` 直接注入域服务、`ErrandController` 只注入 case），本票不借机重构别域调用链；`.feed-card.viewed` 三处各写一份是 scoped 样式的固有形态，提全局就要动帖子代码（map 定「post 一行不改」）。spec 轴三点已处理：跑腿筛选路径补 `viewed` 断言、卡片复用到搜索 / 收藏 / 他人主页带来的「浏览 0」用「0 不展示」收口、`MARKET_SORTS` 从 `utils/trade.ts` 移回 `MarketView`（唯一消费方，别让工具文件为跑腿排序而改）。

### 已知口径注记

- **详情显示原始数字、卡片走 `formatCount`（1.2万）**：卡片位宽有限才缩写；详情与商品详情既有形态保持一致。
- **匿名恒 `false` 而不是 `null`**：前端 `v-if` 判真即可，不必区分「没登录」与「没看过」——两者都不该灰显，区分只会让前端多一个分支。
- **灰显只覆盖推荐列表**：与帖子同一取舍（帖子也只有主页 feed 填 `viewed`）；卡片组件在搜索 / 收藏 / 他人主页照常渲染，只是不带灰显态与 0 浏览量。
- **0 浏览量不展示**：上线初期两域都从 0 起（商品存量 PV 已清零、跑腿本来没有浏览量），新上架卡片不显示「浏览 0」；热度档里 0 与不显示等价，不影响排序。

## Comments
