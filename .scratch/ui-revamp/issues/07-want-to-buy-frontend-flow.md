Status: resolved
Type: grilling
Blocked by:

# 07: 求购前端全链路口径

## Question

求购（WantToBuy）后端已全链路可用，**前端入口为零**：`POST /api/trade/want-to-buy`、`/draft`、`/submit`、`/pay`、`/withdraw`、`/takedown`、`PUT /{id}`、`DELETE /{id}`、`GET /want-to-buy`、`GET /want-to-buy/mine` 都在，但没有列表栏、没有卡片、没有详情页、没有发布页（`/publish/product` 只做卖闲置）；`api/trade.ts` 连 `createWantToBuy` 都没有。

要定死并写进 spec 的是：

**① 市集第三栏"求购"**：`MarketView` 的 tab 从 `跑腿 / 闲置` 扩为 `跑腿 / 闲置 / 求购`；列表筛选（`GET /want-to-buy` 支持 `category`）与排序；卡片形态沿用「首页信息流分隔与卡片规范」票选定的分隔与卡面规则（Q10："求购跟闲置逻辑差不多，界面也应该差不多"）。

**② 求购详情页**：与商品详情同构的**只读简化版**（Q10）——图片相册 + 标题 + 描述 + 预算 + 发布者 + "私信联系"，**无下单/购买动作**（求购的动作只有私信）。点发布者头像可进作者主页（Q13）。

**③ 发求购入口与流程**：`PublishIndexView`（加号页）增加"发求购"；发布页是新建还是在 `ProductPublishView` 上加模式；求购走**先付后审**（发布 → 冻结预算 → 待审），与商品"先审后售"不同，需复用跑腿已有的"发布 → 支付 → 待审"交互；草稿（`/draft`）、提交（`/submit`）、撤回（`/withdraw`）、下架（`/takedown`）、编辑（`PUT`，改标题/描述/图片回待审、预算不可改）、编辑重审后的通知口径。

**④ "我的-闲置-我求购的"**：现有 tab 里"我求购的"点击跳 `/market`（写死的），要改为跳求购详情；tab 的归属（留在"闲置"子 tab 还是独立）与列表口径（含草稿/待审/被拒？）一并定。

正式请求：定死页面清单、流程、以及**可复用的既有交互**（跑腿支付链路、`ImageUrlPicker`、`EmptyState` 等）。

## Answer

**2026-09-11 收敛**：两轮 frontier（8 + 3 问）→ 用户「都按你推荐的」全部采纳。口径已进 spec（「市集求购前端链路」节）。

### 决策速览

| # | 问题 | 结论 |
| --- | --- | --- |
| Q1 | 详情页数据来源 | **新增** `GET /api/trade/want-to-buy/{wantId}`（免登录；`NORMAL` 对外，本人另见全部状态 + `rejectReason`） |
| Q2 | 「私信联系」 | **纯私聊、不带卡片**（照跑腿 `createDirectRoom` → `/chat/{roomId}`） |
| Q3 | 求购卡头像 | 后端 DTO **补 `avatar`**（mapper 已 `LEFT JOIN bs_user`，加 `u.avatar` 一行） |
| Q4 | 详情页视角 | **同页双模式**：访客＝只读 + 底部满宽「私信联系」；发布者＝票 03 操作区（有操作→等分底栏 / 纯等待→状态卡）；图片区复用票 02 轮播 |
| Q5 | 发求购入口/页 | `PublishIndexView` 加「发求购」→ **新建** `WantPublishView.vue`；复用跑腿「发布 → 支付 → 待审」 |
| Q6 | 「我求购的」 | **留在「闲置」子 tab**；列表出**全部状态** + 状态徽章；动作统一放详情页；修死链 |
| Q7 | 求购列表排序 | **补后端 `sort`**（`time`/`price_asc`/`price_desc`）＋前端三按钮，照 `GET /products` 同构 |
| Q8 | 编辑重审通知 | **不新增站内通知**，仅前端 toast「已提交审核」 |
| Q9 | `/market` 默认 tab | **保持跑腿** |
| Q10 | 编辑入口路由 | **镜像帖子**：`/publish/want?draft={wantId}`；仅标题/描述/配图可改 |
| Q11 | 求购 tab 空态 | 放「去发求购」→ `/publish/want` |
| Q12 | 被拒后重发（code-review 追问补问） | **补 `POST /want-to-buy/{wantId}/to-draft`**（照商品 `to-draft`）→ `REJECTED → DRAFT`，状态条给「转草稿」+「删除」 |

> **code-review 修正（2026-09-11）**：Spec 轴揪出原稿 3 处错，已改：① 草稿编辑端点——`PUT /{wantId}` 是 **`NORMAL` 专用**，草稿须走 `PUT /{wantId}/draft`，故拆成 `?draft={id}`（全字段可改）/ `?edit={id}`（仅标题/描述/配图）两种编辑态；② 「去支付」自相矛盾——草稿的「去支付」＝先 `submit` 再 `pay`；③ 冻结时点——`pay` 时才冻结，「待支付」应是「**待付预算**」（同票 03「应付金额」），待审/求购中才是「冻结预算」。

### 决策依据（事实底座，均已查证）

- **票面「后端已全链路」不成立**，实测 4 处缺口：① 闲置有 `GET /trade/products/{productId}`（`ProductController.java:137`），求购**无**单取接口（只有 `GET /want-to-buy` 恒 `NORMAL`、`/mine` 全状态）；② 聊天卡片类型 `CardRefTypeVO` 只有 `PRODUCT/POST/ERRAND`、**无 `WANT`**，跑腿「联系」本就不带卡片；③ `WantToBuyResponseDTO` 只有 `nickname`、**无头像**（`WantToBuyMapper.xml:20/164/186` 三处查询均已 `LEFT JOIN bs_user u`，补 `u.avatar` 是一行）；④ 商品有 `POST /products/{productId}/to-draft`（`ProductController.java:130`）而求购无，且 `WantToBuyStatusVO` 把 `REJECTED` 定为终态、编辑端点只认 `NORMAL`。
- **`PUT /want-to-buy/{wantId}` 只认 `NORMAL`**（`WantToBuyServiceImpl.java:102`）；草稿编辑是 `PUT /want-to-buy/{wantId}/draft`（`ProductController.java:242-249`）且**允许改预算/分类**——原稿把两者混为一谈，已拆开。
- 前端 `types/api.ts` 的 `WantToBuy` 缺 `images`/`rejectReason`（后端已返回）。
- 求购状态机：`DRAFT →(submit)→ PENDING_PAYMENT →(pay，冻结预算)→ PENDING_REVIEW →(通过)→ NORMAL /(拒，自动退款)→ REJECTED`；`withdraw`＝`PENDING_REVIEW→DRAFT`+退款、`takedown`＝`NORMAL→DRAFT`+退款；编辑仅 `NORMAL` 可改、改后回 `PENDING_REVIEW` 且**不重新支付/冻结**、预算与分类不可改（`TradePublishCase`、`WantToBuyMapper.xml`）。
- `GET /want-to-buy` 只接 `category`＋分页、**无 `sort`**（`WantToBuyMapper.xml:160-180`），与 `GET /products`（有 `sort`）不一致。
- 交易域现状：闲置「我发布的」对**每个状态**都渲染 `PRODUCT_STATUS_TEXT`、**无独立草稿箱**（`ProfileView.vue:362-387`）；帖子另有 `/me/post-drafts` 草稿箱（**帖子专属**）。故求购「我求购的」内联全部状态，与交易域一致。
- 帖子编辑惯例：`/publish/post?draft={postId}`（`PostDraftBoxView.vue:37`）。
- 复用件俱在：`ImageUrlPicker`、`EmptyState`、`NavBar`、`ErrandDetailView` 的 `doAction`+`load` 支付/等待态（`ErrandDetailView.vue:155-171`）、票 03 操作区规则、票 02 轮播规范。

### 页面清单

| 页 | 路由 | 说明 |
| --- | --- | --- |
| 市集·求购 tab | `/market`（`activeTab='want'`） | 第三栏；分类筛选 + 排序 + 双列求购卡 |
| 求购详情 | **`/want/:id(\d+)`（新）** | 访客只读 + 私信；发布者操作区 |
| 发求购 | **`/publish/want`（新）** | 新建；`?draft={wantId}` 为编辑态 |
| 我的·我求购的 | `/profile`（闲置子 tab） | 全部状态 + 徽章 |

### 后端变更（本票改口径）

| 方法 | 路径 | 鉴权 | 变更 |
| --- | --- | --- | --- |
| GET | `/api/trade/want-to-buy/{wantId}` | 免登录 | **新增** |
| GET | `/api/trade/want-to-buy` | 免登录 | **加 `sort`** |
| POST | `/api/trade/want-to-buy/{wantId}/to-draft` | 登录 | **新增**（`REJECTED → DRAFT`，照商品） |
| （DTO） | `WantToBuyResponseDTO` | — | **加 `avatar`** |

### 产出（已落）

- **spec**：「市集求购前端链路」节（§0 后端缺口 + ① 市集第三栏 + ② 详情页 + ③ 发布流程 + ④ 我的-我求购的 + ⑤ 复用件清单 + 依赖）；索引 ④ 改「已定稿」。
- **map**：Decisions-so-far 加本条；「Out of scope · 求购后端改动」按实测修正（4 处小改动纳入本 spec）。

### 下游

- **frontier 移至票 08**（列表加载与页面状态修复；自票 05 起已解除阻塞）；票 09（分享位）同在 frontier（已 resolved：**不做**，仅留装饰图标）。
