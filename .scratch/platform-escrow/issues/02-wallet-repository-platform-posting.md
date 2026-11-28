# 02: 钱包仓储三笔平台过账（买家出账 / 平台出账 / 双流入账）+ 懒创建平台账户

**What to build:** 把商品订单的三笔资金动作从「冻结在买家账上」翻成「平台代收账户过账」。每笔动作仍是**钱包域单仓储事务**（`TransactionTemplate` REQUIRED，事务边界 = 仓储，ADR-0016），每次事务内落**双边流水 + 双边余额**；`frozen_amount` 在商品链路**不再写入**（列保留、历史值保留，只在买家出账的余额守卫里作为减项）。平台账户 = `virtual_wallet` 的一行（`user_id = Constants.PLATFORM_USER_ID`，`balance` 初始 0），**懒创建**（`INSERT IGNORE`，撞 `uk_user` 幂等），不依赖启动顺序或初始化脚本，不新增表、不改 DDL、不改 `payment_order` 结构。

**Blocked by:** 01（需要 `Constants.PLATFORM_USER_ID` 与四个新流水类型）

**Status:** resolved（三笔平台过账 + 平台账户懒创建 + 平台侧孤儿取数口已实现，8 个文件按票落地；按交付口径未运行 mvn / 任何测试）

改动范围（**只改这些文件**）：`bs-domain/.../wallet/adapter/repository/IWalletRepository.java`、`bs-domain/.../wallet/service/IWalletAccountService.java`、`bs-domain/.../wallet/service/WalletAccountServiceImpl.java`、`bs-infrastructure/.../adapter/repository/wallet/WalletRepository.java`、`bs-infrastructure/.../dao/wallet/IWalletDao.java`、`bs-infrastructure/.../dao/wallet/IWalletDetailDao.java`、`bs-app/src/main/resources/mybatis/mapper/WalletMapper.xml`、`bs-app/src/main/resources/mybatis/mapper/WalletDetailMapper.xml`。

### 1. 平台账户懒创建

- `IWalletRepository` / `IWalletAccountService` 新增重载 `ensureWallet(String userId, BigDecimal initialBalance)`；保留 `ensureWallet(String userId)` 并委托 `ensureWallet(userId, new BigDecimal("1000"))`（注册赠送额口径不变，`UserRegisterCase` 无需改动）。
- `IWalletDao.insertIgnore` 已经按 `#{balance}` 落库，不需要改 SQL；`WalletRepository.ensureWallet` 去掉写死的 `INITIAL_BALANCE`，改由参数传入。
- `WalletRepository` 私有方法 `ensurePlatformWallet()` → `ensureWallet(Constants.PLATFORM_USER_ID, BigDecimal.ZERO)`，在三笔资金动作事务的**开头**调用。

### 2. `pay(payNo, userId)`：买家出账 + 平台入账

保持现有前置校验不变（支付单存在 / 归属 / `CLOSED` 拒绝 / `PAID` 幂等返回 / `markPaid` 条件 UPDATE 成功后才动钱）。动钱段改为：

1. 流水先行：买家 `PRODUCT_PAY` 行（`order_id = bizOrderId`、`biz_round = 0`、`user_id = 买家`）。`insertIgnoreDetail` 返回 0 → 该笔已处理过，直接返回 `alreadyPaid = true`，不动任何余额。
2. 平台 `PLATFORM_IN` 行（`user_id = PLATFORM_USER_ID`、同 `order_id`、`biz_round = 0`）。
3. 买家余额条件扣减：`balance = balance - amount`，守卫 `balance - frozen_amount >= amount`（保留对历史冻结额的尊重）；影响 0 行 → 抛 `BusinessException(PARAM_ERROR, "零钱余额不足，无法完成支付")`，整笔回滚（支付单留 `WAIT_PAY`）。
4. 平台余额累加：`updateIncreaseBalance(PLATFORM_USER_ID, amount)`。
5. `wallet_pay_task` 回调任务行照旧在**同一事务**内 `insertIgnore`。
6. **不写 `frozen_amount`**（`updateFreeze` 不再被商品链路调用，方法本身保留给非商品链路）。

### 3. `settleProductOrder(orderId, sellerId, amount)`：平台出账 + 卖家入账

同一 `TransactionTemplate` 内：

1. `ensurePlatformWallet()`。
2. 幂等锚 = **卖家结算行**：`PRODUCT_INCOME`（`order_id`、`biz_round = 0`、`user_id = sellerId`）。`insertIgnoreDetail` 返回 0 → 整笔视为已结算过，直接 `return`，不动任何余额。
3. 平台 `PLATFORM_OUT` 行（`user_id = PLATFORM_USER_ID`、同 `order_id`、`biz_round = 0`）。
4. 平台余额守卫扣减：`UPDATE ... SET balance = balance - amount WHERE user_id = #{userId} AND balance >= #{amount}`；影响 0 行 → **`log.error` 记明订单 / 金额 / 平台余额不足，再抛 `BusinessException(BUSINESS_ERROR, "平台代收余额不足，结算失败")` 整笔回滚**——不静默跳过、不把平台余额扣成负数。
5. 卖家余额累加：`updateIncreaseBalance(sellerId, amount)`。

### 4. `refundProductOrder(orderId, buyerId, amount)`：平台出账 + 买家入账

与 3 同形，仅锚与收款方不同：

1. `ensurePlatformWallet()`。
2. 幂等锚 = **买家退款行**：`PRODUCT_REFUND`（`order_id`、`biz_round = 0`、`user_id = buyerId`）。0 行 → 已退过款，`return`。
3. 平台 `PLATFORM_OUT` 行（同 `order_id`、`biz_round = 0`、`user_id = PLATFORM_USER_ID`）。
4. 平台余额守卫扣减（同 3.4：不足记 ERROR + 抛异常整笔回滚）。
5. 买家余额累加：`updateIncreaseBalance(buyerId, amount)`。

**关于结算与退款共用 `PLATFORM_OUT`**：同一订单只会终态一次（`COMPLETED` 结算 / `CANCELLED` 退款），`uk_order_type(order_id, type, biz_round, user_id)` 因此天然保证「同一订单平台只出账一次」；不要为退款另开一个平台专用类型。这一点写进两方法的 Javadoc。

### 5. 孤儿扫描取数口（平台线索）

- `IWalletDetailDao` + `WalletDetailMapper.xml` 新增 `selectOrphanPlatformIn(platformInType, platformOutType, thresholdMinutes, limit)`：平台有 `PLATFORM_IN`、同订单同轮次**无** `PLATFORM_OUT`、且入账时间早于阈值（沿用 `INTERVAL #{thresholdMinutes} MINUTE`），按 `d.id` 升序 `LIMIT #{limit}`。表内自联结（两处引用同表）按仓库 SQL 规范引入别名 `d` / `u`；`NOT EXISTS` 半连接是仓库唯一豁免的写法，照 `selectOrphanFrozen` 的先例。
- `IWalletRepository` + `WalletAccountServiceImpl` 新增 `List<WalletDetailEntity> listOrphanPlatformIn(int thresholdMinutes, int limit)`，仓储实现委托 DAO 再用现有 `toDetailEntity` 映射。Javadoc 写明「平台侧自查：平台有入账无出账 = 平台已收但未结算/退款」。
- **本票保留** `listOrphanFrozen` / `selectOrphanFrozen`（票 03 会在改完用例后删除它们）；同理保留 `freeze` / `unfreeze` / `income`。
- 域服务新增对外方法 `settleProductOrder` / `refundProductOrder`（签名 `(String orderId, String sellerId|buyerId, BigDecimal amount)`），Javadoc 写明「平台出账 + 收款方入账，单仓储事务，双边流水幂等；平台余额不足整笔回滚」。

**不要碰**：`IdleCase`（票 03）、测试树（票 04）、任何文档与 `sql/`（票 05）、`payment_order` / `product_order` 的 DDL、`WalletDetailTypeEnum`（票 01 已加好类型）。

验收清单：

- [x] 三笔动作各自一个 `TransactionTemplate.execute`（REQUIRED）：`pay` / `settleProductOrder` / `refundProductOrder` 各一个，事务内先落双边流水再动双边余额，任一步抛异常整笔回滚（含流水）
- [x] 幂等锚在业务侧那一行：买家 `PRODUCT_PAY`（0 行 ⇒ `alreadyPaid=true` 返回）/ 卖家 `PRODUCT_INCOME`（0 行 ⇒ return）/ 买家 `PRODUCT_REFUND`（0 行 ⇒ return），锚 0 行时一律不碰余额
- [x] 买家出账守卫 `balance - frozen_amount >= amount`（`updateDecreaseAvailableBalance`，0 行抛 `PARAM_ERROR`「零钱余额不足，无法完成支付」）；平台出账守卫 `balance >= amount`（`updateDecreaseBalance`，0 行 `log.error` 记订单 / 金额 / 平台余额 + 抛 `BUSINESS_ERROR`）
- [x] 商品链路不再写 `frozen_amount`：`pay` 里原来的 `updateFreeze` 调用已删除，商品三笔只做 `balance` 加减；`updateFreeze` / `updateUnfreeze` 保留，调用方只剩保留方法 `freeze` / `unfreeze` 自身
- [x] 平台行懒创建：每个资金事务开头 `ensurePlatformWallet()` → `ensureWallet(Constants.PLATFORM_USER_ID, BigDecimal.ZERO)`，`INSERT IGNORE` 撞 `uk_user` 幂等，`balance` 从 0 起
- [x] `listOrphanPlatformIn` 单表内自查：`selectOrphanPlatformIn` 在 `wallet_detail` 上自联结（别名 `d` / `u`）+ `NOT EXISTS` 半连接，返回平台入账无出账且超阈值的行，按 `d.id` 升序 `LIMIT`
- [x] 编译闸门：**未运行 mvn / 任何测试**（spec 交付口径，由维护者自行验证）；改动只增方法 / 调用并改注释，8 个文件自洽——DAO 方法名与 mapper `id` 逐一对应、XML 已用解析器验证 well-formed、无悬空引用

**落地注记（实现取舍）：**

- **SQL 方法名最终定为**：`IWalletDao.updateDecreaseAvailableBalance(userId, amount)`（买家出账，守卫 `balance - frozen_amount >= amount`）、`IWalletDao.updateDecreaseBalance(userId, amount)`（平台出账，守卫 `balance >= amount`）；孤儿扫描 `IWalletDetailDao.selectOrphanPlatformIn(platformInType, platformOutType, thresholdMinutes, limit)`，仓储 / 域服务对外 `List<WalletDetailEntity> listOrphanPlatformIn(int thresholdMinutes, int limit)`。`insertIgnore` / `insertIgnoreDetail` 的 SQL 一字未改（`insertIgnore` 本来就按 `#{balance}` 落库）。
- **事务内语句顺序**（三笔一致：「平台账户懒创建 → 双边流水 → 双边余额」，任一步失败整笔回滚）：
  - `pay`：`ensurePlatformWallet()` → 原有前置校验 / `markPaid` → 买家 `PRODUCT_PAY`（幂等锚，0 行 ⇒ `alreadyPaid=true` 返回、不动余额）→ 平台 `PLATFORM_IN` → `updateDecreaseAvailableBalance(买家)` → `updateIncreaseBalance(平台)` → `wallet_pay_task` 行。
  - `settleProductOrder`：`ensurePlatformWallet()` → 卖家 `PRODUCT_INCOME`（幂等锚，0 行 ⇒ return）→ 平台 `PLATFORM_OUT` → `updateDecreaseBalance(平台)`（0 行 ⇒ `log.error` + 抛异常）→ `updateIncreaseBalance(卖家)`。
  - `refundProductOrder`：与结算同形，锚换成买家 `PRODUCT_REFUND`、收款方换成买家，平台腿同为 `PLATFORM_OUT`。
- **「结算与退款共用 `PLATFORM_OUT`」的幂等怎么处理**：幂等锚严格按票面落在业务侧那一行（卖家 `PRODUCT_INCOME` / 买家 `PRODUCT_REFUND`），锚 `INSERT IGNORE` 返回 0 即整笔已处理、直接 return。平台那一腿**不当锚**，但它的 0 行**不被静默忽略**：写不进来说明同订单平台早已出账（典型：已退款订单又被要求结算），此时两边账已不一致，`log.error` 后抛 `BusinessException(BUSINESS_ERROR, "平台流水已存在，资金状态不一致")` 整笔回滚。三笔动作共用私有方法 `insertPlatformDetail(type, orderId, amount)`，`pay` 的 `PLATFORM_IN` 走同一守卫。
- **发现票面写错 / 不严谨的地方**：
  1. 票面 §4「`uk_order_type` 因此天然保证『同一订单平台只出账一次』」只有在平台腿 0 行时**报错**才成立。`INSERT IGNORE` 的 0 行若被吞掉，唯一键只挡住「不重复写流水」，挡不住余额被第二次扣减（已退款订单再调结算会二次扣平台余额）。故补了上一条的硬守卫——这也是与票面最实质的差异。
  2. 票面 §1 让 `ensureWallet(userId)` 委托 `new BigDecimal("1000")`：实现保留命名常量 `REGISTER_GIFT_BALANCE`（原 `INITIAL_BALANCE` 改名，因为它现在只描述注册赠送额），避免魔法数；行为一致，`UserRegisterCase` 未动。
  3. 票面 §5 的平台孤儿 `NOT EXISTS` 只要求 `order_id` + `biz_round`：实现额外比了 `u.user_id = d.user_id`——平台入 / 出两腿的 `user_id` 都是平台账户，判据等价，但自联结能走满 `uk_order_type(order_id, type, biz_round, user_id)` 的完整列（与 `selectOrphanFrozen` 保持同形）。
  4. 票面 §4.4 对退款只写「同 3.4」：文案按方向改成 `平台代收余额不足，退款失败`（结算侧用票面原文 `平台代收余额不足，结算失败`）。
- **其他取舍**：`ensurePlatformWallet()` 放在 `pay` 事务最开头（票面要求），因此支付因余额不足回滚时平台建行也一起回滚；并发下 `INSERT IGNORE` 撞 `uk_user` 只是行锁等待，幂等无害。商品链路已无任何 `frozen_amount` 写入点，`freeze` / `unfreeze` / `income` / `listOrphanFrozen` / `updateFreeze` / `updateUnfreeze` 全部保留给非商品链路（票 03 才有权删）。
- **未碰**：`IdleCase`（票 03）、测试树（票 04）、文档与 `sql/`（票 05）、`WalletDetailTypeEnum` / `Constants`（票 01）。已 merge `feat/platform-escrow` 顶端（`f12dd8e`，含票 05 文档与 ADR-0019），无冲突。
