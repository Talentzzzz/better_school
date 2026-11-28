# map：UI/UX 改版（信息流 / 帖子详情 / 作者主页与隐私 / 市集求购 / 订单支付 / 状态修复）

Label: wayfinder:map

## Destination

`.scratch/ui-revamp/spec.md` 定稿：8 项改版的完整 spec——① 信息流分隔与卡片规范、② 帖子详情布局、③ 作者主页（含隐私开关与资料编辑）、④ 市集求购前端链路、⑤ 下单支付操作区、⑥ "我的"页状态停留与列表加载修复——视觉选型与数据/接口口径全部落定，拆票即可开发。

## Notes

- 领域：better_school 校园平台（校园墙 + 二手 + 跑腿）。本 effort = 一轮 UI/UX 改版 + 作者主页/隐私新功能 + 求购前端补齐；**只产出决策与 spec，不落地代码**
- 用到的 skill：视觉票走 `prototype`；口径票走 `grilling` + `domain-modeling`
- 参考图（仓库根目录）：`示例.jpg`（信息流分隔）、`fcc256337cab8d96e8e6edcccf94ff87.jpg`（帖子详情）、`55a4b0d413d2fb4ee8c844d69d906455.jpg`（作者主页）、`ScreenShot_2026-09-11_022904_160.png`（评论 tab＝评论列表）
- 事实底座：
  - 前端 `front/src/views/`：HomeView、MarketView、ProfileView、post/PostDetailView、trade/ProductOrderView、trade/ProductDetailView、PublishIndexView；组件 `components/PostCard.vue`、`ProductCard.vue`、`ErrandCard.vue`
  - 后端 `backend/bs-trigger/.../PostController.java`：`/posts/mine`、`/posts/liked`、`/posts/commented` **全部是本人视角**（`SecurityUtils.currentUserId()`），无公开的按用户查询
  - 后端 `UserController.java`：只有 send-code/register/login，**无公开用户资料接口**；全仓**无 follow 体系**（`grep follow|关注` 无命中）
  - 求购后端已全链路（`POST /trade/want-to-buy`、draft/submit/pay/withdraw/takedown），**前端入口为零**（无列表栏、无卡片、无详情页、无发布页）
  - 第 4 项 bug 根因：`MarketView.vue` 挂载只调 `loadErrand()`；跑腿/闲置**共用一个底部 IntersectionObserver 哨兵**，只在交叉状态*变化*时回调——闲置列表为空时哨兵已在视口内，切 tab 不改变交叉状态 → `loadProduct()` 永不触发，点分类 chip 才由 `watch` 兜底加载
- 已在本会话 grilling 敲定的范围（细节见各票）：不做关注/粉丝体系、不做帖子收藏、不做单条评论"取消公开"、不做属地/IP 展示、不改头像/昵称、不做分享链路（票 09，图标仅作装饰）
- 用户偏好：每张视觉票出 **3 个变体**逐个选；选型结论回填 spec；纯视觉不引后端

- **进度（2026-09-11）**：9 票**全部 resolved**、**frontier 已清空**；`spec.md` 索引 ①–⑥ 六节全部「已定稿」，可拆实施票。票 09 以「不做」收口（本 effort 最后一张票）。

## Decisions so far

<!-- 索引：每个已关闭票一行；细节在该票 ## Answer 里 -->

- **票 01 · 信息流分隔与卡片规范** → 选定 **D（A×B 结合）**：灰底 `#f2f2f5` + 白卡（圆角 12 / 阴影 / 内边距 8）+ 列行距收 8，图片统一 1:1；无封面帖用 A 的柔和渐变卡面、摘要在上**标题在底部**。四处信息流统一，求购列表沿用。已写进 `spec.md`「信息流分隔与卡片规范」节。原型存 throwaway 分支 `prototype/feed-separation`。

- **票 03 · 下单支付操作区** → 选定 **D（一条二选一规则）**：**有可点操作 → 顶部状态条 + 底部等分底栏**（金额 / 次操作 / 主操作 各占 1/3，**取消恒定在主操作左侧**，主按钮只写动词）；**纯等待 / 终态 → 状态卡，不设底栏**。原三案里 B 被采纳为「纯等待」分支、C 被采纳为「有操作」分支，A 落选。**顺带修掉**：线上状态文案是买家视角裸映射（卖家看到「待支付」，应为「待买家付款」；同映射还复制在 `MyProductOrderView` / `ProfileView`）；线上 `DELIVERED + 访客` 渲染空操作条。跑腿订单详情页底栏**同构**，spec 已注明复用同一规范（本票不出图）。已写进 `spec.md`「下单支付操作区」节。原型存 throwaway 分支 `prototype/order-action-area`。

- **票 02 · 帖子详情布局** → 选定 **D（C 页面 + A 作者栏/评论流 + 图片轮播）**：作者栏即顶栏（无「帖子详情」标题，返回 + 头像 + 昵称 + 联系/删除 + 分享位），正文沉浸（通栏轮播，始终只露一张，左右滑动切换，圆点 + `n/N`），评论紧跟正文当主体（排序吸顶），底栏＝输入 + 点赞 + 评论（**无收藏、无发送按钮**；评论之间**不画分割线**，靠留白自然分隔）。五条翻页指令拼合而成，细节见票面。已写进 `spec.md`「帖子详情布局」节。原型存 throwaway 分支 `prototype/post-detail`。**顺带**：分享位只留视觉占位，真链路**另立票 09**（本次唯一新增票，不在原 8 项内）→ 票 09 已 resolved：**不做**，图标保留为纯装饰。

- **票 04 · 作者主页布局（原型）** → 选定 **D（A 的骨架 + B 的私信位 + 去分割线 + 右上角「设置」）**：深色渐变资料头（左对齐大资料块）+ 吸顶下划线 tab + 双列白卡瀑布（**沿用票 01 卡面规范**）作骨架；私信取 B 的位置——**资料行右侧行内 chip**；**评论与跑腿**项之间都不画分割线（14px 留白自然分隔，与票 02 同口径）；右上角「设置」（齿轮 pill）→ 底部「隐私设置」面板含 `公开我的闲置`/`公开我的跑腿` 两开关（演示用），关闭后对应 tab 仍展示、空态「该用户已隐藏此内容」（Q9）。只显**获赞**、操作区只有私信、无关注/粉丝/收藏。tab=笔记/赞过/评论/闲置/跑腿（**按票面顺序**；注意「我的」页 `ProfileView` 现行是「…跑腿/闲置」，落地时同步）。**设置入口口径＝本人看自己主页才显示，访客看不到**（用户 2026-09-11 确认）。已写进 `spec.md`「作者主页」节（**布局部分已定稿**；数据/接口口径——公开资料接口、获赞口径、评论按用户查询、访客口径过滤、隐私字段、资料编辑——**待票 06**）。原型存 throwaway 分支 `prototype/author-profile`，截图 `.scratch/ui-revamp/proto-04/`。**顺带**：code-review 揪出 D 的返回键是无处理函数的死件（票面明写「顶部返回」），已接 `NavBar` 同口径 `goBack`。

- **票 05 · "我的"页新头部与状态停留（原型）** → **头部选定 A（深色资料头 · 卡片入口，「现状微调」）；停留载体选定 (b) 前端 store / 模块级缓存**（用户 2026-09-11 拍板，载体按其「按你推荐的」采纳）。A ＝ 沿用现有深色渐变资料头 + 钱包/信用分卡片，加**个性签名（含性别）** + 右上角**设置入口**（内含编辑资料 / 隐私设置两页），「获赞与收藏」收口为**获赞**（只算自己笔记被赞之和，删掉商品收藏数）；tab 顺序改 `笔记/赞过/评论/闲置/跑腿`。载体三案实测：(a) URL query 可分享/刷新不丢，**但跨 tabbar 切换会丢**（tabbar 是写死的 `/profile`）；(c) keep-alive 能「不重建」，但要全局改 `router-view` + 按组件名登记，且**滚动位置本仓不自动保**；(b) 改动最小、跨 tabbar 与二级页往返都保留，**且正是票 08 列表状态要的地基**（⚠️ 票 08 修订：进 store 的只有 **tab 选择**，分页/数据/滚动位置留在组件内）——故选定。**停留范围定死**：主 tab + 子 tab 都停留（子 tab 只在所属主 tab 内有效，换主 tab 回落默认）；**跨 tabbar 切换保留**；**刷新后回「笔记」接受**。**顺带定掉**：作者主页头部与「我的」页头部**不抽同一组件**，只共用样式规范（差异是真实的操作区差异，且分属两张落地票）。已写进 `spec.md`「"我的"页状态停留与列表加载修复」节（**已定稿**；列表加载口径由票 08 收口）。原型存 throwaway 分支 `prototype/my-page-state`（2 commit：`cc2d6ad` 出稿 → `65c883c` code-review 收口），截图 `.scratch/ui-revamp/proto-05/`（18 张）。code-review 两轴并行**无硬违规**，修 6 处（含 1 个真 bug：切载体丢 `?variant=` 导致头部变体被重置）。**解除 `08-list-state-loading-fixes` 的阻塞**。

- **票 06 · 作者主页与隐私的数据/接口口径（grilling）** → 一轮 frontier（7 问）用户「都按你推荐的」全部采纳。**公开资料** `GET /api/user/{userId}/profile`（免登录；**手机号不外露**——作者主页去掉「账号」行；获赞＝该用户 `NORMAL` 帖 `like_count` 之和，不含评论获赞）。**按用户列内容**＝新增一组公开只读接口（`/community/users/{userId}/{posts,liked,comments}`、`/trade/users/{userId}/products`、`/errand/users/{userId}/orders`），访客口径写死（笔记 `NORMAL` / 赞过源帖 `NORMAL` / 评论 `NORMAL` 且源帖 `NORMAL` / 闲置 `ON_SALE` / 跑腿 `publisher_id`+`PUBLISHED·TAKEN`）；**本人看自己主页同口径**。**评论 tab** 改评论列表（新接口带来源帖标题/封面，我的与作者主页共用；旧 `/posts/commented` 保留给 `SearchView` `scope=comments`；跳转 `/post/{id}?commentId=` 锚定）。**隐私开关——修订票 04**：从「闲置/跑腿」改为「**赞过/评论**」（闲置/跑腿本就公开可搜、藏起来无收益；赞过/评论才是真隐私轨迹）——`bs_user.show_liked/show_comments` 默认公开、**仅他视**、关掉则他人看空态「该用户已隐藏此内容」。**资料编辑** `PUT /api/user/profile`（签名 ≤40 可空 / 性别 `MALE`·`FEMALE`·`NULL` 不展示）+ 隐私写接口 `PUT /api/user/privacy`。**路由** `/user/:userId` 新页、补 `SecurityConfig` 白名单（`GET /api/user/*/profile` 必补）。表变更＝迁移 `021_user_profile_and_privacy.sql`（`bs_user` 加 4 列 + `post_comment` 补 `idx_user_status_created`；init.sql 同步）；文档新建 `backend/docs/db|api/user.md`、补 community/trade/errand 三处。spec「作者主页」节数据口径定稿、索引 ③ 改「已定稿」。**frontier 移至票 07（市集求购前端链路）**。

- **票 07 · 市集求购前端链路（grilling）** → 两轮 frontier（8+3 问）+ code-review 追问 1 问（Q12），用户「都按你推荐的」全采纳。**票面「后端已全链路」不成立**，实测需补 **4 处**——① 新增 `GET /trade/want-to-buy/{wantId}`（闲置有 `GET /products/{id}` 单取、求购没有）；② `GET /want-to-buy` 补 `sort`（`time`/`price_asc`/`price_desc`，照 `GET /products` 同构）；③ `WantToBuyResponseDTO` 补 `avatar`（mapper 已 `LEFT JOIN bs_user`，补 `u.avatar` 一行）；④ 新增 `POST /want-to-buy/{wantId}/to-draft`（照商品，`REJECTED → DRAFT`，状态条给「转草稿」）。聊天卡片类型无 `WANT`（`CardRefTypeEnum` 只有 `PRODUCT/POST/ERRAND`）→ 私信走**无卡片纯私聊**（跑腿先例），**不补**。**页面**：市集第三栏「跑腿/闲置/求购」（**默认仍跑腿**；分类筛选 + 排序；卡片沿用票 01 求购卡；空态「去发求购」）、**新** `/want/:id` 详情（访客只读 + 满宽「私信联系」；发布者按**票 03 规则**出操作区——等分底栏/状态卡；图片复用**票 02 轮播**）、**新** `/publish/want` 发布页（**两种编辑态**：`?draft={id}` 全字段可改走 `PUT /{id}/draft`、`?edit={id}` 仅标题/描述/配图走 `PUT /{id}`；「发布并去支付」复用跑腿「发布→支付→待审」）、「我的-闲置-我求购的」留子 tab、出**全部状态** + 徽章、修 `/market` 死链。编辑重审**不新增站内通知**（仅前端 toast）。**code-review（Spec 轴）修正原稿 3 处错**：草稿编辑端点（`PUT /{id}` 是 `NORMAL` 专用）、「去支付」两处自相矛盾、冻结时点（「待支付」应是「待付预算」）。已写进 `spec.md`「市集求购前端链路」节（索引 ④ 定稿）。**frontier 移至票 08**（票 09 同列；该票已 resolved＝**不做**，仅留装饰图标）。

- **票 08 · 列表加载与页面状态修复（grilling）** → 一轮 frontier 七问，用户「都按你推荐的」全采纳。**修法**：不补一行 `watch`，而是**抽 `useWaterfall` composable**（`front/src/composables/useWaterfall.ts`），**每 tab 一个实例**、各自持有独立 sentinel/observer/分页——跑腿/闲置/求购三份状态彻底独立；composable **不在 `onMounted` 自动加载**，由页面「首次进入该 tab」显式 `load()`，把加载时机从 observer 收回页面（即第 4 项根因修法）；`reset()` 供筛选/排序变更、并把 window 滚动归零。**换用范围**＝MarketView 三 tab + `HomeView`（同一翻页形状仓内已**复制三份**，一并换用免留三版；`SearchView` 非无限滚动不涉）。**加载时机**：进市集只加载 `activeTab` 指向的那一个（首次默认跑腿），其余首次切入才加载——不会一次拉三个列表。**状态口径**：市集 `activeTab` **进 store**（跨 tabbar 保留 tab 位），**列表数据/分页不进 store**（组件卸载即丢、重进重新首屏加载；落选「数据进 store」因引入「缓存何时过期」新问题）；**同页内切 tab 恢复各 tab 的 `scrollY`**（手写 `window.scrollTo`，仅在组件存活期内有效——无 `scrollBehavior`/`KeepAlive`）。**「我的」页**：三 ref 进 store（票 05 已定）、数据不进、**不引入 composable**（一次性 50 条非分页）。store 落点建议 `front/src/stores/uiState.ts`。**明确不做**：统一加载态/空态/骨架屏组件规范（沿用 `EmptyState`、不做骨架屏）。已写进 `spec.md`「"我的"页状态停留与列表加载修复」节（索引 ⑥ 定稿）。**frontier 移至票 09**（分享位落地；已 resolved＝**不做**，仅留装饰图标）。

- **票 09 · 分享位落地（grilling）** → **不做**。用户 2026-09-11 拍板「分享暂时不做了，留个图标就行」：帖子详情顶栏右转箭头**保留为纯装饰图标**（非交互元素，避免票 04 那种「无处理函数的死件」），不新增端点/字段/依赖，**无需另立实现票**。事实自核修正票面前提两处：① 本仓无原生壳、只有浏览器一个运行面（非「App 内 WebView」）；② 卡片类型 `PRODUCT`/`POST` 可发、`ERRAND` 只渲染不可发。将来若要做的方向（面板＝发送给好友＋复制链接）记在票面 Answer，不在本 effort。已写进 `spec.md`「帖子详情布局」节「分享位（票 09 定）」子节。

## Not yet specified

- **统一加载/空态规范**：各列表的空态、加载态、骨架屏是否统一成一套组件规范——**票 08 已定本 effort 不收口**（唯一共享抽象是 `useWaterfall`；空态沿用各页现有 `EmptyState`、不做骨架屏）。各实施票沿用现有做法即可
- **跑腿订单详情页底栏的落地**（票 03 出）：与商品订单**同构**，spec 已注明复用「下单支付操作区」节；但跑腿的可点操作可能多于 2 个（多人单的组队 / 补位 / 退出任务 / 进入协作群 / 发起争议），「等分底栏至多 3 格、多余次操作收进更多」的收口待实施票定（本票不出图）

## Out of scope

- **关注/粉丝社交图谱**：用户明示"不做关注，显示获赞就行"（Q3）
- **帖子"收藏"**：用户明示"不加收藏"（Q5）；帖子无收藏概念，不新增该资产关系
- **单条评论"取消公开"**：用户明示"你忽略就行，不做这个"（Q14）；隐私只做全局开关
- **属地/IP 展示**：无数据源，引入需独立链路，另立 effort（Q16）
- **头像/昵称编辑**：本次资料编辑只做签名 + 性别；昵称改动牵动全站展示口径，另立 effort
- ~~**求购后端改动**：后端已全链路，本 effort 只补前端~~ —— **票 07 实测推翻此前提**：需补 4 处小改动（`GET /want-to-buy/{id}` 单取、`GET /want-to-buy` 加 `sort`、`WantToBuyResponseDTO` 加 `avatar`、`POST /want-to-buy/{id}/to-draft`），已纳入 spec「市集求购前端链路」§0
