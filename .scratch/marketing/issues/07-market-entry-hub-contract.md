Status: resolved
Type: grilling
Blocked by:

# 07: 市集入口行落地口径（聚合订单页 + 市集消息页 + 底部消息收口）

## Question

把市集入口行的三个落地页与底部消息的迁移收口定死（入口行的**长相**归票 02 原型，本票定**数据与去向**）。

**① 我的订单 = 市集聚合订单页**：三 tab（闲置 / 跑腿 / 求购）各自的数据源——复用现有接口（`/me/product-orders` 那页的 `listMyProductOrders`、`MyErrandView` 的跑腿列表、「我的-闲置-我求购的」那个接口），还是新做聚合接口？**求购单是否入内**（现状只挂在「我的」页的子 tab）？三 tab 各自的状态筛选要不要（现状：商品订单 buy/sell 双 tab、跑腿 publish/take 双 tab + 状态筛选）？

**② 市集消息页**：数据从哪来——复用 `GET /api/chat/notifications` 加 `refType` 过滤参数，还是新接口？tab 划分（按 `refType` 分「闲置 / 跑腿 / 求购」还是别的分法）；分页；**已读口径与底部消息页是否共享**（同一张 `notification` 表的 `is_read`：两边读同一批行，还是一处已读另一处也跟着变）；空态；点一条跳去哪（订单详情）。

**③ 营销通知的 type / refType（与票 04 的事件契约一起定）**：现有 `NotificationTypeVO` 的 10 个值**没有营销类**。成团 / 中奖 / 积分变动这三类通知：新增枚举值，还是复用 `ORDER_EVENT`？它们**不属于市集**（是活动消息），那归底部消息的哪个 tab、还是干脆只弹 toast 不进通知表？这条决定 `notification.type` / `ref_type` 的取值，进而决定市集消息页的过滤条件。

**④ 底部消息页的收口**：移除市集三类交易动态后 system tab 剩什么；删掉「活动消息」横幅后的页面结构（两段式：互动 + 私信）；**先 grep 那条横幅原来跳的 `/notifications?tab=system` 还有没有别的引用方**；`NotificationsView.vue` 的 `TYPE_TEXT` / `INTERACTION_TYPES` 要不要跟着改。

**⑤ 旧入口的去留**：聚合页落地后，`/me/product-orders`、`/me/errands`、`/me/favorites`、「我的」页里「闲置-我求购的」是否保留、从哪里还能进；tabbar「我」页里的入口要不要一起收口（避免同一种数据出现三个入口）。

**⑥ 与票 06 的边界**：本票只管市集入口行与消息相关的接口与去向；营销域自身的接口（活动页、抽奖、积分、拉新）归票 06。

---

## Answer

**2026-09-14 收敛**：一轮 grill（Q1–Q7，用户回「都按你推荐的」）+ 一轮补问（Q8 已读口径，用户选 **B 真水位**）。结论已写进 `spec.md` 的「市集入口行」「市集消息页」「底部「消息」页收口」「营销域」四节。

### 0. 事实自核（票面前提三处修正 + 三处新发现）

1. **`/me/...` 是前端路由，不是接口**。真实接口：`GET /api/trade/orders/mine?role=buy|sell`、`GET /api/errand/orders/mine?role=publish|take&status=`、`GET /api/trade/want-to-buy/mine`、`GET /api/trade/favorites`。后端**无 `/api/me/*`**，也**无任何聚合接口**（三域各在 `ProductController` / `ErrandController`）。
2. **`MyErrandView` 是前端视图名**，不是返回类型；跑腿「我的」列表返回 `ErrandOrderResponseDTO`。
3. **`GET /api/chat/notifications` 没有 `refType` 过滤参数**——只有 `page`/`pageSize`（`NotificationController.java:36-48`）。前端是**拉全量**（`NotificationsView.vue:56-61` 循环翻页）后在**客户端**按 `type` 过滤。
4. **【新发现，本票的关键事实】`ORDER_EVENT` 里没有一条「发给买/卖家的商品订单动态」**：`PRODUCT` 侧的 `ORDER_EVENT` 只有 `remindReviewers(reviewerIds, "PRODUCT", "商品", productId)`，收件人是 **ADMIN+AUDITOR**（`TradePublishCase.java:219-243`）；**商品订单的支付 / 交付 / 确认不发任何通知**。发**给本人**的交易动态只有求购（`notifyWantPaidReview`，`TradePublishCase.java:250-258`）与跑腿（`notifyPaidReview` / `notifyScattered`，`ErrandOrderCase.java:219-227 / 270-283`）。
5. **同一个 `refType=ERRAND` 同时承载两类通知**：发给用户的交易动态（`ErrandOrderCase.java:224`，收件人 `publisherId`）与发给审核员的审核提醒（`ErrandOrderCase.java:241`，收件人 `reviewerIds`）⇒ **归属不能只按 `refType` 推导**，必须由**生产点显式标注**（见 §4 的 `scope` 列）。
6. **票 02 ④ 原型列的交易事件与后端实际生产方对不上**：原型写的「待支付 / 取消 / 支付入审 / 散团 / 审核未通过」里，**实际有生产方的只有「支付入审」（跑腿、求购）与「散团」**；「待支付 / 取消 / 审核未通过」当前**没有任何生产方**（原型是示意图）。本票**不补**这些生产点（属商品/跑腿订单生命周期的通知补全，在本票范围外）；市集消息页的系统消息以**现有生产方**为准。

### 1. 聚合订单页（入口行「我的订单」的去向）

- **数据来源**：前端**并发调三个既有接口、前端聚合**，**不做**后端聚合接口（与票 06「活动页前端并发调三家、不做 BFF」同口径）。**各 tab 各自分页自己的源**，无跨源合并。
- **三 tab 顺序 = 跑腿 / 闲置 / 求购**（与市集页、市集消息页外层同序）。⚠️ **修正票 01 §8** 原写的「闲置 / 跑腿 / 求购」。
- **求购入内**，作为一等 tab（不再只挂在「我的」页子 tab）。
- 各 tab 形态（受接口能力约束）：
  - **跑腿** ← `GET /api/errand/orders/mine`：保留「我发布的 / 我接的」双 sub-tab + **状态筛选**（接口支持 `status`）。
  - **闲置** ← `GET /api/trade/orders/mine`：保留「我买的 / 我卖的」双 sub-tab，**无**状态筛选（接口无 `status` 参数）。
  - **求购** ← `GET /api/trade/want-to-buy/mine`：**无** sub-tab，状态用**行内角标**（接口无 `status` 参数）。
- **不另开原型票**（Q7）：外层套票 02 ① 的三 tab 样式（与市集页同款），列表行沿用被吸收的旧页实现。

### 2. 市集消息页

- **数据源**：复用 `GET /api/chat/notifications`，**新增 `scope` 过滤参数**（`scope` 见 §4；不再按 `refType` 过滤——`refType` 区分不出「发给用户的动态」与「发给审核员的提醒」）。
- **系统消息口径 = 发给本人的交易动态**：即 `ORDER_EVENT` 且生产点标 `scope ∈ {MARKET_ERRAND, MARKET_WANT}`。**排除 `PRODUCT`**（无用户动态）、**排除审核提醒**（同为 `ORDER_EVENT × ERRAND`，但收件人是审核员、`scope = BOTTOM_SYSTEM`）。⚠️ **修正票 01 §9** 原写的 `refType ∈ {PRODUCT, ERRAND, WANT_TO_BUY}`。
- **tab 映射**：跑腿 ← `MARKET_ERRAND`；求购 ← `MARKET_WANT`；**闲置 tab 无系统消息行**（无生产方）。
- **「系统消息 = 一条会话」选「不落 chat 表」**（票 02 转下游的那一问）：系统消息**不建 `room`/`message` 行**，前端把该 `scope` 的 notification 行按域渲染成**伪会话气泡列表**（气泡 = 标题 + 正文）。理由：系统消息无需回复、无已读回执、无 seq 水位，造真 `room` 会污染 chat 域。
- **点一条跳订单详情**：按 `refType` 跳既有路由 `/errand/:id`、`/want/:id`。
- **分页与空态**（票面 ② 问）：系统消息的伪会话气泡流**按 `page/pageSize` 分页**（复用 `listNotifications` 的分页，默认 `1/20`，上限 50）；对家会话列表沿用 chat 会话列表现状。空态走页面通用空态组件（票 02 ④ 已画「看空态」）——某 tab 既无系统消息又无对家会话时给通用空态。
- **对家会话（私信）怎么按域筛**（票 02 三条候选选 **(a)**）：`room` 新增一列**会话来源**，1v1 首次由「私信联系」入口建会话时写入；`uk_direct_pair` 不变；**接受「首来源钉死」**（同一对人先聊闲置又聊求购，仍是同一条会话、同一标签）。跑腿多人单群（`ref_type=ERRAND`）天然可筛。⚠️ **反转票 01 §9 的「市集消息页不含私信」**——「对家会话」正是私信，理由与代价已在此写清。

### 3. 底部「消息」页收口

- **删「活动消息」横幅**：`MessagesView.vue:75` 的 `@click="router.push('/notifications?tab=system')"`，**全仓仅此 1 处**引用该链接（已 grep 确认），删除安全。
- **结构 = 三段式**（互动 + 系统消息 + 私信），保持票 02 的定稿。⚠️ **修正票面 ④ 原写的「两段式」**（那是票 02 修订前的旧稿）。
- **system tab 剩什么** = `scope = BOTTOM_SYSTEM` 的全部：`ANNOUNCEMENT / AUDIT_RESULT / AUDIT_PENDING / REPORT_RESULT / CREDIT_CHANGE` + 营销三类（§4）+ `ORDER_EVENT` 的审核提醒。
- **关键改动**：底部 system 的过滤从「非交互类型」`!interaction.includes(n.type)`（`NotificationsView.vue:63-64`）改为**按 `scope = BOTTOM_SYSTEM`**。否则市集交易类（`ERRAND/WANT_TO_BUY` 的 `ORDER_EVENT`）会**同时出现在两页**、重复。
- `INTERACTION_TYPES` **不变**；`TYPE_TEXT` **补 `AUDIT_PENDING`**（现有 9 值漏了它，落到显示兜底「通知」）+ 营销三值。
- **顶部 nav 不变**（票 02 已定）。

### 4. 营销通知的 `type` / `refType` / `scope`

- **新增三个枚举值**：`REFERRAL_GROUPED` / `LOTTERY_WON` / `POINTS_CHANGED`，**不复用 `ORDER_EVENT`**（其语义是交易动态，营销是活动消息）。
- `ref_type = MARKETING`（对齐 spec 已定的 `dedup_key` 前缀 `MARKETING:`）。
- **落 `notification` 表、`scope = BOTTOM_SYSTEM`**（进**底部** system tab）；**不进市集消息页**。投递机制仍是票 04 定的事件订阅（单体当第四个消费者、复用 `NoticeService.sendSystem`）。⚠️ **反转票 01 §9 / 票 04 §5 的「营销通知 type/refType 待票 07 定」**——现在定了。

### 5. 已读 / 未读模型 = 水位线（Q8 选 B）

- **新增 `scope` 列到 `notification`**：**写入时由生产点显式标注**（不能从 `(type, refType)` 推导——见 §0.5）。取值：`MARKET_ERRAND` / `MARKET_WANT` / `BOTTOM_SYSTEM` / `INTERACTION_LIKE` / `INTERACTION_COMMENT`（`MARKET_PRODUCT` 预留、当前无生产方）。
- **照抄 chat 的读模型**（`user_seq` + `timeline.delivery_seq` + `room_user.last_read_seq`，`init.sql:203/240/254`）：`notification` 加 `seq` 列，由 `notification_seq(user_id, scope, seq)` 计数器在**写入事务内取号**（行锁到提交 ⇒ 取号序 = 提交序）；水位表 `notification_read(user_id, scope, last_read_seq)`。
- **标已读** = 推水位（O(1)）；**未读数** = `计数器 − 水位`（O(1)）；**每个页面/范围各推各的水位、互不误清**。
- **⚠️ 不能拿 `notification.id` 直接当水位**（省掉取号器）：`notification.id` 是全局物理自增，**分配序 ≠ 提交序**，并发插入下会「跳读」（正是 chat 在 `timeline` 上专门绕开的坑，`init.sql:240` 注释原话：「仅物理自增，禁止当同步游标——id 分配序 ≠ 提交序，并发下漏拉」）。
- **`is_read` 列退休**（读态由水位算，`read` 字段在响应里由 `id/seq <= 水位` 推出）；现有 `POST /api/chat/notifications/read-all`（`UPDATE … SET is_read=1 WHERE …`）**由新的 `POST /api/chat/notifications/read` 取代**。
- **接口**：`GET /api/chat/notifications?scope=`（新增过滤）、`GET /api/chat/notifications/unread-count?scope=`、`POST /api/chat/notifications/read`（按 scope 推水位）。**对家会话的未读不新增接口**——chat 会话列表已带每 room 的 `unread`，前端按 `room.source ∈ MARKET_*` 求和。
- ⚠️ **回填**：本章改了 `notification` 表结构（`scope` / `seq` 两列 + 水位表 + 计数器表 + `is_read` 退休），属**票 05（数据模型）**的领地；spec「数据模型与行级时序」节与迁移清单需同步补，另需一条迁移 `026_notification_read.sql`。

### 6. 旧入口去留

- **删两个订单类旧页**：`/me/product-orders`（`MyProductOrderView.vue`）+ `/me/errands`（`MyErrandView.vue`）——**吸收进聚合订单页**。`SearchView.vue:253`「查看更多我的跑腿 ›」改指**聚合页跑腿 tab**。（这两个路由现在分别**零入站引用** / 仅 SearchView 一处引用。）
- **保留 `/me/favorites`（`MyFavoritesView.vue`）**：票 01 §8 定「收藏 → 现有 `/me/favorites`（不新建）」，它是入口行「收藏」格的**去向**，不删。⚠️ **修正栅**：助手原推荐把 `MyFavoritesView` 一并删了，与票 01 §8 直接矛盾，此处纠正。
- **`ProfileView.vue` 收敛**：闲置 subtab 只留「我发布的」（我收藏的 / 我求购的 / 我购买的移出）、跑腿 subtab 只留「我发布的」（我接的移出）——让**聚合页**成为订单/交易类唯一入口、`/me/favorites` 成为收藏唯一入口，兑现票 01 §8「避免同一种数据出现三个入口」。⚠️ 这是本票波及面最大的一处改动。

### 7. 边界（票面 ⑥）

本票只定市集入口行与消息相关的接口与去向；营销域自身接口（活动页 / 抽奖 / 积分 / 拉新）归票 06（已 resolved），本票不碰。
