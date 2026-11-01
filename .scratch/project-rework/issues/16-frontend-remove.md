# 票 16 · 前端删功能页（求购 / 群聊 / 多人单 / 评价 / 拉新）+ 成色遗留

Type: task
Status: open
Blocked by: 03, 04, 09

## 上下文预算（硬约束）

| 项 | 值 |
| --- | --- |
| 目标 | **150–200k**；硬顶 **300k** |
| 预估改文件 | ~22（删 4 个 + 改 ~18），全部在 `front/src` |
| 必读 | 本票；仅下面第 2 节列出的文件（**不要全仓 grep**） |
| 不读 | `backend/**`（后端已由 02/03/04/09 收口）；`sql.md`；`front/node_modules` |
| 必跑 | `cd front && npm run build`（`vite build`）+ 手工走查 |
| 越界信号 | 想「顺手把营销页改成新抽奖」→ 停手，那是票 14 |

## 已核实事实（票面已定位到文件，别再全仓搜）

1. **抽检页已不存在**（票 02 已删），本票无需处理。
2. 各功能的前端残留文件（`front/src/` 相对路径）：

| 功能 | 处理 |
| --- | --- |
| 求购 | **删** `views/trade/WantDetailView.vue`、`views/trade/WantPublishView.vue`、`components/WantToBuyCard.vue`、`utils/wantAction.ts`；**改** `api/trade.ts`、`router/index.ts`、`stores/uiState.ts`、`types/api.ts`、`views/MarketView.vue`、`views/PublishIndexView.vue`、`views/me/MyOrdersView.vue`、`views/msg/MarketMessagesView.vue` |
| 群聊 | **删** `views/chat/ChatMembersView.vue`；**改** `api/chat.ts`、`router/index.ts`、`types/api.ts`、`utils/chat.ts`、`views/MessagesView.vue`、`views/MarketView.vue`、`views/msg/MarketMessagesView.vue`、`views/msg/NotificationsView.vue` |
| 多人单 | **改** `views/errand/ErrandPublishView.vue`（人数/报酬设定） |
| 跑腿评价 | **改** `api/errand.ts`、`types/api.ts`、`views/errand/ErrandDetailView.vue` |
| 拉新 / 邀请码 | **改** `views/marketing/MarketingView.vue`、`api/marketing.ts`、`types/api.ts`（只删邀请码区块，其余等票 14） |
| 成色 condition（票 05 连带遗留） | **改** `api/trade.ts`、`types/api.ts`、`components/ProductCard.vue`、`views/trade/ProductDetailView.vue`、`views/trade/ProductPublishView.vue` |
| 市集分区来源枚举 `WANT_TO_BUY` | **改** `types/api.ts`、`views/MarketView.vue`、`views/msg/MarketMessagesView.vue` |

3. 后端已删的对应能力：`WANT_TO_BUY`/`WANT_FROZEN`/`MARKET_WANT`/`bs-want-paid`（票 03）、群聊/多人单相关枚举与端点（票 04）、`errand_evaluation` 与评价接口（票 05）、referral 全套（票 09）、`product.condition` 列（票 05）。
4. 前端 `condition`（成色）已无后端支撑：Spring 忽略未知字段，不发也不报错，只是卡片标签渲染为空——**本票清掉**。

## Question

前端随后端删减做「减法」：删掉求购、群聊、多人单设定、跑腿评价入口、拉新邀请码区块；清掉 `condition`（成色）遗留；同步清理路由 / 导航 / store / api / 类型层；保证无死链、无空区块。**已有视觉风格不变**。

## 施工边界（不做什么）

- 不改营销页与抽奖相关的**其余**部分（→ 票 14）。
- 不动审核工作台（→ 票 14）。
- 不改视觉风格、不重构组件、不动 `front/` 以外的代码。

## 验收

1. `cd front && npm run build` 绿。
2. 源码级清零（`front/src` 内）：`want|Want|WANT_TO_BUY|MARKET_WANT|WANT_FROZEN`、`ChatMembers|群`、`condition|成色`、`invite|Invite|referral|Referral|拉新`、`评价|evaluation`。
3. 手工走查（390 宽移动视口 + 桌面）主要页面无死链 / 空区块：首页、市集、发布索引、消息、消息-市集分区、我的-订单、跑腿发布/详情、商品发布/详情/卡片。
4. `## Answer` 记录：走查截图目录、逐页结论、发现的票面外问题（只记不修）。

## Answer

**结果：前端减法收口完成；`npm run build` 绿；五组验收词在 `front/src` 内全零；34 条走查记录（390 宽移动 + 桌面 × 17 页/路由）全通过，无死链、无空区块、无 console/page error。视觉风格未动。**

### 1. 改动清单（`front/src`，删 5 改 25）

| 功能 | 删除 | 修改 |
| --- | --- | --- |
| 求购 | `views/trade/WantDetailView.vue`、`views/trade/WantPublishView.vue`、`components/WantToBuyCard.vue`、`utils/wantAction.ts` | `api/trade.ts`、`router/index.ts`、`stores/uiState.ts`、`types/api.ts`、`views/MarketView.vue`、`views/PublishIndexView.vue`、`views/me/MyOrdersView.vue`、`views/msg/MarketMessagesView.vue` |
| 群聊 | `views/chat/ChatMembersView.vue` | `api/chat.ts`、`router/index.ts`、`types/api.ts`、`utils/chat.ts`、`views/MessagesView.vue`、`views/MarketView.vue`、`views/msg/MarketMessagesView.vue`、`views/msg/NotificationsView.vue`、`stores/chatRooms.ts`、`views/chat/ChatDetailView.vue` |
| 多人单 | — | `views/errand/ErrandPublishView.vue`、`views/errand/ErrandDetailView.vue`、`api/errand.ts`、`types/api.ts`、`utils/errand.ts`、`components/ErrandCard.vue`、`views/me/MyOrdersView.vue`、`views/AuthorProfileView.vue` |
| 跑腿评价 | — | `api/errand.ts`、`types/api.ts`、`views/errand/ErrandDetailView.vue` |
| 拉新 / 邀请码 | — | `views/msg/NotificationsView.vue`（仅 `REFERRAL_GROUPED: '拉新'` 一行；营销页与 `api/marketing.ts` 已由票 14 随营销页重写删净） |
| 成色 condition | — | `api/trade.ts`、`types/api.ts`、`components/ProductCard.vue`、`views/trade/ProductDetailView.vue`、`views/trade/ProductPublishView.vue` |
| 市集分区来源枚举 | — | `types/api.ts`（`RoomSource` 收为 `ERRAND｜PRODUCT`、`ReviewTargetType` 去 `WANT_TO_BUY`）、`views/MarketView.vue`、`views/msg/MarketMessagesView.vue` |

关键口径（与后端 DTO 逐字对齐，票面已核过）：`ChatRoomSummary` 去掉 `type/name/refType/status`；`ChatRoom` 只剩 `roomId`；`ChatMessage.msgType` 去 `SYSTEM`；`ErrandOrder` 去 `requiredTakers/roomId/members` 与 `ErrandOrderMember`、`ErrandEvaluation`；`Product.condition` 与 `ProductCondition` 删；`getNotificationsUnreadCount('MARKET_WANT')` 与 `MARKET_WANT` 分区删。

### 2. 验收对照

| 验收 | 结果 |
| --- | --- |
| 1. `cd front && npm run build` | ✅ `vue-tsc -b && vite build` 绿（`✓ built in 4.37s`） |
| 2. 源码级清零（`front/src`） | ✅ 五组正则全 0 命中：`want\|Want\|WANT_TO_BUY\|MARKET_WANT\|WANT_FROZEN`；`ChatMembers\|群`；`condition\|成色`；`invite\|Invite\|referral\|Referral\|拉新`；`评价\|evaluation` |
| 3. 手工走查 | ✅ 见 §3：390×844（DPR 2）+ 1280×800 两档，17 个页面/路由 ×2 = 34 条记录 / **64 条断言全过**；页内 `<a href>` 58 处 + 来源内站内跳转 114 处逐个对路由表，**死链 0 处**；console/pageerror 全 0 |
| 4. `## Answer` | ✅ 本节（票面外问题见 §4） |

走查方式：`vite dev`（5173，已在跑）+ Playwright（自动解析本机可用的 chromium）驱动，`/api/**` 用夹具按「收口后的后端契约」应答（商品无 `condition`、跑腿单无 `requiredTakers`、无私信群、无求购、无评价、无 referral），页面级断言 + 逐页整屏截图 + console/pageerror 采集 + 死链双向核（页内锚点 + 源码跳转目标）。

### 3. 走查截图目录与逐页结论

- 目录：`.scratch/project-rework/walkthrough-16/shots/`（34 张：`mobile-*.png` / `desktop-*.png`）
- 脚本与报告：`.scratch/project-rework/walkthrough-16/walk.mjs`、`.../report.json`（含 `staticLinkCheck` 段 + 逐页 landedPath / 64 条断言 / 页内锚点 / console 错误 / 文本样本）
- 复跑：`node .scratch/project-rework/walkthrough-16/walk.mjs`（需 5173 上的 dev server；playwright/chromium 缺失时按脚本头注释准备）

| # | 页面（390 宽 + 桌面） | 结论 |
| --- | --- | --- |
| 01 | 首页 | 无残留、无报错；feed 正常 |
| 02 | 市集·跑腿 | 顶部只剩「跑腿 / 闲置」两 tab；跑腿卡片按单人悬赏渲染 `¥20`（无「每人」） |
| 03 | 市集·闲置 | ProductCard 正常出卡，价格行不再有第二个成色标签（改动前该标签恒为空） |
| 04 | 发布索引 | 只剩 3 个入口（发帖子 / 卖闲置 / 发跑腿），求购入口与 `/publish/want` 路由一并消失 |
| 05 | 消息 | 私信行用对家昵称 + 头像，「群」标签已去；系统消息行正常 |
| 06 | 消息·市集分区 | 只剩「跑腿 / 闲置」两栏；跑腿栏「系统消息 + 跑手消息」两段完整，无空区块 |
| 07 | 系统通知 | system 分段正常，无 `REFERRAL_GROUPED` 角标文案 |
| 08 | 我的·订单 | 只剩「跑腿 / 闲置」两 tab；跑腿行按单人悬赏渲染，无求购列表与「预算」标签 |
| 09 | 跑腿发布 | 「需要人数」整块删除（多人单设定下线），悬赏/限制时长两段完整，余额校验总额=悬赏 |
| 10 | 跑腿详情（待接单） | 「悬赏金额」单人语义；无「人数」行、无组队成员卡、无协作群入口；操作区只剩「立即抢单 ¥20」 |
| 11 | 跑腿详情（已完成） | 无评价入口、无「互评」卡片（不再出现「暂无评价」空块）；「订单已完成」提示保留 |
| 12 | 商品发布 | 「成色」chips 整块删除，分类 chips + 价格 + 交易地点 + 图片四段完整 |
| 13 | 商品详情 | 标签行只剩「分类 + 浏览数」；成色标签已去，无空标签占位 |
| 14 | 我的 | 资料区/入口正常，无求购入口 |
| 15-17 | 已删路由 `/publish/want`、`/want/1`、`/chat/11/members` | 三者在两档视口下均被 catch-all 兜回 `/home` 且页面非空 —— 死链不落白屏 |

补充：`群`、`求购`、`成色`、`评价`、`拉新` 等字样在两档 × 17 页的整页可见文本里 0 命中（脚本按 `FORBIDDEN` 列表逐页校验）。

### 4. 发现的票面外问题（只记不修）

1. `front/vite.config.ts:29` 注释仍写「referral 域已随票 09/16 下线，代理一并删除」——代理本身票 14 已删，但 `referral` 字样留在 `front/src` 之外的配置注释里（验收 2 只查 `front/src`，故未动）。
2. **票面「拉新 / 邀请码」行的三处目标已由票 14 提前满足**：`views/marketing/MarketingView.vue` 无邀请码面板、`api/marketing.ts` 无 `getInviteCode/listInvitees/createGroup` 与 4201–4206 业务码；本票实际只删掉 `NotificationsView.vue` 的 `REFERRAL_GROUPED: '拉新'` 一行。
3. 后端 `GET /chat/rooms/{roomId}/members`（`ChatMembersResponseDTO`）**仍在**：1v1 已读回执的水位初值仍需要它（spec §7.5），故前端保留 `listMembers`，只把类型名收敛为后端口径的 `RoomMember`/`RoomMembers`，并删掉只服务群聊的 `readersOf/allRead` 与「全部已读」读者头像块。
4. `types/api.ts` 的 `NotificationType` 联合类型仍缺后端已产出的 `AUDIT_PENDING`、`POST_LIKED`、`LOTTERY_WON`、`POINTS_CHANGED` 等值（`NotificationsView` 用 `Record<string, string>` 兜底故不报错、也不影响渲染）——不在本票五组清零词内，未动。
5. `ReviewWorkbenchView.vue`（审核工作台）本身未动；其「求购」审核位随 `ReviewTargetType` 收窄自然消失（符合票面「不动审核工作台」）。

### 5. 连带影响清单（超出票面表格的文件，均有据）

1. `views/chat/ChatDetailView.vue`：票面未列，但 `ChatMembers` 清零 + 路由删除后「成员」按钮成死链，必须改；顺带清掉引用票 04 已删字段的死分支（`isSealed`=SEALED、`isGroup`=GROUP、`SYSTEM` 消息分支）。**运行时行为不变**——后端早已不返回这些值。
2. `stores/chatRooms.ts`：删 `previewOf` 的 `SYSTEM` 分支与 `systemText` 依赖（`utils/chat.ts` 的 `systemText` 随之删除）。
3. `utils/errand.ts` + `components/ErrandCard.vue` + `views/me/MyOrdersView.vue` + `views/AuthorProfileView.vue`：`isMultiTakerOrder/orderTotalReward` 与「每人 ¥」三元表达式随之删除（`requiredTakers` 后端已不返回，恒为单人语义，属死分支）。
4. `utils/trade.ts`、`views/ProfileView.vue`：仅注释里的「求购」措辞（非验收词，但同属求购残留话术）。
5. 新增 `.scratch/project-rework/walkthrough-16/`（走查脚本 + 报告 + 34 张截图）入库当验收证据。

### 6. code-review 对照（两轴并行）

**Standards 轴**：无 domain 词汇漂移（新类型名 `RoomMember`/`RoomMembers` 对齐 `CONTEXT.md`「会话成员」；`r.source === kind` 对齐「会话来源/对家角色」；`msgType: TEXT|IMAGE|CARD` 对齐「消息类型」）。已采纳 5 条并复跑：①走查脚本不再硬编码 npx 缓存路径，改为「常规 `require('playwright')` → `PLAYWRIGHT_ROOT` → 扫 npx 缓存且只认 chromium 可执行文件真实存在的那份」；②「闲置」tab 点击收窄为 `.nav-tabs .nav-tab`，去掉 `.first()` 的脆弱选择器；③删掉中继函数 `roomAvatar`（3 处模板直接读 `peerAvatar`）；④`payAmount` 注释去掉已不存在的「单人单」措辞；⑤在 `ChatDetailView` 注明 `listMembers` 为何保留。**未采纳并说明**：`previewOf` 的 `default` 分支仍可达（`msgType` 是 `TEXT|IMAGE|CARD`，TEXT 正落 default，非死分支）；`¥{{ formatMoney(order.reward) }}` 在三处出现属不同行版式（拿 `ErrandCard` 复用会构成组件重构，票面「不重构组件」禁止）；「删掉取成员、只用 `summary.peerUserId`」会丢 `last_read_seq` 水位初值（READ 推送只在新读时来），历史消息的「已读」会被误判成「未读」，故保留。

**Spec 轴**：无「实现得像但做错」项；类型与已落地后端 DTO 逐条对得上（`ChatRoomSummaryResponseDTO` 无 type/name/refType/status、`ChatRoomResponseDTO` 只剩 roomId、`ErrandOrderResponseDTO` 无 requiredTakers/members/roomId、`MsgTypeVO` = TEXT/IMAGE/CARD、`NotificationScopeVO` 无 MARKET_WANT）。两条提示已就地补证据或说明：①「无死链」原先只证了 3 条已删路由兜回 `/home`，现补上页内 `<a>` 58 处 + 源码 114 处跳转目标对路由表的静态核对（0 死链），并给首页/系统通知/我的三个页面补了明确断言（断言数 54 → 64）；②「拉新」行的 `MarketingView.vue`/`api/marketing.ts` 不在本次 diff 内，属票 14 提前满足（见 §4.2）。

### 7. 边界内的说明（评审提问点）

1. `views/admin/ReviewWorkbenchView.vue` **文件未动**：其 `TABS` 本就只有 `ERRAND/POST/COMMENT`（无求购位），故 `ReviewTargetType` 收窄不可能改变工作台行为——`types/api.ts` 的联合类型属票面要求（验收 2 清零 `WANT_TO_BUY`）。
2. `ProductDetailView.vue` 的 `.condition-line/.condition-tag` → `.tag-line/.meta-tag` **是验收 2 的直接要求**（旧类名含 `condition`，不清零就过不了源码级检查）；只改名，样式值一字未动，视觉不变。
3. 票面文件表未列的 7 个文件（`ChatDetailView.vue`、`stores/chatRooms.ts`、`utils/errand.ts`、`utils/trade.ts`、`components/ErrandCard.vue`、`views/AuthorProfileView.vue`、`views/ProfileView.vue`）均为**删共享定义后的强制连带**（`isMultiTakerOrder`/`orderTotalReward`/`marketRoomSource`/`systemText`/`roomAvatar` 被删或收窄），逐条理由见 §5。`ChatDetailView.vue` 里删掉的 `SEALED`/`GROUP`/`SYSTEM` 分支与「成员」入口在收口后已是死代码（后端无对应字段/枚举），运行时行为不变。

<!-- 收口时补：Context used: ~270k（估，硬顶 300k 内）；未触发拆分；连带影响清单见 §5 -->

