Type: task
Status: resolved

# 01 跑腿资金链路两段化 + errandFundOrphanScan（ErrandFundPort 退役）

## 问题

ADR-0016 废止组合事务端口。`ErrandFundPort` 四个方法（payWithFundFreeze / cancelWithRefund /
timeoutCancelWithRefund / completeWithIncome）在 `CompositeTxExecutor` 一个物理事务里编排
wallet + errand（+credit）三个域的仓储，违反「事务边界 = 仓储」纪律。

## Answer

- 删除 `IErrandFundPort` / `ErrandFundPort`；`CompositeTxExecutor` 保留（票 02 的 trade 端口仍在用）。
- `ErrandOrderCase` 四条链路两段化（照 `confirmOrder` 既有样板，各自域内事务 + 流水 uk 幂等）：
  - 支付：`preparePayOrder`（校验/幂等，零副作用）→ `freeze`（FROZEN，资金段先行，余额不足直接失败、订单留待支付）→ `confirmPayOrder`（翻转）。
  - 取消 / 超时取消 / 自动完成：翻转段先行（`cancelByPublisherWithSearchSync` / `markTimeoutWithSearchSync` / `autoCompleteWithSearchSync` 单仓储事务，未命中抛异常时资金段尚未执行零残留），case 层按结果对象补 unfreeze / INCOME 入账 / committed 补 `ERRAND_TIMEOUT` 扣分。
- 孤儿扫描 `ErrandFundOrphanScanCase` + xxl `errandFundOrphanScan`（0/30、FIRST、SERIAL_EXECUTION、重试 0），
  **无状态窗口重扫**（推翻了 ADR 草稿里照抄 drawCountPurchaseScan 的逐行退避——动作全是单次幂等调用，
  无 per-row 状态/退避/死信；ADR-0016 与 CLAUDE.md 措辞已同步改）。判据零新表：
  - 钱包侧 `listOrphanFrozen`：FROZEN 无同单同轮次同人 UNFROZEN 且超 10min → 订单**未终态不动钱**
    （防「解冻后翻转又成功」短款，等支付超时关单后下一轮收敛）；
  - 订单侧 `listRecentSettled`（近 10min 终态）：COMPLETED 补 INCOME；CANCELLED 且 TIMEOUT 事件
    `from_state ∈ {CONFIRMED, PICKED_UP}` 补扣分（committed 从事件行 from_state 推导）。
- VO：`ErrandPayResult.alreadyPublished`、`ErrandOrderEventVO.fromState`、扫描汇总 `ErrandFundOrphanScanSummaryVO`。
- 文档：`errand.md` 三处宣称改两段式；ADR-0016 Migration 标本票完成；CLAUDE.md 纪律更新。
- 测试：`ErrandFeedCursorPagingIntegrationTest` 构造收参；四个资金一致性测试注释同步（外显行为不变）；
  `WsIntegrationTest` 既有 Jackson 导入错（`PropertyAccessor` 在 annotation 包）顺手修。
- 编译：bs-domain/infrastructure/case/trigger compile + bs-app test-compile 全绿；集成测试未跑（无 docker）。
- 提交：`cbfc61a` + `5c2152e`，分支 `chat-ws-layering-rollout`。
