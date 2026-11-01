# 05: "我的"页状态停留与列表加载修复

**Spec:** `.scratch/ui-revamp/spec.md` §「"我的"页状态停留与列表加载修复」（spec 索引 ⑥）

**What to build:** 两块。

**① 列表加载地基（含根因修复）。** 抽一个无限滚动 composable（建议落 `front/src/composables/useWaterfall.ts`）：每 tab 一个实例，各自持有独立 sentinel / observer / 分页 / `finished`；**不在 `onMounted` 自动加载**，由页面在「首次进入该 tab」时显式 `load()`（把加载时机从 observer 收回页面——这才是闲置 tab 首屏不加载的根因，只补一行 `watch(activeTab)` 是治标）；`reset()` 清空分页并把 window 滚动归零。市集的跑腿/闲置两份手写分页与首页的那一份，一并换用（同一翻页形状仓内已复制三份）。市集 `activeTab` 与「我的」页三个 tab ref 进模块级 store（跨底部 tabbar 与二级页往返保留 tab 位；**列表数据不进 store**，组件卸载即丢）。同页内切 tab 时恢复各 tab 的滚动位置（离开存该 tab 的 `scrollY`、切入 `nextTick` 后 `scrollTo`）。进市集**只加载当前 tab**，其余首次切入才加载。

**②「我的」页头部改版。** 沿用现有深色渐变资料头，新增个性签名（含性别）；「获赞与收藏」收口为**获赞**（去掉商品收藏数那一项）；右上角加**设置入口**；tab 顺序改为 **笔记 / 赞过 / 评论 / 闲置 / 跑腿**；「评论」tab 换成评论列表形态（与 **04** 的作者主页评论共用同一接口与形态）。设置内含两页：**编辑资料**（签名 ≤40 + 性别，昵称/头像本次不改）与**隐私设置**（`公开我的赞过` / `公开我的评论` 两个全局开关）——这两页调用 04 落地的 `PUT /api/user/profile` 与 `PUT /api/user/privacy`。

**明确不做**：URL query 承载 tab、keep-alive/`scrollBehavior`、统一的骨架屏（空态继续用现有 `EmptyState`）。

**Blocked by:** 04（设置两页依赖其写接口；「评论」tab 依赖其按用户评论接口与形态）

**Status:** resolved

- [x] 进入市集只加载当前 tab；首次切入跑腿/闲置才加载，且两者分页、`finished`、已加载数据各自独立；闲置首屏空列表的 bug 不再复现
- [x] 市集 tab 与「我的」页主/子 tab 跨底部 tabbar 往返保留；二级页返回也保留；刷新后回落默认（我的→笔记、市集→跑腿）
- [x] 子 tab 只在所属主 tab 内有效：换主 tab 时回落该主 tab 默认值，不出现「无任何子 tab 高亮」的破态
- [x] 同页内切 tab 恢复各 tab 滚动位置；分类/排序变更时重置到顶部
- [x] 「我的」页头部含个性签名/性别/获赞与设置入口；获赞不再计入商品收藏数
- [x] 编辑资料页（签名/性别）与隐私设置页（赞过/评论两开关）可读写，写入后他视生效、本人不受影响
- [x] 「评论」tab 为评论列表形态（评论内容 + 「来自笔记 · 标题」），与作者主页同接口同形态
- [x] 构建通过；手测覆盖 切 tab / 切 tabbar / 二级页往返 / 刷新 / 筛选变更 / 滚动位置

## 实现要点（票 05）

**新增文件**

- `front/src/stores/uiState.ts`：`marketTab: 'errand' | 'idle' | 'want'`（`'want'` 是给票 06 的占位，spec 落地注意明写「扩为 `'errand' | 'idle' | 'want'`」）+ `profileTab` / `profileProductTab` / `profileErrandTab`。`setProfileTab(next)` 在**换主 tab 时把离开的那个主 tab 的子 tab 回落默认值**（spec「旧子 tab 作废、回落到该主 tab 的默认值」），且 `next === this.profileTab` 时直接返回。state 显式标注返回类型 `UiState`：不标的话 `profileProductTab: DEFAULT_PRODUCT_TAB` 会被收窄成字面量 `'sell'`，`'want'` 赋值处 vue-tsc 报错。
- `front/src/composables/useWaterfall.ts`：`useWaterfall<T>(fetchPage, opts?)` → `{ list, loading, finished, loaded, sentinel, load, reset }`。每实例自持 page / observer / 哨兵；**不在 `onMounted` 自动加载**；`loaded` 在首次 `load()` 成功后置 `true`（页面据它判断「首次进入该 tab 才加载」）；`reset()` 清分页 + `window.scrollTo(0, 0)`；哨兵用 `watch(sentinel, …, { immediate: true })`，随 tab 的 `v-if` 增删自动重新 observe，`onUnmounted` disconnect。
- `front/src/components/SettingsSheet.vue`、`front/src/components/ProfileCommentRow.vue`（共享件，见下）。

**`finished` 判据**（相对 master 的唯一语义收紧）：`res.list.length < pageSize || list.length >= res.total`，替代三份手写里那份 `list.length >= total && total !== 0`。好处是空结果显示页也能落到 `finished`，不再反复请求 page 1。

**换用范围**

- `HomeView.vue`：一份 `useWaterfall` 机械替换（chip 变更 `reset()` + `load()`）。
- `MarketView.vue`：跑腿/闲置各一个实例，**各自的哨兵移进各自的 tab 模板内**（原共享单哨兵落在 `v-if/v-else` 外面，切 tab 不改变交叉状态、回调不重放，就是第 4 项 bug 的根因）；`activeTab` 改读写 store（`ui.marketTab`），进入只 `loadTabIfNeeded(ui.marketTab)`，其余首次切入才加载、之后靠各自哨兵续页；同页内切 tab 存/恢复各 tab `scrollY`；筛选/排序变更 `reset()` + `load()`。
- `ProfileView.vue`：`tab` / `productTab` / `errandTab` 三 ref → 读写 store；数据仍留在组件内，**不引入 `useWaterfall`**（一次性 50 条）；同页内切主 tab 也存/恢复 `scrollY`。

**「我的」页头部**（按 spec 尺寸表逐项）：右上角「设置」pill（齿轮 15px + 文字 13px/500 `#fff`、`padding: 0 13px`、`$radius-full`、底 `rgba(255,255,255,.16)`）；性别跟账号同一行、11px 圆底 `rgba(255,255,255,.22)` 白字（♂/♀，未设置不展示）；昵称/账号下方签名行（13px / 1.5 / `rgba(255,255,255,.86)`、距上 12px）；数字区只留「获赞」，值取 `getPublicProfile(auth.userId).receivedLikeCount`（**原 `likedTotal` 的 `favorites.value.length` 项随之整体删除，computed 已移除**）；tab 序改 `笔记 / 赞过 / 评论 / 闲置 / 跑腿`。

**「评论」tab**：改用 `listUserComments(auth.userId, 1, 50)`（票 04 的按用户评论接口），行形态与作者主页共用同一组件；`listCommentedPosts` 保留给 `SearchView`，未动。

**共享件抽取**：map 的票 04 条写「票 05 的『设置入口』只需在 `ProfileView` 挂同一入口，别再各写一版表单/开关」，故把 04 内联的底部设置面板抽成 `SettingsSheet.vue`、把评论行抽成 `ProfileCommentRow.vue`，`AuthorProfileView.vue` 改为复用（视图与样式逐字搬迁，无视觉变化，已回归验证）。

**本票修掉的第二个真 bug**（自检发现，非 code-review 提出）：原 `watch(tab)` 对 `posts` 直接 `return`（假定挂载时已加载笔记）。tab 进 store 后，刷新前停在「闲置」、刷新后 store 回落「笔记」没问题，但「按 store 恢复成非 posts → 再点回笔记」时 `posts` 从未加载，列表永远空。改为 `loadedTabs` 集合统一把关「该 tab 是否加载过」，`load()` 按 store 当前 tab 拉首次。

**code-review 采纳**

- Spec 轴：① `setProfileTab` 在重复点当前主 tab 时会把子 tab 悄悄重置（真 bug）→ 加 `next === this.profileTab` 早返回；② ProfileView 未做「同页内切 tab 恢复滚动位置」（impl 票 bullet 4 未限定页面）→ 按市集同一手写方案补上，浏览器验证 700 → 0 → 700。
- Standards 轴：① 本票新增的 `loadedTabs` 与原有 `errandsLoaded` 在同一轴上重复把关 → 删掉 `errandsLoaded`（`errandsLoading` 保留，模板空态在用）；② store 默认值在 `state()` 与 `setProfileTab` 两处重复 → 提常量同源。

**code-review 未采纳（留档）**

- `useWaterfall` 的 `opts.pageSize` 只有 `finished` 判据在用（callers 各自把 10 写进 `fetchPage`）——spec 第 673–681 行明写该签名与 `pageSize` 默认 10，保留。
- `reset()` 内 `window.scrollTo(0, 0)` 属「取数 composable 管了滚动」——spec 第 684 行明写「`reset()` …同时把 window 滚动归零」，保留。
- `MarketTab` 含 `'want'` 而 MarketView 目前只渲染两 tab——spec 落地注意明写要扩，`'want'` 是票 06 的落点，保留。
- 筛选变更时若正有请求在飞，`reset()` 后新 `load()` 会在 `loading` 守卫处早退、旧响应回填列表——**master 既有行为**（原 `watch` 同一形状），本票明写「语义同现状」，未擅自改。
- 抽取共享件动了票 04 已落地的 `AuthorProfileView.vue`——map 明写要挂「同一入口」，且已对 04 的可见行为回归（签名/获赞/他视无设置入口/评论 tab 形态均未变）。

**已知波及 / 不在本票范围**

- 市集第三栏「求购」tab 本体（含 `ui.marketTab === 'want'` 的模板分支）留给票 06：store 的 `'want'` 值、三 tab 的加载时机判据现已就位，06 只需加 tab 与实例。
- 票 06 还会动 `ProfileView.vue` 的「我的-闲置-我求购的」跳转目标（现为 `/market`，spec §4 指向求购详情），届时与票 05 无并发方。
- 空态仍用各页现有 `EmptyState`，未做骨架屏；`ErrandDetailView` 等其它页面未动。

**验收**

- `npm run build`（vue-tsc + vite）绿；ProfileView chunk 16.28 kB。
- Playwright 拦 `/api`（**必须用正则 `^http://localhost:5178/api/` 限定**；glob 会把 Vite 的 `/src/api/*.ts` 模块请求也拦成 JSON 致白屏）伪造数据，390×844 + 1280×900 双视口读数：
  1. 进入市集只请求跑腿（`products` 请求 0），切「闲置」才加载并靠哨兵续页（10 → 20 条，滚到底出「已经到底啦」）——第 4 项 bug 不再复现；
  2. 同页切 tab 滚动位置 700 → 0 → 700，切回不重拉（`loaded` 生效）；筛选变更回落顶部（scrollY 0）；
  3. 跨底部 tabbar 往返保留市集 tab（闲置）与「我的」主+子 tab（闲置 / 我收藏的）；二级页（`/settings/privacy`、`/product/1`）返回同样保留；刷新回落（市集 → 跑腿、「我的」→ 笔记）；
  4. 「我的」页头：签名 / 性别 ♀ / 「305 获赞」/ 设置入口 1 个、tab 序 `笔记/赞过/评论/闲置/跑腿`；
  5. 「评论」tab 走 `GET /api/community/users/100/comments` 出评论列表（含「来自笔记 · 标题」）；
  6. 子 tab 作用域：重复点当前主 tab 保持「我收藏的」，换主 tab 再回来回落「我发布的」；
  7. 设置面板 → `/settings/profile`、`/settings/privacy`；作者主页回归（签名 / 获赞 / 他视无设置入口 / 评论 tab 形态）。
  - 注：Playwright `click()` 会把非吸顶的 tab 条先滚进视口，测滚动位置须用 `page.evaluate` 直接 `el.click()`，否则量到的是被自动滚动后的值。
- 截图 12 张存 `.scratch/ui-revamp-impl/verify-05/`。控制台无非预期错误（仅 chat WS 握手失败：拦 `api` 后 ws ticket 为 undefined，环境固有）。
