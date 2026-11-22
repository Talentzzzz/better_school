Type: task
Status: resolved

# 03 marketing PointsDeductPort 三段化（购买单先落 CREATED → 扣分 → 推进 DONE）

## 问题

ADR-0016 废止组合事务端口。marketing `PointsDeductPort`（`infrastructure.raffle.adapter.port.points`）
在 `TransactionTemplate`（REQUIRED）内先 INSERT 购买单（raffle 仓储）再委托积分域 `deduct`
（points 仓储），跨仓储合并事务——ADR-0014 的核心支柱「单在 ⇒ 分已扣」本身即跨仓储事务。
按 ADR-0016 Decision，本链路改**三段式**：

1. 购买单 INSERT，状态 `CREATED`（raffle 仓储本地事务）——购买单仍是订单兼幂等键与对账源
   （ADR-0014 的表形态、唯一键、Stripe 幂等入口**全部保留**）；
2. 积分扣减（points 仓储本地事务，流水先行幂等）；
3. 推进 `DONE`（条件 UPDATE，守卫 `state <> 1`，从 CREATED 与 DEAD 都可达）。

「单在 ⇒ 分已扣」降级为「单在 ⇒ 扣分结果未知、扫描收敛」。

## 开放问题的 triage 结论（2026-11-23 落地）

- **状态机分叉**：`DEDUCTED(0)` 改名 `CREATED(0)`，code 不变 → **存量行零迁移**（老行真实语义
  「分已扣」，新口径下重放扣分被积分流水唯一键幂等吸收）。两条分叉（CREATED 无扣分流水 =
  补扣；有流水未 DONE = 只需推进）被三层幂等统一吸收，收敛动作恒为「扣分 → 入账 → 置 DONE」
  全量重放，调用方无需先判流水。
- **扫描归属**：顺 marketing 既有形态——**并入 `drawCountPurchaseScan`**（ADR-0016 明文
  「drawCountPurchaseScan 均不变」；购买单本身就是状态表，零新表），认领/退避/死信基建保留，
  收敛动作从「入账 + 置 DONE」扩展为三段全量重放。与 errand/product 的无状态重扫不冲突：
  判据「流水与业务行比对」在此链路由购买单状态列 + 积分流水幂等等价承担。
- **并发**：撞 `uk_purchase_order_id` 仍是并发同 token 的唯一互斥点，重放路径扣分幂等吸收；
  **积分不足 = 业务永久失败**：单即置 DEAD（不占补偿重试轮次），一分未扣，充值后带原 token
  重放自愈（finish 守卫 `state <> 1` 从 DEAD 可达）。

## 交付（已完成）

- `IPointsDeductPort` / `PointsDeductPort` 删除；跨域编排落 `marketing-case`
  `DrawCountPurchaseCase`（purchaseDrawCount 三段 + compensateDueOrders 扫描循环）。
- `IRaffleDrawCountPurchaseService` 收敛为 raffle 域内单据面（createOrder / completeOrder /
  markOrderDead / listDueOrders / claimDueOrder / resultOf）；`markPurchaseOrderDead` 改按
  购买单号条件更新（同步路径无物理 id 可调）。
- 状态机：`CREATED(0) → DONE(1)`，`DEAD(2)`（重试耗尽或积分不足）。
- `DrawCountPurchaseScanJob` / `LotteryController` / `PointsController` / `IPointsService`
  注释与注入点同步。
- 集成测试只同步注释/断言未跑（无 docker）；文档：`marketing-services.md` §4、`lottery.md`
  购买段、`schema.sql` 注释、ADR-0016 Migration 票 C 标完成、`backend/CLAUDE.md` 清退口径。

## References

- `docs/adr/0014`（被 ADR-0016 修正的 Decision 条 + 表形态保留范围）
- `docs/adr/0016` Decision「购买次数链路（marketing PointsDeductPort）」条
