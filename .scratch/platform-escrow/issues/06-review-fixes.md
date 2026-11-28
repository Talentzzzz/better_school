# 06: code-review 双轴修复票（Standards + Spec）

**What to build:** 收口 `code-review` 两个轴的 findings 与合并者挖出的夹具硬伤。每条 finding 都已经由 lead 裁决为「修」或「按先例接受」，照下表执行；接受项**不要**擅自改，但要在本票落地注记里留理由。

**Blocked by:** 无（票 01–05 已全部合入 `feat/platform-escrow`，本票直接基于其顶端）

**Status:** ready-for-agent

评审固定点 = `9037f07`（票 01–05 全部改动），证据：`git diff 9037f07...HEAD`。

## A. Standards 轴

### A1【修】局部变量命名违反 CLAUDE.md
`WalletRepository` 里 `sellerDetail` / `buyerDetail`（类型 `WalletDetail`）与 `insertPlatformDetail(WalletDetailTypeEnum platformType, …)` 的 `platformType`（类型 `WalletDetailTypeEnum`）违反根 `CLAUDE.md`「业务载体类型的局部变量与方法参数取『类型名首字母小写』」。

- `sellerDetail` → `walletDetail`；`buyerDetail` → `walletDetail`（各自作用域只有一个该类型变量，不需要语义前缀）；
- `insertPlatformDetail` 的参数 `platformType` → `walletDetailTypeEnum`（方法体内 `platformType.name()` / 日志里的 `platformType` 同步改）。

### A2【修】`PLATFORM_USER_ID` 放错包，违反 `backend/CLAUDE.md` §8
§8：「域内常量枚举放 `types/<域>`，别往 `common` 塞」。`PLATFORM_USER_ID` 是钱包域常量，却落在 `types/common/Constants`。

- 新建 `bs-types/src/main/java/com/betterschool/types/wallet/Constants.java`（照 `types/draw/Constants`、`types/points/Constants` 先例：同包同名类），把 `PLATFORM_USER_ID` 与它的 Javadoc 整体搬过去；
- 从 `types/common/Constants` 删除该常量；
- 全仓更新引用：`git grep -n "PLATFORM_USER_ID"`（`WalletRepository`、三个集成测试、`CONTEXT.md`、`docs/adr/0019-*`、`backend/better-school/docs/db/user.md` 若提及——**文档里的写法统一成 `types.wallet.Constants.PLATFORM_USER_ID`**；`.scratch/` 下的历史票不改，那是历史记录）。

### A3【修】结算 / 退款两段同形代码（Duplicated Code）
`settleProductOrder` 与 `refundProductOrder` 除幂等锚的类型与收款方外逐句相同（平台 `PLATFORM_OUT` → 平台守卫扣减 → 收款方加余额 → 不足记 ERROR 抛异常）。抽一个私有方法：

```java
/** 平台出账 + 收款方入账：平台配对流水 → 平台余额守卫扣减 → 收款方加余额（结算与退款共用） */
private void platformOutAndCredit(WalletDetail receiverDetail, String scene)
```

`scene` 只用于日志与异常文案（「结算」/「退款」）；收款方的 `WalletDetail`（已带 orderId / amount / userId / bizRound）由调用方先建好（它就是幂等锚）。抽完两个 public 方法应各剩「ensurePlatformWallet → 建锚 → 锚 0 行 return → platformOutAndCredit」四步。**不要**为了去重把幂等锚也塞进私有方法（锚类型是两段的唯一真差异，塞进去会变成参数化的 switch）。

### A4【接受】测试里 `platformBalance()` 三类各一份
与仓库既有测试风格一致（`Pay` / `Cancel` 本来就各带 `balanceOf` / `frozenAmountOf`），`PostApiTestBase` 不该为一次改造扩公共面。**不改**。

### A5【接受】`selectOrphanPlatformIn(platformInType, platformOutType, …)` 两参数恒为常量
沿用 `selectOrphanFrozen` 的既有取数口形状，且让流水类型名留在 Java 侧而不是硬编码进 mapper XML。**不改**。

### A6【接受】票 `Status: resolved` 不在 triage 五值内
`docs/agents/issue-tracker.md` 的 Wayfinding 段与仓库既有已关闭票（如 `chat-member-table-refactor/issues/03`、`chat-ws-layering/issues/01`）都用 `resolved` 表示收口；implement-spec 也要求「按 issue tracker 关闭工作的方式」收口。**不改**（本票落地注记留一句理由即可）。

## B. Spec 轴

### B1【修】仓库根 `sql.md`（1086 行整库 DDL 文档）漏同步
spec 的 User Story 24 要求「DB 文档同步资金归属口径」，票 05 只改了 `backend/better-school/docs/db/*`，而 `docs/db/README.md` 明确「无独立小节的表语义以 `sql.md` 为准」，旧口径仍在生效：

- `sql.md` 里 `virtual_wallet` 块（约 :633–648）：`frozen_amount` 注释改成与 `schema.sql` 同一句（商品链路已退役、仅历史残留作买家出账守卫的减项），并补「平台账户是本表一行（`user_id = types.wallet.Constants.PLATFORM_USER_ID`、balance 从 0 起、懒创建）」+ 对账不变量一句；
- `wallet_detail` 块（约 :650–668）：`type` 注释换成 `RECHARGE/FROZEN/UNFROZEN/INCOME/PRODUCT_PAY/PRODUCT_REFUND/PRODUCT_INCOME/PLATFORM_IN/PLATFORM_OUT`；`user_id` 注释补「`PLATFORM_*` 为平台行」；
- `payment_order`（约 :669–692）与 `wallet_pay_task`（约 :694）的说明段落里「落 `PRODUCT_FROZEN` 流水并扣冻」「钱已冻」改成「买家出账 `PRODUCT_PAY` + 平台入账 `PLATFORM_IN`」「钱已由平台代收」；
- 与 `schema.sql` 的对应注释**逐字一致**（两份文档的 wallet 块本就互为镜像，别写成两套措辞）。

### B2【修】ADR-0019 与代码矛盾：历史冻结订单的收敛
`docs/adr/0019-platform-escrow-account.md` 称历史冻结订单的结算失败「靠孤儿扫描与人工核对收敛」，但新判据要求订单先有 `PLATFORM_IN`——映射后的旧订单没有这条腿，扫描永远看不见。改成诚实的口径：**旧口径历史数据没有平台腿，新扫描不兜底它们；开发/测试库按 spec 直接重建，存量库需人工核对**（spec 的 Out of Scope 已把存量修复划出去）。

### B3【修】ADR-0019 漏记两处实现决策（评审确认为「做了但没进决策记录」）
在 Decision 段补两条：

- **支付超时不退款**：`handlePayTimeout` 只关支付单、无资金动作——超时只取消 `PENDING_PAYMENT` 订单，平台从未收到钱，退款会给买家凭空入账；「支付已进平台、订单却因超时取消」的并发窗口由孤儿扫描按「有入账无出账」收敛（spec 第 62 行的「取消 / 支付超时 → 平台退款」指的是真的收到过钱的场景）。
- **平台腿唯一键是硬守卫**：平台配对流水 `INSERT IGNORE` 撞 0 行时不静默跳过，而是 ERROR + 抛异常整笔回滚——唯一键因此真正保证「平台对同一订单只收一次、只付一次」，而不是靠调用顺序的善意。

### B4【修】三个测试类的发布夹具缺 `quantity`，7/9 用例真跑会红
`createProduct` 的 body 只传 title/description/images/price/tradeLocation/category，发布落台账默认 **1 件**（`IdleRepository:163`），但多数用例下单 `quantity=2`，`ProductInventoryMapper.xml` 的 `remaining >= #{quantity}` 守卫会让下单直接「库存不足」——`assertSuccess(response, "商品下单")` 在 fixture 阶段就失败。

- 在 `ProductPayCompositeTxIntegrationTest` / `ProductCancelFundConsistencyIntegrationTest` / `ProductSettleFundConsistencyIntegrationTest` 三个 `createProduct` helper 的 body 里补 `Map.entry("quantity", 2)`（与 `ProductInventoryIntegrationTest` 的既有写法对齐）；
- 补完核对：凡是 `createProductOrder(..., 2)` 的用例都有 2 件可扣；`quantity=1` 的两个用例（余额不足、待支付取消）也仍然成立（2 ≥ 1）；
- 断言金额（39.80 / 960.20 / 1039.80）与 `quantity=2` 一致，不需要改。

### B5【修】票 05 的收尾瑕疵
- 票 05 的 6 个验收复选框仍空 → 勾上（内容确实都做了）；
- 票 05 落地注记称「`PRODUCT_UNFROZEN` 那句未改」，但 diff 里实际改了 → 改成事实。

## 硬性要求

- **不运行 `mvn`、不运行任何测试、不跑构建、不跑 SQL**（用户明确要求，由维护者自行验证）。
- 只改上面点名的文件：`WalletRepository.java`、新建 `types/wallet/Constants.java`、`types/common/Constants.java`、三个集成测试、`sql.md`、`docs/adr/0019-platform-escrow-account.md`、`.scratch/platform-escrow/issues/05-docs-and-adr.md`、本票文件；A2 若 grep 出别处的 `PLATFORM_USER_ID` 引用（含 `CONTEXT.md` / `docs/db/user.md`）一并更新。
- **不要**碰：`schema.sql`（票 05 已改好）、`docs/db/*` 除 A2 的常量名替换外、`IdleCase`、mapper XML、`ProductFundOrphanScanSummaryVO`、ADR-0015/0016/0017/0018 的历史叙述。
- 遵守 `CLAUDE.md` / `backend/CLAUDE.md`（本次修复本身就是它们的执行）。

验收清单：

- [ ] A1–A3 已改；A4–A6 未改且落地注记写明接受理由
- [ ] `PLATFORM_USER_ID` 迁到 `types/wallet/Constants`，`types/common/Constants` 里已删除，全仓引用（代码 + 文档）无残留旧包路径
- [ ] `sql.md` 的 wallet 四块与 `schema.sql` 注释逐字一致，旧「冻结/扣冻」现行表述清零
- [ ] ADR-0019 补上超时不退款、平台腿硬守卫两条 Decision，并修正历史数据收敛那句
- [ ] 三个测试的 `createProduct` 都传 `quantity=2`；票 05 复选框已勾、失真注记已改
- [ ] 不跑构建；`git grep -n "Constants.PLATFORM_USER_ID"` 只剩 `.scratch/` 历史票与 `common.Constants` 无关内容

**落地注记（实现取舍）：** 待实现者补。
