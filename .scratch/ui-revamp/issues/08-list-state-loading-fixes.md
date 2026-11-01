Status: resolved
Type: grilling
Blocked by: 05

# 08: 列表加载与页面状态修复口径

## Question

本票收口"列表首次加载 / 页面状态保持"这一类问题（第 4、8 项），**依赖「我的页新头部与状态停留原型」选定停留载体后再定**（所以 Blocked by 05）。

**① 闲置 tab 首屏不加载（bug）**：根因已定位——`MarketView.vue` 挂载只 `loadErrand()`；跑腿与闲置**共用一个底部 `IntersectionObserver` 哨兵**，只在交叉状态*变化*时回调。闲置列表初始为空时哨兵已在视口内，切到"闲置" tab 不改变交叉状态 → `loadProduct()` 永不触发；只有点分类 chip 走了 `watch` 才兜底加载（所以"先点数码再点回全部才刷出数据"）。

要定的是**修法**，而不是只补一行：切 tab 即触发该 tab 首屏加载（若未加载过）；跑腿/闲置/求购**各自独立**的分页、finished、滚动位置状态；是否把翻页+哨兵抽成可复用的 composable（`useWaterfall` 之类）避免三个 tab 再复制一份。

**② "我的"页 tab 停留落地**：按「我的页新头部与状态停留原型」选定的载体（URL query / store / keep-alive）落实——主 tab + 子 tab；跨底部 tabbar 切换是否保留；从"我的"进二级页（钱包/信用分/草稿箱/订单）返回后的表现。

**③ 新增求购 tab 后的加载时机**：市集变三 tab 后，切到哪个 tab 才加载哪个；避免首次进入把三个列表全部拉一遍。

产出：修复方案 + 状态管理口径（含是否抽 composable 的结论），写进 spec 的"列表加载与页面状态"节。

## Answer

**2026-09-11 一轮 frontier 七问，用户「都按你推荐的」全部采纳。**

结论一句话：**抽 `useWaterfall` composable（每 tab 一个实例、各自独立哨兵与分页状态）；市集 `activeTab` 进 store、列表数据不进；同页内切 tab 恢复各 tab 的滚动位置；「我的」页 tab 选择进 store、数据不进、不引入 composable。**

### 事实自核（开工前实测，修正/补充票面前提）

- **根因属实**：`MarketView.vue:101-114` 只在 `onMounted` 建**一个** observer，哨兵 `<div ref="sentinel">` 在 `v-if/v-else` **外面**（第 194 行）——切 tab 不改变交叉状态，回调不重放；闲置列表初始为空时哨兵已在视口内 → `loadProduct()` 永不触发。票面描述准确。
- **`useWaterfall` 的复制范围**：同一套「`page/loading/finished` + `load()` + 筛选 `watch` 重置」的分页形状在仓内**复制了三份**——`MarketView.vue` 的 `loadErrand` 与 `loadProduct`（`:16-99`，两组 refs + 两个 loader + 两个 `watch`）各一份、`HomeView.vue:21-51` 一份；`IntersectionObserver` 的接线是**两处**手写（`MarketView.vue:101-114` 单哨兵内按 `activeTab` 分支；`HomeView.vue:53-64`）。`SearchView.vue` **不是**无限滚动列表，不在抽取范围。
- **无兜底机制**：`router` 无 `scrollBehavior`，`App.vue` 是裸 `<router-view />`、无 `KeepAlive`；`#app` 无 `overflow`——页面滚动挂在 window 上，滚动位置要保必须手写 `window.scrollTo`。
- **「我的」页已是按 tab 懒加载**：`ProfileView.vue:150-154` 的 `watch(tab)` + `TAB_LOADERS`，但**不分页**（`pageSize: 50` 一次性），五个 tab 为 `posts/liked/comments/errands/products`。
- **求购 tab 尚不存在**：`MarketView.vue:13` 的 `activeTab` 只有 `'errand' | 'idle'`；票 07 §1 已把「市集三 tab 懒加载与分页/`finished` 载体合流口径」显式挂到本票（原文「…见「列表加载」相关节（票 08 待定）」，本票收口时已把该指针更新为指向本节）。

### Q1 · 首屏加载触发机制（①/③ 的根因修法）→ 选 (a)

**每个 tab 一个独立哨兵 + 独立 observer**，切 tab 时若未加载过则显式 `load()`。

- 每 tab 一份 `<div ref="sentinelX">` + 自己的 observer，`page/loading/finished` 各自持有——**天然就是三份独立状态**，也让 Q2 的 composable 顺理成章。
- 落选 (b) 单哨兵 `nextTick` 后重新 `observe`（靠重放回调触发，隐晦）；(c) 单哨兵 + `watch(activeTab)`（状态仍共享，与「各自独立」目标相悖）。
- **加载时机**：进入市集**只加载 `activeTab` 指向的那一个 tab**（首次进入即默认「跑腿」）；其余 tab **首次切入才加载**，之后靠各自哨兵续页。「避免首次进入把三个列表全拉一遍」由「未 `load()` 过就不请求」直接满足。

### Q2 · 是否抽 `useWaterfall` composable → 选 (a)

**抽 `useWaterfall`，MarketView 三 tab 用，`HomeView` 一并换用。**

- 同一套翻页形状在仓内已**复制三份**（见「事实自核」），抽出后 `HomeView` 是**机械替换**、风险低；不换则同一套翻页逻辑在仓里留三版。
- 落选 (b) 只用于 MarketView（留双轨）；(c) 不抽、写三份（正是要避免的复制）。
- 落点：新建 `front/src/composables/useWaterfall.ts`（新目录，仓内首个 composable）。

### Q3 · composable 接口形态与「是否自动加载」

```ts
useWaterfall<T>(
  fetchPage: (page: number) => Promise<{ list: T[]; total: number }>,
  opts?: { pageSize?: number }
) => { list, loading, finished, loaded, sentinel, load, reset }
```

- `pageSize` 默认 `10`；内部保留 `IntersectionObserver(rootMargin: '250px')`，`onUnmounted` 自动 `disconnect`。
- `loaded`：由 composable 在**首次 `load()` 成功后**置 `true`；页面据它判断「该 tab 是否已加载过」，未加载过才调 `load()`——这就是 Q1「切 tab 触发首屏加载」的判据。
- **关键：不自动在 `onMounted` 加载**——由页面在「首次进入某 tab」时显式 `load()`。这把「加载时机」的控制权从 observer 手里收回页面，正是第 4 项的根因修法。
- `reset()` 供筛选/排序变更时清空重拉，**同时把 window 滚动归零**。

### Q4 · 市集 tab 选择与列表数据，跨 tabbar 保留到什么程度 → 选 (b)

**(b) `activeTab` 进 store（跨 tabbar 保留 tab 位）；列表数据/分页仍只活在组件内**（切回来按 `activeTab` 重新首屏加载）。

- 与票 05「我的」页**同构**（tab 选择进 store、数据不进），代价最小。
- **落选 (c)**（列表数据也进 store）：会引入「缓存何时过期」——新品上架/审核变动后何时刷新——这一新问题，本 effort 不值当；票 05 已把「切回来不重新请求」列为**不做**。
- 后果：滚动位置记忆（Q5）**只在同一次页面停留内**有效；组件一卸载，数据与滚动位置一起丢，重进滚到顶。
- **修订票 05 的一句预期**：票 05 选 (b) 的「额外理由」写过「08 的分页/finished/滚动位置**本就必须放在组件外**才好复用……选 (b) 等于一次把 08 的地基也打了」。本票实测修正——**分页/数据/滚动位置留在组件内**（`useWaterfall` 实例），**只有 tab 选择进 store**；(b) 的「地基」作用落在 tab 选择，不落在列表数据。

### Q5 · 同页内切 tab 时的滚动位置 → 选 (b)

**每 tab 记住自己的 `scrollY`，切回时恢复。**

- 三个 tab 共享同一个 window 滚动容器，保留数据 + 恢复位置才自洽（切回跑腿仍看到刚才那屏）。
- 实现约十行：离开 tab 时存 `scrollY`、切入时 `nextTick` 后 `window.scrollTo`。
- 落选 (a) 一律归零（切回看不到原处）；(c) 不动（会落到另一 tab 的半中腰）。

### Q6 · 「我的」页与本票的合流口径（② 落地细节）

- `tab` / `productTab` / `errandTab` 三个 `ref` → **读写 store**（票 05 已定）。
- **数据不进 store**，每次挂载按 store 里当前的 tab 重新加载（与票 05 表格「组件重建、重新请求」一致）。
- **「我的」页不引入 `useWaterfall`**——它是「一次性 50 条」非分页列表（含改形态后的「评论」tab），套 composable 只添间接。
- 跨 tabbar 与二级页往返**保留 tab 位**；**刷新回落「笔记」**；子 tab 作用域沿用票 05（换主 tab 旧子 tab 作废、回落到「我发布的」）。

### Q7 · 筛选/排序变更 + 统一空态/加载态是否本票收口

- 筛选 chip / 排序按钮变更 → 对应 tab 的 `useWaterfall.reset()` + 重新 `load()` + **滚到顶**（同现状语义，只是收进 composable）。
- **「加载态 / 空态 / 骨架屏」的统一组件规范本票不收口**——map 已将其列为「落地时再定」。本票只产出 `useWaterfall` 这一处共享抽象；空态继续沿用各页现有 `EmptyState`，**不做骨架屏**。

### 产出与落点

- 修法：`MarketView.vue` 三 tab 各持一个 `useWaterfall` 实例（三份独立 sentinel/observer/分页）；`HomeView.vue` 换用同一 composable。
- 新文件：`front/src/composables/useWaterfall.ts`；模块级 Pinia store（建议 `front/src/stores/uiState.ts`，名字落地时可调）收「我的」页三 ref 与市集 `activeTab`。
- 已写进 `spec.md`「"我的"页状态停留与列表加载修复」节「列表加载与页面状态（票 08 定）」子节；索引 ⑥ 改「已定稿」。

### 下游

- 本票 resolved 后 **frontier 只剩票 09**（分享位落地；已 resolved：**不做**，仅留装饰图标）。
- 本 effort **只产出决策与 spec，不落地代码**。
