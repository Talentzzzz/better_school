Type: task
Status: resolved

## Comments

- 2026-11-20 完成：`IProductPayCompositeTxPort.cancelWithRefund(orderId, buyerId, amount)`——解冻退款（`PRODUCT_UNFROZEN`，uk_order_type 幂等）与取消翻转（含台账回补 + 搜索同步随行）经 `CompositeTxExecutor` 并入一个物理事务，翻转未命中整事务回滚；已支付取消走端口、待支付保持原单事务翻转（`needRefund` 路由），`ProductCancelResult` 语义不变。3 条集成测试随交付（未执行）。提交 `4f25a20`，合并 `b883d3d`。
Blocked by: 06

# 07 商品订单取消收口（解冻退款 + 取消一个物理事务）

## 问题

spec User Stories 12：取消订单时解冻退款与取消必须同事务生效，货款不被永久悬置。

## 交付

在商品订单取消路径上，经组合事务端口把「订单取消翻转 + 买家解冻退款」并入一个物理事务。与跑腿取消同型。

## 约束

- 与 06 同文件（商品订单 ServiceImpl），注意不破坏 06 已收口的支付路径。
- 测试外显行为断言，**不运行测试套件**。

## 验收

- 取消成功 ⇒ 订单取消且货款解冻回余额。
