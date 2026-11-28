# 02: 钱包仓储三笔平台过账（买家出账 / 平台出账 / 双流入账）+ 懒创建平台账户

**What to build:** 把商品订单的三笔资金动作从「冻结在买家账上」翻成「平台代收账户过账」。每笔动作仍是**钱包域单仓储事务**（`TransactionTemplate` REQUIRED，事务边界 = 仓储，ADR-0016），每次事务内落**双边流水 + 双边余额**；`frozen_amount` 在商品链路**不再写入**（列保留、历史值保留，只在买家出账的余额守卫里作为减项）。平台账户 = `virtual_wallet` 的一行（`user_id = Constants.PLATFORM_USER_ID`，`balance` 初始 0），**懒创建**（`INSERT IGNORE`，撞 `uk_user` 幂等），不依赖启动顺序或初始化脚本，不新增表、不改 DDL、不改 `payment_order` 结构。

**Blocked by:** 01（需要 `Constants.PLATFORM_USER_ID` 与四个新流水类型）

**Status:** ready-for-agent

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

- [ ] 三笔动作各自一个 `TransactionTemplate.execute`，事务内完成双边流水 + 双边余额；任一步失败整笔回滚（含流水）
- [ ] 幂等锚在业务侧那一行（买家 `PRODUCT_PAY` / 卖家 `PRODUCT_INCOME` / 买家 `PRODUCT_REFUND`）：锚 `INSERT IGNORE` 影响 0 行即整笔返回，不动余额
- [ ] 买家出账守卫 `balance - frozen_amount >= amount`；平台出账守卫 `balance >= amount` 且不足时 ERROR 日志 + 抛异常
- [ ] 商品链路不再写 `frozen_amount`；`updateFreeze` / `updateUnfreeze` 保留但无商品调用方
- [ ] 平台行懒创建，`balance` 从 0 起，撞 `uk_user` 幂等
- [ ] `listOrphanPlatformIn` 可在 `wallet_detail` 单表内自查（自联结别名 d/u），返回平台入账无出账且超阈值的行
- [ ] 编译闸门：**不运行 mvn / 任何测试**（spec 交付口径）；改动后本模块应能整体编译（不引入悬空引用）

**落地注记（实现取舍）：** 待实现者补。
