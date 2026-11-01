# map：UI/UX 改版落地（实施）

## Destination

按 [ui-revamp spec](../ui-revamp/spec.md)（唯一需求来源，索引 ①–⑥ 六节全部「已定稿」；设计阶段见 [ui-revamp map](../ui-revamp/map.md)）完成改版的全部编码落地：信息流卡面与分隔、帖子详情、作者主页与隐私、市集求购链路、下单支付操作区、列表加载与「我的」页状态。

## Notes

- **需求问题一律查 spec 对应章节**；本 effort 不再重开设计讨论。发现 spec 缺口回 `ui-revamp` 补票。
- **拆票口径**：一张票 = spec 一节；后端接口并进其所属节的票（纯垂直切片，不单列后端票）。
- 领域词汇遵循仓库根 `CONTEXT.md`；评论区相关判据遵循 `docs/adr/0001-comment-tree-model.md`。
- 事实校核（相对 spec 落笔后）：迁移 **021 与 `bs_user` 的 `signature/gender/show_liked/show_comments` 列已存在**（票 06 的 spec 工作已落），③ 的剩余是 Java 侧（`UserPO`/mapper/端点/白名单）。求购 4 处缺口**确实存在**，且求购端点折在 `ProductController`/`ITradeService` 内，非独立 controller。
- 前端无测试设施（无 vitest/e2e），验收靠 `npm run build`（`vue-tsc`）+ 手测；后端集成测试统一放 `bs-app/src/test`（Testcontainers + 真实 HTTP，见 `backend/docs/testing.md`）。

## 依赖图（Blocked by）

```
01 卡面规范 ──┬─→ 04 作者主页 ──→ 05 我的页状态 ──┐
              │                                    ├─→ 06 市集求购
02 帖子详情 ──┼────────────────────────────────────┤
              │                                    │
03 订单操作区 ┘（独立，无阻塞）                      ┘
```

- **01–06 全部已完成**（06 于 2026-09-12 落地，effort 收官）
- 01 → 04（作者主页笔记/赞过复用卡面）
- 04 → 05（设置两页依赖其写接口；「评论」tab 依赖其按用户评论接口）——已满足
- 01/02/04/05 → 06（求购卡、轮播、发布者跳主页、`useWaterfall`、store 的 `'want'` 位）
- 06 排在链尾：合并粒度下求购要等 04→05 链。若嫌尾长，可把 05 的 `useWaterfall`+store 地基单抽一票（已与用户确认按当前 6 张发布）

## 文件并发提示（非阻塞边，但建议串行）

- **05 / 06 都改 `MarketView.vue`**（加载与 store / 第三栏）→ 均已落地，全部完成。
- **03 / 05 / 06 都改 `ProfileView.vue`**（订单行状态文案 / 整页头部 / 「我的-闲置-我求购的」跳转与六态徽章）→ 均已落地，全部完成。

## Decisions so far

- 2026-09-11 拆票定稿：6 张（spec 六节 1:1），纯垂直、后端并进各节，落 `.scratch/ui-revamp-impl/issues/`
- 票 01（resolved）：信息流分隔与卡片规范落地。新增令牌 `$bg-page-alt`(#f2f2f5)/`$radius-card`(12px)/`$shadow-card` 与共享类 **`.feed-card`**（卡片外壳）/ **`.feed-thumb`**（图片圆角 8）在 `variables.scss`/`common.scss`；`PostCard`/`ProductCard`/`ErrandCard` 改写为灰底白卡 + 图统一 1:1（去掉 `dynamic`）；`HomeView`/`MarketView` 铺灰底。**票 04/06 复用这套令牌与共享类，不要再各写卡片外壳**。
- 票 02（resolved）：帖子详情布局落地。顶栏换作者栏（无「帖子详情」标题；分享位是 `<span aria-hidden>` 非交互元素）；图片区竖向平铺 → 通栏轮播，**抽成共享组件 `front/src/components/ImageCarousel.vue`**（props `images`/`seed`/`ratio`，手势阈值 40px + 圆点 + `n/N`）——**票 06 求购详情相册直接复用该组件，别再各写一份滑动手势**；评论流去分割线与楼中楼竖线、根评论/楼内行均 `align-items:flex-start`（修「楼中楼一展开根评论头像飘到中间」）；底栏换 输入+点赞+评论（无发送、无收藏）。既有能力（排序/楼中楼展开/分页/删评论/删帖/联系）逐字保留。
- 票 03（resolved）：下单支付操作区落地。新增 **`front/src/utils/orderAction.ts`**——`OrderRole` + `statusText(status, role)` / `statusNote(status, role)` / `orderActions(status, role)` / `OrderActionSet`，按 spec 的 15 组合表把状态文案与操作集合**按角色**取词（`amountLabel` 只给标签，金额仍取订单 `price`；判据 `primary || secondary` 即「有操作 → 等分底栏 / 纯等待 → 状态卡」二分）。`ProductOrderView.vue` 底部 `<footer>` 按此二分重写（`.status-head` 状态卡 / `.status-hero` + `.action-bar` 等分底栏，金额 → 次 → 主），四个后端调用与确认文案逐字未动；修掉线上 `DELIVERED + 访客` 的空操作条。两处列表页的裸映射（`MyProductOrderView` 的 `STATUS_TEXT`、`ProfileView` 的 `PRODUCT_ORDER_STATUS_TEXT`）一并改按角色取词。**⚠️ 06 求购详情页 / 跑腿详情页复用本节判据时：直接 import `utils/orderAction.ts`，别再各写一份 statusText 裸映射；`OrderActionSet` 的 `key` 域可直接扩。** 两条留档见票面「已知波及」：三格非像素级等宽（沿用 spec/原型的 `flex: 1`）、`PAID/访客` 说明「转给你」沿用用户选定原型未擅改。
- 票 04（resolved）：作者主页 + 公开资料/隐私接口落地。**新增共享件与令牌（后续票直接复用）**：前端 `api/user.ts`（`getPublicProfile`/`updateProfile`/`updatePrivacy`）+ `types/api.ts` 的 `PublicUserProfile`/`AuthorProfileComment`/`Gender`；`utils/errand.ts#errandTypeText`（跑腿类型中文名，原 `ErrandCard`/`ErrandDetailView` 各一份已收敛）；设置两页 `views/settings/ProfileEditView.vue`（`/settings/profile`）与 `PrivacySettingsView.vue`（`/settings/privacy`）——**票 05 的「设置入口」只需在 `ProfileView` 挂同一入口，别再各写一版表单/开关**。后端新增**公开只读**接口组：`GET /api/user/{userId}/profile`、`GET /api/community/users/{userId}/posts|liked|comments`、`GET /api/trade/users/{userId}/products`、`GET /api/errand/users/{userId}/orders`，与两条写接口 `PUT /api/user/profile`、`PUT /api/user/privacy`；隐私「仅他视生效」的判据收进 **`IUserRepository.isLikedVisibleTo`/`isCommentsVisibleTo`**（社区域直接调，别再各写裸判断）。**票 05 复用面**：按用户评论接口 + 评论列表形态（`UserCommentResponseDTO`：`commentId/content/likeCount/createdAt/postId/postTitle/postCover`）即「我的-评论」tab 的数据与形态，`ProfileView` 只需接线。⚠️ 口径修订两处已同步文档：`PUT /profile` 是**整体覆盖**两字段（非「部分更新」，理由见票面）、隐私两开关是**赞过/评论**（非 04 原型里的闲置/跑腿）。两条真 bug 留档见票面：原型/首版 `.panel` 的 `overflow:hidden` 杀死吸顶；「赞过」开关曾连带隐藏「笔记」tab。
- 票 05（resolved）：「我的」页状态停留 + 列表加载修复落地。**新增共享件（06 直接复用）**：`front/src/composables/useWaterfall.ts`（`{list, loading, finished, loaded, sentinel, load, reset}`；**不自动加载**、`loaded` 判「首次进入才 load()」、`reset()` 清分页+滚动归零；`finished` 判据改 `res.list.length < pageSize || list.length >= total`）与 `front/src/stores/uiState.ts`（`marketTab: 'errand'|'idle'|'want'` + 「我的」页三 tab；**只存 tab 选择，不存列表数据**）。`HomeView` / `MarketView` 换用 composable（市集每 tab 一个实例 + **各自哨兵移进各自 tab 模板内**——原共享单哨兵就是「闲置首屏不加载」的根因）；进市集只加载 `marketTab`，其余首次切入才加载；同页切 tab 存/恢复各 tab `scrollY`。「我的」页：头部加设置 pill + 性别圆底 + 签名行、数字区只留「获赞」（`getPublicProfile().receivedLikeCount`，删掉原 `likedTotal` 里的 `favorites.length`）、tab 序改 `笔记/赞过/评论/闲置/跑腿`、「评论」tab 改 `listUserComments` + 评论列表形态。**抽出 `components/SettingsSheet.vue`（设置入口）与 `components/ProfileCommentRow.vue`（评论行），`AuthorProfileView` 一并改用**——06 若再需要设置入口/评论行别再各写一份。⚠️ **06 落求购 tab**：store 的 `'want'` 值与三 tab 加载判据已就位，06 只需加 tab 与第三个 `useWaterfall` 实例；`MarketView` 现按 `v-if="ui.marketTab === 'errand'" / v-else-if 'idle'` 渲染。两条真 bug 修在票面（store 重复点当前主 tab 会重置子 tab；store 恢复成非 posts 时「笔记」tab 永不加载）。留档：`opts.pageSize` 仅 `finished` 在用、`reset()` 兼管滚动、`MarketTab` 含未用的 `'want'`——三条均为 spec 明写，未擅改。
- 票 06（resolved，2026-09-12）：市集求购前端链路落地，本次改版收官。**后端 4 处补齐**（照 spec §0）：`GET /trade/want-to-buy/{wantId}` 单取（NORMAL 对外可见、其余仅本人、他人 404）、list 加 `sort`（time/price_asc/price_desc，域层白名单收敛）、`WantToBuyResponseDTO`+entity+PO 补 `avatar`（mapper 三查询补 `u.avatar`）、`POST /{wantId}/to-draft`（REJECTED→DRAFT，照商品同名端点）；`docs/api/trade.md` 同步。**前端新增** `utils/trade.ts`（`TRADE_CATEGORIES`+`tradeCategoryText`——**顺手收敛了原 4 份分类文本副本**：MarketView chips / ProductCard / ProductDetailView / ProductPublishView）、`utils/wantAction.ts`、`components/WantToBuyCard.vue`、`views/trade/WantDetailView.vue`（`/want/:id`）、`views/trade/WantPublishView.vue`（`/publish/want`）；`MarketView` 加第三 tab、`PublishIndexView` 加第 4 项、`ProfileView`「我求购的」修死链+六态徽章+驳回原因、`types/api.ts` 加 `WantToBuyStatus` 并补 `WantToBuy` 的三字段。⚠️ **一条 Decision 被修订**：本行原写「06 直接 import `utils/orderAction.ts`，`OrderActionSet` 的 key 域可直接扩」——实测 `OrderActionSet` 只有**单槽** `secondary`，而求购 DRAFT 需**两枚**次操作（删除+编辑）且底栏至多 3 格，故另落 `WantActionSet`（`secondary: WantAction[]`）；**二分判据与 `.action-bar`/`.action-seg` 尺寸仍逐字沿用票 03**，未改 `orderAction.ts`。**跑腿详情页仍复用「二分判据 + 自有集合」这一形状**（其可点操作可能 >2，收口口径留后续票）。
- **effort 状态**：spec 六节全部「已定稿」，票 01–06 全部 resolved。

## Not yet specified

- **跑腿订单详情页底栏的「至多 3 格、多余次操作收进更多」收口**：spec §「下单支付操作区」已定复用同一二分规则，但跑腿可点操作可能多于 2 个（多人单组队/补位/退出/协作群/争议），收口口径待该页落地时定。**票 03 已落地且按 spec 明示未动 `ErrandDetailView.vue`**（判据与 `utils/orderAction.ts` 可直接复用），留给该页的实施票。
- **统一加载态/空态/骨架屏组件规范**：spec ⑥ 已定本 effort 不收口，各票沿用现有 `EmptyState`。

## Out of scope

（同 [ui-revamp map](../ui-revamp/map.md) Out of scope：关注/粉丝、帖子收藏、单条评论取消公开、属地/IP、头像/昵称编辑、分享链路。）
