Status: resolved
Type: grilling
Blocked by:

# 09: 分享位口径（结论：不做，仅留装饰图标）

## Question

**来源：票 02**。帖子详情原型按参考图在作者栏右上角保留了**分享位**（右转箭头图标），但 App 全仓**没有分享链路**（无生成链接、无复制、无系统分享），map 也把本轮范围定在 UI 改版 + 作者主页 + 求购前端。票 02 的结论是**只保留视觉占位、真链路另立票**（2026-09-11 拍板），本票接住它。

要定下来的：

- **分享是什么**：复制站内链接 / 二维码 / 系统原生分享（Web Share API）/ 生成分享卡片图？浏览器与 App 内 WebView 的支持面不同，要选一套能落地的
- **分享给谁**：站内转发（私信发卡片，链路已存在——`CardRefType` 已支持 `POST`）还是外链到站外？外链落地到详情页的**登录要求**是什么（现有 `/post/:id` 不要求登录）
- **落地态**：待审 / 已下架 / 被删的帖子，外链打开后显示什么
- **图标语义**：详情页右上角那个箭头，点下去直接触发分享，还是先弹底部分享面板（站内转发 / 复制链接 二选一）
- **其它详情页**（商品、跑腿）是否共用同一个分享位与面板
- **计数**：要不要「分享数」这个新指标（要则涉及后端新字段，超出本轮「纯前端」口径）

产出：分享链路口径，写进 spec 的对应节；若确认要新指标或新端点，另立实现票。

## Answer

**本 effort 不做分享链路。** 帖子详情顶栏右上角的右转箭头**保留为纯装饰图标**——不可点、不挂 `@click`、不用 `role="button"`，纯视觉占位（用户 2026-09-11 拍板原话：「分享暂时不做了，留个图标就行」）。

不新增后端端点、不新增表字段、不引入前端依赖；**无需另立实现票**，本票即终点。Question 列的六问（分享是什么 / 分享给谁 / 落地态 / 图标语义 / 是否跨详情页共用 / 是否要分享计数）均以「不做」收口。

### 决策依据（票面前提的事实自核，2026-09-11）

| 票面前提 | 核实结果 |
| --- | --- |
| App 全仓无分享链路 | ✅ 成立。全仓无 `navigator.share` / `clipboard` / 分享组件；`front/package.json` 依赖只有 axios/misans/pinia/vue/vue-router，**无二维码类库** |
| `CardRefType` 已支持 `POST` | ✅ 成立，但**只到 `PRODUCT`/`POST`**：`CardRefTypeVO{PRODUCT, POST, ERRAND}` 里 `ERRAND` 仅用于**渲染**，发送入口（`ChatDetailView.vue` 的 `PendingCard`，判据 `refType !== 'PRODUCT' && refType !== 'POST'` 即拒）**不接受跑腿卡片** |
| `/post/:id` 不要求登录 | ✅ 成立（`router/index.ts` 该路由无 `requiresAuth`；`SecurityConfig` 放行 `GET /api/community/**`） |
| 「浏览器与 App 内 WebView 的支持面不同」 | ⚠️ 前提不适用：本仓是**纯 Vite/Vue3 移动优先 Web**，无 Capacitor / 原生壳，只有「浏览器」一个运行面 |
| 站内转发能力 | 帖子卡片**发送链路已全通**（`?card=POST:{id}` → `pendingCard` 条 → `sendMessage`）；缺口只在**选人入口**——`/messages/contacts`（`ContactSearchView.vue`）只列已有会话、不透传 `card` 参数 |

### 若将来要做（记录方向，不在本 effort 范围）

本轮在 frontier 里 grill 出、**未被采纳**的候选口径，供后续 effort 起步：面板动作＝「发送给好友」（复用已全通的 POST 卡片链路，需给 `/messages/contacts` 补 `?card=` 透传）＋「复制链接」（`navigator.clipboard`，注意安全上下文降级），`navigator.share` 存在时另加「系统分享」；外链落地 `/post/:id` 免登录、可见性沿用详情页现有口径；**跑腿卡片当前不可发**，要共用需先扩 `CardRefTypeVO` 的发送面。

### 落地口径

- 图标为**非交互元素**（`<span>`/`<div>`），**不要**写成无处理函数的 `<button>`——那正是票 04 code-review 揪出的「死件」形态。
- 其它详情页（商品 `/product/:id`、跑腿 `/errand/:id`）**不挂**分享位。
- 已写进 `spec.md`「帖子详情布局」节的「分享位（票 09 定）」子节；无实现票。
