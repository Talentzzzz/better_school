Status: resolved
Type: prototype
Blocked by:

# 02: 帖子详情布局（原型）

## Question

帖子详情当前是：`NavBar("帖子详情")` + 一张白色卡片装正文与图片 + 评论区 + 底部一条"输入框 + 发送"栏。参考仓库根目录 `fcc256337cab8d96e8e6edcccf94ff87.jpg`：顶部是作者行（头像 + 昵称 + 关注位 + 分享）、正文与图片、**以评论为主体的列表**（含"展开 N 条回复"）、底部固定操作栏（输入框 + 点赞 + 评论）。

出 **3 个变体原型**，选定一套作为 spec 的"帖子详情"节。

约束（已在 map 的 grilling 中定死，原型不要越界）：
- **不引入收藏**：底部栏只有 点赞 + 评论，不做参考图里的"收藏 765"
- **不引入关注**：顶部作者行不做"关注"按钮，保留现有"私信/联系"入口
- **评论区头像/昵称可点进作者主页**（见「作者主页布局原型」票）——原型要体现这个可点状态
- 现有能力必须保留：最热/最新排序、楼中楼"展开 N 条回复"、楼内"展开更多回复"、"加载更多评论"、本人可删评论/删帖
- 正文与图片：现有独立封面 + 相册的展示口径不变

产出：3 个变体原型 + 选定结论，资产链接回填本票。

## Answer

**选定 D。** 走 `prototype` skill 的 UI 分支（sub-shape A：挂在既有路由 `/post/:id`，不另起原型页）：`?variant=A|B|C|D`，底部浮动切换器（← → 或左右方向键循环，URL 可分享、刷新稳定）。无 `?variant` 时行为与现状完全一致。

数据全部 mock（`postDetailMock.ts`，图片走 picsum，离线自动降级为渐变占位）。**变体模式下不取真数据**——mock 里刻意铺满了本次要看的全部状态：作者本人评论（「作者」标）、有楼中楼的评论、楼内嵌套回复（「▶ 被回复人昵称」）、当前用户自己的评论（显示「删除」）、已点赞评论、第二页评论（「加载更多评论」）、楼内「展开更多回复」、**独立封面 + 相册**（cover 不在 images 里、排在相册前）。

### 变体对照（A/B/C 是票面点名的原始三案，D 是翻页后拼合的选定案）

| 变体 | 名 | 作者怎么呈现 | 正文与图的形态 | 评论怎么进 | 点赞/评论计数在哪 | 底部栏 |
| --- | --- | --- | --- | --- | --- | --- |
| A | 评论流主导 | **作者就是顶栏**（无「帖子详情」标题）：返回 + 头像 + 昵称 + 联系 + 分享 | 紧凑，图竖向平铺在正文里 | 直接是页面主体，紧跟正文 | 底栏 | 输入 + ♥ + 💬 |
| B | 卡片分区 | NavBar 标题 + 卡内作者行 | 灰底上两张白卡之一 | 卡② 独立区块 | 帖子卡的卡内 action row | 输入 + 发送 |
| C | 沉浸正文+评论面板 | 正文下的作者行 | **通栏大图**打头，无标题栏 | **从底部升起的半屏评论面板** | 悬浮操作条 | 输入 + ♥ + 💬 |
| **D** | **（用户翻页拼合，最终选定）** | **取 A 的吸顶作者栏**，右上角分享位换**右转箭头** | **取 C 的通栏正文，但图改轮播**（始终只露一张，左右滑动切换） | **取 A 的评论流** | 底栏 | 输入 + ♥ + 💬 |

- D 与 A/B/C 的关系：页面骨架取 **C**（沉浸正文、无「帖子详情」标题栏、悬浮底栏），作者栏与评论流取 **A**，图片另起**轮播**方案。
- **图片轮播**是本票新增的一轴：不再竖向平铺（A/B 那样一列），**始终只展示一张**，`pointer` 拖拽（阈值 40px）左右切换，配圆点 + `n/N` 计数。

### 选定结论

**选 D**，用户 2026-09-11 拍板，五条翻页指令原文：

1. 「C的页面，并且图片不要做成一列，始终只有一张图片，通过左右滑动切换别的图片」
2. 「作者那一栏选A的做法，但是右上角的分享按钮换成右转箭头」
3. 「评论流做成A的，但是A现在有bug，点展开的时候根评论的头像会错位」
4. 「照我说的做个D出来」
5. 「评论与评论间不要有分割线，自然分割」——去掉根评论的 `border-top: 0.5px $line`，行距 12px→14px（相邻两条之间 28px 留白），留白是评论之间唯一的分隔手段

完整口径（页面结构、尺寸色值、评论项规范、保留能力、明确不做）已写进 `.scratch/ui-revamp/spec.md` 的「帖子详情布局」节，落地时以那节为准。

**落选**：A（图片竖向平铺太占屏、页面不够沉浸）、B（仍是"帖子卡 + 评论卡"两段式，改版幅度最小、未取）、C（评论收进底部面板，要多一次点击才看得到评论——D 把评论流改回 A 的直出）。

**分享位**：按参考图保留**视觉占位**（右转箭头），App 无分享链路、map 也没列这项 → 已**另立票 09**（用户拍板「保留占位，另立票」）→ 票 09 已于 2026-09-11 resolved：**不做**分享链路，图标保留为纯装饰。

### 原型期揪出的两个 bug（已在原型里修掉，落地时别抄回去）

1. **根评论头像错位**（用户点名）：评论行是 flex，默认 `align-items: stretch`；头像外壳是 `<button>`，按钮会把内容垂直居中——楼中楼一展开、整块变高，根评论的头像就飘到整块中间。修法：评论行与楼内行统一 `align-items: flex-start`（A/B/C/D 四处都改了）。
2. **轮播鼠标拖拽失效**：`<img>` 触发浏览器原生图片拖拽（`dragstart` → `pointercancel`），把起点重置掉。修法：轮播容器内 `:deep(img) { pointer-events: none; -webkit-user-drag: none }`。（触屏上不一定复现，但桌面翻页必然踩到。）

### code-review 收口（2026-09-11，两轴并行）

跑 `/code-review`（Standards × Spec）后又修了三处，都是「票面/规范说了，但原型其实没做到」的窟窿：

1. **「▶ 被回复人昵称」四个变体里都不可达**（两轴同时揪出，最高价值）：`MOCK_REPLIES` 每条 `parentId` 都等于所在楼的根 id，而渲染判据是 `parentId !== 楼根 id`，标签**永远不显示**；mock 只用 `replyToNickname` 表达嵌套，恰好是 **ADR-0001 明确否掉的「root + reply_to_user」模型**。按 ADR-0001 的双字段树改写：回复楼内某条时 `parentId` 填**那条**的 id（102→101、203→202、302→301），标签这才真的渲染出来。
2. **「独立封面 + 相册」口径没被演示**：mock 的 `cover` 原本就在 `images` 里，变体又直接绑 `post.images`——票面要求「展示口径不变」，但原型既没演练也没验证。改法：mock 换成真·独立封面（`bs-pd-cover`），口径抽成 `useProtoPostDetail.displayImages`（与线上 `PostDetailView` 同一段逻辑）供四个变体共用，轮播因此变成 4 张、能看见封面排在最前。
3. **D 底栏的输入框是假的**：spec 写了「真实 `<input>`，Enter 提交」，原型里却是个 `div`。改成真 `<input>`（Enter 回 toast，不落后端），并补上回复态提示条（`回复 @昵称` + 取消）——spec 那两句现在名副其实。

留档未改（判断项，非缺陷）：四个变体的评论标记高度重复是原型**刻意**保留的（只共享状态机 `useProtoPostDetail`、不共享布局，否则变体就不是变体了）；`const d = useProtoPostDetail()` 的单字母命名在 throwaway 里不值得全量重命名。`PrototypeSwitcher` 没人传的 `bottom` prop 已删。

### 资产

- 代码（throwaway 分支 `prototype/post-detail`，**不进 master**）：
  - `front/src/components/prototype/postVariants.ts`（变体清单 + 键→组件映射，一处定义）
  - `front/src/components/prototype/useProtoVariant.ts`（`?variant=` 解析与 DEV 门禁；组件表由调用方传入，多页共用）
  - `front/src/components/prototype/postDetailMock.ts`（mock 帖子/评论/回复 + `MOCK_VIEWER_IS_AUTHOR` 开关）
  - `front/src/components/prototype/useProtoPostDetail.ts`（三变体共用的状态机：排序/楼中楼/删除/点赞/回复目标——**只共享状态，布局各自写**）
  - `front/src/components/prototype/PrototypeSwitcher.vue`（浮动切换器）
  - `front/src/components/prototype/PostDetailVariant{A,B,C,D}.vue`（四个变体）
  - `front/src/views/post/PostDetailView.vue`（`?variant=` 接线）
- 截图：`.scratch/ui-revamp/proto-02/`（`post-A-hero` / `post-A-thread` / `post-B-top` / `post-B-comments` / `post-C-hero` / `post-C-sheet` / `post-D-hero` / `post-D-slide2` / `post-D-thread`）

### 怎么看

```bash
cd front && npm run dev
```

- `http://localhost:5173/post/1?variant=A`（→ B → C → **D**），切换器可点、URL 可分享、←/→ 循环
- 只看选定结果就是 `http://localhost:5173/post/1?variant=D`
- 轮播：鼠标左右拖拽图片区（或点圆点）翻页；展开楼中楼看根评论头像的对齐

**本票不落地代码**（map 定死：本 effort 只产出决策与 spec）。D 的规范待后续实施票套用到 `/post/:id`。原型代码留在 throwaway 分支 `prototype/post-detail` 备查，不进 master。

### 生产构建口径

`protoVariant` 与切换器都在 `import.meta.env.DEV` 后，生产构建里 `DEV=false` → 取值为 `undefined` → 变体与切换器**都不可能渲染**。如实说明：变体组件是**静态 import**，未被 tree-shake，仍以死代码形式留在 bundle 里（throwaway 分支，不进 master，故不处理）。`PostDetailView` 产物因此从 master 的 **11.15 kB（gzip 3.99 kB）** 涨到原型分支的 **51.70 kB（gzip 12.36 kB）**，只发生在原型分支上。
