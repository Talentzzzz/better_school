# 03: 前端·市集消息页

**Spec:** `.scratch/marketing/spec.md` §「市集消息页」+ §「营销域」→「通知与已读模型」

**What to build:** 市集内部新增一个消息页：外层三个 tab（顺序与市集页一致：跑腿 / 闲置 / 求购）。每个 tab 顶部把该域的系统消息做成**一条会话行**（形态与私信行同构），点进去是一个全屏聊天框，该域的系统消息就是框里的多条气泡；系统消息行之下按域摊开对家会话。

**Blocked by:** 02（系统消息读 `scope` 过滤的通知、已读推水位）

**Status:** resolved

- [x] 外层三 tab，**顺序与市集页一致**：跑腿 / 闲置 / 求购
- [x] 每个 tab 顶部 = **一条系统消息会话行**：蓝圆铃铛（46px 圆）+「系统消息」+ 最新一条正文预览（省略）+ 右侧竖排「时间 / 未读计数」；**不是**折叠面板、**不显示「共 N 条」**
- [x] 点系统消息行 → 全屏聊天框（带返回、日期分隔「近 7 天」），气泡 = 标题 + 正文（左对齐、白底、左上圆角小），**不落 chat 的 `room`/`message` 表**
- [x] 系统消息取数 = `scope ∈ {MARKET_ERRAND, MARKET_WANT}` 的 notification（即**发给本人的**交易动态）；**闲置 tab 无系统消息**
- [x] 系统消息行之下按域不同：跑腿 → 「跑手消息」分区（对家会话摊开）；闲置 → 「我买的」/「我卖的」**两个可切换分类**（chips，一次看一栏，各带未读角标）；求购 → 「卖家消息」分区
- [x] 对家会话行 = 头像（44px）+ 角色小标签（跑手 / 买家 / 卖家）+ 昵称 + 最新消息 + 右侧时间与未读计数；对家归属：跑腿单里恒为跑手、卖家 → 我买的、买家 → 我卖的
- [x] `room` 新增**会话来源列**并落迁移：1v1 首次由「私信联系」入口建会话时写入（跑腿 / 闲置 / 求购）；`uk_direct_pair` 不变；**接受首来源钉死**（同一对人先聊闲置又聊求购，仍是同一条会话、同一标签）；跑腿多人单群（`ref_type = ERRAND`）按既有字段天然可筛
- [x] 点一条对家会话 → 进既有聊天页；系统消息行的正文不进聊天页
- [x] 已读走 02 的水位线模型（各 tab / 各范围各推各的水位）
- [x] 空态走页面通用空态组件
- [x] `npm run build`（`vue-tsc`）通过；双视口目视验收（入口行落点由票 04 接，本票只需页面可直达）
- [x] 视觉照 `prototype/marketing` 分支的 `MarketMsgD.vue`（用户四轮修订后回「就是这个效果」定稿的那一版）

## 实现要点（票 03）

### 落地前定的一处 spec 缺口：`room` 的「会话来源列」取值口径

票面/票 07 §2 只写了「1v1 首次由『私信联系』入口建会话时写入（跑腿 / 闲置 / 求购）」，但**对家角色（跑手 / 买家 / 卖家）与闲置的「我买的 / 我卖的」是视角相关的**——1v1 会话是两人共用的同一行，而「我买的」是相对「我」而言；只存域，服务端推不出请求者那一侧。**用户 2026-09-15 拍板：存两列 `source` + `initiator_id`**，角色由「来源 + 本人是不是首建发起人」现算（即选型时描述的那一版：「双方视角各自正确」）。

### 落地清单

**后端（单体，改的是既有 `room` 表，不是三个营销 schema）**

- 迁移 `027_room_source.sql`：`room` 加 `source VARCHAR(20) NULL`（`ERRAND`/`PRODUCT`/`WANT_TO_BUY`）+ `initiator_id BIGINT NULL`；**`uk_direct_pair` 不动**（1v1 同人唯一会话正是「首来源钉死」的前提）。`schema.sql` 原地同步为终态；迁移测试 `RoomSourceMigrationTest`（旧库带存量数据直跑 027：两列可空、存量不回填、`uk_direct_pair` 列组合不变）。
- `RoomEntity` / `RoomPO` / `RoomSummaryPO` / `ChatMapper.xml`（1 处 insert + 4 处 room 单行查询列清单 + 会话列表投影）逐处补列。
- **建会话入口带来源**：`IChatSendService.createDirectRoom(userId, targetUserId, source)`，只在**首次插入**时写 `source` + `initiator_id`；`uk_direct_pair` 命中即原样返回既有 room（**首来源钉死**，二次进入带来源也不覆盖）。`POST /api/chat/rooms/direct` body 增可选 `source`，非法值 → `PARAM_ERROR`（400，`info`「会话来源不合法」）。
- **对家角色按请求者现算**（`ChatQueryServiceImpl#peerRoleOf`，不落库）：跑腿 → 恒 `TAKER`（来源列定不了方向：发布方/跑手两侧都能发起）；闲置 → 发起人 `SELLER` / 对方 `BUYER`（商品详情「联系卖家」= 买家发起）；求购 → 发起人 `BUYER` / 对方 `SELLER`（求购详情「私信联系」= 卖家发起，`WantDetailView` 的 visitor 动作才带它）。`source` 为空 → 角色为空。会话列表 DTO 增 `source` / `peerRole` / `refType`（`initiatorId` **不出**）——`refType` 是为跑腿多人单群（`ref_type = ERRAND`）而暴露的。
- 集成测试 `ChatRoomSourceIntegrationTest`（5 用例）：来源+发起人落库与两侧角色、跑腿/求购两侧标签、首来源钉死（二次带来源不覆盖）、首页入口不带来源且事后不补写、非法来源 400。
- 文档：`docs/db/chat.md` 两列 + 四条注意；`docs/api/chat.md` 列表字段与 `POST /rooms/direct` 的 `source`；`CONTEXT.md` 补「会话来源」「对家角色」词条。

**前端**

- 新页 `front/src/views/msg/MarketMessagesView.vue` + 路由 `/market/messages`（`requiresAuth`；入口行由票 04 接，本票只要页面可直达）。三 tab 的标题 / 系统消息 `scope` / 分区标题收在一张 `TAB_META`（一次改一处，不摆三张平行表）。
- 系统消息行 / 聊天框 / 分区标题 / chips / 对家行全部照 `MarketMsgD.vue` 逐条落样式（含 46px 铃铛、`44px 14px 14px 14px` 气泡圆角、`近 7 天` 分隔）。对家会话取 `chatRooms.rooms` 客户端按 `source` / `refType` 筛（**首页入口建的会话 `source` 为空，天然不进市集消息页**——用户规则「从首页进去走底部」）。
- 已读：开系统消息会话 → `POST /chat/notifications/read {scope}` 推该域水位；未读角标取 `GET /chat/notifications/unread-count?scope=`（O(1)，票 02 的接口）。**各 tab 各推各的水位**：实测开跑腿系统消息后跑腿角标清零、求购角标与对家会话未读不动。
- 三处市集入口带来源：`ErrandDetailView`（`ERRAND`）/ `ProductDetailView`（`PRODUCT`）/ `WantDetailView`（`WANT_TO_BUY`）；`AuthorProfileView` 与 `PostDetailView` 的私信入口**不传**。
- 空态判据 = **本 tab 既无系统消息又无对家会话**（票 07 §2 口径），且等 `chatRooms.loaded` 与系统消息取数落地再判，免得首屏闪空态。

### 验证实测

- 后端：`ChatRoomSourceIntegrationTest` 5/5、`RoomSourceMigrationTest` 1/1；聊天域整包 `com.betterschool.integration.chat.*` 8 类全绿（含既有 5 类无回归）；**全量 335/335 绿**（`mvn -o test`，62 个 surefire 报告，含本票新增 6 用例；基数由票 02 收口时的 329 增至 335）。
- 前端：`npm run build`（`vue-tsc -b` + `vite build`）绿，产物含 `MarketMessagesView-*.js`。
- 目视：Playwright 拦 `/api`（正则限 `/api/`，回伪造数据）双视口 390×844 / 430×932 逐态截图，存 `.scratch/marketing-impl/verify-03/`（`01`–`13`）：跑腿（系统行 + 跑手消息 + 多人单群）、闲置「我买的」/「我卖的」chips 两侧、求购、系统消息聊天框、开后水位线清零、空态。核对的计算样式：系统行铃铛 46px、对家行头像 44px、气泡圆角左上 4px 其余 14px、tab 吸顶 `top: 48px`。**另核**：点气泡跳 `/errand/9001`（票 07 §2 的「按 refType 跳既有路由」）、`source` 为空的会话不出现在任何 tab。

### code-review 采纳项（两轴并行，2026-09-15）

1. **求购角色漏用发起人（Spec 轴）**：原按「求购对家恒为卖家」写死，但 `WantDetailView` 的「私信联系」只在 **visitor** 动作里（`WANT_VISITOR_ACTIONS`），发起人是卖家、对家是买家——写死会让发起人把自己看成卖家。改为与闲置同构的发起人拆分（发起人 `BUYER` / 对方 `SELLER`），分区标题仍是票面定的「卖家消息」。测试补两侧断言。
2. **系统消息气泡不可点（Spec 轴）**：spec §市集消息页 / 票 07 §2 明写「点一条 → 按 `refType` 跳 `/errand/:id` 或 `/want/:id`」，票面只写了气泡内容、漏了这条。已补：气泡改 `<button>`，按域拼路由；`refId` 为空（公告类）不跳。
3. **前端三张平行表（Standards 轴）**：`TABS` / `TAB_SCOPE` / `TAB_SECTION` 都以 `RoomSource` 为键且各写一份，合并为一张 `TAB_META`。

### 已知波及 / 不在本票范围

- **入口行（`MarketView.vue` 三 tab 之下的四格）由票 04 接**；本票只保证 `/market/messages` 直达，`MarketView.vue` 一字未动（票 04 落点含「消息」格未读角标的口径）。
- **跑腿多人单群行出「群」标签**：票面只写了角色标签取值（跑手/买家/卖家），群无单一对家；沿用底部消息页的「群」标签先例（`MessagesView.vue` 的 `.thread-tag`），属票面外的自定项。
- **闲置「我卖的」当前无生产方**：全仓只有「买家联系卖家」一个商品入口（`ProductDetailView.onContact`），故 `我卖的` 一栏实际恒空——与「闲置 tab 无系统消息」同属「设计已定、暂无生产方」，非缺陷。
- **跑腿对家恒为跑手**是票面口径的妥协：`ErrandDetailView` 两侧都能发起（发布方联系跑手 / 跑手联系发布方），`source` 列定不了方向；真要分清得再存发起人在单里的角色，本票不做。
- `parseRoomSource` 是 `ChatController` 里第三个「null 检查 → `valueOf` → 捕获 `IllegalArgumentException` → `PARAM_ERROR`」形状（前两个是 `parseMsgType` / `parseRefType`）。与票 02 对同类发现的口径一致：**只加参不重构既有两处**，要收就三处一起收，另开票。
- 联调开发库要跟上终态需手工跑 `sql/migrations/027_room_source.sql`（存量 1v1 会话来源为空 = 不进市集消息页，属预期）；测试库用 `scripts/test-env.sh reset-test-db` 重建。
