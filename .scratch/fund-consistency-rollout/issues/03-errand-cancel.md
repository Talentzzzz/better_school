Type: task
Status: resolved

## Comments

- 2026-11-20 完成：`IErrandFundPort.cancelWithRefund(orderId, publisherId, reward)`（解冻 uk 幂等 + 翻转未命中整事务回滚）；`cancelByPublisher` 重接（needRefund=true 走组合事务，待支付保留原单事务翻转）；case 层撤两步解冻。5 条集成测试随交付（未执行）。提交 `ee247cd`，合并 `1a1913f`。
- 维护者备注：解冻动作无 0 行失败路径，钱包侧原子性以「成功⇒钱到位 / 重放⇒不重复退款」两向外显覆盖（测试类头注释已说明）。
- 移交 code-review：票 02 测试 `ErrandPayFundConsistencyIntegrationTest:44` 断言 balance=990 疑似笔误（updateFreeze 只加 frozen_amount 不动 balance，可用余额 = balance − frozen 口径）。
Blocked by: 02

# 03 跑腿取消收口（解冻退款 + 取消一个物理事务）

## 问题

spec User Stories 4：取消一个已冻结悬赏的订单时，解冻退款与订单取消必须同时生效；现状两步分离，中间态宕机悬赏永久悬置冻结态，只能人工 SQL 解冻。

## 交付

在跑腿订单取消路径上，经组合事务端口把「订单取消翻转 + 发布方解冻退款（解冻流水，踩唯一键幂等）」并入一个物理事务。

## 约束

- 与 02 同文件（跑腿订单 ServiceImpl），注意不破坏 02 已收口的支付路径。
- 测试外显行为断言（取消后钱回余额、幂等重放无害），**不运行测试套件**。

## 验收

- 取消成功 ⇒ 订单取消且冻结额解冻回余额；无悬置冻结。
