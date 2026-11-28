# 资金模型从「冻结」改为「平台代收」：买家先付给平台，完成/取消时平台出账

商品订单的资金归属改为**平台代收**：买家支付时余额真正减少、平台账户余额增加；订单完成时平台出账、卖家入账；取消 / 支付超时由平台出账、退回买家。平台账户就是 `virtual_wallet` 的一行（`user_id = types.wallet.Constants.PLATFORM_USER_ID`，`balance` 从 0 起、懒创建），不新增账户表、不改 `payment_order` / `product_order` 结构。`frozen_amount` 在商品链路退役（列保留、历史值保留，只作买家出账守卫的减项）。

**为什么改**：ADR-0018 把钱包改造成模拟支付渠道后，支付仍只是抬高买家的 `frozen_amount`——钱没有真正离开买家账户，平台侧没有任何一条账能回答「这笔货款现在由谁持有」。更严重的是账本身不平：按「可用余额 = `balance − frozen_amount`」的口径，支付只加冻结不减余额；确认收货又是「解冻买家（只减冻结、不加余额）+ 给卖家入账」，等于买家锁定的钱被释放、卖家又凭空收到一笔，同一笔货款被记了两次。用户明确要的模型是「买家先把钱付给平台，平台托管，订单完成后平台再付给卖家」——既不是冻结在买家账上，也不是一步式转账。

## Decision

- **平台账户 = `virtual_wallet` 的一行**：`user_id = types.wallet.Constants.PLATFORM_USER_ID`（与系统通知助手 `SYSTEM_USER_ID` 分开，避免一个账号两种角色），`balance` 初始 `0`、`frozen_amount` 恒 `0`。播种走懒创建：`ensureWallet(userId, initialBalance)` 增加带初始余额的重载，注册路径仍用注册赠送额、平台路径用 `0`，`INSERT IGNORE` 撞 `uk_user` 幂等。不新增账户表，**`payment_order` / `product_order` 结构零改动**（收银台、订单详情、回调链路都不受影响）。
- **三笔资金动作改成平台过账**，每笔仍是一个钱包域仓储事务（ADR-0016），双边各落一条流水：

  | 时点 | 买家 | 平台 | 卖家 | 流水 |
  |---|---|---|---|---|
  | 支付（收银台确认） | `balance − 金额` | `balance + 金额` | — | `PRODUCT_PAY` + `PLATFORM_IN` |
  | 完成（买家确认收货） | — | `balance − 金额` | `balance + 金额` | `PLATFORM_OUT` + `PRODUCT_INCOME` |
  | 取消 / 支付超时 | `balance + 金额` | `balance − 金额` | — | `PLATFORM_OUT` + `PRODUCT_REFUND` |

- **三笔必须一起改**：只改支付这一笔，平台会只进不出——确认收货后卖家拿到钱、平台余额却永远不减，总账凭空多钱。完成与取消不是「其他链路」，是同一笔货款的后半段。
- **仓储方法收敛为两个原子动作**：`settleProductOrder(orderId, sellerId, amount)`（平台出账 + 卖家入账）与 `refundProductOrder(orderId, buyerId, amount)`（平台出账 + 买家入账）；`pay(payNo, userId)` 内部改成买家出账 + 平台入账。每个都在一个 `TransactionTemplate`（`REQUIRED`）里完成「双边流水 + 双边余额更新」，任一步失败整笔回滚。结算与退款共用的「平台配对流水 → 平台守卫扣减 → 收款方加余额」抽成一个私有方法，幂等锚仍留在两个 public 方法里（锚类型是两段唯一的真差异）。跨域顺序仍由用例层（`IdleCase`）编排，不引入跨域事务。商品链路不再调 `freeze` / `unfreeze` / `income`，这三个方法若无其他调用方一并删除，避免留下两套并行的资金口径。
- **流水双边语义**（`wallet_detail.type`，枚举共存、历史值保留）：
  - `PRODUCT_PAY`：买家支付出账，承接原 `PRODUCT_FROZEN`；
  - `PRODUCT_REFUND`：买家退款入账，承接原 `PRODUCT_UNFROZEN`；
  - `PRODUCT_INCOME`：卖家入账（语义不变）；
  - `PLATFORM_IN` / `PLATFORM_OUT`：平台收 / 付。**结算与退款都走 `PLATFORM_OUT`**，方向由订单终态判定（`COMPLETED` = 结算、`CANCELLED` = 退款）；同一订单二者互斥，因此 `uk_order_type (order_id, type, biz_round, user_id)` 天然防双出账。
  - `RECHARGE` / `FROZEN` / `UNFROZEN` / `INCOME` 保留，但商品链路不再产生。
- **幂等锚 = 业务侧那一行**：买家支付行（`PRODUCT_PAY`）、卖家结算行（`PRODUCT_INCOME`）、买家退款行（`PRODUCT_REFUND`）。`INSERT IGNORE` 影响 0 行即整笔视为已处理、直接返回；平台侧那行同事务写入，不需要额外的锚。
- **平台腿的唯一键是硬守卫，不是「静默跳过」**：平台配对流水（`PLATFORM_IN` / `PLATFORM_OUT`）的 `INSERT IGNORE` 撞 0 行（`uk_order_type` 冲突）时不当作「已处理过」继续动余额，而是 ERROR + 抛异常整笔回滚。静默跳过会让同一订单被平台收 / 付第二次、而两边账已经不一致；把唯一键当硬守卫，等于让「平台对同一订单只收一次、只付一次」由数据库约束守住，而不是靠调用顺序的善意。
- **余额守卫**：买家出账用 `balance − frozen_amount >= amount`（保留对历史冻结额的尊重）；平台出账用 `balance >= amount`，不足视为不变量破坏——抛异常整笔回滚 + ERROR 日志，不静默跳过、不容许把平台余额扣成负数。卖家 / 买家入账只加余额。
- **`frozen_amount` 在商品链路退役**：不再冻结、不再解冻；列与历史值保留，只在买家出账守卫里作为减项。其他历史流程若仍写它，行为不变。
- **对账不变量**：平台 `balance` = Σ（有 `PLATFORM_IN` 且无 `PLATFORM_OUT` 的订单金额之和）。本期只把口径写进文档，**不新增定时对账 job**；运营用一条 SQL 即可核对。
- **资金孤儿扫描判据换成「平台有入账无出账」**：同订单有 `PLATFORM_IN`、无 `PLATFORM_OUT`、且超过阈值（沿用 10 分钟）；裁决口径仍是「宁可长款」——订单 `COMPLETED` 补结算、`CANCELLED` 补退款、其余状态不动钱。退款目标从流水行 `user_id`（现在是平台）改为订单的买家 / 卖家。**这个判据只覆盖改造后真的过了平台账的订单**：旧冻结口径生成的订单没有平台腿，扫描看不见（见 Consequences「存量库需人工核对」）。
- **支付超时不退款**：`handlePayTimeout` 只关支付单（`WAIT_PAY → CLOSED`）、没有任何资金动作——超时只取消 `PENDING_PAYMENT` 订单，平台从未收到这笔钱，退款等于给买家凭空入账。spec 里「取消 / 支付超时 → 平台退款」指的是**确实收到过钱**的场景（已支付订单取消）；「支付已进平台、订单却因超时被取消」的并发窗口由孤儿扫描按「有入账无出账」收敛（订单 `CANCELLED` 时补退款）。

## Considered Options

| 方案 | 为什么否 |
|---|---|
| 新建「平台账户表」（`platform_account` 之类） | 账户模型不同构：账户 = 余额 + 流水 + 幂等键，`virtual_wallet` 已经全有。新表要复制流水表、幂等键与守卫 SQL，零收益；用户明确要「平台就是 `virtual_wallet` 的一行」 |
| 只改支付这一笔（支付扣买家余额 + 平台入账），完成/取消沿用解冻 | 平台只进不出：确认收货后卖家拿到钱、平台余额永远不减，总账凭空多钱；取消则买家既被解冻、平台又留着钱，同一笔货款记两次。三笔是同一笔货款的三段，拆开改等于中途换口径 |
| 给 `payment_order` 加 `REFUNDED` 状态表达退款 | 退款已有独立事实源（`PLATFORM_OUT` + `PRODUCT_REFUND` 双边流水 + `uk_order_type` 幂等），支付单重复表达只会引入第二条真相；且结算这个方向支付单根本表达不了（ADR-0018 已否过 REFUNDED） |
| 保留冻结，另加一条平台行「双记一次」 | 支付时既要冻结买家、又给平台记一笔入账，等于同一笔钱在两处同时存在，对账时无法判定平台余额是否真实；「冻结额」从此只是账面噪声，退役比保留干净 |
| 平台出账前不校验余额（只记日志继续） | 余额扣成负数后，对账不变量失去意义，且后续退款会继续把负数越滚越大；不变量破坏必须整笔回滚并暴露 ERROR，宁可让退款/结算失败被扫描重试，也不静默产出坏账 |

## Consequences

**正向**

- 钱在谁手里说得清：平台 `balance` 就是「已代收未结算/未退款」的总额，运营能直接用一条 SQL 对账。
- 账平了：三笔动作各自双边成对，买家、平台、卖家三方余额之和在每个时点都可解释，不再出现「解冻 + 入账」的重复记账。
- 语义与产品对齐：流水类型名（`PRODUCT_PAY` / `PRODUCT_REFUND` / `PLATFORM_IN` / `PLATFORM_OUT`）就是业务动作，对账不再需要二次解释。
- 支付单、收银台、进程内回调、支付单状态机、订单状态机全部不变；`payment_order` / `product_order` 零 DDL 改动。
- 事务边界不变：三笔各自是钱包域单仓储事务，平台行属于钱包域，不引入跨域事务（ADR-0016）。

**负向（已接受）**

- **平台账户是一个全局热点行**：所有支付 / 结算 / 退款都更新同一行，并发受行锁串行化。课程项目量级可接受；若未来量大需拆分或改为记账式汇总，另立规格。
- 平台余额为 0 起，**结算 / 退款可能因平台余额不足而失败**。此时整笔回滚 + ERROR；但**新判据的孤儿扫描兜不住存量旧单**：扫描只认「有 `PLATFORM_IN` 无 `PLATFORM_OUT`」，按旧冻结口径生成的订单本来就没有平台腿、类型映射后也没有，扫描永远看不见它们。因此**开发 / 测试库按 spec 直接重建**（`reset-test-db`），**存量库需人工核对**（spec 的 Out of Scope 已把存量数据修复划出去）。这是把「账不平」换成「可观测的失败 + 一次人工核对」，是本次接受的代价。
- 历史 `wallet_detail` 行需要一次性类型映射（见 Migration），映射后旧行的类型与当时的实际动作仍有一层解释成本（旧 `PRODUCT_FROZEN` 行在旧口径下并没有真的扣余额）。
- 运行中对账仍靠人工 SQL（本期不建定时 job），没有自动告警。

## Migration

- **历史 `wallet_detail` 一次性映射**（`backend/better-school/sql/migrations/2026-11-29-wallet-detail-type-mapping.sql`，可重复执行、幂等）：

  ```sql
  UPDATE wallet_detail SET type = 'PRODUCT_PAY'    WHERE type = 'PRODUCT_FROZEN';
  UPDATE wallet_detail SET type = 'PRODUCT_REFUND' WHERE type = 'PRODUCT_UNFROZEN';
  ```

  改造前（冻结口径）落的历史行执行一次即与新类型对齐；之后重复执行无匹配行即无操作。开发 / 测试库亦可直接 `scripts/test-env.sh reset-test-db` 重建。
- **代码**：`bs-types` 新增钱包域常量 `types.wallet.Constants.PLATFORM_USER_ID`；`WalletDetailTypeEnum` 新增 `PRODUCT_PAY` / `PRODUCT_REFUND` / `PLATFORM_IN` / `PLATFORM_OUT`（`PRODUCT_FROZEN` / `PRODUCT_UNFROZEN` 保留为历史枚举值，兼容存量行）；`IWalletRepository` / `WalletRepository` 新增 `settleProductOrder` / `refundProductOrder`、`pay` 改双边过账、`ensureWallet` 增带初始余额重载、孤儿扫描改「有入账无出账」判据；`IdleCase` 改调两个新方法，`scanFundOrphans` 改判据。
- **文档随票**：`CONTEXT.md`（支付段 + 平台代收账户 / 平台出账词条）、`backend/better-school/docs/db/user.md`、`docs/db/idle.md`、`docs/db/outbox.md`、`docs/api/idle.md`、`docs/api/payment.md`、`sql/schema.sql` 注释、ADR-0018 加取代注记。
- **不做**：`payment_order` / `product_order` / `virtual_wallet` 的任何列、索引、约束变更；不建定时对账 job；多商户 / 分账、平台提现充值、真实微信/支付宝接入均不在本次范围。

## References

- `docs/adr/0015-fund-flow-composite-tx-rollout.md`（资金链路组合事务的起点；其核心决定已被 ADR-0016 取代）
- `docs/adr/0016-tx-boundary-is-repository.md`（事务边界 = 仓储、两段式、宁可长款——本次三笔动作的边界与顺序判据）
- `docs/adr/0018-wallet-as-payment-gateway.md`（支付单 / 收银台 / 进程内回调 / 支付单状态机；资金归属口径由本 ADR 取代）
- `backend/better-school/docs/db/user.md`（`virtual_wallet` / `wallet_detail` / `payment_order` / `wallet_pay_task` 字段与语义）
- `.scratch/platform-escrow/spec.md`（本次改造的规格与取舍来源）
