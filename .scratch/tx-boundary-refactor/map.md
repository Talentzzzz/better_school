# tx-boundary-refactor

## Notes

- 口径源头：`docs/adr/0016-tx-boundary-is-repository.md`（用户拍板「事务边界 = 仓储」，2026-11-22）。
  全部硬约束（长款判据、幂等、无状态窗口重扫、编译工具链）集中在 spec.md，票内不重复。
- 票 01 已完成但目录是事后立的：实现过程在 `2026-11-22` 工作日志「票 A 落地」一节，此处只留存档。

## Decisions-so-far

- 01（resolved）：跑腿四链路两段化 + `errandFundOrphanScan` 无状态窗口重扫。关键决定：
  取消/超时/自动完成**翻转段先行**、支付**资金段先行**；扫描订单未终态不动钱；
  committed 从 TIMEOUT 事件行 `from_state` 推导（`ErrandOrderEventVO` 加 fromState）。
  提交 `cbfc61a`/`5c2152e`。
- ADR-0016 的孤儿扫描措辞已从「照 drawCountPurchaseScan 逐行退避」改为「无状态窗口重扫」。

## Fog

- 票 02：商品孤儿扫描与跑腿扫描是否合并为一个 job（按 type/域分派）还是各自独立 job，实现时定。
- 票 03：状态机分叉的补偿语义、扫描归属（并入 drawCountPurchaseScan vs 无状态重扫）、同 token 重放
  与 Stripe 语义衔接——三个开放问题待 triage。
