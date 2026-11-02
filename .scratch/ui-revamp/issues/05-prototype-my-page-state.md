Status: resolved
Type: prototype
Blocked by:

# 05: "我的"页新头部与状态停留（原型）

## Question

两件事合并出一张原型票：

**(i) 新头部**：第 7 项要求在"我的"里加个性签名与隐私设置。头部要新增**个性签名**（含**性别**）的展示位，以及一个**设置入口**（内含"编辑资料"与"隐私设置"两页）。现有头部已有：头像/昵称/账号、获赞与收藏数字、钱包/信用分（管理员另有审核工作台）卡片。注意：**没有帖子收藏**（Q5），"获赞与收藏"这个口径要顺带收口（改成"获赞"，只算自己笔记被赞数）。

**(ii) tab 停留行为**（第 8 项）：现状是"我的"页每次挂载都回到"笔记" tab。要求：看了"评论" → 点钱包 → 返回 → 仍停在"评论"。出原型**演示这个行为**，并对比**三种停留载体**，供选定：
- (a) URL query（`/profile?tab=comments&sub=want`，可分享、刷新不丢、浏览器返回栈天然正确）
- (b) 前端 store / 模块级缓存（刷新丢失）
- (c) `keep-alive` 组件缓存（组件不重建，滚动位置可保）

停留范围也是本票要定死的：**主 tab + 子 tab**（如"闲置"内的"我求购的"）都停留；**跨底部 tabbar 切换**（离开"我的"去"市集"再回来）是否保留。

选定结果直接写进 spec，并**解除 `08-list-state-loading-fixes` 的阻塞**。

产出：3 个头部变体原型 + 停留行为演示 + 载体选定结论，资产链接回填本票。

## Answer

**2026-09-11 选定：头部 A（深色资料头 · 卡片入口，现状微调）；停留载体 (b) 前端 store / 模块级缓存。**

### 选定结论

**头部选 A**。A 是「现有头部最小改动」案：沿用现有深色渐变资料头 + 钱包/信用分卡片，只加**个性签名（含性别）**、右上角**设置入口**，并把「获赞与收藏」收口成「获赞」。理由：改动面最小，且与「作者主页」（票 04 选定 D，同款深色渐变资料头 + 下划线 tab）**同源**——两页头部将来共用同一套样式规范，不会分裂成两种观感。

**落选**：B（深色居中 · 三格数字统计条）把钱包/信用分从卡片降成数字、管理员入口降成一行文字，数字条确实更紧凑，但资产入口的可点性被削弱；C（浅色资料头 · 列表入口）整块换成浅色 + 白底列表行，与作者主页的深色头不同源。

**载体选 (b) store / 模块级缓存**。三条实测依据：

| 载体 | 刷新后 | tabbar 去市集再回来 | 切回来时 | 落地成本 |
| --- | --- | --- | --- | --- |
| (a) URL query | **保留** | **丢**（tabbar 的 `/profile` 不带 query） | 组件重建、重新请求 | 每个 tab/子 tab 控件都要读写 query；值是用户可改的字符串，必须校验 |
| **(b) store（选定）** | 丢 | **保留** | 组件重建、重新请求 | 最小：一个模块级 reactive，读写直连 |
| (c) keep-alive | 丢 | 保留 | **不重建、不重新请求** | 要包全局 `router-view` + 按组件名登记进 `include`；滚动不自动保；缓存会脏，得在 `onActivated` 决定何时刷新 |

**落选理由**：
- **(a) URL query 落选**：票里「跨 tabbar 切换是否保留」既已定「保留」，(a) **开箱就是坏的**——底部 tabbar 是写死的 `<router-link to="/profile">`，回「我」时 query 已丢，tab 掉回「笔记」（原型实测截图 `stay-url-tabbar-reset.png`）。要修得让全局 tabbar 带 query，代价与收益不成比例。它的强项（可分享链接 / 刷新不丢）在本 App（PC 网页 MVP、移动壳）不是硬需求。
- **(c) keep-alive 落选**：唯一独占的好处是「组件不重建、不重新请求」，但为此要引入全局 `router-view` 改造 + 按组件名字符串登记（易漏易错）；并且**票 08 的列表状态（分页 / finished / 滚动位置）一旦放进缓存组件，就多出一个「缓存何时过期」的新问题**。它宣称的「滚动位置可保」在本仓**不自动成立**——页面滚动挂在 window 上，还得另配 `scrollBehavior`。

**选 (b) 的额外理由**：票 08 本来就需要「跑腿 / 闲置 / 求购各自独立的分页、finished、滚动位置」——这些状态必须搬到组件外面才能复用（08 问的「要不要抽 `useWaterfall`」）。选 (b) 等于**一次把 08 的地基也打了**，不是为停留单独引入一个新概念。

> 将来若要「刷新不丢 / 链接可分享」，可在 (b) 的存储模型上**再叠一层 URL 同步**，不必推翻重来。这是以后的事，本票不做。

### 停留范围（本票定死）

- **主 tab 停留**：笔记 / 赞过 / 评论 / 闲置 / 跑腿。
- **子 tab 停留**：闲置内的（我发布的 / 我收藏的 / 我求购的 / 我购买的）、跑腿内的（我发布的 / 我接的）。子 tab 只在所属主 tab 内有效——换主 tab 时旧子 tab 作废，回落到该主 tab 的默认值（不作废会出现「没有任何子 tab 高亮」的破态，原型里实测踩到）。
- **跨底部 tabbar 切换：保留**。离开「我的」去「市集」再回来，仍停在原 tab（(b) 天然满足）。
- **二级页往返：保留**。钱包 / 信用分 / 草稿箱 / 订单等二级页返回后仍停在原 tab（票面点名的场景）。
- **刷新后回到「笔记」**：接受。(b) 的已知代价，明确记入 spec。

### 头部规范（选定 A 之后要落地的改动）

在现有 `ProfileView.vue` 头部基础上：

1. **个性签名**：昵称/账号下方新增一行，`13px / 1.5 / rgba(255,255,255,.86)`，距上 `12px`。
2. **性别**：跟在账号同一行，`11px` 圆底 `rgba(255,255,255,.22)` 白字（与票 04 作者主页的资料区口径一致）。
3. **设置入口**：头部右上角「设置」（齿轮 `15px` + 文字 `13px / 500 / #fff`，`padding: 0 13px`，`$radius-full`，底 `rgba(255,255,255,.16)`）。
4. **「获赞与收藏」→「获赞」**：数字取**自己笔记被赞数之和**，**删掉原公式里的商品收藏数**（现行 `likedTotal = 笔记被赞之和 + favorites.length`，落地时去掉后一项）。
5. **管理员**的审核工作台卡片照旧保留。
6. **tab 顺序**：`笔记 / 赞过 / 评论 / 闲置 / 跑腿`（与票 04 作者主页对齐；现行 `ProfileView` 是「…跑腿 / 闲置」，落地时改序）。

**设置入口内含两页**（同票 04 口径）：

- **编辑资料**：个性签名（≤40 字）+ 性别（男 / 女 / 不展示）。**昵称 / 头像本次不改**（牵动全站展示口径，另立 effort）。
- **隐私设置**：`公开我的闲置` / `公开我的跑腿` 两个全局开关，默认公开，只影响他人查看、本人不受影响（票 04 / Q9 口径）。

### 资产

- 代码（throwaway 分支 `prototype/my-page-state`，**不进 master**；2 个 commit：`cc2d6ad` 出稿 → `65c883c` code-review 收口）：
  - `front/src/components/prototype/MyPageHeader{A,B,C}.vue` — 三个头部变体
  - `front/src/components/prototype/{ProtoPanel.vue,ProtoSettingsSheet.vue,ProtoStayBar.vue}` — tab 面板 / 设置面板 / 载体读数条
  - `front/src/components/prototype/{myPageMock.ts,myPageVariants.ts,useProtoStay.ts}` — mock、变体表、三载体状态源
  - `front/src/components/prototype/{PrototypeSwitcher.vue,useProtoVariant.ts}` — 与票 02/03/04 同源的切换器
  - `front/src/views/prototype/{MyPagePrototypeView.vue,MyPagePrototypeWallet.vue}` — 宿主与二级页样板
  - `front/src/App.vue`（按载体决定 keep-alive）、`front/src/router/index.ts`（DEV 三元 + 免登录旁路）
- 截图 `.scratch/ui-revamp/proto-05/`（18 张）：A 5 张（笔记 / 评论 / 闲置·我求购的 / 跑腿 / 管理员）、B 2 张、C 2 张、设置 3 张（菜单 / 编辑资料 / 隐私）、停留演示 6 张（钱包页 / url 返回 / url 被 tabbar 重置 / store 载体 / keep-alive 评论态 / keep-alive 返回）

### 怎么看（复现）

```bash
git checkout prototype/my-page-state
cd front && npm run dev
```

- 头部三变体 `?variant=A|B|C`（默认 A）；管理员头部加 `&admin=1`；点右上角「设置」看编辑资料 / 隐私设置
- 停留对比用浮条上方的载体切换 + 读数：切「评论」→ 钱包 → 返回（三种都留）→ **F5**（只有 URL query 留）→ tabbar 去**市集**再点**我**（URL query 掉回「笔记」）→ 读数里 URL / store「挂载 +1」、keep-alive 只「激活 +1」

### code-review 收口（2026-09-11，两轴并行）

Standards 轴 × Spec 轴并行复核，**无硬违规**（票 03 踩过的「原型类名撞全局类」本票不存在：新类名与 `common.scss` 全局类零交集）。修了 6 处，其中 1 个真 bug：

1. **真 bug**：切停留载体时 `router.replace` 没 spread 原 query，**`?variant=` 被丢掉**——在 C 上点「Store」，头部会悄悄变回 A。
2. 「退出登录」按钮误接 `settingsOpen = true`（假件）→ 删除。
3. `ProtoPanel` 的 liked 空态 `v-if` 恒为 false → 删死代码与无用 import。
4. 无封面帖卡面不符票 01 规范 → 改成整张卡面渐变 + meta 在卡内。
5. 信用分 / 审核入口原是点了没反应的 `<button>` → 改 `<div>`。
6. 获赞改为「自己笔记 ♥ 之和」派生，让 Q5 收口后的口径可见；删没人读的 `ProtoNote.cover`。

顺带把管理员头部做成 `?admin=1` 可演示（原先 `MOCK_IS_ADMIN = false` 恒不可见）。

### 下游

- **解除 `08-list-state-loading-fixes` 的阻塞**（其 `Blocked by: 05` 已满足），frontier 移至票 06。
- spec「"我的"页状态停留与列表加载修复」节已回填本票部分；**列表加载修复**（第 4 项闲置 tab 首屏不加载的修法、跑腿 / 闲置 / 求购各自的加载时机、是否抽 `useWaterfall` composable）留待票 08。
