# 04: 前端·市集入口行 + 聚合订单页 + 旧入口收口

**Spec:** `.scratch/marketing/spec.md` §「市集入口行」

**What to build:** 市集页三 tab 之下、`filter-bar` 之上插入一行「我的订单 / 收藏 / 消息 / 活动」四格入口，并把「我的订单」的去向做成一个**聚合订单页**（前端并发调三个既有接口、三 tab），最后把两个订单类旧页删掉、`ProfileView` 的重复 subtab 收敛。

**Blocked by:** 02（消息格角标要未读数接口）、03（消息格指向市集消息页）

**Status:** resolved

- [x] 入口行位置：市集页三 tab 之下、`filter-bar` 之上；**搜索仍是 nav 右上图标**（跳 `/search?scope=market`），不做真输入框
- [x] 白底整条、四个等分格；每格 = 42px 圆角方图标块（各自浅色底 + 主题色图标，圆角 14px）+ 12px 灰字标签居中
- [x] 消息格右上角带未读角标（红底白字，>99 显示 `99+`）
- [x] **未读角标范围**：spec 未定，落地时须定并写进票面（推荐：市集消息页三类未读之和 = `MARKET_ERRAND` + `MARKET_WANT` + 对家会话未读）
- [x] **三层吸顶按顺序压住**：`.market-nav` 48px / sticky `0`；入口行 sticky `48px`；`filter-bar` sticky **`48px + 入口行实测高`**
- [x] `filter-bar` 的 sticky top **由入口行高度导出**（用 `ResizeObserver` 实测 `offsetHeight` 后下发），**不能两处各写一个魔数**；入口行高度受全局 `body { line-height: 1.7 }` 影响（12px 字实测行盒 20.4px ⇒ 原型实测整行 86.4px，不是 73px）
- [x] 去向：我的订单 → 聚合订单页；收藏 → 现有 `/me/favorites`（**不新建**）；消息 → 票 03 的市集消息页；活动 → `/marketing`（**该路由由票 11 建，本票只落链接，验收不含跳转**）
- [x] 聚合订单页：前端**并发调三个既有接口**（`/api/errand/orders/mine`、`/api/trade/orders/mine`、`/api/trade/want-to-buy/mine`）、**前端聚合**，不做后端聚合接口；三 tab = 跑腿 / 闲置 / 求购（与市集页同序），各 tab 各自分页
- [x] 跑腿 tab 保留「我发布的 / 我接的」双 sub-tab + 状态筛选；闲置 tab 保留「我买的 / 我卖的」双 sub-tab（无状态筛选）；求购 tab 无 sub-tab、状态用行内角标
- [x] 外层套本节的入口行三 tab 样式，列表行沿用旧页实现（**不另开原型票**）
- [x] 删 `/me/product-orders`（`MyProductOrderView.vue`）与 `/me/errands`（`MyErrandView.vue`）两个订单类旧页（吸收进聚合页）
- [x] `SearchView.vue:253`「查看更多我的跑腿 ›」改指聚合页跑腿 tab
- [x] **保留 `/me/favorites`（`MyFavoritesView.vue`）**——它是「收藏」格的去向，落地后该路由仍在
- [x] `ProfileView.vue` 闲置 subtab 收敛为「我发布的」单栏、跑腿 subtab 收敛为「我发布的」单栏（我收藏的 / 我求购的 / 我购买的 / 我接的移出），让聚合页成为订单类唯一入口
- [x] `npm run build`（`vue-tsc`）通过；双视口目视验收三层吸顶不高亮/不叠压，截图存 `verify-04/`
- [x] 视觉照 `prototype/marketing` 分支的 `MarketEntryA.vue`（变体 A，白卡图标格）

## 实现要点（票 04）

### 落地清单

- **入口行**：新组件 `front/src/components/MarketEntryRow.vue`（照 `prototype/marketing:MarketEntryA.vue` 逐字搬：`padding: 8px 12px 10px`、42px 圆角 14 图标块、12px `$text-2` 标签、badge `top:-5px/right:-7px` 16px 胶囊）。吸顶 `sticky/48px/z-index 99` 写在组件根上。四格去向键导出为 `EntryKey` 类型（`<script lang="ts">` 类型块 + `defineEmits<{ go: [key: EntryKey] }>`）。
- **MarketView**：入口行插在 `</header>` 与三处 `filter-bar` 之间；`.filter-bar` 的 `top` 由 `:style="{ top: filterTop }"` 下发、`z-index` 由 99 降到 97（低于入口行），CSS 里的 `top: 48px` 已删。`syncFilterTop()` 实测 `entryRef.$el.offsetHeight` 后写 `48 + h`，`ResizeObserver` 观察入口行、`onBeforeUnmount` disconnect。
- **消息角标取数**：`noticeUnread`（`getNotificationsUnreadCount('MARKET_ERRAND')` + `'MARKET_WANT'`，`Promise.all` 并发）+ `roomUnread`（computed 覆 `chatRooms`，筛 `marketRoomSource` 非空）→ `entryUnread`。未登录时 `loadEntryUnread()` 直接 return（**不取数，否则 unread-count 401 会被 http 拦截器推去 `/login`**），实测游客态无角标、不跳登录。
- **聚合订单页**：新页 `front/src/views/me/MyOrdersView.vue` + 路由 `/me/orders`（`?tab=errand|idle|want` 定初值，`SearchView` 的「查看更多我的跑腿」即指 `?tab=errand`）。三个 `useWaterfall` 实例（跑腿 / 闲置 / 求购），`onMounted` 三个 `load()` 并发（= 并发调三个既有接口）；子 tab 与状态筛选切换 = `reset()` + `load()` 本 tab 的流；每 tab 自带哨兵与分页（`pageSize 10`）。跑腿状态筛选走**服务端** `status` 参数（旧页是取 50 条再客户端 filter，分页下不能那么干）。
- **旧入口收口**：`git rm` 两个旧视图 + 两条路由；`SearchView` 改指；`ProfileView` 删 4+2 子栏与对应 state/loader/SCSS；`uiState` 删 `profileProductTab`/`profileErrandTab`（全仓零引用后删）；`WantPublishView.goMyWants()` 顺势改指 `/me/orders?tab=want`（原来靠 `profileProductTab='want'` 落到已删的子栏）。

### 落地裁决（票面留白 / 有歧义处）

- **角标范围（票面要求落地时定）**：＝ 市集消息页三类未读之和 —— `MARKET_ERRAND` 系统消息未读 + `MARKET_WANT` 系统消息未读 + **市集消息页收录的全部对家会话未读**。会话口径与市集消息页**同源**（新 `front/src/utils/chat.ts: marketRoomSource()`：`source` 优先，跑腿多人单群按 `refType==='ERRAND'` 归跑腿），票 03 的 `MarketMessagesView.partyRooms` 一并改用它 —— 否则「页面上看的」与「角标算的」会各写一份再各自漂移。实测：3 + 2 + 4 = 9，非市集会话的 7 不计入；>99 显 `99+`（灌 80+80 得 160 实测显示 `99+`）。
- **「外层套本节的入口行三 tab 样式」**：读作「复用市集那条三 tab 的等分形态」，与票 03 的市集消息页取同解（等分 + 主色高亮，**不搬**市集页 `.nav-tab` 的 17px + 下划线指示器）。两者样式原本逐字重复 ⇒ 抽成全局 `.tabs-equal`（`styles/common.scss`），聚合页与市集消息页共用。
- **「并发调三个接口」**：按字面落成 `onMounted` 三个 `load()` 并发（跑腿我发布的 / 闲置我买的 / 求购），**不做**后端聚合接口；两个次级子栏（我接的 / 我卖的）按需懒加载。若日后嫌首屏多两个请求，改成「只加载当前 tab」是一行的事。

### code-review 采纳项

- **跑腿状态文案收敛**：`MyOrdersView` 抄的 `ERRAND_STATUS_TEXT` 与 `ProfileView`、`ErrandDetailView` 已是**三份**且已漂移（`PENDING_REVIEW` 一处「待审」一处「人工审核中」）⇒ 提到 `utils/errand.ts` 一份，三处改引用（顺带给 `ProfileView` 补上原先缺的 `REJECTED`）。
- 入口行四格键的联合类型原本在组件与调用方各写一份 ⇒ 导出 `EntryKey` 复用。
- `.tabs-equal` 抽取（同上）。
- 未采纳：`activity → /marketing` 无路由（按票面本就留给票 11，见下）；`ensureLoaded()` 被评「死代码」（`onMounted` 已全量加载 ⇒ 常态是 no-op）—— 保留，它同时承担**加载失败后切回本 tab 重试**（与 `MarketView.loadTabIfNeeded` 同形）；`readTab(route.query.tab)` 不监听 query 变化（现有调用方全是跨页跳转，不触发）。

### 已知波及 / 不在本票范围

- **`活动` 格现在点下去会落到 `/home`**：`/marketing` 由票 11 建，本票只落链接；票 11 落地后自动生效（catch-all `/:pathMatch(.*)*` 会把未知路径弹回首页）。
- **角标只在进入市集页时取一次系统消息未读**：会话未读是 computed（WS 推送实时更新），系统消息未读是挂载时一次 HTTP。停在市集页期间新到的系统消息要重进本页才反映——与底部消息页入口角标同一节奏（通知无 WS 推送）。要实时得另建通知推送，不在本票。
- `marketRoomSource` 带来一处**静默语义变化**：原先跑腿 tab 只认 `refType==='ERRAND'`（不管 `source`），现在 `source` 优先 —— 即「来源已钉死为另一域、却带跑腿 refType」的会话会归到它的来源域。认定 `source` 是权威口径（首来源钉死）故为预期行为。
- 首帧 `filterTop` 是 `48px`、挂载后立刻校正为 `48 + 实测高`，理论上有一帧抖动（实测截图/量值无异常）。

### 验证实测（`verify-04/`，15 张）

- **三层吸顶实测（390×600 scrolled / 1280×800）**：`nav 0–48`（sticky 0 / z100）、`entry 48–134`（sticky 48 / z99，`offsetHeight` 86）、`filter 134–176`（sticky top **实测 134px** = 48 + 86 / z97）。滚到底（scrollY 273）三层位置不变、无叠压 —— 与原型实测 134.4 一致。
- **角标**：登录态 9；灌 80+80 显示 `99+`；游客态无角标且不被推去登录。
- **四格去向**：我的订单 → `/me/orders`；收藏 → `/me/favorites`；消息 → `/market/messages`；活动 → `/marketing`（未建 ⇒ 落 `/home`，见上）。
- **聚合页**：`?tab=want` / `?tab=idle` 定初值；跑腿「我发布的 / 我接的」、闲置「我买的 / 我卖的」切换各拉各的（网络记录：`role=publish` → `role=take`）；状态切到「已完成」请求带 `status=COMPLETED`（服务端筛选生效）；闲置行状态按角色取词（卖家侧 `PAID` 显「待交付」）；求购无子 tab、行内角标（预算绿色 / 已拒显理由）。
- **`ProfileView`**：闲置 / 跑腿两 tab 子栏数为 **0**，各只列「我发布的」（闲置用 `products/mine`、跑腿用 `orders/mine?role=publish`）。
- **`npm run build`（`vue-tsc -b` + vite build）通过**；产物无 `MyErrandView`/`MyProductOrderView` chunk。
- 前端无测试设施（本仓无 vitest/e2e），故验收 = build + 浏览器目视（Playwright 拦 `/api/` 回伪造数据；路由正则须限定 host，`**/api/**` 会把 Vite 的 `/src/api/*.ts` 模块请求也拦成 JSON）。
