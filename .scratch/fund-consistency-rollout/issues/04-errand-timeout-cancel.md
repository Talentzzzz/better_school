Type: task
Status: resolved

## Comments

- 2026-11-20 完成：`IErrandFundPort.timeoutCancelWithRefund(orderId, publisherId, takerId, reward, committed)`——解冻退款 + committed 时信用扣减（`ICreditAccountService.applyChange`，uk_user_reason_ref 幂等）+ `markTimeoutWithSearchSync` 条件翻转并入一个物理事务，翻转 0 行按失败重投、资金随之回滚。终态短路语义保留（重投读到终态直接 return，不进端口）。5 条集成测试随交付（未执行）。提交 `4b55bde`，合并 `be69aec`。超时中心本体零改动。
Blocked by: 03

# 04 跑腿超时取消收口（解冻 + 信用分扣减一个物理事务）

## 问题

spec User Stories 5、6、9：订单因超时被系统自动取消时，悬赏解冻退回必须与取消同事务，即使取消那一刻服务崩溃也不丢；超时且接单方已开工时，解冻与信用分扣减一起生效。

## 交付

在超时中心回调的跑腿订单超时取消路径上，经组合事务端口把「订单取消翻转 + 发布方解冻退款（开工场景另含接单方信用分扣减）」并入一个物理事务。

## 约束

- 终态短路保持现状语义：重投一个已处理的超时回调，读到终态即安全返回——资金已随终态在同一事务内完成过，重投永远无害。
- 测试外显行为断言（回调重放无害、宕机窗口状态收敛），**不运行测试套件**。
- 超时中心本体零代码改动（仅业务侧接入）。

## 验收

- 超时取消成功 ⇒ 解冻回退（+ 开工场景信用分扣减）与订单取消原子生效。
