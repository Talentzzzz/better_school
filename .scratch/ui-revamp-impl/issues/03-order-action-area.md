# 03: 下单支付操作区

**Spec:** `.scratch/ui-revamp/spec.md` §「下单支付操作区」（spec 索引 ⑤）

**What to build:** 商品订单页 `/trade/order/:id` 的底部操作区按一条二选一规则重写：**当前「状态 × 角色」下存在可点操作 → 顶部状态条 + 底部等分底栏**；**纯等待 / 终态 → 状态卡，不设底栏**。判据是操作表是否给出 `primary` 或 `secondary`。底栏按「金额 → 次操作 → 主操作」分格（缺格均分，**取消恒定排在主操作左侧**），主按钮只写动词、不重复金额。

同时修掉一个线上真 bug：状态标题与说明原本是一张按**买家视角**写的裸映射，卖家点进待支付单会看到「待支付」。落地时 `statusText` / `statusNote` / `orderActions` 都必须按**角色**（买家/卖家/访客）取词。同一张裸映射还复制在「我的订单」列表页与「我的」页的订单行里，一并按角色取词。另外修掉 `DELIVERED + 访客` 组合当前渲染**空操作条**的问题。

四个操作的既有后端调用与确认弹窗文案不变（支付 / 标记已交付 / 确认收货 / 取消订单），`busy` 禁用态保留；页面其余部分不动。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] 15 个「状态 × 角色」组合的呈现（等分底栏 or 状态卡）与 spec 组合表一致
- [x] 每组合至多一个主操作；需要 3 格时各占 1/3，只有金额 + 一个按钮时各占 1/2；取消恒在主操作左侧
- [x] 卖家与访客看到的状态标题/说明不再是买家口吻（如待支付单卖家显示「待买家付款」）
- [x] 纯等待与终态不渲染底栏；`PAID`/`DELIVERED` 的卖家与访客视角、`COMPLETED`/`CANCELLED` 均按 spec 呈现
- [x] `DELIVERED + 访客` 不再出现空操作条
- [x] 四个操作的调用与确认文案不变；`busy` 期间禁用态保留
- [x] 两处列表页（我的订单、我的页订单行）的状态文案按角色正确
- [x] 构建通过；买家与卖家两个视角各手测一遍

## 实现要点（票 03）

**落点**

- 新增 `front/src/utils/orderAction.ts`（spec「落地注意」建议的两个落点里选了 utils）：
  - `OrderRole = 'buyer' | 'seller' | 'visitor'`；`statusText(status, role)`、`statusNote(status, role)`、`orderActions(status, role)`、`OrderActionSet`。
  - `OrderActionSet` **不携带金额**——金额仍取订单 `price`，只给 `amountLabel`（原型 mock 里的 `amount: 68.5` 是演示数据，未搬）。判据 `primary || secondary` 即「纯等待 / 有操作」二分。
  - `OrderAction.key`（`pay|cancel|delivered|confirm`）把按钮绑回既有四个 handler，`ProductOrderView` 侧用 `ACTION_HANDLERS: Record<key, () => void>` + `onAction(a?: OrderAction)` 分派；四个调用的地址、toast、确认文案、`busy` 语义逐字未动。
- `ProductOrderView.vue`：`role` computed 取代 `isBuyer`/`isSeller`；模板二分——`!canAct` 走 `.status-head` 状态卡（无底栏），否则 `.status-hero` + `.action-bar` 等分底栏（金额 → 次 → 主）。页脚 `padding-bottom` 改 24px，有底栏时才叠 `.has-bar` 的 88px。
- 两处列表页删掉各自的裸映射：`MyProductOrderView` 用 `statusText(o.status, role === 'buy' ? 'buyer' : 'seller')`（同一张单买卖两侧都出现在此页，本次修掉卖家读买家词）；`ProfileView` 的「我购买的」行恒为买家视角，硬编码 `'buyer'`——该列表来自 `listMyProductOrders('buy')`，改口径时须与调用一起改。

**踩坑（spec 已点名，落地再确认）**

- 底栏 **不挂 `.safe-bottom`**，安全区写进自己的 `padding: 10px 12px calc(10px + env(safe-area-inset-bottom))`——scoped `.action-bar[data-v-x]` 权重高于全局类，简写 `padding` 会压掉 `padding-bottom: env(...)`。
- 分格类名 **不能叫 `.cell`**（`common.scss` 有同名全局列表行类，会把 `.btn-primary` 红底盖成白底白字），落为 `.action-seg`。

**code-review 采纳**

- Spec 轴抓出真缺口：纯等待状态卡的说明缺 spec 尺寸表的「距上 `4px`」（原型 `.head-note` 有，落地漏了）——已补 `.status-head .status-note { margin-top: 4px }`（**限定在 `.status-head` 内**，避免与 `.status-hero` 的 `gap: 4px` 叠成 8px）。其余 15 组合的分支/金额标签/次操作/主操作/等待文案/状态标题/说明被逐行核对，与 spec 组合表一致。
- Standards 轴的三条 judgement call **未采纳**并留档：①`.action-sec` 覆盖 `.btn-outline` 的底色/字色/内边距——这正是 spec 尺寸表要求的「次按钮复用 `.btn-outline`：`border #ddd`、文字 `$text-2`」；②三个 `switch` 并行（`statusText`/`statusNote`/`orderActions`）——spec「落地注意」明确点名这三个函数，且 `statusNote` 是 06 求购/跑腿详情页的预定复用面；③`.action-sec, .action-main` 与 `.action-seg` 重复声明 `height: 44px`——防御性重复，且与原型逐字一致。

**已知波及 / 不在本票范围**

- **分格非严格等宽（留档，未改）**：`.action-seg` 是 `flex: 1`（`flex-basis: 0%`），但按钮自带 `padding: 0 8px` + 1px 边框，浏览器会把 flex base size 垫到 padding+border 之和，于是实测三格为 `105.3 / 123.3 / 121.3`（两格为 `170 / 188`）、不是精确 1/3 与 1/2。三方来源一致（spec 尺寸表写的也是 `flex: 1 等分`；原型 `OrderActionVariantD.vue` 同样写法；用户翻页选定的是原型）。要精确等宽需改 `display: grid; grid-auto-flow: column; grid-auto-columns: 1fr`，或去掉按钮内边距——**本票按 spec 与原型保留 `flex: 1`**，若验收要求像素级等分，改一行即可。
- **`PAID / 访客` 的说明是「货款已托管冻结，交付后转给你」**：对第三方访客称「你」不成立。此串与用户翻页选定的原型（截图 `.scratch/ui-revamp/proto-03/D-paid-visitor.png`）**逐字一致**，且 spec 的 `statusNote` 散文只给了买家/卖家两种口吻、未定访客口径，故本票未擅改。注：同一份原型的 `DELIVERED`/`COMPLETED` 是三分支（访客得「转给卖家」），仅 `PAID` 是二分支——不一致在上游 artifact，要改应连 spec/原型一起改。
- **跑腿订单详情页（`ErrandDetailView.vue`）未动**：spec §「同构复用」要求它复用本节的二分规则，但该页可点操作可能多于 2 个（多人单组队/补位/退出/协作群/争议），收口口径 spec 明列为「后续实施票范围」，见 impl `map.md` 的 Not yet specified。
- 求购详情页（票 06）的两模式操作区按 spec §市集求购 复用本节 `orderActions` 判据，`OrderActionSet` 的形状已按「金额格 + 至多 1 主 + 次操作恒在主左侧」设计，可直接扩 `key`。

**验收方式**

- `npm run build`（vue-tsc + vite）绿。
- Playwright 拦 `/api`（正则 `^http://localhost:5177/api/` 限定——**不能用 `**/api/**`**，glob 会把 Vite 的 `/src/api/*.ts` 模块请求也拦成 JSON 导致白屏）伪造 `GET /trade/orders/{id}`，逐一遍历 5 状态 × 3 角色共 15 组合，读回 DOM 与计算样式：分支（`.status-head` / `.status-hero`+`.action-bar`）、状态标题、说明、等待文案、金额标签、分格 `flex-grow/高度/字色/底色/边框色`。截图 15 张存 `.scratch/ui-revamp-impl/verify-03/`（另 3 张列表页截图同目录）。
- 交互实测：次操作「取消订单」弹出确认（文案未变）→ 点「取消」不产生请求；主操作「确认支付」弹确认 → 点「确定」→ `POST /api/trade/orders/202609110001/pay` → toast「支付成功，资金已托管冻结」。`DELIVERED + 访客` 复核为状态卡、无空操作条。
- 列表页：伪造 5 状态订单，`MyProductOrderView` 买/卖两侧读出 `待支付 / 待卖家交付 / 待收货` 与 `待买家付款 / 待交付 / 待买家确认`；`ProfileView`「我购买的」读出买家口径。
