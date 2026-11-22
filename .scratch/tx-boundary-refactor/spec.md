# tx-boundary-refactor：ADR-0016 组合事务端口全仓清退

## 目标

落实 ADR-0016（事务边界 = 仓储，废止组合事务端口）：把现存三个组合事务端口的使用方全部改为
「各域仓储本地事务两段式 + 无状态窗口重扫的孤儿扫描」，并清退 `CompositeTxExecutor`。

## 范围与进度

| 票 | 对象 | 状态 |
|---|---|---|
| issues/01 | better-school `ErrandFundPort` 四链路 + `errandFundOrphanScan` | **resolved**（2026-11-22，`cbfc61a`/`5c2152e`） |
| issues/02 | better-school `ProductPayCompositeTxPort` 两链路 + 商品扫描 | **resolved**（2026-11-22，未提交） |
| issues/03 | marketing `PointsDeductPort` 三段化 | **resolved**（2026-11-23，未提交） |

## 硬约束（全票共用）

- **事务边界 = 仓储**：一个物理事务只写一个仓储的表；跨域编排顺序调用只落 case 层。
- **段落顺序 = 长款判据**：宕机窗口只允许「资金悬置/冻结（可凭流水回补）」，不允许「状态已推进而资金缺失」。
- **幂等**：资金动作流水先行（`wallet_detail` `uk_order_type`、`user_credit_detail` `uk_user_reason_ref`），
  翻转段条件 UPDATE 守卫前态；三路重试（MQ 重投 / 客户端重试 / 扫描）全部安全。
- **扫描 = 无状态窗口重扫**：判据是流水行与业务行状态比对，零新增状态表；无 per-row 状态、无退避、无死信
  （动作全是单次幂等调用）。口径以 `docs/adr/0016` Decision 为准。
- 编译验证用 `Z:/wt-draw-recon/tools`（JDK21 + Maven 3.9.9）；集成测试需 docker MySQL/Nacos，本机无，不跑。

## References

- `docs/adr/0016-tx-boundary-is-repository.md`（口径与 Migration）
- `docs/adr/0014`（购买单表形态与幂等口径，票 03 沿用其表不改形态）
- `.scratch/fund-consistency-rollout/`（历史设计记录，组合事务端口的来路，不追改）
