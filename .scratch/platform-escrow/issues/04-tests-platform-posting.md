# 04: 集成测试同步到平台代收口径（支付 / 取消 / 结算）

**What to build:** 把既有两个资金链路集成测试从「冻结/解冻」翻到「出账/入账 + 平台代收」，并补一个结算（确认收货）测试——这是本次改造新增的最大行为，没它 seller 侧资金归属没有回归网。主缝仍是商品订单用例 `IdleCase` 的公开方法（`payOrder` / `cancelOrder` / `markDelivered` / `confirmReceived`）；断言只测外显：订单状态、买家/平台/卖家三方余额、`wallet_detail` 的流水行（类型 + 金额 + 订单号）、重复调用不重复动钱。**不断言事务内部顺序与调用次数**，不用探针注入。

**Blocked by:** 03（需要终态的用例 API 与流水类型名）

**Status:** ready-for-agent

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

- [ ] 两个既有测试的类型名/金额/余额断言全部翻到新口径，无「冻结/解冻」残留
- [ ] 新增结算测试覆盖「正常结算 / 重复确认 / 取消后不结算」三个外显方向
- [ ] 平台余额断言在「平台账户行不存在」时按 0 处理
- [ ] 只动测试文件，不动生产代码、不动 `PostApiTestBase`
- [ ] 编译闸门：**不运行 mvn / 任何测试**（spec 交付口径；测试正确性由维护者本地验证）

**落地注记（实现取舍）：** 待实现者补。
