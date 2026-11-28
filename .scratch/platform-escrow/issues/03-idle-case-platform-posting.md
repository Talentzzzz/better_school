# 03: 用例编排翻到平台过账（确认收货 / 取消 / 超时 / 资金孤儿扫描）+ 钱包旧资金口径退役

**What to build:** 让 `IdleCase` 三笔资金动作改调钱包域新的原子方法，并把「冻结/解冻」口径从用例层与钱包对外接口上彻底退役。`IdleCase` 仍只注入各域 service、**不注入任何其他 case**（上一轮解耦成果保持）。跨域顺序仍由用例层编排，三笔动作各自是钱包域单仓储事务（ADR-0016）。

**Blocked by:** 02

**Status:** resolved（`IdleCase` 四笔资金动作翻到平台过账、孤儿扫描收敛为单段平台线索，钱包旧 `freeze` / `unfreeze` / `income` / `listOrphanFrozen` 口径与测试探针全部删除）

### 1. 确认收货 → 平台结算卖家

`confirmReceived`：删掉 `unfreeze(PRODUCT_UNFROZEN)` + `income(PRODUCT_INCOME)` 两次调用，改为一次 `walletAccountService.settleProductOrder(orderId, productConfirmResult.getSellerId(), productConfirmResult.getAmount())`（平台出账 + 卖家入账，单事务、双边流水）。买家余额此刻**不再变化**（钱在支付时已扣）。

### 2. 取消 → 平台退款买家

`cancelOrder`：`cancelResult.isNeedRefund()` 为真时改为 `walletAccountService.refundProductOrder(orderId, cancelResult.getBuyerId(), cancelResult.getAmount())`（平台出账 + 买家入账）。待支付取消（`needRefund = false`）仍然零资金动作；`closePaymentOrderQuietly` 保留不变。

### 3. 支付超时 → 只关单，不退款

`handlePayTimeout` **不做资金动作**：idle 域的 `handlePayTimeout` 只取消 `PENDING_PAYMENT` 订单，此刻平台从未收到钱，若调退款会给买家凭空入账（平台余额守卫会失败）。保留「关支付单」这一步。**在方法注释里写清**：待支付超时无资金动作可做；「支付已入平台、订单已因超时取消」这个并发窗口由第 4 步的资金孤儿扫描按「平台有入账无出账」收敛。

### 4. 资金孤儿扫描 → 判据换成「平台有入账无出账」

`scanFundOrphans()` 由两段收敛为**一段**（平台线索已覆盖原两段的全部场景，因为退款/结算都会写 `PLATFORM_OUT`）：

1. `walletAccountService.listOrphanPlatformIn(PLATFORM_ORPHAN_THRESHOLD_MINUTES, SCAN_LIMIT)`：平台 `PLATFORM_IN`、同订单同轮次无 `PLATFORM_OUT`、超过阈值（沿用 10 分钟）、单轮上限沿用 200。
2. 逐行取订单：`idleService.getOrderEntity(walletDetailEntity.getOrderId())`；订单为 null → `log.error`「平台入账流水无对应订单，需人工核查」并跳过（不动钱）。
3. 裁决口径保持「宁可长款」（`COMPLETED` → 补结算、`CANCELLED` → 补退款、其余状态不动钱）：
   - `COMPLETED`：`settleProductOrder(orderId, buyerOrSeller…)` 用**订单上的** `sellerId` + 订单金额（`price × quantity`，`quantity` 为 null 时按 1，与既有一致）；
   - `CANCELLED`：`refundProductOrder(orderId, 订单上的 buyerId, 订单金额)`；
   - 其他状态（`PAID` / `DELIVERED` / `PENDING_PAYMENT`）：`continue`，不动钱——宁可长款等到终态，避免「订单还没完成就退了款」；
   - 退款 / 结算目标一律取**订单**的买家 / 卖家，**不要**再用流水行的 `user_id`（那现在是平台）。
4. 删除 `unfreezeTerminalOrphans`、`replayPaidCancelUnfreeze` 两个私有方法与 `EVENT_CANCELLED` 常量；删除只因第二段存在的 `SETTLED_WINDOW_MINUTES` 常量；`FROZEN_ORPHAN_THRESHOLD_MINUTES` 改名 `PLATFORM_ORPHAN_THRESHOLD_MINUTES` 并改注释口径。
5. `ProductFundOrphanScanSummaryVO` 字段改为 `platformInOrphanTotal`（平台入账无出账候选行数，含跳过的未终态行）/ `settleDone`（补结算数）/ `refundDone`（补退款数），注释重写；`ProductFundOrphanScanJob` 的 `String.format` 文案同步（「平台入账无出账 %d 行（补结算 %d、补退款 %d）」）。三个字段都用上，别留哑字段。
6. `IIdleService.listRecentSettled` / `latestEventOf` 不再有调用方：**可以保留**（域能力，删它超出本票收益），但 `IdleCase` 里不得再引用。

### 5. 旧资金口径退役（接口收敛）

商品链路之外已无调用方，一并删除，避免留下两套并行的资金口径：

- `IWalletAccountService` / `WalletAccountServiceImpl`：删 `freeze(String, BigDecimal, String)`、`freeze(String, BigDecimal, String, WalletDetailTypeEnum)`、`unfreeze(...)` 三个重载、`income(...)` 两个重载、`listOrphanFrozen(...)`；
- `IWalletRepository` / `WalletRepository`：删对应 `freeze` / `unfreeze` / `income` / `listOrphanFrozen` 方法；
- `IWalletDetailDao` / `WalletDetailMapper.xml`：删 `selectOrphanFrozen`；`WalletMapper.xml` 的 `updateFreeze` / `updateUnfreeze` 若已无 DAO 方法引用则连同 `IWalletDao` 的声明一起删除（`recharge` 仍用 `updateIncreaseBalance`，保留它）；
- 删除测试支撑类 `bs-app/src/test/java/com/betterschool/integration/support/WalletIncomeFailureProbe.java`（它 override 的 `income` 已不存在；全仓无使用方）。**不要**碰其它测试文件（票 04 负责）。
- 保留 `WalletDetailTypeEnum.FROZEN` / `UNFROZEN` / `INCOME` / `PRODUCT_FROZEN` / `PRODUCT_UNFROZEN` 枚举常量（历史数据读取，票 01 已定），只删代码路径。

### 6. 注释口径同步（同一票内做完，别留「冻结」字样误导下一个人）

`IdleCase` 顶部类注释与三段分界注释、`ProductConfirmResult` / `ProductCancelResult` 的「解冻/入账」字样、`PaymentCase` 与 `WalletPaymentServiceImpl` 的「扣冻 / 冻结流水」字样、`WalletPayTaskEntity` / `IWalletRepository` / `IWalletAccountService` 里描述商品支付冻结的注释。统一改成：支付 = 买家出账 + 平台入账；完成 = 平台出账 + 卖家入账；取消/超时 = 平台出账 + 买家入账。

**不要碰**：DDL / `sql/`、任何 `docs/`、CONTEXT.md（票 05）、测试断言（票 04）。`IdleCase` 的 `createOrder` / `ensurePayNo` / `payOrder` 三个支付入口不动。

验收清单：

- [x] `confirmReceived` 只调 `settleProductOrder`；`cancelOrder` 只在 `needRefund` 时调 `refundProductOrder`；`handlePayTimeout` 无资金动作且注释解释「晚到支付由孤儿扫描收敛」
- [x] `scanFundOrphans` 单段平台线索：`COMPLETED` 补结算、`CANCELLED` 补退款、未终态不动钱、订单缺失记 ERROR 跳过；目标买家/卖家取自订单而非流水行
- [x] 摘要 VO 三字段与新口径一致，job 文案同步
- [x] 钱包 `freeze` / `unfreeze` / `income` / `listOrphanFrozen` 从 service 与 repository 删除，全仓无残留引用；`WalletIncomeFailureProbe` 删除
- [x] `IdleCase` 仍只注入域 service，未注入 `PaymentCase` / 其他 case
- [x] 全仓 grep `PRODUCT_FROZEN` / `PRODUCT_UNFROZEN` 只剩枚举常量、`@Deprecated` 注释与票 04 待改的测试断言
- [x] 编译闸门：**不运行 mvn / 任何测试**（spec 交付口径）

**落地注记（实现取舍）：**

**孤儿扫描最终形态**：`scanFundOrphans()` 单段——`walletAccountService.listOrphanPlatformIn(PLATFORM_ORPHAN_THRESHOLD_MINUTES=10, SCAN_LIMIT=200)` 取「平台有 `PLATFORM_IN`、同订单同轮次无 `PLATFORM_OUT`、已超阈值」的行，逐行 `idleService.getOrderEntity(orderId)`（订单缺失 `log.error` 跳过、不动钱），按订单终态裁决：`COMPLETED` → `settleProductOrder(orderId, 订单 sellerId, 订单金额)`、`CANCELLED` → `refundProductOrder(orderId, 订单 buyerId, 订单金额)`，其余状态（`PENDING_PAYMENT` / `PAID` / `DELIVERED`）`continue`（宁可长款）。金额用 `ProductOrderEntity.totalAmount()`（`price × quantity`，`quantity` 为 null 按 1，与旧实现同口径），目标买家/卖家一律取自订单，不取流水行的 `user_id`（那现在是平台）。

**摘要 VO 字段名**：`platformInOrphanTotal`（候选行数，含跳过的未终态行 / 订单缺失行）/ `settleDone` / `refundDone`；`ProductFundOrphanScanJob` 文案「商品孤儿扫描完成：平台入账无出账 %d 行（补结算 %d、补退款 %d）」，三个字段都被 job 使用，无哑字段。

**删掉的方法与文件**：
- `IWalletAccountService` / `WalletAccountServiceImpl`：`freeze`（2 个重载）、`unfreeze`（3 个重载）、`income`（2 个重载）、`listOrphanFrozen`；
- `IWalletRepository` / `WalletRepository`：`freeze`（2 个）、`unfreeze`（3 个）、`income`（2 个）、`listOrphanFrozen`；
- `IWalletDetailDao` / `WalletDetailMapper.xml`：`selectOrphanFrozen`；`IWalletDao` / `WalletMapper.xml`：`updateFreeze`、`updateUnfreeze`（`updateIncreaseBalance` 仍被 `recharge` 与卖家/平台入账使用，保留）；
- `IdleCase`：私有 `unfreezeTerminalOrphans` / `replayPaidCancelUnfreeze`、常量 `EVENT_CANCELLED` / `SETTLED_WINDOW_MINUTES`，`FROZEN_ORPHAN_THRESHOLD_MINUTES` 改名 `PLATFORM_ORPHAN_THRESHOLD_MINUTES`；
- 测试支撑类 `WalletIncomeFailureProbe`（全仓无使用方）；
- `WalletDetailTypeEnum` 的 `FROZEN` / `UNFROZEN` / `INCOME` / `PRODUCT_FROZEN` / `PRODUCT_UNFROZEN` 常量全部保留（历史数据读取）。

**与票面不同 / 需要维护者留意的地方**：
1. **注释同步超出票面第 6 节清单**：为让「全仓 grep `PRODUCT_FROZEN`」只剩枚举 / `@Deprecated` / 票 04 测试断言，`IdleServiceImpl`（`confirmPayOrder`、`cancelOrder` 注释里明确写着 `PRODUCT_FROZEN` 与「冻结 / 解冻」现行口径）也同步改成平台过账描述；同时把 `IWalletPaymentService`、`PaymentOrderStateEnum`、`IPaymentNotifyPort`、`PaymentNotifyHandler` 里「扣冻 / 冻结款 / 解冻链路」的现行表述改为出账 / 入账。仅注释，无逻辑改动。
2. **金额取法**：票面写「订单金额（`price × quantity`，`quantity` 为 null 按 1，与既有一致）」，实现直接用实体 `ProductOrderEntity.totalAmount()`（同一口径），不再手写乘法。
3. **历史数据盲区**：单段平台线索只覆盖新口径下的订单（每笔支付都落 `PLATFORM_IN`）。改造前已存在的旧口径冻结/解冻流水即使经票 05 映射成 `PRODUCT_PAY` / `PRODUCT_REFUND`，也没有平台腿，不会被平台扫描收敛——spec 已把存量数据修复划出范围（开发/测试库可重建），这里只提示：旧库不要指望孤儿扫描兜底。
4. **`handlePayTimeout` 保留关支付单**：票面说「保留关支付单」，实现未动该调用，只在方法注释写清「无资金动作 + 晚到支付的并发窗口由孤儿扫描收敛」。
5. **`IIdleService.listRecentSettled` / `latestEventOf`** 按票面保留（域能力），`IdleCase` 已不再引用。
6. **未运行 mvn / 任何测试**（按交付口径）；自查只做了静态 `git grep`。
