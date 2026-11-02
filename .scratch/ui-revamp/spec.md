Status: draft

# UI/UX 改版 spec

术语遵循根目录 `CONTEXT.md`。本 spec 由 `.scratch/ui-revamp/map.md` 的 8 项改版**逐票累积**：某节在对应票 resolved 时定稿，其余节留空待回填。

## 索引

| # | 改版项 | 来源票 | 状态 |
| --- | --- | --- | --- |
| ① | 信息流分隔与卡片规范 | 票 01（prototype） | **已定稿** |
| ② | 帖子详情布局 | 票 02（prototype）+ 票 09（分享位） | **已定稿** |
| ③ | 作者主页（含隐私开关与资料编辑） | 票 04 + 票 06 | **已定稿** |
| ④ | 市集求购前端链路 | 票 07 | **已定稿** |
| ⑤ | 下单支付操作区 | 票 03（prototype） | **已定稿** |
| ⑥ | "我的"页状态停留与列表加载修复 | 票 05 + 票 08 | **已定稿** |

## 信息流分隔与卡片规范

**定稿票**：`.scratch/ui-revamp/issues/01-prototype-feed-separation.md`（2026-09-11 选定 **D**）
**原型资产**：throwaway 分支 `prototype/feed-separation`（`front/src/components/prototype/`），截图 `.scratch/ui-revamp/proto-01/`
**参考图**：仓库根目录 `示例.jpg`（双列瀑布流）
**适用范围**：首页发现、市集跑腿、市集闲置、市集求购——四处**统一**用本节规则；求购列表（票 07）沿用同一套分隔与卡面。

### 选型

在 A（纯留白）/ B（灰底白卡）/ C（描边）三案之外，按用户翻页指令追加并最终选定 **D = A×B 结合**：取 B 的卡片外壳（灰底页 + 白卡 + 阴影 + 内边距），把行列间距收到 8，把无封面帖换成 A 的柔和渐变卡面、**标题放底部**。

落选理由：A 贴参考图但卡面无边界、密度偏低；B 的无封面帖形态（灰底引号文字区、标题位次）被否；C 的描边取向与 D 的卡片派重复，未取。

### 分隔与栅格

| 项 | 值 |
| --- | --- |
| 页面底色 | `#f2f2f5`（卡片与页面**底色对比**，是主要分隔手段） |
| 栅格 | 双列瀑布流（`columns: 2`），列内 `break-inside: avoid` |
| 列间距 | `8px` |
| 行间距 | `8px`（列内卡片 `margin-bottom`） |
| 页面左右边距 | `8px` |
| 卡片圆角 | `12px` |
| 卡片底色 | `#fff` |
| 卡片阴影 | `0 1px 3px rgba(0,0,0,.07), 0 1px 2px rgba(0,0,0,.04)` |
| 卡片内边距 | `8px` |
| 卡片按下态 | 底色转 `#fafafa` |

### 卡面通用

- **图片**：统一 **1:1**，圆角 `8px`，裁剪 `object-fit: cover`（不再按原图比例错落）。
- **标题**（有图卡）：距图 `8px`，左右留 `2px`，`14px / 500 / 1.45`，色 `#1c1c1e`，最多 2 行。
- **作者行（meta）**：距上 `6px`，头像 `16px` 圆形 + 昵称（`max-width: 55%`，超长省略）+ 右侧统计/时间（`margin-left: auto`）；字号 `10px`，色 `#999999`。
- **无封面帖**：整张卡面换成柔和渐变 `linear-gradient(135deg, #f3f0ff 0%, #eef4ff 100%)`，内边距 `14px 12px`；**摘要在上、标题在底部**。摘要 `12px / 1.55 / #6b6b70`（最多 3 行）；标题距摘要 `8px`，`15px / 550 / 1.45 / #1c1c1e`（最多 2 行）。**帖子无标题时**不摆「（无标题）」占位，改为把摘要当 lead 排（`14px / #1c1c1e`，最多 5 行）且不渲染标题行。
- **有封面但无标题的帖子**仍按现有约定显示「（无标题）」。

### 各卡面信息排布

| 卡面 | 自上而下的信息 |
| --- | --- |
| 帖子·封面卡 | 封面图（1:1）→ 标题 → meta（头像 + 昵称 + ♥ 点赞数） |
| 帖子·无封面卡 | 渐变卡面内：摘要 → 标题（在底部）→ meta（头像 + 昵称 + ♥ 点赞数） |
| 闲置卡 | 商品图（1:1）→ 标题 → 价格行 → meta（头像 + 卖家 + 「N 人想要」） |
| 跑腿卡 | 顶行（类型 badge + 悬赏）→ 描述 → 路线块 → meta（头像 + 发布者 + 时间） |
| 求购卡 | 顶行（「求购」badge + 分类）→ 标题 → 描述 → 预算 → meta（头像 + 昵称 + 时间） |

字段细则：

- **闲置价格行**：`¥` + 金额（`15px / 700`，色 `$primary` `#ff2442`）+ 成色（`10px / 400`，色 `#999999`）。
- **跑腿悬赏**：单人单显示 `¥ 金额`，**多人单显示「每人 ¥ 金额」**（判据 `requiredTakers > 1`）；`13px / 700`，色 `$primary`。跑腿卡无图。
- **跑腿路线块**：灰底 `#f7f7f8`、圆角 `6px`、内边距 `5px 7px`、`11px` 色 `#666666`，形如 `起 → 止`（箭头为 `#bbbbbb`）。
- **跑腿/求购类型 badge**：内边距 `2px 7px`、圆角 `5px`、`10px / 600`；跑腿为红底 `rgba(255,36,66,.1)` + 红字，「求购」为绿底 `rgba(52,199,89,.14)` + 绿字 `#1f9d4d`。
- **求购预算**：`预算 ¥ 金额`，`14px / 700`，色 `#1f9d4d`（绿），与闲置的红色成交价区分开。求购无成交价。
- **描述**：`12px / 1.55 / #666666`；跑腿最多 4 行，求购最多 3 行。

### 落地注意

- 本规范由**后续实施票**套用到四处信息流；本 effort 不落地代码。
- 现有 `PostCard.vue`（白底无边界、`3:4` 图、`8px` 间距）、`ProductCard.vue`（`3:4` 图）、`ErrandCard.vue`（灰底无图卡）与 `HomeView.vue` / `MarketView.vue` 的 `.waterfall` 样式都需按本节改写。
- 无封面帖的渐变卡面在 `PostCard.vue` 现有 `.text-card` 分支上等价扩展；现有实现是 `meta 在块外`，本节要求 **meta 在卡内**。

## 帖子详情布局

**定稿票**：`.scratch/ui-revamp/issues/02-prototype-post-detail.md`（2026-09-11 选定 **D**）
**原型资产**：throwaway 分支 `prototype/post-detail`（`front/src/components/prototype/`），截图 `.scratch/ui-revamp/proto-02/`
**参考图**：仓库根目录 `fcc256337cab8d96e8e6edcccf94ff87.jpg`（小红书笔记详情）
**适用范围**：`/post/:id` 帖子详情页

### 选型

选定 **D**＝**C 的页面骨架 + A 的作者栏 + A 的评论流 + 图片轮播**。三个原始候选：

- **A 评论流主导**：作者即顶栏、正文紧凑、评论紧跟正文当主体。落选：图片竖向平铺太占屏、页面不够沉浸。
- **B 卡片分区**：灰底上「帖子卡 + 评论卡」两张白卡。落选：仍是两段式，改版幅度最小。
- **C 沉浸正文+评论面板**：通栏大图 + 评论收进底部半屏面板。落选：看评论要多一次点击。

D 由用户四条翻页指令拼合而成（2026-09-11）：

1. 页面取 C（沉浸正文、无「帖子详情」标题栏、悬浮底栏）
2. 图片不做成一列：**始终只展示一张**，左右滑动切换
3. 作者栏取 A 的吸顶做法，右上角**分享位换成右转箭头**
4. 评论流取 A（并修掉 A 的根评论头像错位）

### 页面结构（自上而下）

| # | 区块 | 说明 |
| --- | --- | --- |
| 1 | 吸顶作者栏 | 即顶栏，**没有「帖子详情」标题**；返回 + 头像 + 昵称 + 右侧动作 + 分享位 |
| 2 | 通栏图片轮播 | 始终只露一张，左右滑动切换 |
| 3 | 正文 | 标题（有则显示）+ 正文 |
| 4 | 评论流 | 排序头 + 根评论列表 + 楼中楼 + 加载更多 |
| 5 | 底部固定栏 | 输入 + 点赞 + 评论（**无收藏**） |

### 尺寸与色值

| 项 | 值 |
| --- | --- |
| 页面底色 | `#fff`（正文与评论同底色，靠 0.5px `$line` 分隔，不做卡片分区） |
| 页面左右内边距 | `14px` |
| 底部预留 | `60px`（给固定底栏） |
| 顶栏高度 | `48px`，白底，`fixed` 居中 `max-width: 480px`，`z-index: 20` |
| 顶栏返回 | `32×32` |
| 顶栏头像 | `32px` 圆形，可点 |
| 顶栏昵称 | `15px / 600 / $text-1`，超长省略 |
| 顶栏「联系」 | 复用 `.btn-outline`，高 `28px / 12px` |
| 顶栏「删除」 | `13px / $text-2`，仅本人视角出现 |
| 顶栏分享位 | `32×32`，右转箭头（方框 + 右上箭头）；**纯装饰、不可点**（票 09，见「分享位（票 09 定）」） |
| 评论排序头 | `sticky top: 48px`（吸在顶栏下），白底，`padding: 8px 0` |

### 图片轮播

| 项 | 值 |
| --- | --- |
| 形态 | 通栏（贴左右边），容器 `overflow: hidden` |
| 每张比例 | 固定 **4:3**（不再按原图比例自适应，避免翻页时高度跳动） |
| 切页手势 | `pointer` 拖拽，位移阈值 **40px**，左滑下一张 / 右滑上一张；容器 `touch-action: pan-y` |
| 切页动画 | 轨道 `transform: translateX(-n*100%)`，`transition: .28s ease` |
| 圆点 | 底部居中，`6px` 圆；未选中 `rgba(255,255,255,.55)`、选中 `#fff`；**仅 ≥2 张时出现** |
| 计数 | 右下角胶囊，`n/N`，`11px` 白字 / `rgba(0,0,0,.4)` 底；**仅 ≥2 张时出现** |
| 图片不响应指针 | 容器内 `img { pointer-events: none; -webkit-user-drag: none }`——否则桌面拖拽会触发浏览器原生图片拖拽（`dragstart`→`pointercancel`），滑动失效 |

**展示口径不变**：仍是「独立封面 + 相册」（`cover` 不在 `images` 里时排在相册前），轮播只改**展示形态**（竖向平铺 → 单张翻页），数据链路不动。

### 评论项规范

**根评论**（`padding: 14px 0`，**不画分割线**，`align-items: flex-start`）：

> **评论之间不画任何分割线**——相邻两条之间 28px 留白就是唯一的分隔手段（用户 2026-09-11 明示「不要有分割线，自然分割」）。楼中楼同样不画竖线。

| 元素 | 规格 |
| --- | --- |
| 头像 | `36px` 圆形，**可点**进作者主页 |
| 昵称 | `13px / 500 / $text-3`，**可点**进作者主页 |
| 「作者」标 | 昵称右侧，`10px / 600`，`$primary` 字 + `$primary-light` 底，圆角 `4px`，内边距 `0 5px` |
| 正文 | `15px / 1.55 / $text-1` |
| meta 行 | `margin-top: 5px`，`gap: 14px`：时间（`12px / $text-3`）· 回复 · 删除（仅本人）· ♥（**`margin-left: auto` 贴右**） |
| ♥ 计数 | `15px` 图标 + 计数，`12px / $text-3`；已赞 `$primary` |

**楼中楼**：不画竖线，靠根评论的头像列自然缩进。回复头像 `26px`、昵称 `12px`、正文 `14px`，meta 同根评论（♥ `13px`）。
**嵌套回复**（`parentId ≠ 楼根 id`）在昵称后加「▶ 被回复人昵称」：`11px / $primary`。**判据与昵称口径遵循 ADR-0001**：`parent_id` 记被回复的那条子回复 id、昵称取自 parent 行；回复楼根时 `parent = root = 楼根 id`、不显示该标签。
**展开相关按钮**：「展开 N 条回复」/「展开更多回复」/「收起」均 `12px / $primary`；「加载更多评论」整行 `100%`，`padding: 14px 0`，`13px / $text-3`。

> **踩过的坑**：评论行是 flex，默认 `align-items: stretch`；头像外壳是 `<button>`，按钮会把内容垂直居中——楼中楼一展开整块变高，**根评论头像会飘到整块中间**。评论行与楼内行必须 `align-items: flex-start`。

### 底部固定栏

`fixed` 居中 `max-width: 480px`，白底，上边 `0.5px $line`，`z-index: 20`。

| 元素 | 规格 |
| --- | --- |
| 输入框 | 真实 `<input>`，胶囊（`$radius-full`），高 `34px`，底 `$bg-search`，`13px`；占位色 `$text-3`、输入色 `$text-1`；Enter 提交 |
| 点赞 | ♥ 图标 `24px` + 计数（`13px`），`$text-1` → 已赞 `$primary` |
| 评论 | 💬 图标 `23px` + 评论数（`13px`） |
| 回复态 | 输入框上方一行「回复 @昵称 + 取消」（沿用现有实现），输入框占位文案换成 `回复 @昵称…` |

**没有发送按钮**：正文下也不另设 action row，点赞/评论只出现在这一条底栏。

### 保留的现有能力（落地不得丢）

- 评论**最热 / 最新**排序
- 楼中楼「展开 N 条回复」、楼内「展开更多回复」、「收起」
- 「加载更多评论」分页
- **本人可删评论**（根评论与楼内回复）、**本人可删帖**（顶栏右侧「联系」换成「删除」）
- **联系**（私信）入口：顶栏右侧，行为与现状一致（`createDirectRoom` → `/chat/{roomId}?card=POST:{id}`）
- 顶栏返回沿用 `NavBar` 的 `goBack` 语义（`history.length > 1` 则 back，否则 `/home`）

### 分享位（票 09 定）

**本 effort 不做分享链路**（用户 2026-09-11 拍板原话：「分享暂时不做了，留个图标就行」）。

- 顶栏右上角**保留右转箭头图标**（`32×32`，方框 + 右上箭头），但它是**纯装饰元素**——不可点、不挂 `@click`、不用 `role="button"`，不弹任何面板。
- 不新增后端端点、表字段或前端依赖；分享行为无埋点。
- ⚠️ 落地时**不要**把它写成无处理函数的 `<button>`——那正是票 04 code-review 揪出的「死件」形态；用非交互元素（`<span>`/`<div>`）承载即可。
- 其它详情页（商品 `/product/:id`、跑腿 `/errand/:id`）**不挂**分享位。

### 明确不做

- **收藏**：底栏只有 点赞 + 评论，不做参考图的「收藏 765」
- **关注**：作者栏没有关注按钮
- **分享链路**：顶栏右上角只保留右转箭头**纯装饰图标**（不可点、无行为）——本 effort **不做**，见「分享位（票 09 定）」

### 可点作者（与票 04 的接口）

顶栏头像/昵称、评论区头像/昵称，**全部可点进作者主页**（作者主页本体见票 04）。详情页只负责给出入口与可点态，不定义主页内容。

### 落地注意

- 现有 `PostDetailView.vue`：顶栏从 `NavBar title="帖子详情"` 换成**作者栏**；底部栏从「输入 + 发送」换成「输入 + 点赞 + 评论」；评论列表要加 `align-items: flex-start`。
- 现有实现里删帖入口在 `NavBar` 的 `#right` 插槽（`isMine()` 时显示）——本规范把它挪到作者栏右侧，判据仍是 `post.userId === auth.userId`。
- 图片区从 `display: flex; flex-direction: column`（竖向平铺）改成轮播容器，`ratio` 由 `dynamic` 改为固定 `4:3`。
- 本规范由**后续实施票**套用；本 effort 不落地代码。

## 作者主页（含隐私开关与资料编辑）

**定稿票**：`.scratch/ui-revamp/issues/04-prototype-author-profile.md`（2026-09-11 选定 **D**）
**原型资产**：throwaway 分支 `prototype/author-profile`（`front/src/components/prototype/`），截图 `.scratch/ui-revamp/proto-04/`
**参考图**：仓库根目录 `55a4b0d413d2fb4ee8c844d69d906455.jpg`（小红书个人主页）、`ScreenShot_2026-09-11_022904_160.png`（评论列表形态）
**适用范围**：**作者主页（新页面）**——从帖子详情、商品详情、跑腿详情、评论区的头像/昵称点入（Q13）

> 本节由**票 04（布局）+ 票 06（数据/接口口径）**共同回填。**以下是票 04 的布局部分**；数据来源、公开资料接口、获赞口径、访客口径过滤、隐私字段与资料编辑接口见节末「数据与接口口径（票 06 定稿）」。

### 选型

选定 **D**＝**A 的页面骨架 + B 的私信位 + 评论/跑腿去分割线 + 右上角「设置」**。原始三候选：

- **A 深色资料头·下划线 tab·白卡瀑布**：深色渐变资料头（左对齐大资料块）+ 吸顶下划线 tab + 双列白卡瀑布流。**D 取其骨架。**
- **B 浅色扁平·chip tab·单列列表**：标准顶栏 + 紧凑资料行 + 横滑 chip tab + 所有 tab 统一单列列表。落选；但**私信位**（资料行右侧行内 chip）被 D 采纳。
- **C 封面横幅·居中·分段控件·无卡瀑布**：封面横幅 + 居中资料（头像压封面边）+ 分段控件 + 无卡片瀑布流 + 悬浮私信。落选（与参考图取向最远，卡片规范也另起一套）。

D 由用户两条翻页指令拼合而成（2026-09-11，原文）：

1. 「我喜欢A的，但是希望把私信按钮放到B的那个位置，也就是头像和昵称的右边，并且评论之间不要有分隔线，自然分割就行」
2. 「跑腿也不要有分割线，隐藏按钮不要放在这里，右上角加一个设置，放设置里，如果这只是演示的话就这样」

### 页面结构（自上而下）

| # | 区块 | 说明 |
| --- | --- | --- |
| 1 | 顶部栏 | 返回（左，`40×40` 圆钮）+ **设置**（右，齿轮 pill）——**设置在深色资料头上**，不占独立白条 |
| 2 | 资料行 | 头像 + 昵称/账号/性别 + **行内私信 chip**（贴行右） |
| 3 | 数字区 | **只有获赞**一个数字 |
| 4 | 个性签名 | 一行，「」包裹 |
| 5 | tab 栏 | **吸顶**（`sticky top: 0`）下划线式，5 个 tab |
| 6 | 内容区 | 按 tab 切换（笔记/赞过=瀑布流，评论=列表，闲置=商品网格，跑腿=列表） |
| 7 | 底部 | **沿用 App 既有 tabbar**（`meta.tabbar`），主页不自绘底栏 |

### 尺寸与色值

| 项 | 值 |
| --- | --- |
| 资料头底色 | `linear-gradient(168deg, #5b6862 0%, #3a423d 55%, #2b302c 100%)`（沿用「我的」页资料头渐变） |
| 资料头内边距 | `6px 20px 26px` |
| 顶栏高 | `44px`；返回/设置同排，返回 `40×40` 圆钮，`rgba(255,255,255,.16)` 按下态 |
| 设置 pill | 齿轮 `17px` + 「设置」`13px / 500 / #fff`，`padding: 0 13px`，`$radius-full`，底 `rgba(255,255,255,.16)` |
| 头像 | `72px` 圆形 |
| 昵称 | `21px / 600 / #fff` |
| 账号 | `12px / rgba(255,255,255,.66)`，`margin-top: 5px` |
| 性别 | `18px` 圆底 `rgba(255,255,255,.22)`，`margin-top: 7px` |
| 私信 chip | 高 `32px`，`padding: 0 15px`，`$radius-full`，底 `$primary`，`13px / 600 / #fff` |
| 获赞数字 | `17px / 600 / #fff` + 「获赞」`12px / rgba(255,255,255,.75)`，`margin-top: 20px` |
| 个性签名 | `13px / 1.5 / rgba(255,255,255,.86)`，`margin-top: 10px` |
| 内容面板 | `margin-top: -14px`（压住资料头下沿），`border-radius: 14px 14px 0 0`，白底 |
| tab 栏 | 高 `46px`，`16px`，tab 间距 `26px`；选中 `550` + `22×3` 圆角下划线（`$primary`，`bottom: 1px`） |
| 页面底部预留 | `$tabbar-h + env(safe-area-inset-bottom) + 12px` |

### tab 与内容形态

| tab | 形态 | 说明 |
| --- | --- | --- |
| 笔记 | 双列瀑布流 | 白卡，**完全沿用「信息流分隔与卡片规范」节**（灰底 `#f2f2f5` + 白卡圆角 `12` + 间距 `8` + 图片 `1:1`；无封面帖走渐变卡面） |
| 赞过 | 双列瀑布流 | **帖子列表**，同一套卡面 |
| 评论 | 列表 | **评论列表**形态（见下） |
| 闲置 | 双列网格 | 商品卡：图 `1:1` → 标题 `14/500` → 价格 `15/700 $primary` + 成色 `10/$text-3` → meta（头像 `16` + 昵称 +「N 人想要」`10/$text-3`） |
| 跑腿 | 列表 | 类型 badge（`10/600 $primary`，底 `rgba(255,36,66,.1)`，圆角 `5`）→ 描述 `14` → 路线块（底 `$bg-secondary`，`11/$text-2`，`→` 为 `$text-4`）→ meta（头像 `16` + 昵称 + 时间） |

**tab 集合口径**：与「我的」页对齐 = **笔记 / 赞过 / 评论 / 闲置 / 跑腿**（顺序以本规范为准）。⚠️ 注：现行 `ProfileView.vue` 的实际顺序是「笔记/赞过/评论/**跑腿/闲置**」，落地时以本节顺序为准并同步「我的」页。

### 评论列表（「评论」tab）

参照 `ScreenShot_2026-09-11_022904_160.png`，但**不放「取消公开」按钮**（Q14 已废弃）。

| 元素 | 规格 |
| --- | --- |
| 行内边距 | `14px 16px`，**评论之间不画任何分割线**（相邻两条 28px 留白自然分隔，用户明示） |
| 头像 | `34px` 圆形 |
| 昵称 | `13px / $text-2` |
| 正文 | `15px / 1.5 / $text-1`，`margin-top: 4px` |
| 来源行 | `margin-top: 6px`，`11px / $text-3`：「来自笔记」（`$blue`）+ `·`（`$text-4`）+ 帖子标题（超长省略） |
| 底部行 | `margin-top: 8px`，`11px / $text-3`：时间 + ♥ 点赞数（**贴右**） |

**跑腿列表同样不画分割线**（用户明示「跑腿也不要有分割线」），与评论同一口径：`14px` 行距承担分隔。

### 隐私开关（Q9）

- **两个全局开关**：`公开我的赞过` / `公开我的评论`（默认**开**）。⚠️ 票 04 早先写作「闲置 / 跑腿」，票 06 定稿改为「赞过 / 评论」（理由见「数据与接口口径」节）。
- 关闭后对应 tab **仍然展示**，点进去渲染统一空态 **「该用户已隐藏此内容」**（复用 `EmptyState`）；开关**仅他视生效**，本人不受影响。
- 入口：**右上角「设置」**（齿轮 pill）→ 底部面板「隐私设置」。面板：遮罩 `rgba(0,0,0,.4)`；面板白底、`border-radius: 14px 14px 0 0`、`width: min(100%, 480px)` 居中；标题 `15/600`；开关行高 `50px`、行间 `0.5px $line`；开关 `44×26` 胶囊（关 `#d9d9de`、开 `$primary`，白色圆钮 `22`）。
- **自视 / 他视口径**：设置入口属于**本人**。落地为**本人看自己主页才显示设置入口**，访客看不到（用户 2026-09-11 确认「就这样」）。原型里为了演示，两种开关都挂在同一页上。

### 明确不做

- **关注 / 粉丝 / 收藏**：数字区只显获赞；操作区只有私信，**没有关注按钮**（map Out of scope）
- **单条评论「取消公开」**：Q14 已废弃（map Out of scope）
- **属地 / IP 展示**：无数据源，另立 effort
- **头像 / 昵称编辑**：资料编辑只做**签名 + 性别**（昵称改动牵动全站展示口径，另立 effort）

### 数据与接口口径（票 06 定稿）

**定稿票**：`.scratch/ui-revamp/issues/06-author-profile-privacy-contract.md`

#### 公开资料接口

- `GET /api/user/{userId}/profile`（免登录）→ `userId`、`nickname`、`avatar`、`signature`、`gender`（`MALE`/`FEMALE`，可空＝不展示）、`receivedLikeCount`、`showLiked`、`showComments`。
- **不返回手机号**：作者主页资料行**去掉「账号」行**（改为 昵称 + 性别 + 签名）。手机号只留在「我的」页本人视角（线上「账号」渲染的就是手机号，`ProfileView.vue`）。
- **获赞口径** `receivedLikeCount` = 该用户 `status='NORMAL'` 帖子的 `like_count` 之和（**不含**评论获赞，**不含**待审/驳回/草稿帖）；与「我的」页「获赞」同口径（票 05）。
- `showLiked`/`showComments` **始终返回**，前端据此渲染「该用户已隐藏此内容」。

#### 按用户列内容（五个 tab 一律「看别人」）

新增一组**公开只读**接口，不给现有 `/mine|liked|commented` 加 `userId` 参数（避免私有状态泄露）：

| tab | 接口 | 访客口径过滤 |
| --- | --- | --- |
| 笔记 | `GET /api/community/users/{userId}/posts` | 该用户 `NORMAL` 帖 |
| 赞过 | `GET /api/community/users/{userId}/liked` | 该用户赞过且**源帖 `NORMAL`**，按点赞时间倒序 |
| 评论 | `GET /api/community/users/{userId}/comments` | 该用户 `NORMAL` 评论**且源帖 `NORMAL`**，按评论时间倒序 |
| 闲置 | `GET /api/trade/users/{userId}/products` | `status='ON_SALE'`（卖家视角） |
| 跑腿 | `GET /api/errand/users/{userId}/orders` | `publisher_id={userId}` 且 `status IN ('PUBLISHED','TAKEN')`（**只列发布、不含接单**，2026-09-11 确认） |

- 全部免登录、`page`/`pageSize`（≤50）、时间倒序。
- **本人看自己主页也走公开口径**（草稿/驳回只在「我的」页出现），保住「作者主页」语义单一。

#### 「评论」tab = 评论列表

- 数据源＝`GET /api/community/users/{userId}/comments`，每项 `{ commentId, content, likeCount, createdAt, postId, postTitle, postCover }`（`postTitle`/`postCover` JOIN `post` 取）。
- **「我的-评论」与作者主页-评论共用**同一接口与同一形态；旧 `/posts/commented`（本人视角、按帖子聚合）**保留**——`SearchView` 的 `scope=comments`（搜索我评论过的帖子）仍用它。
- **跳转锚定**：点「来自笔记 · 标题」→ `/post/{postId}?commentId={commentId}`，详情页据 `commentId` 定位高亮（若不在首页已加载范围，落地时按 `commentId` 拉取定位）。

#### 隐私开关（两个全局开关，**赞过 / 评论**）

> ⚠️ **口径修订**：票 04 原型与本文档早先写作「公开我的闲置 / 公开我的跑腿」；票 06 定稿改为「**赞过 / 评论**」——闲置/跑腿的已发布内容本就在市集/跑腿列表公开可搜，主页隐藏近乎零隐私收益；赞过/评论暴露的是浏览与互动轨迹，才是真正需要开关的。（原型里那两个开关当时只是占位演示。）

- **字段**：`bs_user.show_liked` / `bs_user.show_comments`（`tinyint(1) DEFAULT 1`）。
- **默认公开**；存量用户由 `DEFAULT 1` 补齐。
- **生效口径＝仅他视**：`viewer ≠ owner` 且开关关 → 对应 tab 返回空列表、前端渲染「该用户已隐藏此内容」（复用 `EmptyState`）；`viewer = owner` **不受影响**。
- **明确不做**：单条评论「取消公开」（Q14）。

#### 资料编辑（签名 + 性别）

- `PUT /api/user/profile`（需登录，改当前用户）：部分更新 `signature?` / `gender?`。
- **签名**：可空、≤40 字（`bs_user.signature varchar(60)`，按字符校验并 trim）；空串＝不展示。
- **性别**：`MALE`/`FEMALE`，**`NULL` = 不展示**（不另设第三个枚举值）；`bs_user.gender varchar(10) NULL`。
- 隐私设置写接口：`PUT /api/user/privacy`（部分更新 `showLiked?`/`showComments?`）。
- 入口＝「我的」页设置 → 编辑资料 / 隐私设置。

#### 路由与鉴权

- 前端新路由 `/user/:userId` → `AuthorProfileView`（免登录可浏览）。
- 后端新 GET 接口补进 `SecurityConfig` 白名单：**`GET /api/user/*/profile` 必须补**（现白名单只放行 user 的三个 POST）；community/trade/errand 的新 GET 若已被 `GET /api/community/**` 那类匹配覆盖则不动（落地时核）。
- 私信 chip：访客需登录（`createDirectRoom`），未登录 → 跳登录；**本人看自己主页不显示私信 chip，显示「设置」入口**（票 04 已定）。

#### 落地波及（表与文档）

- 迁移 `sql/migrations/021_user_profile_and_privacy.sql`：`bs_user` 加 `signature`/`gender`/`show_liked`/`show_comments`；`post_comment` 补 `idx_user_status_created (user_id, status, created_at)`（按用户列评论）。`init.sql` 已原地同步。
- 文档：新建 `backend/docs/db/user.md`、`backend/docs/api/user.md`；`docs/api/community.md`（「按用户查询」节）、`docs/api/trade.md`、`docs/api/errand.md`、`docs/db/community_comment.md` 已补条目。
- `UserMapper.updateById` 现仅 SET `nickname/avatar/last_login_at`——落地资料编辑时需扩列（勿把 role/status/credit_score 卷进来）。

### 落地注意

- 本规范由**后续实施票**套用；本 effort 不落地代码。原型代码留在 throwaway 分支 `prototype/author-profile` 备查。
- 作者主页是**新页面**，需新增路由；入口是各详情页与评论区的头像/昵称（票 02 已在「帖子详情布局」节留好可点作者接口）。
- 「笔记/赞过」的卡面**复用「信息流分隔与卡片规范」节**，不要另起一套。
- 作者主页头部与「我的」页头部的**组件复用边界**（票 05 出稿后已定）：**不抽同一个组件**，只共用样式规范（深色渐变资料头 + `46px` 吸顶下划线 tab + 卡片规范）。两页操作区本就不同（他视是私信 chip、自视是钱包/信用分/审核入口 + 设置入口），且分属两张落地票；抽成带 slot 的组件只多一层间接。详见「"我的"页状态停留与列表加载修复」节的落地注意。

## 市集求购前端链路

**定稿票**：`.scratch/ui-revamp/issues/07-want-to-buy-frontend-flow.md`（2026-09-11，两轮 frontier 11 问全部「按你推荐的」）
**适用范围**：市集第三栏「求购」、求购详情页、发求购页、「我的-闲置-我求购的」
**卡面/分隔**：列表沿用「信息流分隔与卡片规范」节的**求购卡**（顶行「求购」badge + 分类 → 标题 → 描述 → 预算 → meta）

### 0. 后端缺口与新增契约（票 07 改口径）

票面原假设「求购后端已全链路」，实测有 **4 处**缺口，本票定为**需补**：

| # | 缺口 | 补法 |
| --- | --- | --- |
| 1 | **无单取接口**：闲置有 `GET /trade/products/{productId}`（`ProductController.java:137`），求购只有列表 `/want-to-buy`（恒 `NORMAL`、只接 `category`）与 `/want-to-buy/mine`（全状态） | 新增 `GET /api/trade/want-to-buy/{wantId}`：**免登录**；`NORMAL` 对外可见，**本人**另可见全部状态 + `rejectReason`（复用 `WantToBuyResponseDTO`） |
| 2 | **`WantToBuyResponseDTO` 无头像**（只有 `nickname`），与票 01 求购卡「头像 + 昵称 + 时间」冲突 | mapper 三处查询已 `LEFT JOIN bs_user u`（`WantToBuyMapper.xml:20/164/186`），补 `u.avatar` + DTO 加 `avatar` 字段 |
| 3 | **`GET /want-to-buy` 无排序**（`WantToBuyMapper.xml:160-180` 恒 `created_at DESC`），与 `GET /products` 不一致 | 新增可选 `sort`＝`time`(默认)/`price_asc`/`price_desc`（价格即预算），照 `ProductMapper.xml:214-237` 同构 |
| 4 | **被拒后无「转草稿重发」**：商品有 `POST /products/{productId}/to-draft`（`ProductController.java:130`），求购无；`WantToBuyStatusVO` 把 `REJECTED` 定为终态（「不进草稿箱」），而编辑端点只认 `NORMAL` | 照商品新增 `POST /api/trade/want-to-buy/{wantId}/to-draft`：`REJECTED → DRAFT`，可编辑后重新提交 |

另修前端类型：`types/api.ts` 的 `WantToBuy` 缺 `images`、`rejectReason`（后端已返回）。文档需同步 `backend/docs/api/trade.md`。

求购状态机（后端已定，前端只呈现）：

```
DRAFT ─(submit)→ PENDING_PAYMENT ─(pay，冻结预算)→ PENDING_REVIEW ─(通过)→ NORMAL
                                                     └─(拒，自动退款)→ REJECTED
withdraw:  PENDING_REVIEW ─→ DRAFT（退冻结预算）
takedown:  NORMAL         ─→ DRAFT（退冻结预算）
to-draft:  REJECTED       ─→ DRAFT（照商品补，§0-4）
edit:      NORMAL 可改（PUT /{id}）→ PENDING_REVIEW（不重新支付/冻结）；预算、分类不可改
           DRAFT  可改全字段（PUT /{id}/draft）
```

### 1. 市集第三栏「求购」

- `MarketView.vue` 的 `activeTab` 由 `'errand' | 'idle'` 扩为 `'errand' | 'idle' | 'want'`；顺序 **跑腿 / 闲置 / 求购**，**默认跑腿**（现状不变）。
- **筛选**：分类 chips 同闲置（`DIGITAL/BOOKS/DAILY/SPORTS/TICKETS/OTHER` + 「全部」）→ `GET /want-to-buy?category=`。
- **排序**：`最新 / 价格↓ / 价格↑` 三按钮（同闲置UI）→ `sort=time|price_asc|price_desc`。
- **卡片**：`WantToBuyCard.vue`，按票 01 求购卡（预算绿色 `#1f9d4d`、描述最多 3 行、图 1:1）。列表只出 `NORMAL`。
- **空态**：`EmptyState`「还没有求购」+ 「去发求购」→ `/publish/want`。
- **加载时机**：**切到哪个 tab 才加载哪个**（承接票 05 载体定案）；跑腿 / 闲置 / 求购**各自独立**的分页、`finished` 状态——与票 08 的 `useWaterfall` / store 合流口径见「"我的"页状态停留与列表加载修复」节的「列表加载与页面状态（票 08 定）」子节（**票 08 已定稿**）。

### 2. 求购详情页（`/want/:id(\d+)`，新）

- **数据**：`GET /api/trade/want-to-buy/{wantId}`（§0-1）。路由 `meta` 不带 `requiresAuth`（访客可看，同 `/product/:id`）。
- **布局**（与商品详情**同构的只读简化版**）：图片相册 → 标题 → 预算（`预算 ¥ 金额`，`#1f9d4d`）→ 分类 tag → 描述 → 发布者行 → 发布时间。
- **图片相册**：复用票 02 的**轮播**规范（通栏、左右滑动、圆点 + `n/N`，最多 6 张）——求购是「相册」语义，比商品详情的单图更贴。
- **发布者行**：头像 + 昵称，点击 → `/user/:userId`（票 06 路由，**待票 06 落地**）。
- **无**收藏 / **无**下单 / **无** `viewCount`、`favoriteCount`（求购无这些概念）。
- **双模式**（操作区判据＝票 03「下单支付操作区」节）：

| 状态 | 视角 | 分支 | 底栏分格（左→右） |
| --- | --- | --- | --- |
| NORMAL | **访客** | 等分底栏 | **私信联系**（单格满宽） |
| DRAFT | 发布者 | 等分底栏 | 删除 / 编辑 / **去支付**（＝`submit`+`pay` 两步合一） |
| PENDING_PAYMENT | 发布者 | 等分底栏 | 待付预算 / 删除 / **去支付** |
| PENDING_REVIEW | 发布者 | 等分底栏 | 冻结预算 / **撤回** |
| NORMAL | 发布者 | 等分底栏 | 冻结预算 / 下架 / **编辑** |
| REJECTED | 发布者 | 等分底栏（顶部状态条含驳回原因） | 删除 / **转草稿** |
| CANCELLED | 发布者 | 状态卡（纯等待） | — |

- **求购对票 03 的补充**：底栏**至多 3 格**；操作按「次 → 主」排，每状态**至多 1 个主操作**。金额格按状态取词：`PENDING_PAYMENT`＝**待付预算 ¥X**（**尚未冻结**，同票 03「应付金额」写法），`PENDING_REVIEW` / `NORMAL`＝**冻结预算 ¥X**（`pay` 时才冻结）；`DRAFT` / `REJECTED` / `CANCELLED` 不放金额格。
- 「求购中」**不直接给删除**——先**下架**（退回冻结预算、回到草稿）再删，避免一键毁掉在架求购。
- 撤回 / 下架后提示「冻结预算已退回余额」；`REJECTED` 的驳回原因（`rejectReason`）进状态条，并注明「冻结预算已全额退回」（`ReviewCase` 拒审即退款）。「**转草稿**」走 §0-4 的 `to-draft`，toast「已转入草稿，可编辑后重新提交」（同帖子草稿箱文案）。
- **状态文案**（发布者视角六态）：草稿 / 待支付 / 待审 / 求购中 / 审核未通过 / 已取消。

### 3. 发求购入口与流程

- **入口**：`PublishIndexView` 加第 4 项「发求购」→ `/publish/want`。
- **页面**：**新建 `WantPublishView.vue`**（不复用 `ProductPublishView`——字段与动作都不同）。
- **表单**：标题（≤30，必填）/ 描述（≤200）/ 配图（`ImageUrlPicker` ≤6）/ 预算（≥0.01，必填）/ 分类（必选，同闲置六类）。
- **两动作**：
  - 「**发布并去支付**」→ `POST /want-to-buy`（全量校验）→ 得 `PENDING_PAYMENT` → `replace('/want/'+wantId)`；
  - 「**保存草稿**」→ `POST /want-to-buy/draft` → `DRAFT` → 跳「我的-闲置-我求购的」。
- **编辑态**：同一页 `WantPublishView` 读 query 切 create/edit，**两个编辑端点决定可改字段**：
  - `/publish/want?draft={wantId}`（**草稿编辑**，镜像帖子 `/publish/post?draft=` 惯例）→ **全部字段可改**（含预算、分类）→ `PUT /want-to-buy/{wantId}/draft`；
  - `/publish/want?edit={wantId}`（**求购中编辑**）→ **仅标题 / 描述 / 配图可改** → `PUT /want-to-buy/{wantId}`，保存后 `NORMAL → PENDING_REVIEW`。
  - 后端 `WantToBuyEditRequestDTO` **无 `category`**；预算传不等值即报错「求购预算不支持修改，请下架后重新发布」。两态都需先 `GET /want-to-buy/{wantId}` 取原值回填。
- **支付链路**（复用跑腿「发布 → 支付 → 待审」）：详情页 `PENDING_PAYMENT` 渲染「去支付」→ `POST /pay`（**此时才冻结预算**）→ `PENDING_REVIEW`；`DRAFT` 的「去支付」＝先 `POST /submit`（`DRAFT → PENDING_PAYMENT`）再 `POST /pay`。等待态文案「发布成功，预算已冻结，审核通过后对外可见」（对齐 `ErrandDetailView` 的 `PENDING_REVIEW` 提示）。交互用 `doAction(fn, msg, confirm?)` + `load()` 模式。
- **终局**：通过 → `NORMAL`（站内通知已存在）/ 被拒 → `REJECTED`（**自动退款** + 通知，`ReviewCase` 已有）。
- **超时**：未支付 15 分钟自动 `CANCELLED`（后端定时任务已有），前端**只显状态、不做到秒倒计时**。
- **编辑重审**：保存后 `NORMAL → PENDING_REVIEW`（不重新支付/冻结）→ **不新增站内通知**，仅前端 toast「已提交审核」；重审结果走原有「审核结果」通知。

### 4. 「我的-闲置-我求购的」

- **归属**：**留在「闲置」子 tab**（`ProductTab` 的 `'want'`），不新增主 tab。
- **列表**：`GET /want-to-buy/mine`（返回**全部状态**，时间倒序）→ 行内状态徽章（草稿/待支付/待审/求购中/审核未通过/已取消），被拒行显示原因。
- **行样式**：沿用现 `.errand-row` 紧凑行（与「我发布的」同构）。**动作统一放详情页**，列表行不放行内动作（与闲置「我发布的」一致）。
- **修死链**：整行点击 `/market` → `/want/:id`；空态按钮 `/publish/product` → `/publish/want`。
- **与帖子「草稿箱」的分野**：`/me/post-drafts` 草稿箱是**帖子专属**惯例；交易域（闲置 / 求购）一律在「我的」列表内联全部状态，**不建求购草稿箱**。

### 5. 复用的既有交互清单

| 需求 | 复用 |
| --- | --- |
| 配图上传 | `ImageUrlPicker`（≤6） |
| 空态 | `EmptyState`（`text` + `buttonText` + `click`） |
| 顶栏 | `NavBar`（`title`） |
| 卡片 / 分隔 | 票 01「信息流分隔与卡片规范」求购卡 |
| 支付 + 等待态 | 跑腿 `ErrandDetailView` 的 `doAction(fn, msg, confirm?)` + `load()` |
| 操作区二分 | 票 03「下单支付操作区」判据（有操作→等分底栏 / 纯等待→状态卡） |
| 图片相册 | 票 02 轮播规范 |
| 私信 | `createDirectRoom` → `/chat/{roomId}`（**无卡片**，跑腿先例） |
| 发布者主页 | `/user/:userId`（票 06，待落地） |

### 依赖与落地注意

- **依赖票 06**：`/user/:userId` 路由与公开资料接口（票 06 为 spec-only，尚未落地）——求购详情页的「点发布者进主页」在其后。
- **依赖票 08**（**已定稿**）：市集三 tab 的懒加载与分页/`finished` 载体合流口径见「列表加载与页面状态（票 08 定）」子节。
- **后端 3 处新增（§0）**需在实施票落地，并同步 `backend/docs/api/trade.md`。
- 本 effort **只产出决策与 spec，不落地代码**。

## 下单支付操作区

**定稿票**：`.scratch/ui-revamp/issues/03-prototype-order-action-area.md`（2026-09-11 选定 **D**）
**原型资产**：throwaway 分支 `prototype/order-action-area`（`front/src/components/prototype/`），截图 `.scratch/ui-revamp/proto-03/`
**适用范围**：商品订单支付页 `/trade/order/:id`（`ProductOrderView.vue`）

### 选型

选定 **D**——不是一种固定底栏，而是一条**按「当前角色有没有可点的操作」二选一**的规则：

| 分支 | 形态 | 判据 |
| --- | --- | --- |
| **有操作** | 顶部状态条 + 底部**等分底栏** | 该「状态 × 角色」下存在可点操作（主按钮或次按钮） |
| **纯等待 / 终态** | 顶部**状态卡**，**不设底栏** | 没有可点的操作 |

判据落在 `orderActions(status, role)` 是否返回 `primary` 或 `secondary`。**给没有操作的组合也挂一条空底栏**，正是线上「渲染出一个空操作条」的根源；反过来**把按钮塞进状态卡**，主操作会缩在页面顶部够不着。

三个原始候选（票面点名的「可考虑方向」各落其一）：

- **A 分层底栏**（金额抽成独立行 + 次操作降级文字链 + 主按钮整行）：落选。竖三层占位最高，纯等待态还得另做一套。
- **B 状态卡内联**（状态／金额／说明／按钮合并进一张顶卡）：被采纳为**「纯等待」分支**。
- **C 等分底栏**（金额／次操作／主操作各占 1/3 一行排开）：被采纳为**「有操作」分支**。

用户 2026-09-11 的翻页指令（原话）：

1. 「待支付买家选C，但是取消订单放到确认支付的左边，价格和取消和确认各站底部三分之一」
2. 「待支付卖家选B」
3. 「其他的你标签分类的有问题吧」——状态文案原本是按买家视角写的一张裸映射（见「状态文案必须按角色分」）
4. 规则收口：「有操作→C / 纯等待→B」

### 组合表（15 个：5 状态 × 买家/卖家/访客）

| 状态 | 角色 | 分支 | 金额标签 | 次操作 | 主操作 | 等待文案 |
| --- | --- | --- | --- | --- | --- | --- |
| PENDING_PAYMENT | 买家 | 等分底栏（3 格） | 应付金额 | 取消订单 | 确认支付 | — |
| PENDING_PAYMENT | 卖家 | 状态卡 | 预计收款 | — | — | 等待买家付款 |
| PENDING_PAYMENT | 访客 | 状态卡 | 订单金额 | — | — | 等待买家付款 |
| PAID | 买家 | 等分底栏（2 格） | 货款（托管中） | 取消订单 | — | — |
| PAID | 卖家 | 等分底栏（2 格） | 货款（托管中） | — | 标记已交付 | — |
| PAID | 访客 | 状态卡 | 货款（托管中） | — | — | 等待卖家交付 |
| DELIVERED | 买家 | 等分底栏（2 格） | 待付货款 | — | 确认收货 | — |
| DELIVERED | 卖家 | 状态卡 | 待收货款 | — | — | 等待买家确认收货 |
| DELIVERED | 访客 | 状态卡 | 订单金额 | — | — | 等待买家确认收货 |
| COMPLETED | 任意 | 状态卡 | — | — | — | —（说明已讲完） |
| CANCELLED | 任意 | 状态卡 | — | — | — | —（说明已讲完） |

- **每组合至多一个主操作**：`orderActions` 每个分支最多给一个 `primary`。
- **缺格均分**：需要 3 格时各占 `1/3`；只有金额 + 一个按钮时（`PAID·买家`）两格各占 `1/2`。**取消订单恒定排在主操作左侧**。
- `COMPLETED` 的说明按角色分（卖家「货款已转给你」，其余「转给卖家」）；`CANCELLED` 与角色无关。

### 有操作分支：等分底栏

`fixed` 居中 `max-width: 480px`，白底，上边 `0.5px $line`，`z-index: 20`。

| 项 | 值 |
| --- | --- |
| 底栏内边距 | `10px 12px`，底部叠加安全区 `calc(10px + env(safe-area-inset-bottom))` |
| 分格 | `flex: 1` 等分，`gap: 8px`，高 `44px` |
| 分格顺序 | 金额 → 次操作 → 主操作 |
| 金额块 | 竖排居中：金额 `16px / 600 / $primary`，标签 `11px / $text-3` |
| 次按钮 | 复用 `.btn-outline`：`border #ddd`、文字 `$text-2`；危险操作改 `border rgba($danger,.4)`、文字 `$danger` |
| 主按钮 | 复用 `.btn-primary`，高 `44px` / `14px`；**文案只写动词**（`确认支付` / `确认收货` / `标记已交付`），**金额不重复进按钮** |
| 状态条 | 沿用现有 `.status-hero`：标题 `20px / 600`，说明 `12px / $text-3` |

> **坑 1｜`safe-bottom` 会被压掉**：不要给这个底栏挂全局 `.safe-bottom`——scoped `.bar[data-v-x]` 权重高于它，简写 `padding` 会把 `padding-bottom: env(...)` 覆盖掉，固定栏沉到 iPhone home indicator 底下。安全区要写进自己的 `padding`。
> **坑 2｜`.cell` 是全局类名**：分格不能叫 `.cell`——`common.scss` 有同名全局类（列表行，`background: #fff`），同权重且源序在后，会把 `.btn-primary` 的红底盖成白底白字，**按钮整个隐形**（DOM 在、看不见）。

### 纯等待分支：状态卡

无底栏；一张顶卡，白底 + 下边 `0.5px $line`，内边距 `16px 16px 14px`。

| 元素 | 规格 |
| --- | --- |
| 状态 + 金额 | 同一行两端对齐；状态 `20px / 600`；金额＝标签 `11px / $text-3` + 金额 `19px / 600 / $primary` |
| 说明 | 距上 `4px`，`12px / $text-3` |
| 等待文案 | 距上 `12px`，`13px / $text-2`；**终态不渲染**（说明已讲完，再补一行是重复） |

### 状态文案必须按角色分

**本票揪出的真 bug**：线上 `STATUS_TEXT` 是一张「状态 → 词」的裸映射，全按买家视角写。卖家点进待支付单，顶部显示「待支付」＋「15 分钟内未支付将自动取消」——**卖家没有要支付的钱**，访客也没有。落地时状态标题与说明都必须按角色取词：

| 状态 | 买家 | 卖家 | 访客 |
| --- | --- | --- | --- |
| PENDING_PAYMENT | 待支付 | 待买家付款 | 待买家付款 |
| PAID | 待卖家交付 | 待交付 | 待卖家交付 |
| DELIVERED | 待收货 | 待买家确认 | 待买家收货 |
| COMPLETED | 已完成 | 已完成 | 已完成 |
| CANCELLED | 已取消 | 已取消 | 已取消 |

说明文案（`statusNote`）同理：催付款／催确认的话只对买家说；对卖家说「能拿到什么」（`货款已托管冻结，交付后转给你`、`交易完成，货款已转给你`），不重复买家的词。

### 保留的现有能力（落地不得丢）

- 四个操作的后端调用与确认弹窗文案不变（`payProductOrder` / `markDeliveredProduct` / `confirmProductOrder` / `cancelProductOrder`）
- 金额仍取订单 `price`；`busy` 期间的禁用态保留
- 页面其余部分（商品卡、订单编号／时间、买卖双方卡）不动

### 同构复用：跑腿订单详情页（本票不出图）

`views/errand/ErrandDetailView.vue` 的底部操作区是**同一套**写法（`.action-bar` / `.action-group` + `btn-outline` / `btn-primary`），且组合更多（多人单的组队／补位／退出任务／进入协作群／发起争议）。落地时**复用本节规范**：同一处「有操作 → 等分底栏 / 纯等待 → 状态卡」二分、次操作恒在主操作左侧、主操作只写动词、状态文案按角色分。跑腿的可点操作可能超过 2 个，届时按「至多 3 格、多余次操作收进更多」再定，属后续实施票范围。

### 明确不做

- 不做「更多」收纳——本票 11 个有按钮的组合里可点操作最多 2 个，收纳只会多一次点击
- 不改状态机判据（谁能操作仍由既有后端逻辑决定），本票只改呈现

### 落地注意

- `ProductOrderView.vue` 的 `<footer>` 按上述二分重写；建议把 `statusText(status, role)` / `statusNote(status, role)` / `orderActions(status, role)` 落到该页或一个 `utils/orderAction.ts`（原型里在 `components/prototype/orderActionMock.ts`）
- 线上 `DELIVERED + 访客` 现在渲染**空操作条**（`isBuyer` / `isSeller` 两个分支都不命中，`<div class="action-group">` 里什么都没有）——按「纯等待」分支给等待文案即可修掉
- 同样的裸映射还复制在两处列表页：`views/trade/MyProductOrderView.vue` 的 `STATUS_TEXT`、`views/ProfileView.vue` 的 `PRODUCT_ORDER_STATUS_TEXT`，两处都是买家视角。列表里同一张单对买卖双方都会出现，落地时一并按角色取词

### 生产构建口径

原型代码全部在 `import.meta.env.DEV` 后，生产构建 `DEV=false` → 变体与切换器**都不可能渲染**（免登录旁路同样被编译掉）。但变体组件是静态 import、未被 tree-shake，以死代码留在 bundle 里：`ProductOrderView` chunk 从 master 的 **5.13 kB（gzip 2.12 kB）** 涨到 **16.19 kB（gzip 4.92 kB）**，CSS 从 **1.30 kB** 涨到 **7.50 kB**。只发生在 throwaway 分支上。

## "我的"页状态停留与列表加载修复

**定稿票**：`.scratch/ui-revamp/issues/05-prototype-my-page-state.md`（2026-09-11 选定 **头部 A + 载体 store**）+ `.scratch/ui-revamp/issues/08-list-state-loading-fixes.md`（2026-09-11 定列表加载与页面状态口径）
**原型资产**：throwaway 分支 `prototype/my-page-state`（`front/src/components/prototype/`），截图 `.scratch/ui-revamp/proto-05/`
**适用范围**：`/profile`「我的」页（`ProfileView.vue`）+ 市集三 tab（`MarketView.vue`：跑腿 / 闲置 / 求购）

### 选型

**头部选定 A（深色资料头 · 卡片入口，「现状微调」）**：沿用现有深色渐变资料头 + 钱包/信用分卡片，只加**个性签名（含性别）**、右上角**设置入口**，并把「获赞与收藏」收口成「获赞」。与「作者主页」（票 04 选定 D）**同源**——两页共用同一套资料头样式规范。

落选：B（深色居中 · 三格数字统计条）把钱包/信用分降成数字、管理员入口降成一行文字，紧凑但削弱了资产入口的可点性；C（浅色资料头 · 列表入口）与作者主页的深色头不同源，两页观感会分裂。

**tab 停留载体选定 (b) 前端 store / 模块级缓存**：

| 载体 | 刷新后 | tabbar 去市集再回来 | 切回来时 | 结论 |
| --- | --- | --- | --- | --- |
| (a) URL query | 保留 | **丢**（tabbar 的 `/profile` 不带 query） | 组件重建 | 落选 |
| **(b) store / 模块级缓存** | 丢 | **保留** | 组件重建 | **选定** |
| (c) keep-alive | 丢 | 保留 | 不重建、不重新请求 | 落选 |

- **(a) 落选**：既然「跨 tabbar 切换保留」已定（见下），(a) 开箱即坏——底部 tabbar 是写死的 `<router-link to="/profile">`，回「我」时 query 已丢，tab 掉回「笔记」（原型实测）。要修得让全局 tabbar 带 query，代价与收益不成比例。可分享链接 / 刷新不丢在本 App 不是硬需求。
- **(c) 落选**：独占好处只有「不重建、不重新请求」，但要引入全局 `router-view` 改造 + 按组件名字符串登记 `include`（易漏易错）；且会与本节下半的列表状态打架——分页 / finished / 滚动位置放进缓存组件后多出「缓存何时过期」的问题。它宣称的「滚动位置可保」在本仓**不自动成立**（页面滚动挂在 window 上，需另配 `scrollBehavior`）。
- **选 (b) 的额外理由**：票 08 需要的「跑腿 / 闲置 / 求购各自独立的分页、finished、滚动位置」当时预期「本就必须放在组件外才好复用」，选 (b) 等于**一次把 08 的地基也打了**。
- ⚠️ **票 08 修订上述预期**：08 定稿后，「分页 / 数据 / 滚动位置」留在**组件内**（每 tab 一个 `useWaterfall` 实例），**不进 store**；进 store 的只是**tab 选择**（市集 `activeTab` + 「我的」页三 ref）。故 (b) 的「地基」作用落在 **tab 选择**，不落在列表数据——(b) 依然是对的选择（跨 tabbar 保留 tab 位），只是理由要按此收窄。

> 将来若要「刷新不丢 / 链接可分享」，可在 (b) 的存储模型上**再叠一层 URL 同步**，不必推翻重来。本 effort 不做。

### 头部规范（选定 A）

在现有 `ProfileView.vue` 头部基础上改动：

| # | 元素 | 规格 |
| --- | --- | --- |
| 1 | 资料头底色 | 不变：`linear-gradient(168deg, #5b6862 0%, #3a423d 55%, #2b302c 100%)` |
| 2 | 个性签名 | 昵称/账号下方新增一行，`13px / 1.5 / rgba(255,255,255,.86)`，距上 `12px` |
| 3 | 性别 | 跟账号同一行，`11px` 圆底 `rgba(255,255,255,.22)` 白字（与票 04 资料区同口径） |
| 4 | 设置入口 | 右上角「设置」：齿轮 `15px` + 文字 `13px / 500 / #fff`，`padding: 0 13px`，`$radius-full`，底 `rgba(255,255,255,.16)` |
| 5 | 数字区 | **只显「获赞」**；管理员卡片（审核工作台）照旧保留 |

- **「获赞与收藏」→「获赞」**：数值 = **自己笔记被赞数之和**，**删掉原公式里的商品收藏数**（现行 `likedTotal = notes.reduce(likeCount) + favorites.length`，落地时去掉后一项）。
- **tab 顺序**：`笔记 / 赞过 / 评论 / 闲置 / 跑腿`——与票 04 作者主页对齐；**现行 `ProfileView` 是「…跑腿 / 闲置」，落地时改序**。
- **「评论」tab** 的形态见票 04 的「作者主页」节（评论列表：评论内容 + `来自笔记 · 标题`，我的与作者主页共用同一形态）。

### 设置入口（内含两页）

| 页 | 内容 |
| --- | --- |
| 编辑资料 | 个性签名（≤40 字）+ 性别（男 / 女 / 不展示）。**昵称 / 头像本次不改**（牵动全站展示口径，另立 effort） |
| 隐私设置 | `公开我的赞过` / `公开我的评论` 两个全局开关，默认公开，只影响他人查看、本人不受影响（票 06 修订 Q9 口径，理由见票 06 与「作者主页」节「数据与接口口径」） |

### tab 停留规范

- **载体 = 模块级 store / 模块级缓存**（前端单例 reactive 或 Pinia store，键为「主 tab + 子 tab」）。
- **主 tab 停留**：笔记 / 赞过 / 评论 / 闲置 / 跑腿。
- **子 tab 停留**：闲置内的（我发布的 / 我收藏的 / 我求购的 / 我购买的）、跑腿内的（我发布的 / 我接的）。
- **子 tab 作用域**：子 tab **只在所属主 tab 内有效**——换主 tab 时旧子 tab 作废、回落到该主 tab 的默认值（`闲置→我发布的`、`跑腿→我发布的`）。不作废会出现「没有任何子 tab 高亮」的破态（原型实测踩到）。
- **跨底部 tabbar 切换：保留**。离开「我的」去「市集」再回来，仍停在原 tab。
- **二级页往返：保留**。钱包 / 信用分 / 草稿箱 / 订单等二级页返回后仍停在原 tab。
- **刷新后回到「笔记」**：接受，这是 (b) 的已知代价。

**明确不做**：

- **URL query 载体**：不做（落选理由见上）。即 URL 不承载 tab 状态、不可分享、刷新不保留。
- **keep-alive（全局 `router-view` 缓存）**：本 effort 不引入。若将来确实需要「切回来不重新请求」，另行评估，并同时解决「缓存何时过期」与滚动位置。

### 列表加载与页面状态（票 08 定）

**定稿票**：`.scratch/ui-revamp/issues/08-list-state-loading-fixes.md`（2026-09-11 一轮 frontier 七问全部采纳）

#### 根因（第 4 项闲置 tab 首屏不加载）

`MarketView.vue` 只在 `onMounted` 建**一个** `IntersectionObserver`，哨兵 `<div ref="sentinel">` 在 `v-if/v-else` **外面**——`IntersectionObserver` 只在交叉状态*变化*时回调，切 tab 不改变交叉状态 → 闲置列表初始为空、哨兵已在视口内，`loadProduct()` 永不触发；只有点分类 chip 走了 `watch` 才兜底加载。**只补一行 `watch(activeTab)` 是治标**，本票连「三份状态独立 + 加载时机归页面」一并修掉。

#### `useWaterfall` composable（抽，落点 `front/src/composables/useWaterfall.ts`）

```ts
useWaterfall<T>(
  fetchPage: (page: number) => Promise<{ list: T[]; total: number }>,
  opts?: { pageSize?: number }
) => { list, loading, finished, loaded, sentinel, load, reset }
```

- **每 tab 一个实例**，各自持有 `sentinel` / observer / `page` / `loading` / `finished`——天然三份独立状态，取代现在的共享式单哨兵。
- `pageSize` 默认 `10`；内部 `IntersectionObserver(rootMargin: '250px')`，`onUnmounted` 自动 `disconnect`。
- **不在 `onMounted` 自动加载**：由页面在「首次进入该 tab」时显式 `load()`——把加载时机的控制权从 observer 收回页面，即第 4 项的根因修法。
- `loaded`：composable 在**首次 `load()` 成功后**置 `true`；页面据它判断「该 tab 是否已加载过」，未加载过才调 `load()`——即「切 tab 触发首屏加载」的判据。
- `reset()`：清空列表与分页、供筛选/排序变更重拉，**同时把 window 滚动归零**。
- **换用范围**：`MarketView.vue` 三 tab + `HomeView.vue`。同一套翻页形状在仓内已**复制三份**（`MarketView` 的跑腿/闲置两个 loader + `HomeView` 一份），一并换用避免留三版；`SearchView.vue` 不是无限滚动列表，不在范围。

#### 市集三 tab（跑腿 / 闲置 / 求购）加载时机

- 进入市集**只加载 `activeTab` 指向的那一个 tab**（首次进入即默认「跑腿」）；其余 tab **首次切入才加载**，之后各自靠哨兵续页——**不会**首次进入就把三个列表全拉一遍。
- **跑腿 / 闲置 / 求购各自独立**的分页、`finished`、已加载数据（各一个 `useWaterfall` 实例）。
- **`activeTab` 进 store**（跨底部 tabbar 保留 tab 位）；**列表数据/分页不进 store**，组件卸载即丢，重进按 `activeTab` 重新首屏加载。
  - 落选「数据也进 store」：会引入「缓存何时过期」（新品上架 / 审核变动后何时刷新）这一新问题；票 05 已把「切回来不重新请求」列为不做。
- **同页内切 tab 恢复各 tab 的滚动位置**：离开时存该 tab 的 `scrollY`、切入时 `nextTick` 后 `window.scrollTo`。三 tab 共享同一个 window 滚动容器，保留数据 + 恢复位置才自洽。
  - 注意：滚动位置记忆**只在同一次页面停留内**有效（组件一卸载，数据与位置一起丢，重进滚到顶）——与上一条「数据不进 store」一致。

#### 筛选 / 排序变更

- 分类 chip、排序按钮变更 → 对应 tab 的 `useWaterfall.reset()` + 重新 `load()` + 滚到顶（语义同现状，只是收进 composable）。

#### 「我的」页（`ProfileView.vue`）

- `tab` / `productTab` / `errandTab` 三个 `ref` → **读写 store**（票 05 已定）；数据不进 store，每次挂载按 store 里当前 tab 重新加载（与本节 (b) 载体表格「组件重建、重新请求」一致）。
- **不引入 `useWaterfall`**：它是「一次性 50 条」非分页列表（含改形态后的「评论」tab），套 composable 只添间接。
- 跨 tabbar 与二级页往返保留 tab 位；刷新回落「笔记」；子 tab 作用域沿用票 05。

#### store 落点

- 新建模块级 Pinia store（建议 `front/src/stores/uiState.ts`，名字落地时可调），收「我的」页三 ref（`tab` / `productTab` / `errandTab`）与市集 `activeTab`；沿用仓内 `defineStore` 惯例（见 `stores/auth.ts`、`stores/chatRooms.ts`）。

#### 明确不做

- **统一「加载态 / 空态 / 骨架屏」组件规范**：本票不收口（map 列为「落地时再定」）。空态继续用各页现有 `EmptyState`，**不做骨架屏**。本票唯一的共享抽象是 `useWaterfall`。
- **列表数据跨 tabbar 保留**：不做（见上「数据不进 store」）。
- **`scrollBehavior` / `KeepAlive`**：不引入（票 05 已定；滚动恢复按上面的手写方案，仅限同页停留内）。

### 落地注意

- 现有 `ProfileView.vue`：`likedTotal` 去掉 `favorites.value.length`；`tab` / `productTab` / `errandTab` 三个 `ref` 改为读写 store；tab 顺序改为 `…闲置 / 跑腿`；头部按上表改。
- 现有 `MarketView.vue`：`activeTab` 改读写 store 并扩为 `'errand' | 'idle' | 'want'`；跑腿 / 闲置 / 求购各接一个 `useWaterfall` 实例，替换现在的**共享单哨兵** + `loadErrand`/`loadProduct` 两份手写分页（这是第 4 项 bug 的根因修法）；`HomeView.vue` 换用同一 composable。
- 头部与作者主页的**组件复用边界**：**不抽同一个组件**，只共用样式规范（深色渐变资料头 + `46px` 吸顶下划线 tab + 卡片规范）。两页操作区本就不同（他视是私信 chip、自视是钱包/信用分/审核入口 + 设置入口），且分属两张落地票；抽成带 slot 的组件只多一层间接。
- 本规范由**后续实施票**套用；本 effort 不落地代码。原型代码留在 throwaway 分支 `prototype/my-page-state` 备查。
