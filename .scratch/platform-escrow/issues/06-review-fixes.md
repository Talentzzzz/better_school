# 06: code-review 双轴修复票（Standards + Spec）

**What to build:** 收口 `code-review` 两个轴的 findings 与合并者挖出的夹具硬伤。每条 finding 都已经由 lead 裁决为「修」或「按先例接受」，照下表执行；接受项**不要**擅自改，但要在本票落地注记里留理由。

**Blocked by:** 无（票 01–05 已全部合入 `feat/platform-escrow`，本票直接基于其顶端）

**Status:** resolved（A1–A3 / B1–B5 按票修完、A4–A6 按裁决未改；只做静态自查，未跑 mvn / 测试 / 构建 / SQL）

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

- [x] A1–A3 已改；A4–A6 未改且落地注记写明接受理由
- [x] `PLATFORM_USER_ID` 迁到 `types/wallet/Constants`，`types/common/Constants` 里已删除，全仓引用（代码 + 文档）无残留旧包路径
- [x] `sql.md` 的 wallet 四块与 `schema.sql` 注释逐字一致，旧「冻结/扣冻」现行表述清零
- [x] ADR-0019 补上超时不退款、平台腿硬守卫两条 Decision，并修正历史数据收敛那句
- [x] 三个测试的 `createProduct` 都传 `quantity=2`；票 05 复选框已勾、失真注记已改
- [x] 不跑构建；`git grep -n "Constants.PLATFORM_USER_ID"` 只剩 `.scratch/` 历史票与 `common.Constants` 无关内容（字面 grep 现在必然命中 Java 侧的 `Constants.PLATFORM_USER_ID`——那里的 `Constants` 就是 import 进来的 `types.wallet.Constants`；真正的判断口径是「文档 / SQL 里零裸引用、全仓无 `types.common` 钱包常量残留」）

**落地注记（实现取舍）：**

- **A1**：`settleProductOrder` / `refundProductOrder` 的 `sellerDetail` / `buyerDetail` → `walletDetail`，`pay` 里的同类型局部变量 `buyerDetail` 一并改（同文件同类型、各自作用域只有一个）；`insertPlatformDetail` 的参数 `platformType` → `walletDetailTypeEnum`（`name()` 与 ERROR 日志同步）。全文件再无 `sellerDetail` / `buyerDetail` / `platformType`。

- **A3 抽出的方法签名**：`private void platformOutAndCredit(WalletDetail walletDetail, String scene)`（`bs-infrastructure/.../wallet/WalletRepository.java`）。方法体三步：`insertPlatformDetail(PLATFORM_OUT, orderId, amount)` → 平台余额守卫 `updateDecreaseBalance(PLATFORM_USER_ID, amount)`（0 行 = ERROR + `BusinessException("平台代收余额不足，" + scene + "失败")`）→ `updateIncreaseBalance(receiverId, amount)`。抽完两个 public 方法各剩四步：`ensurePlatformWallet()` → 建锚（`PRODUCT_INCOME` / `PRODUCT_REFUND`）→ 锚 0 行 `return` → `platformOutAndCredit(walletDetail, "结算" / "退款")`；幂等锚按票要求留在 public 方法里，没有参数化进私有方法。`scene` 只进日志与异常文案；日志里原先分列的 `sellerId` / `buyerId` 随两段合并成 `receiverId`（取值同为收款方 `user_id`）。
  - **与票里草图的唯一偏差**：票写的形参名是 `receiverDetail`，落地用 `walletDetail`。理由：`receiverDetail` 违反本票 A1 正在执行的根 `CLAUDE.md`「业务载体类型的局部变量与方法参数取类型名首字母小写」——同一个类刚把同类型的 `sellerDetail` / `buyerDetail` 改掉，再新增一个同类型、不吃类型名的参数等于把 A1 重新引入一次；语义由方法名 + Javadoc 承担。

- **A2 迁移后受影响的文件清单**（常量迁到 `com.betterschool.types.wallet.Constants`）：
  - 新增 `bs-types/src/main/java/com/betterschool/types/wallet/Constants.java`：`PLATFORM_USER_ID` 与 Javadoc 整体搬入，照 `types/draw/Constants`、`types/points/Constants` 的同包同名类先例。原 Javadoc 的 `{@link #SYSTEM_USER_ID}` 改成跨包 `{@link com.betterschool.types.common.Constants#SYSTEM_USER_ID}`（搬迁后 `#SYSTEM_USER_ID` 已不在本类，否则链接失效）；本类不能 `import` common 的同名 `Constants`（编译错误），只能用全限定名。
  - `bs-types/.../types/common/Constants.java`：删除 `PLATFORM_USER_ID` 与它的 Javadoc（`SYSTEM_USER_ID` 等其余常量原样留在 common）。
  - 代码 4 个文件改 import：`WalletRepository`、`ProductPayCompositeTxIntegrationTest`、`ProductCancelFundConsistencyIntegrationTest`、`ProductSettleFundConsistencyIntegrationTest`。每个文件只有一条 `Constants` 单类型 import，不存在「两个 Constants 同时 import」的编译问题。
  - 文档 3 个：`CONTEXT.md`（平台代收账户词条）、`docs/adr/0019-platform-escrow-account.md`（一句话决定 + Decision 首条 + Migration 代码清单）、`backend/better-school/docs/db/user.md`（`virtual_wallet` 两处）——统一写成 `types.wallet.Constants.PLATFORM_USER_ID`。
  - `schema.sql:812` 的 `virtual_wallet` 表注释与 `sql.md` 的镜像同步改成 `user_id = types.wallet.Constants.PLATFORM_USER_ID`（只换这一个字符串，列 / 索引 / 约束未动）——按 lead 追加裁决：常量已换包，裸写指向已不存在的类；票里「不要碰 `schema.sql` 注释本体」那条限制因此收窄为「不改列 / 索引 / 约束」。全仓已无裸 `Constants.PLATFORM_USER_ID`。

- **B1 与 schema 的对齐方式**：四个围栏块（`virtual_wallet` / `wallet_detail` / `payment_order` / `wallet_pay_task`）的 DDL 现在逐行取自 `schema.sql`（:801–812、:814–827、:832–847、:854–866），并用脚本把两边 `CREATE TABLE … ) ENGINE … ;` 区间取出做字符串比对，四块全部相等（不是「看着一样」）。逐字一致的要求顺带修掉票里没点名的两处漂移：`wallet_pay_task` 的 `uk_order_id` 注释（「支付任务」→「回调任务」）与表注释（「钱包支付收敛任务 / 冻结事务内落行」→「支付回调任务 / 支付事务内落行，与支付单 PAID + 平台代收入账同生同死」）；`virtual_wallet` / `wallet_detail` 两处 `user_id` 的列对齐空格也随 schema 对齐（逐字一致下无法保留 sql.md 原来的对齐风格）。
  - 说明段落：`payment_order` / `wallet_pay_task` 的散文改成 schema 注释块的同一措辞（「落买家出账 `PRODUCT_PAY` + 平台入账 `PLATFORM_IN` 双边流水、买家余额条件扣减 + 平台余额入账」「钱已由平台代收」），旧「落 `PRODUCT_FROZEN` 流水并扣冻」「钱已冻」清零。
  - **平台账户 + 对账不变量**没有塞进 DDL 注释（schema 里没有这两句，塞进去就破坏逐字镜像），而是写在 `virtual_wallet` 围栏之后的引用块里：`user_id = types.wallet.Constants.PLATFORM_USER_ID`、`balance` 从 0 起、懒创建、对账不变量 = 平台 `balance` = Σ（有 `PLATFORM_IN` 无 `PLATFORM_OUT` 的订单金额）。这样 DDL 部分仍是 schema 的逐字镜像，语义补充不污染镜像。

- **B2**：ADR-0019 的 Consequences 里「靠孤儿扫描与人工核对收敛」改成诚实口径——扫描判据只认「有 `PLATFORM_IN` 无 `PLATFORM_OUT`」，按旧冻结口径生成的订单本来就没有平台腿、类型映射后也没有，扫描永远看不见；所以写「开发 / 测试库按 spec 直接重建、存量库需人工核对」（spec Out of Scope 已把存量修复划出去）。同时给 Decision 里的孤儿扫描条目补一句「本判据只覆盖改造后真的过了平台账的订单」，避免在判据定义处再次误导。

- **B3**：Decision 段补两条——「平台腿的唯一键是硬守卫，不是静默跳过」（撞 0 行 → ERROR + 抛异常整笔回滚，让唯一键真正保证平台对同一订单只收一次 / 只付一次）与「支付超时不退款」（`handlePayTimeout` 只关支付单、不动资金；并发窗口由孤儿扫描按「有入账无出账」收敛）。另在「仓储方法收敛为两个原子动作」那条补一句实现形状（共用段抽私有方法、幂等锚留在 public 方法），避免 ADR 与代码再次脱节。

- **A4 接受理由**：三个测试类各一份 `platformBalance()`（容忍「平台行缺失 = 0」）是仓库既有测试风格——`balanceOf` / `frozenAmountOf` 本来就按类复制，`PostApiTestBase` 不提供余额读取口；为一处改造扩基类公共面收益低于耦合成本，且每份方法带着自己类的取数说明（为什么不能用 `queryForObject`）。**未改**。

- **A5 接受理由**：`selectOrphanPlatformIn(platformInType, platformOutType, …)` 沿用 `selectOrphanFrozen` 的既有取数口形状，让「平台入账 / 出账」这两个枚举名留在 Java 侧而不是硬编码进 mapper XML；两个参数恒为常量是这层抽象的代价，换成无参版本会把流水类型名焊进 SQL。**未改**。

- **A6 接受理由**：`resolved` 是 `docs/agents/issue-tracker.md` Wayfinding 段与仓库既有已关闭票（`chat-member-table-refactor/issues/03`、`chat-ws-layering/issues/01`）的收口写法，也是 implement-spec 要求的「按 issue tracker 关闭工作的方式」；triage 五值管的是进入实现前的状态机，不是关闭标记，两者不冲突。**未改**。

- **B4 结论与剩余风险**：`quantity` 从 `ProductPublishRequestDTO`（`@Min(1)` / `@Max(999)`）→ `ProductController.publishProduct` → `IdleCase` → `IdleServiceImpl.publishProduct` → `ProductEntity.create`（:54 赋值）→ `IdleRepository.save`（:163 缺省 1）全链路通，夹具补 `Map.entry("quantity", 2)` 后：7 个 `createProductOrder(..., 2)` 的用例都有 2 件可扣，`quantity=1` 的两个用例（余额不足、待支付取消）也仍成立（2 ≥ 1）；断言金额（39.80 / 960.20 / 1039.80）与 `19.90 × 2` 一致，未改。真跑会红的风险主要不在夹具而在钱包口径本身（平台行懒创建的回滚、`balance − frozen_amount` 守卫），本票按要求未执行验证。

- **还发现的问题**（都没在本票改，留记录）：
  1. `schema.sql:812` 表注释里的裸 `Constants.PLATFORM_USER_ID`（A2 后已非全限定名）**已在本票追加轮修掉**，`sql.md:647` 镜像同步、块级比对仍全等——见注记末尾「追加轮」。
  2. 仓库根 `review_diff.txt` 是 `8eb9548` 就提交进来的评审 diff 产物（约 500 行、含 wallet 段历史 diff），不属于任何特性；建议删除或进 `.gitignore`。
  3. `docs/plans/notify-event-refactor.md:53` 仍以 `types/common/Constants.java` 为编辑目标——那是通知 topic 的历史计划、与钱包常量无关，A2 未动它。
  4. `pay` 的「买家锚 0 行即视为已支付、直接返回」与 `insertPlatformDetail` 的硬守卫之间有个理论缝隙：若买家 `PRODUCT_PAY` 行已存在而平台 `PLATFORM_IN` 行缺失（历史半截数据），重复支付会一直走 `alreadyPaid` 短路、不会补平台腿，孤儿扫描也看不见（它判的是「有入账无出账」）。属存量数据修复范畴（spec Out of Scope），记录待后续。

- **交付口径**：按要求**未运行 `mvn`、任何测试、构建与 SQL**；全部结论来自静态阅读、`git grep` 与两份文档的块级字符串比对。

- **追加轮（lead 裁决，同票同分支）**：把 `backend/better-school/sql/schema.sql:812` 与 `sql.md:647` 的 `virtual_wallet` 表注释从裸 `Constants.PLATFORM_USER_ID` 改成 `types.wallet.Constants.PLATFORM_USER_ID`——A2 换包后裸写指向已不存在的类，lead 裁决推翻本票原「不要碰 schema.sql 注释本体」的限制（收窄为「不改列 / 索引 / 约束」）。只改这一个字符串（`git diff -U0 -- schema.sql` 恰好 1 行），改完重跑四块块级比对仍 4/4 全等；仍未跑 mvn / 测试。
