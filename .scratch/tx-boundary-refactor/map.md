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
- 03（resolved）：marketing 购买链路三段化。关键决定：
  `DEDUCTED(0)` 改名 `CREATED(0)`（code 不变，存量行零迁移，老行重放扣分被积分流水唯一键吸收）；
  状态机分叉被三层幂等吸收（收敛动作恒为三段全量重放，无需判「有没有扣分流水」）；
  扫描**并入 drawCountPurchaseScan**（购买单本身就是状态表，零新表，认领/退避/死信基建保留）——
  与 errand/product 的无状态重扫并存，ADR-0016 明文本链路「drawCountPurchaseScan 均不变」；
  积分不足 = 业务永久失败，单即置 DEAD（不占重试轮次），充值后原 token 重放自愈；
  编排落 `marketing-case` `DrawCountPurchaseCase`（domain 服务不再互相注入）。

## Fog

- 票 02：商品孤儿扫描与跑腿扫描是否合并为一个 job（按 type/域分派）还是各自独立 job，实现时定
  （已落地为独立 `productFundOrphanScan`，`listOrphanFrozen` 参数化两条扫描线）。
