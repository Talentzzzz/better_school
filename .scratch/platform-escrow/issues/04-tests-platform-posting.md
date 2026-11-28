# 04: 集成测试同步到平台代收口径（支付 / 取消 / 结算）

**What to build:** 把既有两个资金链路集成测试从「冻结/解冻」翻到「出账/入账 + 平台代收」，并补一个结算（确认收货）测试——这是本次改造新增的最大行为，没它 seller 侧资金归属没有回归网。主缝仍是商品订单用例 `IdleCase` 的公开方法（`payOrder` / `cancelOrder` / `markDelivered` / `confirmReceived`）；断言只测外显：订单状态、买家/平台/卖家三方余额、`wallet_detail` 的流水行（类型 + 金额 + 订单号）、重复调用不重复动钱。**不断言事务内部顺序与调用次数**，不用探针注入。

**Blocked by:** 03（需要终态的用例 API 与流水类型名）

**Status:** resolved（两个既有资金测试翻到「买家出账 / 平台代收 / 平台退款」新口径，新增结算测试覆盖正常结算 / 重复确认 / 取消后不结算三方向，平台余额断言容忍账户行缺失）

改动范围（**只改测试树**）：`bs-app/src/test/java/com/betterschool/integration/idle/ProductPayCompositeTxIntegrationTest.java`、`.../ProductCancelFundConsistencyIntegrationTest.java`、新增 `.../ProductSettleFundConsistencyIntegrationTest.java`。基类 `PostApiTestBase`、测试库配置一概不动。

### 1. `ProductPayCompositeTxIntegrationTest`（改口径）

- 支付成功（2 × 19.90 = 39.80）：订单 `PAID`；买家 `balance = 960.20`、`frozen_amount = 0`；**平台 `balance = 39.80`**；流水恰两条——买家 `PRODUCT_PAY`（39.80）+ 平台 `PLATFORM_IN`（39.80），同一 `order_id`。
- 余额不足（单价 2000 > 初始 1000）：`payOrder` 抛 `BusinessException`；订单留 `PENDING_PAYMENT`；无 `PRODUCT_PAY` / `PLATFORM_IN` 行；买家 `balance` 仍 1000；平台余额仍 0；无 `PAID` 事件。
- 幂等重放：连调两次 `payOrder`，仍是各一条流水、买家扣一次 39.80、平台加一次 39.80。
- 类注释与断言消息改成新口径（把「冻结」二字全部替换掉）。

### 2. `ProductCancelFundConsistencyIntegrationTest`（改口径）

- 已支付订单取消：订单 `CANCELLED`；买家 `balance = 1000`、`frozen_amount = 0`；平台 `balance = 0`；流水恰两条——买家 `PRODUCT_REFUND`（39.80）+ 平台 `PLATFORM_OUT`（39.80）。
- 幂等重放：再取消一次，退款流水仍各一条，余额不再变化（买家 1000、平台 0）。
- 待支付订单取消：无任何流水、买家 1000、平台 0（平台账户很可能还没被创建 → 余额断言用「行缺失视为 0」的辅助方法，别让 `queryForObject` 抛 `EmptyResultDataAccessException`）。
- 类注释同步去「解冻」。

### 3. 新增 `ProductSettleFundConsistencyIntegrationTest`（结算）

至少三个用例：

1. 确认收货结算：支付 → `idleCase.markDelivered(orderId, sellerId)` → `idleCase.confirmReceived(orderId, buyerId)`；断言订单 `COMPLETED`、**卖家 `balance = 1039.80`**、买家仍 `960.20`（确认收货不动买家余额）、平台 `balance = 0`；流水恰两条——卖家 `PRODUCT_INCOME`（39.80）+ 平台 `PLATFORM_OUT`（39.80）。
2. 重复确认幂等：再调一次 `confirmReceived`，卖家仍 1039.80、平台仍 0、两类流水各仍一条。
3. 取消的订单永不结算：支付后取消，再调 `confirmReceived` 应失败（`BusinessException`，订单已非 `DELIVERED`）；断言卖家 `balance = 1000`（未入账）、无 `PRODUCT_INCOME` 行、平台 `balance = 0`（退款已出账）。

### 4. 平台余额断言工具

测试里平台账户用 `Constants.PLATFORM_USER_ID`（`com.betterschool.types.common.Constants`）取数：`SELECT balance FROM virtual_wallet WHERE user_id = ?`，行不存在返回 `BigDecimal.ZERO`。买方 / 卖方断言沿用既有 `balanceOf` + `frozenAmountOf` 形状（`frozenAmountOf` 在支付后必须断言为 0，这是 `frozen_amount` 退役的外显物证）。

验收清单：

- [x] 两个既有测试的类型名/金额/余额断言全部翻到新口径，无「冻结/解冻」残留（`冻结` 只剩 `frozen_amount` 退役断言里的「冻结额应为 0」表述；`PRODUCT_FROZEN` / `PRODUCT_UNFROZEN` 在测试树零命中）
- [x] 新增结算测试覆盖「正常结算 / 重复确认 / 取消后不结算」三个外显方向
- [x] 平台余额断言在「平台账户行不存在」时按 0 处理（`platformBalance()` 用 `queryForList` + 空列表回落 `BigDecimal.ZERO`）
- [x] 只动测试文件，不动生产代码、不动 `PostApiTestBase`（3 个文件全在 `bs-app/src/test/.../integration/idle/`）
- [x] 编译闸门：**不运行 mvn / 任何测试**（spec 交付口径；测试正确性由维护者本地验证）

**落地注记（实现取舍）：**

一、**三个测试类共 9 个用例（只改测试树 3 个文件）**。所有断言都落在订单状态 / 买家·平台·卖家三方余额 / `wallet_detail` 行（type + amount + order_id）上，方法名中文、`assertThat(...).as("中文说明")`、私有工具形状与既有测试一致。

- `ProductPayCompositeTxIntegrationTest`（改口径，3 例）：`支付成功_订单转已支付且买家出账平台入账`（PAID；双边恰两行 `PRODUCT_PAY` + `PLATFORM_IN`，金额均 39.80；买家 960.20、平台 39.80、`frozen_amount` 0）；`余额不足_支付整事务回滚订单留待支付`（`BusinessException`；订单留 `PENDING_PAYMENT`；零流水；买家 1000；平台按缺行 = 0；无 `PAID` 事件）；`幂等重放_同结果无重复出账`（再调一次仍是两行流水、买家只扣一次、平台只加一次）。
- `ProductCancelFundConsistencyIntegrationTest`（改口径，3 例）：`已支付订单取消_订单取消且平台退款买家余额复原`（`CANCELLED`；退款一对 `PRODUCT_REFUND` + `PLATFORM_OUT` 各 39.80；买家回 1000、平台 0、`frozen_amount` 0）；`已取消订单重放_终态短路同结果不重复退款`（整单仍四条流水、余额不再变）；`待支付订单取消_无流水资金零副作用`（零流水、买家 1000、平台缺行 = 0）。
- `ProductSettleFundConsistencyIntegrationTest`（新增，3 例）：`确认收货_平台结算卖家入账订单完成`（`COMPLETED`；结算一对 `PRODUCT_INCOME` + `PLATFORM_OUT` 各 39.80；卖家 1039.80、买家仍 960.20、平台 0）；`重复确认_幂等不重复结算`（卖家仍 1039.80、平台仍 0、整单仍四条流水）；`已取消订单_永不结算`（再调 `confirmReceived` 抛 `BusinessException`；卖家仍 1000、无 `PRODUCT_INCOME`、平台 0、整单四条里没有结算腿）。

二、**平台余额断言怎么处理缺行**：三个类各自有一个 `platformBalance()`，用 `jdbcTemplate.queryForList("SELECT balance FROM virtual_wallet WHERE user_id = ?", BigDecimal.class, Constants.PLATFORM_USER_ID)`，空列表返回 `BigDecimal.ZERO`。平台账户是活动事务里懒创建的：余额不足支付、待支付取消两种用例里该行根本不存在，`queryForObject` 会抛 `EmptyResultDataAccessException`。买方 / 卖方仍用严格 `balanceOf`（行必须存在）。

三、**发现票面写错 / 含糊的地方**：

1. 票 §2 / §3 说取消与结算「流水恰两条」，字面不成立：`wallet_detail` 只追加不删行，支付时落的两条（`PRODUCT_PAY` + `PLATFORM_IN`）仍在，取消 / 结算后**整单是四条**。已按「退款 / 结算动作恰新增一对（每类各 1 行，无重复落账）+ 整单四条且类型集合确定」断言，没有把「恰两条」写成整单行数。
2. 票 §1 的「幂等重放」实际测的是**用例层短路**（`IdleCase.payOrder` 见订单已非 `PENDING_PAYMENT` 直接 return，根本没进钱包），而票面把幂等归因于 `uk_order_type`。对外的「不重复动钱」结论不变，但维护者不该指望这个用例覆盖钱包流水的唯一键分支；真正的钱包幂等由 §3 的重复确认（`PRODUCT_INCOME` 锚）与取消重放（终态短路）分别覆盖。
3. 票 §4 说平台账户行用 `Constants.PLATFORM_USER_ID` 取数，已照做；补充事实：支付失败回滚时**平台懒创建行一并回滚**（`ensurePlatformWallet` 在同一钱包事务里），所以「缺行 = 0」不只出现在待支付取消。

四、**断言依赖的关键实现事实（未来改动会直接让测试变红）**：`payOrder` 后的订单状态依赖 `WalletPaymentServiceImpl.pay` 提交后**同步**调 `IPaymentNotifyPort`（`PaymentNotifyHandler` 进程内回调）；若将来把回调改成 MQ 异步，`支付成功_…` / `结算…` 两类的 `PAID` / `COMPLETED` 断言会立刻失败。另：结算与退款共用 `PLATFORM_OUT`，同单第二笔平台出账会被唯一键判为不一致并整笔回滚，所以本票按「同一订单只有一条终态资金路」只构造单终态用例。
