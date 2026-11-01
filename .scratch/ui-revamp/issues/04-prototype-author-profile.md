Status: resolved
Type: prototype
Blocked by:

# 04: 作者主页布局（原型）

## Question

本 effort 新增"作者主页"：从**帖子详情、商品详情、跑腿详情、评论区**的头像/昵称点入（Q13）。参考仓库根目录 `55a4b0d413d2fb4ee8c844d69d906455.jpg`（小红书个人主页：顶部资料区 + 关注/发私信 + 笔记/收藏 tab + 内容瀑布流）。

出 **3 个变体原型**，选定一套作为 spec 的"作者主页"节。

约束（已在 map 的 grilling 中定死，原型不要越界）：
- **无关注、无粉丝、无收藏**：顶部数字区只显示**获赞**（不显示"关注 798 / 粉丝 183 / 收藏"）；操作区只有**私信**，没有"关注"按钮
- 资料区展示：头像、昵称、账号/ID、个性签名、性别（可选展示）
- tab 集合与"我的"页对齐：**笔记 / 赞过 / 评论 / 闲置 / 跑腿**
- 访客口径只含公开可见内容（闲置只在售、跑腿只公开中）
- 隐私关闭时对应 tab **仍展示、点进去显示"该用户已隐藏此内容"空态**（Q9）——原型要体现这个空态
- "评论" tab 是**评论列表**形态（评论内容 + `来自笔记 · 帖子标题` + 时间 + 点赞），参照 `ScreenShot_2026-09-11_022904_160.png`，但**不放"取消公开"按钮**（Q14 已废弃）
- "赞过" tab 是帖子列表
- 底部沿用 App 的 tabbar；顶部返回

产出：3 个变体原型 + 选定结论，资产链接回填本票。

## Answer

**选定 D。** 走 `prototype` skill 的 UI 分支，**sub-shape B**：作者主页是新页面（无既有宿主页可挂），故起 throwaway 路由 `/prototype/author-profile`，`?variant=A|B|C|D` 切变体，底部沿用 App tabbar。数据全 mock（`authorProfileMock.ts`，头像/图片留空走渐变占位）。

### 选定结论

**选 D**，用户 2026-09-11 拍板。D 不在原始三案里，是翻页后的拼合产物，指令原文：

1. 「我喜欢A的，但是希望把私信按钮放到B的那个位置，也就是头像和昵称的右边，并且评论之间不要有分隔线，自然分割就行」
2. 「跑腿也不要有分割线，隐藏按钮不要放在这里，右上角加一个设置，放设置里，如果这只是演示的话就这样」

即 **D ＝ A 的页面骨架 + B 的私信位 + 评论/跑腿去分割线 + 右上角「设置」**：

- 私信从 A 的**整行白底按钮**挪到**资料行右侧的行内 chip**（取 B 的位置与红色 pill 形态）
- **评论与跑腿**项之间都不画分割线，`14px` 行距自然分隔（与票 02 帖子详情同一口径）
- 右上角 `···` 换成**「设置」**（齿轮 pill）→ 底部「隐私设置」面板，内含 `公开我的闲置` / `公开我的跑腿` 两个开关（演示用，无持久化）

**落选**：A 作为母案被大量采纳（页面骨架全留），但其整行私信按钮落选；B 只采纳私信位，整体的扁平风落选；C 整体落选（与参考图取向最远，卡面也另起一套）。

完整口径（页面结构、尺寸色值、tab 与内容形态、评论列表、隐私开关、明确不做、待票 06 定的数据口径、落地注意）已写进 `.scratch/ui-revamp/spec.md` 的「作者主页」节，落地时以那节为准。

### 变体对照（A/B/C 是原始三案，D 是选定案）

| 变体 | 名 | 资料头 | tab 形态 | 内容布局 | 私信位 |
| --- | --- | --- | --- | --- | --- |
| A | 深色资料头·下划线 tab·白卡瀑布 | 深色渐变，左对齐大资料块（头像 72） | 吸顶下划线 | 双列白卡瀑布（沿用票 01 卡片规范） | 整行白底按钮 |
| B | 浅色扁平·chip tab·单列列表 | 标准顶栏 + 紧凑资料行（头像 56） | 横滑 chip | 所有 tab 统一**单列列表** | 资料行内红色 chip |
| C | 封面横幅·居中·分段控件·无卡瀑布 | 封面横幅 + 居中（头像压封面边） | 分段控件（segmented） | 无卡片瀑布流（图 + 文案直接排） | 悬浮 FAB |
| **D** | **（选定）** | **同 A** | **同 A** | **同 A** | **取 B：资料行右侧行内 chip** |

资料头左对齐 / 扁平行 / 居中三态、tab 下划线 / chip / 分段三种、内容白卡瀑布 / 单列列表 / 无卡瀑布三种——不是换色。

### 已落实的票面约束

- 无关注/粉丝/收藏，顶部只显**获赞**；操作区只有**私信**（无关注按钮）
- 资料区：头像、昵称、账号、个性签名、性别
- tab 集合：笔记 / 赞过 / 评论 / 闲置 / 跑腿——**按票面顺序**（注：「我的」页现行顺序是「…跑腿/闲置」，本节顺序为准，落地时同步「我的」页）
- 访客口径只含公开内容：闲置只在售、跑腿只公开中（过滤实现位置归票 06）
- 评论 tab = 评论列表（评论内容 + `来自笔记 · 标题` + 时间 + 点赞），**无「取消公开」**
- 赞过 tab = 帖子列表
- 隐私：右上角**「设置」**→ 底部「隐私设置」面板，两个开关 `公开我的闲置` / `公开我的跑腿`；关闭后对应 tab **仍展示**、点进去是「该用户已隐藏此内容」空态（Q9）。演示用，无持久化
- 底部沿用 App tabbar；顶部返回（D 接 `goBack`，与 `NavBar` 同口径）

### code-review 收口（2026-09-11，两轴并行）

Standards 轴 × Spec 轴并行复核。**未发现硬违规**——票 03 踩过的「原型类名撞全局类」（`.cell`）在本票不存在（新原型样式全 scoped，已逐个核对与 `common.scss` 的类名交集）。修了两处：

1. **Spec 轴**：D 顶部返回是无 `@click` 的死件，而票面 Q 明写「顶部返回」→ 接上 `NavBar` 同口径的 `goBack`（`history.length > 1` 回退，否则回首页）。A/C 的返回键同为死件，但按「落选案留档不动」的惯例未改。
2. **Standards 轴**：路由注释仍写「三变体 `?variant=A|B|C`」，D 加入后未同步。

**留档未改（判断项，非缺陷）**：变体间 `TABS` 结构与 errand 金额三元重复（落选案刻意不共享布局，prototype skill 明确警告变体间共享过多代码）；`ProtoNote.cover` 当布尔用（名字没说清）；`.grid`/`.panel` 与全站 `.waterfall`/`.content-panel` 的用词漂移；`#f2f2f5`/`#6b6b70` 硬编码——这两个色值**恰是票 01 spec 的原文**，非漂移；`ProtoPrivacy` 与其它 `Proto*` 类型分居两文件。

### 资产

- 代码（throwaway 分支 `prototype/author-profile`，**不进 master**；4 个 commit：`9b7ace5` 出稿三案 → `23b9d26` 拼 D → `5bc3ff4` D 第二轮 → `3be0dea` code-review 收口）：
  - `front/src/components/prototype/authorProfileMock.ts` — 资料/笔记/赞过/评论/闲置/跑腿 mock
  - `front/src/components/prototype/authorProfileVariants.ts` — 变体清单 + 键→组件映射 + `ProtoPrivacy`
  - `front/src/components/prototype/AuthorProfileVariant{A,B,C,D}.vue` — 三个原始案 + 选定案 D
  - `front/src/components/prototype/ProtoNoteCard.vue` — 帖子卡（沿用票 01 卡片规范）
  - `front/src/components/prototype/{PrototypeSwitcher.vue,useProtoVariant.ts}` — 切换器与 `?variant=` 门禁（与票 02/03 同源）
  - `front/src/views/prototype/AuthorProfilePrototypeView.vue` — 宿主（D 之外的变体仍保留浮层演示入口）
  - `front/src/router/index.ts` — throwaway 路由（`meta.tabbar`）
- 截图 `.scratch/ui-revamp/proto-04/`（17 张）：A/B/C 各 4 张（笔记 / 评论 / 闲置或跑腿 / 隐私空态），D 5 张（笔记 / 评论 / 跑腿 / 设置面板 / 隐藏空态）

### 怎么看

```bash
git checkout prototype/author-profile
cd front && npm run dev
```

- 选定结果就是 `?variant=D`：`http://localhost:5173/prototype/author-profile?variant=D`
- 点右上角**「设置」**开关两个隐私开关，再看「闲置 / 跑腿」tab 的空态
- 切 ← → 或点箭头可循环回 A、B、C 对照（这三个的隐私开关在左下浮层里）
- 运行期后端不在线会弹「网络异常」toast（聊天域轮询失败，与本票无关）
