# 06: 市集求购前端链路

**Spec:** `.scratch/ui-revamp/spec.md` §「市集求购前端链路」（spec 索引 ④）

**What to build:** 打通求购的浏览 → 详情 → 发布 → 我的四条前端链路，并补齐后端缺口。

**后端 4 处补齐**（同步 `backend/docs/api/trade.md` 与前端 `types/api.ts`）：
1. 新增 `GET /api/trade/want-to-buy/{wantId}` 单取（免登录；`NORMAL` 对外可见，本人另可见全部状态 + `rejectReason`）——照商品单取同构。
2. `GET /api/trade/want-to-buy` 补可选 `sort`＝`time`(默认)/`price_asc`/`price_desc`（价格即预算）——照商品列表的排序分支同构。
3. `WantToBuyResponseDTO` 补 `avatar`（mapper 三处查询已 `LEFT JOIN bs_user`，补 `u.avatar`）——现只有 `nickname`，与求购卡「头像 + 昵称 + 时间」冲突。
4. 新增 `POST /api/trade/want-to-buy/{wantId}/to-draft`（`REJECTED → DRAFT`，可编辑后重新提交）——照商品同名端点。
   另修前端类型：`WantToBuy` 缺 `images`、`rejectReason`（后端已返回）。

**前端：**
- 市集第三栏「求购」：tab 顺序 跑腿/闲置/求购、**默认仍跑腿**；分类 chips 同闲置；排序 最新/价格↓/价格↑；卡片沿用 **01** 的求购卡（「求购」绿 badge + 分类 → 标题 → 描述 → 绿色预算 → meta）；空态引导去发求购；加载时机与分页走 **05** 的 `useWaterfall` 口径（三 tab 各自独立）。
- 新求购详情页 `/want/:id`（免登录，同商品详情）：图片相册**复用 02 的轮播规范**；标题 → 预算 → 分类 → 描述 → 发布者行（点击进 `/user/:userId`）→ 发布时间；无收藏/无下单/无 `viewCount`。操作区按 **03** 的二选一规则：访客只读时「私信联系」单格满宽；发布者按状态出等分底栏或状态卡，**至多 3 格**、金额格按状态取词（待支付＝「待付预算 ¥X」尚未冻结 / 待审与求购中＝「冻结预算 ¥X」）。「求购中」不直接给删除，先下架再删；被拒显示驳回原因并可「转草稿」。
- 新发求购页 `/publish/want`（不复用商品发布页）：字段 标题/描述/配图(≤6)/预算/分类；两动作「发布并去支付」（→ `PENDING_PAYMENT` → 跳详情页）与「保存草稿」（→ `DRAFT` → 跳我的求购列表）；编辑态读 query 分流——`?draft=` 全字段可改（走草稿编辑端点）、`?edit=` 仅标题/描述/配图可改（走求购中编辑端点，保存后回待审、不重新支付）；预算不支持改（不等值即报错）。支付链路复用跑腿「发布→支付→待审」，超时/驳回/撤回/下架文案按 spec。
- 发布索引加第 4 项「发求购」入口。
- 「我的-闲置-我求购的」：整行点击修死链（`/market` → `/want/:id`）、空态按钮指向 `/publish/want`、行内显示六态徽章（列表行不放行内动作，动作统一在详情页）。

**Blocked by:** 01（求购卡沿用其卡面规范）、02（详情页复用其轮播规范）、04（发布者行跳作者主页）、05（市集第三栏接其 `useWaterfall` 与 store）

**Status:** resolved

- [x] 市集第三栏可切换、带分类筛选与排序、首次切入才加载、分页与另两栏独立；空态引导到发求购页
- [x] 求购详情：访客只读并满宽「私信联系」；发布者按状态出对应底栏/状态卡（至多 3 格、至多 1 个主操作）；草稿「去支付」＝先 submit 再 pay；被拒显示驳回原因且可「转草稿」；撤回/下架提示冻结预算已退回
- [x] 发求购：发布并去支付 → `PENDING_PAYMENT` 后跳详情；保存草稿 → `DRAFT` 并跳我的求购列表；`?draft=` 全字段可改、`?edit=` 仅标题/描述/配图可改；改预算被拒并给出提示
- [x] 「我求购的」行点击进 `/want/:id`（不再跳市集）、空态按钮指向 `/publish/want`、行内显示六态徽章、被拒行显示原因
- [x] 后端 4 处补齐各有集成测试；`docs/api/trade.md` 与前端类型同步
- [x] 构建通过；访客视角与发布者各状态（草稿/待支付/待审/求购中/被拒/已取消）手测通过

## 实现要点（票 06）

### 后端 4 处补齐（`backend`）

1. **`GET /api/trade/want-to-buy/{wantId}` 单取**：`IWantToBuyService.wantToBuyDetail(wantId, currentUserId)` 做可见性——`NORMAL` 对外可见，其余五态仅发布者本人可见、他人与访客一律 404（不泄露存在性）；控制器按商品详情同构在 `REJECTED && isOwner` 时补 `rejectReason`（用 `entity.isOwner(...)` 内联判定而非 `fillWantToBuyRejectReason` 复用，后者 `currentUserId.equals(...)` 对访客 null 会 NPE）。`/want-to-buy/mine` 是字面量路径，Spring `PathPatternParser` 的字面量优先级高于 `{wantId}`，未被抢走（有集成测试钉住）。
2. **`GET /want-to-buy` 加 `sort`**：`time`(默认)/`price_asc`/`price_desc`（价格＝预算），照 `ProductMapper.xml` 同构落 `<choose>`；域层做白名单收敛（非法值回落 `time`，不让脏参数透传 SQL）。签名改动穿透 `IWantToBuyService.listWantToBuy` / `IWantToBuyRepository.pageList` / `IWantToBuyDao.selectPage`。
3. **`avatar`**：`WantToBuyResponseDTO` / `WantToBuyEntity` / `WantToBuyPO` 各加字段，mapper 三处查询（`selectById`/`selectPage`/`selectPageByUser`）补 `u.avatar`（三处本就 `LEFT JOIN bs_user`，只差这一列）。
4. **`POST /want-to-buy/{wantId}/to-draft`**：`REJECTED → DRAFT`，新 `rejectToDraft` 条件 UPDATE + 域方法 `moveRejectedWantToBuyToDraft`（照商品同名端点）。被拒时预算已退回，重提走 `submit → pay`，按 `pay_round` 冻结新轮次流水。

`docs/api/trade.md` 已同步（新增单取、`to-draft`，补 list 的 `sort`，`to-draft` 路由提示）。

### 前端

- **新增 `front/src/utils/trade.ts`**：`TRADE_CATEGORIES`（六类 chips 配置）+ `tradeCategoryText`。**顺手收敛了原有的 4 份分类文本副本**（`MarketView` chips、`ProductCard`、`ProductDetailView`、`ProductPublishView`），否则本票会新增第 5、6 份。
- **新增 `front/src/utils/wantAction.ts`**：`WANT_STATUS_TEXT`（发布者六态）+ `wantActions(status)` / `wantVisitorActions()`。⚠️ **偏离 map 的一条 Decision**：map 写「06 直接 import `utils/orderAction.ts`，`OrderActionSet` 的 key 域可直接扩」——但 `OrderActionSet` 只有**单槽** `secondary`，而求购 DRAFT 需要**两枚**次操作（删除 + 编辑），底栏至多 3 格；故另落自有 `WantActionSet`（`secondary: WantAction[]`），**二分判据（有无可点操作 → 状态条+等分底栏 / 纯等待 → 状态卡）与 `.action-bar`/`.action-seg` 尺寸逐字沿用票 03**，未改动 `orderAction.ts`（不把求购 key 灌进商品订单的封闭 union）。
- **`WantToBuyCard.vue`**：按票 01 求购卡——`.feed-card` 外壳，**首图 1:1（`images[0]`，`.feed-thumb` + `AppImage`，无配图则不占位退回纯文字卡）** → 顶行绿 badge「求购」+ 分类 → 标题 → 描述（`ellipsis-3`）→ 绿色 `#1f9d4d` 预算 → meta（头像 16 + 昵称 + 时间）。⚠️ **spec 此处自相矛盾、按用户拍板定案**：§「各卡面信息排布」表（L62）与票面 bullet 都只列「顶行→标题→描述→预算→meta」（无图），但 §1 行文（L408）写了「图 1:1」；初版按卡面表做成无图（与跑腿卡同口径），**用户 2026-09-12 选「显示首图 1:1」**，遂改为有图版并保留无图兜底。图的间距用 `.feed-thumb + .want-head { margin-top: 8px }`——无图卡首元素即顶行，不留白。
- **`MarketView.vue`**：nav 加第三 tab「求购」（顺序 跑腿/闲置/求购，默认仍跑腿）；分类 chips 与排序按钮复用闲置那套；第三个 `useWaterfall` 实例；`loadTabIfNeeded` 加 `'want'` 分支（首次切入才加载）；`scrollPos` 的 `MarketTab` 三值早就位。空态「还没有求购」→ `/publish/want`。
- **新增 `views/trade/WantDetailView.vue`**（路由 `/want/:id(\d+)`，免登录）：`NavBar` → 二分状态区 → （被拒时）驳回原因卡 → `ImageCarousel`（复用票 02，`n/N` + 圆点）→ 标题/绿色预算/分类 → 求购说明 → 发布者行（点进 `/user/:userId`）+ 发布时间。**无收藏/下单/浏览数**。
- **新增 `views/trade/WantPublishView.vue`**（路由 `/publish/want`，需登录）：三模式——create（保存草稿 / 发布并去支付）、`?draft=`（全字段可改，两动作；「发布并去支付」＝ PUT draft → submit → pay）、`?edit=`（预算与分类**禁用** + 「预算不可修改，如需调整请先下架后重新发布」提示，单动作「保存并提交审核」）。预算不传给 `PUT /{id}`（DTO 无该字段，null＝不改）。
- **`PublishIndexView.vue`** 加第 4 项「发求购」（🔎）。
- **`ProfileView.vue`「我的-闲置-我求购的」**：整行 `/market` → `` `/want/${w.wantId}` ``；空态 `/publish/product` → `/publish/want`；右列加六态徽章（`.errand-status`，被拒红底 `.rejected`）+ 绿色预算；被拒行在描述下加 `.errand-reason` 显示原因。
- **`types/api.ts`**：`WantToBuy` 补 `images`/`avatar`/`rejectReason`，`status` 由 `string` 收紧为新增 `WantToBuyStatus` 六态 union（`WANT_STATUS_TEXT` 的索引签名依赖它）。**`api/trade.ts`**：`listWantToBuy` 由位置参数改为对象参数（加 `category`/`sort`），新增单取/发布/草稿/提交/支付/撤回/下架/编辑/转草稿/删除共 10 个函数。

### code-review 采纳项

两轴并行跑（Standards / Spec）。**Spec 轴**：动作区七行组合、金额格取词、六态徽章、`?draft=`/`?edit=` 可改字段、后端 §0 四处全部核对通过，无「实现错」项。**Standards 轴**：无 documented-standard 硬违规。采纳的改动：

- **【Spec】驳回原因挪进状态条**：原实现把 `rejectReason` 放在 hero 下方一张独立 `.reject-card`，spec §2 表线 428 明写「顶部**状态条含**驳回原因」、线 433「驳回原因进状态条，并注明冻结预算已全额退回」→ 改为在 `.status-hero` 内渲染 `.status-reject`（无独立卡），REJECTED 的 note 相应改为「冻结预算已全额退回，可修改后重新提交」以免与状态标题重复；另渲染一张卡也随之下线。
- **【Spec】预算下限收紧**：客户端原判 `budgetNum <= 0`，0.005 会放过（靠后端 `@DecimalMin("0.01")` 兜底弹错）→ 改 `< 0.01` + toast「预算需不低于 0.01」。
- **【Standards】分类文本收敛**：原计划只在 3 个新文件用 `tradeCategoryText`、留着 4 份旧副本——改为**一并收敛**（见上）。
- **【Standards】`wantVisitorActions()` → `WANT_VISITOR_ACTIONS` 常量**：无参函数返回常量字面量属无谓调用；访客只可能看到 NORMAL、与状态无关，落常量更诚实。
- **【Standards】`'time'|'price_asc'|'price_desc'` 去重**：原重复声明在 `api/trade.ts` 两处 + `MarketView` 两处 → 提 `utils/trade.ts` 的 `SortOrder` 共享。
- **【Standards】文档标题去掉工单号**：`trade.md` 新增节原带「（UI 改版票 06）」——与该文件既有的「票 06」（指设计阶段票 06，作者主页口径）撞名，删除。
- **【自检】`WantDetailView` 的删除动作**：最初走 `doAction`，会在 `router.replace('/market')` 之后仍 `await load()` 对已删求购取详情、弹「求购不存在」→ 改为不走 `doAction` 的 load，独立 confirm + 请求 + 跳转。

### 未采纳 / 留档

- **`OrderActionSet` 未复用**：`OrderActionSet` 只有**单槽** `secondary`，求购 DRAFT 需两枚次操作；reviewer 建议把 `OrderActionSet.secondary` 拓宽为数组以合流——未采纳，那要连带改 `ProductOrderView` 的模板（商品订单最多 1 个次操作，拓数组只为让求购复用一个类型），收益不抵改动面。已按「二分判据与底栏尺寸逐字沿用」落地，偏离已记 map。
- **后端 sort 白名单 switch 与前端三份 `.filter-bar`/`useWaterfall` 脚手架重复**：sort 归一化的 4 行 switch 与 `ProductServiceImpl` 同形；市集三 tab 的筛选条/瀑布实例是同一形状三份。均为 judgement call，未收口——前者为 4 行 × 2 处（第三处出现再提），后者是票 05 既有结构（errand/idle 本已各一份），本票只按既定形状加第三份，抽组件会牵动三个 tab 的哨兵 ref。已留档。
- **`WantDetailView` 的 `doAction`/handler map/`.action-bar` 样式与 `ProductOrderView` 1:1**：spec 明写「同一套写法、逐字沿用票 03」，抽共享组件/CSS 会同时动已落地的 `ProductOrderView`，未做。
- **三格非像素级等宽**（117/117/115、171/187）：与票 03 同一成因（`flex:1` 按钮自带 padding/border），沿用未改。
- **测试工具方法第三份副本**（`adminSession`/`createPaidWant`/…）：仓库既有 `TradeFullChainIntegrationTest`、`TradeRejectDraftAndEditIntegrationTest` 同形，未抽 `TradeApiTestSupport` 基类（会牵动两个已落地测试类）。
- **`opts.pageSize`、`reset()` 兼管滚动**：沿用票 05 composable，未动。

### 不在本票范围

- **跑腿详情页底栏收口**（spec 已列后续票）：`ErrandDetailView.vue` 未动。
- **统一加载态/骨架屏**：仍用各页现有 `EmptyState`。
- **求购收藏/下单/浏览数**：求购无这些概念，详情页明确不渲染（已验 `favBtn=0 / buyBtn=0 / viewCount=0`）。

### 验收

- **后端**：`mvn -o test -pl bs-app -am` 全量 **323 用例绿**（原 315 + 本票新增 8）。新增 `WantToBuyFrontendFlowIntegrationTest`（8 用例）覆盖：单取对访客可见且带 avatar/nickname、非公开态仅本人（他人/访客 404）、被拒单取带 `rejectReason` 且他人 404、不存在 id 404 且 `/mine` 未被抢占、三种 sort + 非法值回落 + 分类叠加、列表/我的都带 avatar、`to-draft` 全链路（被拒→草稿→改预算重提→重新支付产生第二条 `WANT_FROZEN` 新轮次）、非被拒/他人/访客的闸门。
- **前端**：`npm run build`（vue-tsc + vite）绿；`WantDetailView` chunk 5.67 kB、`WantPublishView` 4.95 kB、`MarketView` 6.41 kB。
- **浏览器**（Playwright 拦 `/api` 伪造数据，正则 `^http://localhost:5173/api/` 限定——glob 会把 Vite `/src/*.ts` 模块请求也拦成 JSON 致白屏；截图 15 张存 `.scratch/ui-revamp-impl/verify-06/`）：
  - **市集第三栏**：进市集只请求跑腿（want-to-buy 请求 0）；切「求购」才发 1 次 `sort=time&page=1&pageSize=10`；卡片 badge「求购」+ 分类 + 绿色 `rgb(31,157,77)` 预算 + meta 3 项；卡指向 `/want/1`；排序切「价格↓」发 `sort=price_desc`；chips＝全部+六类；空态「还没有求购」/「去发求购」→ `/publish/want`；**有配图的卡出 1:1 首图（实测 167×167 正方形、图与顶行间距 8px），无配图的卡不出图块（顶行距卡顶仅 8px 内边距）**。
  - **求购详情 ×（访客 + 发布者 6 态）**：访客 NORMAL＝hero「求购中」+ 单格满宽「私信联系」（宽 351≈满宽）；DRAFT＝「草稿」+〔删除(danger)/编辑/去支付〕；PENDING_PAYMENT＝「待支付」+〔¥120.50 待付预算 / 删除 / 去支付〕；PENDING_REVIEW＝「待审」+〔¥120.50 冻结预算 / 撤回〕；NORMAL 发布者＝「求购中」+〔¥120.50 冻结预算 / 下架 / 编辑〕；REJECTED＝「审核未通过」+ **状态条内**「驳回原因：…」行 +〔删除 / 转草稿〕（`.reject-card` 计数 0）；CANCELLED＝状态卡「已取消」+ 等待文案、**无底栏**（`.action-bar` 计数 0）。主按钮底色 `rgb(255,36,66)`、危险次按钮字色 `rgb(227,77,89)`、格高 44px；金额格取词按状态正确（待付 vs 冻结）。
  - **交互**：DRAFT「去支付」→ `POST /submit` + `POST /pay` 两步；下架/撤回各弹确认（「确认下架？下架后冻结预算将全额退回余额」）→ 对应端点 → toast「已下架，冻结预算已退回余额」/「已撤回，冻结预算已退回余额」；转草稿 → toast「已转入草稿，可编辑后重新提交」；删除 → 确认 → `DELETE` → toast「已删除」→ `/market`。
  - **发求购**：create 六类 chips + 预算可编辑 + 两按钮（保存草稿 / 发布并去支付）；「发布并去支付」→ `POST /trade/want-to-buy` → `/want/7`；「保存草稿」→ `POST /draft` → `/profile` 且落「闲置 / 我求购的」；`?draft=` 预填草稿值且预算可编辑、两按钮；`?edit=` 预算与分类禁用 + 提示、单按钮「保存并提交审核」、toast「已提交审核」；预算填 `0.005` → 前端拦下、**不发请求**、toast「预算需不低于 0.01」。
  - **「我求购的」**：六态徽章依序 草稿/待支付/待审/求购中/审核未通过/已取消；绿色预算；被拒行显示「其他：内容不合规」；点第 4 行 → `/want/4`；空态「还没有发布求购」/「去发求购」→ `/publish/want`。
  - **发布索引**：4 项（发帖子/卖闲置/发跑腿/发求购）。
  - **重构回归**：商品详情 tag 读「九成新 / 图书」、闲置卡渲染、发布闲置 chips 六类+四成色默认「数码」——与收敛前一致。
  - **双视口**：390×844 与 1280×900 均截图核对。
  - 控制台无非预期错误（仅 mock 图 URL `ERR_NAME_NOT_RESOLVED` 与 chat WS `ticket=undefined` 握手失败＝拦 api 后的环境固有噪声）。
