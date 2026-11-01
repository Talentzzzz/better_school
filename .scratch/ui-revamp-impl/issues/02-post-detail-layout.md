# 02: 帖子详情布局

**Spec:** `.scratch/ui-revamp/spec.md` §「帖子详情布局」（spec 索引 ②）

**What to build:** 帖子详情页 `/post/:id` 整体改版。顶栏从「帖子详情」标题栏换成**作者栏**：返回 + 头像 + 昵称 + 右侧动作（本人显示「删除」、非本人显示「联系」）+ 分享位；分享位是**纯装饰图标**（右转箭头，不可点、不挂交互、不用 `role="button"`）。图片区从竖向平铺改为**通栏轮播**：始终只露一张、左右滑动切换、底部圆点与右下角 `n/N` 计数（均仅 ≥2 张时出现），图片统一 4:3。正文之后紧接评论流：排序头吸顶，**根评论与楼中楼都不画分割线**（靠留白自然分隔），评论行必须 `align-items: flex-start`（否则楼中楼一展开根评论头像会飘到中间）。底栏换成 输入框 + 点赞 + 评论数（**无收藏、无发送按钮**），输入框 Enter 提交。

保留既有能力：最热/最新排序、楼中楼展开 N 条回复/更多/收起、加载更多评论、本人可删评论与删帖、联系（私信）入口。子回复的「▶ 被回复人昵称」判据与昵称口径遵循 **ADR-0001**。

**Blocked by:** None (can start immediately) — 软依赖：顶栏头像/昵称与评论头像/昵称应可点进作者主页，该页面由 **04** 提供；若本票先落地，点击会暂落到首页兜底路由。

**Status:** resolved

- [x] 顶栏为作者栏、无「帖子详情」标题；本人视角右侧出现「删除」，非本人出现「联系」；分享位为不可点的非交互元素
- [x] 图片区为轮播：单张展示；拖动超过阈值左滑下一张/右滑上一张；圆点与 `n/N` 仅 ≥2 张时出现；容器内图片不响应指针事件（否则桌面拖拽触发原生图片拖拽、滑动失效）
- [x] 「独立封面 + 相册」的展示口径不变（`cover` 不在 `images` 里时排在相册前），数据链路不动
- [x] 评论之间与楼中楼内均无分割线；楼中楼靠根评论头像列自然缩进、无竖线
- [x] 评论排序（最热/最新）、「展开 N 条回复」「展开更多回复」「收起」、加载更多评论均可用；本人可删根评论、子回复与自己发的帖子
- [x] 底栏为 输入（Enter 提交）+ 点赞（可切已赞态）+ 评论数；无收藏、无发送按钮；回复态显示「回复 @昵称 + 取消」
- [x] 顶栏头像/昵称与评论区头像/昵称可点进作者主页
- [x] 构建通过；手测覆盖 浏览/点赞/评论/回复/删除评论/删帖/联系

## 实现要点（票 02）

- **顶栏 = 作者栏**（`PostDetailView.vue`）：去掉 `NavBar title="帖子详情"`，改 `fixed` 居中 `max-width:480px`、高 `48px`、`z-index:20` 的自绘条。返回 `32×32` 沿用 `NavBar` 的 `goBack` 语义（`history.length > 1` 则 back，否则 `/home`）；头像 `32` + 昵称 `15/600/$text-1` 省略 二者同为一个 `.topbar-author` 按钮（`openAuthor(userId)`）；右侧本人 `删除`（`13px/$text-2`）、非本人 `联系`（复用 `.btn-outline`，`28px/12px`）。**分享位是 `<span aria-hidden="true">`**（`32×32`、方框+右上箭头）——非交互元素，无 `@click`、无 `role="button"`，遵票 09 与票 04 code-review 的「死件」禁令。
- **图片轮播抽成共享组件** `front/src/components/ImageCarousel.vue`（新增）：props `images` / `seed` / `ratio`（默认 `4:3`）。内部持 `index` + `dragStartX`，`pointerdown/up` 算位移、阈值 `40px`（左滑下一张 / 右滑上一张）；轨道 `transform: translateX(-n*100%)` + `transition .28s ease`；圆点 `6px`（未选 `rgba(255,255,255,.55)` / 选中 `#fff`）与右下 `n/N` 胶囊（`11px` 白字 / `rgba(0,0,0,.4)` 底）**均仅 ≥2 张时渲染**；容器 `overflow:hidden` + `touch-action:pan-y`，且 `:deep(img) { pointer-events:none; -webkit-user-drag:none }`（否则桌面拖拽触发原生图片拖拽 → `dragstart/pointercancel`，滑动失效）。详情页用法：`<ImageCarousel :images="displayImages" :seed="post.postId" ratio="4:3" />`，`displayImages` 口径未动（`cover` 不在 `images` 里时前置）。
- **评论流**：排序头 `sticky top:48px`、白底、`padding:8px 0`（吸在作者栏下，`z-index:10 < 20`）。根评论 `.comment-item` 与楼内 `.reply-item` 均 `align-items:flex-start`、`padding:14px 0`、**`border-bottom:none`**；楼中楼 `.thread` **无 `border-left`**，靠根评论头像列（36px + 10px gap）自然缩进。根评论头像/昵称 `36/13·500/$text-3` 可点，回车楼内回复 `26/12`。`删除` 从原来的「行尾独立按钮」挪进 meta 行（顺带消掉 flex 行多一个子元素对 `flex-start` 的干扰）。meta 行 `margin-top:5px`、`gap:14px`，♥ 用 `margin-left:auto` 贴右（根评论 ♥ `15px`、楼内 `13px`）。「作者」标 `10/600/$primary` + `$primary-light` 底。
- **底栏**：`fixed` 居中 `max-width:480px`、`z-index:20`、上边 `0.5px $line`；真实 `<input>`（高 `34`、`$radius-full`、底 `$bg-search`、`13px`、Enter 提交）+ ♥ `24px` + 💬 `23px`（均为图标+计数 `13px`，点赞态 `$primary`）。**无发送按钮、无收藏**。回复态在输入框上方渲染「回复 @昵称 + 取消」，占位换成 `回复 @昵称…`。
- **保留能力全部在位**：`switchCommentSort`（最热/最新）、`toggleThread`/`loadReplies`/`hasMoreReplies`/`refreshThread`（展开 N 条 / 展开更多 / 收起）、`loadComments` 分页、`onDeleteComment`/`onDeleteReply`/`onDeletePost`、`onContact`（`createDirectRoom` → `/chat/{roomId}?card=POST:{id}`）、`toggleLike`/`toggleCommentLike` 乐观更新与回滚——脚本层**只删了轮播与 `CATEGORY_TEXT`，其余逐字未动**。
- **code-review 采纳项（两轴）**：
  - Spec/Standards 共有：`▶ 被回复人昵称` 原按 D 原型写成只判 `parentId !== rootId`，会把空昵称渲染成裸「▶ 」——已补回既有守卫 `&& r.replyToNickname`（只抑制退化态，不违背 ADR-0001「忽略 parent 行状态」）。
  - Standards 轴「Feature Envy / 复用缺口」：轮播状态与手势逻辑内嵌在视图里；spec §市集求购前端链路明写求购详情相册「复用票 02 的轮播规范」、impl map 也把「轮播」列为 06 的依赖——故抽成 `ImageCarousel.vue`，**06 直接复用该组件，别再各写一份滑动手势**。
- **未采纳的判断项（Standards 轴，留档备查）**：① 底栏安全区写成自己的 `padding-bottom: calc(8px + env(safe-area-inset-bottom))` 而非复用全局 `.safe-bottom`——**照票 03 坑 1 的既有结论**（scoped `.bar[data-v-x]` 权重高于 `.safe-bottom`，简写 `padding` 会覆盖掉父类的 `padding-bottom`），此处「另写」才是正解；② `goBack`（4 行）与 `NavBar` 逐字相同，未抽 composable——一行级重复，抽出去只多一层间接；③ `.author-badge` 与 `ErrandDetailView` 的 `.role-tag` 形近但语义不同（「作者」标 vs 跑腿类型 badge），且 spec 对本标有独立色值，未合并；④ 顶栏高 `48px` 在 `padding-top` / `height` / 吸顶头的 `top` 三处字面量重复，沿用 `NavBar.vue` 既有的硬编码惯例，未新建设计令牌。
- **spec 歧义留档**：尺寸表「页面底色 `#fff`（正文与评论同底色，**靠 0.5px `$line` 分隔**，不做卡片分区）」——按 D 原型（正文与评论之间**无**该线）与票面验收项（**未列**该线）落地为**不画**，即「不做卡片分区 + 评论之间无分割线」是本条的实际形态。若原意是要在**正文与评论流之间**补一条 `0.5px $line`，请示意，改动是一行。
- **验收方式**：`npm run build`（含 `vue-tsc`）通过；前端无测试设施，故用 Playwright 拦 `/api/**` 回伪造数据（注意：路由 glob 用 `**/api/**` 会误伤 Vite 的 `/src/api/*.ts` 模块请求导致白屏，须用正则 `^https?://<host>/api/` 限定），在 **390×844** 与 **1280×900** 双视口目视 + 读数核对计算样式，截图存 `.scratch/ui-revamp-impl/verify-02/`。
  - 读数核过：轮播 `aspect-ratio: 1/0.75`、`overflow:hidden`、`touch-action:pan-y`、`img{pointer-events:none}`、3 张时圆点 3 个 + `1/3`；单图帖圆点/计数**不出现**；`.comment-item{align-items:flex-start; border-bottom:0}`、`.reply-item{align-items:flex-start}`、`.thread{border-left:0}`、头像按钮实测高 36/26（未被 stretch 撑高）；排序头 `position:sticky; top:48px` 且滚动 1200px 后恰好停在 `top=48`；底栏 `position:fixed; bottom:0; z-index:20`、input `h34/radius 999/$bg-search`；分享位 `tagName=SPAN`、`32×32`；`navbar-title` 与 `send-btn` 均不存在。
  - 交互验过（访客态）：左拖 100px → `2/3` + `translateX(-100%)`；右拖 → 回 `1/3`；拖 30px（< 阈值）不切页；在图片区按下后移出容器再抬起 → 手势作废（`pointerleave` 清起点）；最热/最新切换生效；点 ♥ → `/login?redirect=/post/1`。（本人态，`bs_user.userId=7`）：顶栏 `删除` 出现、`联系` 消失；每条评论都有 `删除`；♥ 呈已赞红；点「回复」→ 底栏出现「回复 @昵称 + 取消」且占位变 `回复 @昵称…`；输入框 Enter → 新评论乐观插顶、输入清空、toast「评论成功」。点评论头像 → `/user/21` 由 catch-all 回退 `/home`（票面已声明的软依赖行为）。
- **已知波及（不在本票范围）**：① 原顶栏下方的作者信息行（`时间 · 分类 · 浏览数`）随改造移除，**`viewCount` 在详情页不再展示**——spec 新的页面结构与 D 原型均无此位，若需保留请回 `ui-revamp` 补票；`CATEGORY_TEXT` 因无引用一并删除。② 求购详情页（票 06）落地时**复用 `ImageCarousel.vue`**，不要再写一份手势。
