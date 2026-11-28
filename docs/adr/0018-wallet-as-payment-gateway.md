# wallet 改造成「模拟微信支付」渠道：支付单 + 收银台 + 进程内回调

钱包曾是用户可见的「余额 / 充值 / 流水」功能，商品订单在订单页点支付时直接冻结钱包。本次把钱包改造成一个**对用户隐藏的模拟支付渠道**：它扮演微信支付，商户（idle 域）下单时向它预下单，用户拿支付单号进独立收银台支付，支付成功后钱包通过进程内端口回调商户。资金仍从钱包可用余额扣（冻结托管），但「钱包」不再是一个用户功能。

**为什么改**：用户拍板要一条完整、可演示的模拟支付链路——独立收银台页、支付单号、支付成功后回调通知商户，而不是「点支付即冻结」的一步式动作。钱包的余额/充值/流水对用户没有产品价值（余额由注册赠送、没有真实充值渠道），继续暴露只会让人以为存在一个可充值的钱包。把它收敛成「微信支付」的角色，既保住资金托管与两段式一致性口径，又让支付流程符合真实第三方支付的交互形态。

## Decision

- **钱包对用户隐藏**：删除用户端 `/api/wallet/**`（余额 / 充值 / 流水）与前端钱包页、个人页钱包入口。资金动作只在支付链路内部发生，用户不再直接看到 `virtual_wallet`。
- **新增支付单 `payment_order`**（归钱包域）：模拟微信支付的「统一下单」产物，字段 `pay_no`(uk)、`biz_order_id`(uk)、`user_id`、`amount`、`subject`、`state`(`WAIT_PAY/PAID/CLOSED`)、`pay_time`。一单一支付单，预下单按 `biz_order_id` 幂等（`INSERT IGNORE` 撞键回读既有行，返回同一 `payNo`）。
- **下单即预下单，best-effort**：`createOrder` 落订单（PENDING_PAYMENT）+ 扣库存 + 超时任务后，best-effort 向钱包预下单拿 `payNo`；预下单失败不拖垮下单，订单详情 / 支付前按 `biz_order_id` 幂等懒创建。订单详情对「买家 + 待支付」返回 `payNo`。
- **独立收银台**：新增 `/api/pay`（`GET /api/pay/orders/{payNo}`、`POST /api/pay/orders/{payNo}/pay`）与前端 `/pay/:payNo` 页；前端订单页支付按钮改为跳收银台，删除 `POST /api/idle/orders/{orderId}/pay` 与 `IIdleService.payProductOrder`。
- **支付事务仍是单仓储事务**（ADR-0016）：`WalletRepository.pay` 一次事务内完成「支付单 WAIT_PAY→PAID（条件 UPDATE 先行，并发双支付只有一个抢到）→ PRODUCT_FROZEN 流水（`uk_order_type` 幂等）→ 余额条件冻结 → `wallet_pay_task` 回调任务行」。任一步失败整体回滚，支付单留 WAIT_PAY。
- **回调 = 进程内端口，不是 HTTP notify_url**：`IPaymentNotifyPort`（钱包域 port）由 idle 域 `PaymentNotifyHandler` 实现。支付事务提交后 `WalletPaymentServiceImpl` 调它翻转订单「待支付 → 已支付」；成功 / 永久拒绝（订单已取消、不存在、付款人不符）→ 任务置 DONE；抛异常 → 留 PENDING 由 `productPayTaskScan` 退避重放（1/2/4/8/16 分钟，5 次耗尽置死信）。
- **`wallet_pay_task` 语义调整，表结构不变**：从「冻结 outbox」改为「支付回调 outbox」（ADR-0017 同形：域内最终一致任务表、进程内扫描重放）。任务行仍在支付事务内落库，「行在 ⇒ 支付单已 PAID 且钱已冻」。扫描 job 名沿用 `productPayTaskScan`。
- **关闭支付单**：订单取消 / 超时 best-effort `closePaymentOrder`（`WAIT_PAY → CLOSED`，已支付空操作）。已支付订单取消仍走原 PRODUCT_UNFROZEN 解冻退款，支付单**没有** `REFUNDED` 状态。

## Considered Options

| 方案 | 为什么否 |
|---|---|
| 保留订单页直接支付，只是把钱包入口藏起来 | 用户明确要「独立模拟微信收银台页」，不是订单页内联支付；没有支付单号也演示不了第三方支付的「统一下单 → 收银台 → 回调」形态 |
| 回调走 HTTP `notify_url`（真起一个本地回调 endpoint） | 同进程单体没有跨进程边界，起 HTTP 只为模拟形态，多一跳序列化与端口暴露，收益为零；进程内端口用接口即可表达「商户实现回调」的边界 |
| 回调走 MQ / `mq_outbox` | 违反 ADR-0017 判据：收敛方是本进程，域内动作应用域自己的任务表，不绕 broker |
| 支付事务里直接翻转 idle 订单 | 跨仓储事务，违反 ADR-0016「事务边界 = 仓储」；两段式 + 回调 outbox 重放才是既有范式 |
| 给 `payment_order` 加 REFUNDED 状态 | 退款已有独立事实源（`wallet_detail` PRODUCT_UNFROZEN + `uk_order_type` 幂等），支付单重复表达退款只会引入第二条真相 |

## Consequences

**正向**

- 支付链路形态完整、可演示：预下单 → 独立收银台 → 支付 → 回调翻单，接近真实第三方支付。
- 钱包从用户功能收敛为内部渠道，暴露面变小；余额/充值/流水的接口与前端页面删除。
- 事务边界与幂等口径全部沿用 ADR-0016 / ADR-0017：钱包事务只写钱包域表，回调 outbox 与支付同生同死，重放幂等。
- 预下单失败不阻塞下单，支付前懒创建，链路对钱包故障更钝感。

**负向（已接受）**

- 多一张 `payment_order` 表、多一层「支付单状态」与「订单状态」的映射，排查时要同时看两张表。
- 预下单是 best-effort，订单详情 GET 带写副作用（懒创建 `payNo`），已 try/catch 不阻塞详情。
- 支付成功但回调永久失败（如订单已被取消）时，钱先冻着、靠 `productFundOrphanScan` 收敛——「宁可长款」的既有取舍不变。
- 前端既有 ID 类型不一致（大量 `number` 与 `\d+` 路由正则，而后端业务 ID 是 UUID）是历史问题，本次不重构；新收银台路由 `/pay/:payNo` 已避免数字正则，但完整 UI 冒烟可能仍被历史问题阻塞。

## Migration

- **后端**：新增 `payment_order` 表与 `PaymentOrderEntity` / `PaymentOrderStateEnum` / `PaymentPayResult` / `IPaymentNotifyPort` / `IWalletPaymentService` / `PaymentCase` / `PaymentNotifyHandler` / `PaymentController` / `IPaymentService` / `PaymentOrderResponseDTO`；`IWalletRepository` / `WalletRepository` 新增预下单、查支付单、`pay`、关闭，`freeze` 移除 PRODUCT_FROZEN 落任务特殊逻辑；`IdleCase` 删 `payOrder` / `replayDuePayTasks`，`createOrder` best-effort `ensurePayNo`，取消/超时 best-effort `closePaymentOrder`；`ProductController` 删支付路由、详情返回 `payNo`；`ProductPayTaskScanJob` 改调 `PaymentCase.replayDueNotifyTasks`。
- **前端**：删 `WalletView.vue`、`api/wallet.ts`、`/me/wallet` 路由与个人页入口；新增 `api/pay.ts`、`CashierView.vue`、`/pay/:payNo`；订单页支付按钮跳收银台。
- **已删除**：`WalletController`、`IWalletService`、`WalletRechargeRequestDTO`、`WalletResponseDTO`、`WalletTransactionResponseDTO`。
- **文档随票**：`docs/db/user.md`、`docs/db/README.md`、`docs/api/README.md`、`docs/api/payment.md`（新增）、`docs/api/idle.md`、删除 `docs/api/wallet.md`、根 `CONTEXT.md`、`sql/schema.sql`、`sql/xxl-job-tasks.sql`、`sql.md`。
- ADR-0017 的 Migration 中「商品支付收敛任务」一条描述的「冻结 outbox」语义由本 ADR 调整为「支付回调 outbox」，表结构与扫描 job 名不变。

## References

- `docs/adr/0016-tx-boundary-is-repository.md`（事务边界 = 仓储；跨域两段式；宁可长款）
- `docs/adr/0017-outbox-two-kinds-per-domain-tables.md`（域内最终一致任务表；`wallet_pay_task` 表形状的来源）
- `backend/better-school/docs/api/payment.md`（收银台接口与支付状态机）
- `backend/better-school/docs/db/user.md`（`payment_order` / `wallet_pay_task` 表字段与语义）
