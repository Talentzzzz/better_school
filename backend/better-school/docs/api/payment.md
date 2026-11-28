# 支付收银台（模拟微信支付）

钱包域扮演「微信支付」的角色：商户（idle 域）下单时向钱包**预下单**拿支付单号 `payNo`，用户拿 `payNo` 进独立模拟收银台支付，支付成功后钱包通过**进程内回调端口**通知商户翻转订单。资金仍从钱包可用余额扣（冻结托管）。

全部接口需登录（JWT Bearer），且仅付款人本人可操作。表结构见 `docs/db/user.md` 的 `payment_order` / `wallet_pay_task`。

> **钱包对用户已隐藏**：前端钱包页 / 个人页钱包入口、用户端 `/api/wallet/**`（余额 / 充值 / 流水）已全部撤除；余额与冻结只在支付链路内部流转。`GET /api/wallet/balance` 等旧接口返回 404。

## 预下单（无独立 HTTP 接口）

下单 / 订单详情时由服务端 best-effort 完成，不暴露给客户端单独调用：

- `POST /api/idle/orders` 内部向钱包预下单（`payment_order`，state=WAIT_PAY），把总额与商品标题快照进支付单；预下单失败不拖垮下单。
- `GET /api/idle/orders/{orderId}` 对「买家 + 待支付」订单按 `biz_order_id` 幂等懒创建并返回 `payNo`；非本人 / 非待支付时 `payNo` 为 null。订单详情有写副作用，已 best-effort try/catch，不阻塞详情。

客户端拿到 `payNo` 后跳转收银台 `/pay/{payNo}`（前端路由），再调下面两个接口。

## GET /api/pay/orders/{payNo}

收银台查询支付单（需登录，仅付款人本人）。

- 成功: `data = { payNo, bizOrderId, subject, amount, state, createdAt }`
  - `state`: `WAIT_PAY` 待支付 / `PAID` 已支付 / `CLOSED` 已关闭
  - `bizOrderId`: 商户订单业务 ID（支付成功后跳回订单详情用）
- 错误: 支付单不存在 → 404；支付单不属于当前用户 → 403

## POST /api/pay/orders/{payNo}/pay

收银台确认支付（「零钱」支付，需登录，仅付款人本人）。

- Body: 无
- 成功: `data = null`；支付单转 `PAID`，同时：
  - 钱包冻结货款（`wallet_detail` type=PRODUCT_FROZEN，`uk_order_type` 幂等）
  - 可用余额条件扣减（`balance − frozen_amount >= amount`，不足 → 500「零钱余额不足，无法完成支付」，支付单留 WAIT_PAY）
  - 落支付回调任务行（`wallet_pay_task` PENDING），与上述动作同一钱包事务
  - 事务提交后进程内回调 idle 域翻转订单「待支付 → 已支付」；成功后任务置 DONE
- 幂等: 已 PAID 重复支付直接返回成功、不重复冻结；已 CLOSED → 500「支付单已关闭」
- 错误: 支付单不存在 → 404；非本人 → 403；余额不足 / 已关闭 → 500

## 支付状态机与回调收敛

```
WAIT_PAY ──pay（扣冻 + 回调任务行，同一钱包事务）──▶ PAID
    │                                                │
    └── 订单取消 / 超时：closePaymentOrder ──▶ CLOSED │
                                                     └── 回调失败 → wallet_pay_task 留 PENDING
                                                         → productPayTaskScan 退避重放（1/2/4/8/16 分钟，
                                                           5 次耗尽置死信），成功后置 DONE
```

- **回调是进程内端口**（`IPaymentNotifyPort`），不是 HTTP `notify_url`；商户侧实现 = idle 域 `PaymentNotifyHandler`，翻转为幂等条件更新。
- 回调永久拒绝（订单已取消 / 不存在 / 付款人不符）不再重试；支付与取消竞态下已冻结的悬置款由 `productFundOrphanScan` 退买家。
- 已支付订单取消仍走原解冻退款链路（`PRODUCT_UNFROZEN`）；`payment_order` 没有 REFUNDED 状态。
- 预下单 / 支付 / 关闭的事务边界都在钱包仓储内（ADR-0016），支付事务只写钱包域表。
