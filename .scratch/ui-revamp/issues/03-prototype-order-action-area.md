Status: resolved
Type: prototype
Blocked by:

# 03: 下单支付操作区（原型）

## Question

`front/src/views/trade/ProductOrderView.vue`（`/trade/order/:id`）底部操作区在按状态 + 角色组合渲染时拥挤：`取消 + 确认支付 ¥xx` 并排（`PENDING_PAYMENT`）、`取消订单 + 标记已交付`（`PAID`）、`确认收货并支付货款`（`DELIVERED`），按钮里还带金额，窄屏下挤成一团。

出 **3 个变体原型**处理"操作区信息层级"，选定一套作为 spec 的"订单支付操作区"节。可考虑的方向示例（不限于此）：主按钮整行 + 次按钮降级为文字链、金额从按钮文案里抽出单独展示、危险操作收进"更多"、状态说明与操作区合并等。

约束：
- 按状态 + 角色（买家/卖家/访客）的所有组合都要覆盖，且每个组合里**至多一个主操作**
- 沿用现有 `btn-primary` / `btn-outline` / `btn-text` 设计语言的观感，可在其上调整
- 本票只出**商品订单支付页**的操作区；跑腿订单详情页底部操作区若发现同构问题，在 spec 里注明复用同一规范（不在本票出图范围）

产出：3 个变体原型 + 选定结论，资产链接回填本票。

## Answer

**选定 D。** 走 `prototype` skill 的 UI 分支（sub-shape A：挂在既有路由 `/trade/order/:id`，不另起原型页）：`?variant=A|B|C|D` 切变体、`?case=` 切第二轴，顶部浮动切换器（← → 循环、URL 可分享、刷新稳定；切换器**贴顶**展开收起，因为本票要评的就是底部操作区，不能压住它）。无 `?variant=` 时行为与现状完全一致。

数据全部 mock（`orderActionMock.ts`，商品图/头像留空）。

### 变体对照（A/B/C 是票面点名的原始三案，D 是翻页后拼合的选定案）

| 变体 | 名 | 骨架 | 金额 | 次操作 | 主操作 |
| --- | --- | --- | --- | --- | --- |
| A | 分层底栏 | 底部固定栏，**三层** | 抽成独立一行（标签左 / 金额右） | 降级为文字链 | **整行**按钮，文案只剩动词 |
| B | 状态卡内联 | **无底栏**，状态＋金额＋说明＋按钮合并进顶卡 | 并进状态那一行的右侧 | 与主按钮同行、在其左 | 内联按钮，文案带宾语 |
| C | 等分底栏 | 底部固定栏，**一行等分** | 独立成块居左 | 一格按钮，在主操作左侧 | 一格按钮，只写动词 |
| **D** | **（选定）** | **按「有没有可点的操作」二选一**：有操作→状态条 + C 的等分底栏；纯等待/终态→B 的状态卡、不设底栏 | 同 C / 同 B | 同 C / — | 同 C / — |

三者结构不重合（**行数 3／0／2、位置 底／顶／底、主按钮形态 整行／内联／等分格**），不是换色。

### 选定结论

**选 D**，用户 2026-09-11 拍板。D 不在原始三案里，是翻页后的拼合产物，指令原文：

1. 「待支付买家选C，但是取消订单放到确认支付的左边，价格和取消和确认各站底部三分之一」
2. 「待支付卖家选B」
3. 「其他的你标签分类的有问题吧」——状态文案是按买家视角写的裸映射，不按角色分（见下）
4. 规则收口：**「有操作→C / 纯等待→B」**

即 D ＝ 一条**二选一规则**，不是一种固定底栏。11 个有按钮的组合里只有 4 个真的有操作，其余 7 个是纯等待/终态。

完整口径（判据、15 个组合表、尺寸色值、状态文案角色表、同构复用、落地注意）已写进 `.scratch/ui-revamp/spec.md` 的「下单支付操作区」节，落地时以那节为准。

**落选**：A（竖三层占位最高，纯等待态还得另做一套）、B 与 C 各自作为**分支**被采纳（B→纯等待，C→有操作），单独作为全局方案落选。

### 本票揪出的一个真 bug：状态文案不按角色分

线上 `STATUS_TEXT` 是「状态 → 词」的裸映射，全按买家视角写。**卖家**点进待支付单，顶部显示「待支付」＋「15 分钟内未支付将自动取消，商品恢复在售」——卖家没有要支付的钱；**访客**同样。翻页时被用户点名（指令 3）。

修法：标题与说明都按角色取词（`statusText` / `statusNote`）——买家「待支付」＝卖家「待买家付款」，对卖家说「能拿到什么」（`交付后转给你`）而不是催他付款。同一张裸映射还复制在 `MyProductOrderView.vue` 与 `ProfileView.vue`（`PRODUCT_ORDER_STATUS_TEXT`），落地时一并改。

**另一个真 bug**：线上 `DELIVERED + 访客` 渲染**空操作条**（`isBuyer` / `isSeller` 两个分支都不命中，`action-group` 里什么都没有）。「纯等待 → 状态卡」这条分支天然把它修掉。

### code-review 收口（2026-09-11，两轴并行）

跑 `/code-review`（Standards × Spec）后修了这些：

1. **`.bar` 的 `safe-bottom` 失效（恰好是选定的 C/D）**：scoped `.bar[data-v-x]` 权重高于全局 `.safe-bottom`，简写 `padding` 把 `padding-bottom: env(safe-area-inset-bottom)` 压掉 → 固定栏沉到 iPhone home indicator 下面。改成把安全区写进自己的 `padding`（A 侥幸没踩，因为它的 `.bar` 不写 `padding`）。
2. **`statusText` 的 `DELIVERED + 访客` 漏改**，仍返回买家的「待收货」；`PAID + 访客` 同理。访客现在读「待买家收货」「待卖家交付」。
3. **`DELIVERED + 访客` 的金额标签**是「待付货款」——访客不欠谁钱，改「订单金额」。
4. **覆盖补齐到 15 个组合**（票面要求「所有组合都要覆盖」，原来只有 11 个，终态的卖家/访客缺席）。顺带发现 `COMPLETED + 卖家` 该是「货款已转给你」而不是「转给卖家」。
5. **终态不再重复**：`note` 已经把话讲完，就不再补一行「订单已完结」。
6. **免登录旁路收窄**到 `/trade/order/`（原来 DEV 下任何带 `?variant=` 的 `requiresAuth` 页都放行）。
7. **危险色改用 `$danger` token**（原硬编码 `#c0392b`）；次操作按钮挂上 `btn-text` 基类（票面点名的设计语言）。

**原型期踩到并修掉的隐形坑**（落地时别抄回去）：分格类名原叫 `.cell`，撞上 `common.scss` 的全局 `.cell`（列表行，`background: #fff`）——同权重、源序在后，把 `.btn-primary` 的红底盖成**白底白字**，「确认支付」按钮 DOM 在、屏幕上完全看不见。改名 `.seg` 并写进 spec 的「坑 2」。

**留档未改（判断项，非缺陷）**：`PAID + 买家` 的等分底栏只有金额 + 「取消订单」两格（各 1/2）——它确实没有主操作，缺格均分已在 spec 写明；`fullLabel`（「确认收货并支付货款」）在选定的 D 里用不到（有操作分支只写动词、金额在独立格），仅独立变体 B 用它演示「金额留在按钮里」这一落选取向。

### 资产

- 代码（throwaway 分支 `prototype/order-action-area`，**不进 master**）：
  - `front/src/components/prototype/useProtoVariant.ts`（`?variant=` 解析与 DEV 门禁）
  - `front/src/components/prototype/PrototypeSwitcher.vue`（浮动切换器，本票加了 `cases` 第二轴）
  - `front/src/components/prototype/orderActionMock.ts`（15 个 case 的 mock + 动作模型 + 按角色分的 `statusText`/`statusNote`）
  - `front/src/components/prototype/orderActionVariants.ts`（变体清单 + 键→组件映射）
  - `front/src/components/prototype/OrderActionVariant{A,B,C,D}.vue`（四个变体）
  - `front/src/views/trade/ProductOrderView.vue`（`?variant=` 接线）、`front/src/router/index.ts`（DEV 免登录旁路）
- 截图：`.scratch/ui-revamp/proto-03/`——**D × 15 个组合**全覆盖，另 A/B/C 各 2 张代表作对照（`A-pending-buyer`/`A-delivered-visitor`、`B-pending-buyer`/`B-pending-seller`、`C-pending-buyer`/`C-paid-buyer`）

### 怎么看

```bash
cd front && npm run dev
```

- `http://localhost:5173/trade/order/1?variant=D`（→ A/B/C，← → 循环）
- 切换器右侧点「案例」展开 15 个「状态 × 角色」组合
- 只看选定结果就是 `?variant=D`；`?variant=D&case=pending-buyer` 直接落到买家待支付

**本票不落地代码**（map 定死：本 effort 只产出决策与 spec）。D 的规范待后续实施票套用到 `/trade/order/:id`，并按 spec 的「同构复用」节套到跑腿订单详情页底栏。原型代码留在 throwaway 分支 `prototype/order-action-area` 备查，不进 master。

### 生产构建口径

`protoVariant` 与切换器都在 `import.meta.env.DEV` 后，生产构建里 `DEV=false` → 变体与切换器**都不可能渲染**（免登录旁路同样被编译掉）。如实说明：变体组件是**静态 import**，未被 tree-shake，仍以死代码形式留在 bundle 里（throwaway 分支，不进 master，故不处理）。`ProductOrderView` 产物因此从 master 的 **5.13 kB（gzip 2.12 kB）** 涨到原型分支的 **16.19 kB（gzip 4.92 kB）**，CSS 从 **1.30 kB** 涨到 **7.50 kB**——只发生在原型分支上。
