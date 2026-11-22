Type: task
Status: needs-triage

# 03 marketing PointsDeductPort 三段化（购买单先落 CREATED → 扣分 → 推进 DONE）

## 问题

ADR-0016 废止组合事务端口。marketing `PointsDeductPort`（`infrastructure.raffle.adapter.port.points`）
在 `TransactionTemplate`（REQUIRED）内先 INSERT 购买单（raffle 仓储）再委托积分域 `deduct`
（points 仓储），跨仓储合并事务——ADR-0014 的核心支柱「单在 ⇒ 分已扣」本身即跨仓储事务。
按 ADR-0016 Decision，本链路改**三段式**：

1. 购买单 INSERT，状态 `CREATED`（raffle 仓储本地事务）——购买单仍是订单兼幂等键与对账源
   （ADR-0014 的表形态、唯一键、Stripe 幂等入口**全部保留**）；
2. 积分扣减（points 仓储本地事务，流水先行幂等）；
3. 推进 `DONE`（条件 UPDATE，守卫 `CREATED`）。

「单在 ⇒ 分已扣」降级为「单在 ⇒ 扣分结果未知、扫描收敛」。

## 开放问题（needs-triage 原因，动手前先拍板）

- **状态机分叉**：ADR-0014 当初明确否掉「先落 CREATED」——理由是「单在」只意味着「扣分结果未知」，
  补偿逻辑分叉（CREATED 而无扣分流水 = 扣分从未尝试，可重试；有扣分流水而未 DONE = 只需推进）。
  两态各自的重放/扫描行为要在本票里定义清楚（可复用现有 `drawCountPurchaseScan` 的扫描框架加一条判据）。
- **扫描归属**：现有 `drawCountPurchaseScan`（cron 0/30，条件更新抢占 + 1/2/4/8/16 退避 + DEAD）是
  marketing 工程内单仓储扫描；本票的孤儿窗口（扣分已提交、DONE 未推进）可直接并入该 job 的扫描条件，
  无需新 job——与 ADR-0016 无状态重扫口径的差异要在票里定夺（营销侧已有 per-row 状态表的基建，
  顺其形态 vs 改无状态重扫）。
- **并发**：同 token 双击在「CREATED 已落、扣分未执行」窗口的重放路径（撞 uk_purchase_order_id →
  读单状态 → 顺势补齐），需明确与 Stripe 语义的衔接。

## 交付（待 triage 后细化）

- `PointsDeductPort` 退役，`RaffleDrawCountPurchaseServiceImpl` / case 编排改三段顺序调用。
- `draw_count_purchase_order` 状态机：`CREATED(0) → DONE(1) → DEAD(2)`（或按开放问题结论调整）。
- 扫描口径扩展 + 测试注释同步 + `marketing-services.md` §4 宣称改写 + ADR-0016 Migration 票 C 标完成。

## References

- `docs/adr/0014`（被 ADR-0016 修正的 Decision 条 + 表形态保留范围）
- `docs/adr/0016` Decision「购买次数链路（marketing PointsDeductPort）」条
