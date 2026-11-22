# 事务边界 = 仓储：废止组合事务端口，资金链路改两段式 + 孤儿扫描

取代 ADR-0015 的核心决定（组合事务端口：`CompositeTxExecutor` 在一个 REQUIRED 物理事务内编排多域仓储动作），并修正 ADR-0014 Decision 中「组合事务在 raffle 侧 port：INSERT 购买单与扣积分同一物理事务」一条——它同样是跨仓储事务。

**为什么改**：用户拍板的硬纪律——**事务的边界是仓储，两个不同的仓储不允许强行合并进同一个物理事务**。「事务落位在仓储内」（一域一仓储、事务收在仓储方法里）是本仓库事务模型的唯一落位；组合事务端口把事务边界上移到基础设施编排层，一个事务里同时出现两个域的表，破坏了该模型。本 ADR 把纪律补成全量口径，不再有资金链路例外。

## Decision

- **事务边界 = 仓储**：一个物理事务只允许写一个仓储（一个域）的表。`CompositeTxExecutor` 组件退役；`domain/<域>/adapter/port/` 下的组合事务端口接口与 `infrastructure/adapter/port/` 下的组合事务实现全部废止。
- **跨域「状态翻转 + 资金动作」一律两段式**，每段各自仓储本地事务：
  1. **资金段**：资金域仓储事务——流水先行（`INSERT IGNORE`，`uk_order_type` / `uk_user_reason_ref` 冲突 = 已处理过，幂等跳过）+ 可用余额条件扣减 / 解冻 / 入账。
  2. **翻转段**：业务域仓储事务——条件 UPDATE 守卫前态，事件行 / feed 索引行 / 搜索 outbox 行随行落库（与现行仓储口径一致，本就是单仓储事务，不变）。
- **段落顺序逐链路定，判据唯一**：宕机窗口留下的中间态必须是**长款**（资金悬置/冻结、可凭流水单向回补），不得是**短款**（状态已推进而应付资金缺失）。支付类（冻结/扣分）资金段恒先行——余额不足直接失败，订单留待支付；退款/入账类的顺序在重构票里按此判据逐条复核。
- **孤儿扫描**：每条两段式链路一个收敛 job（cron `0/30`）。**无状态窗口重扫**，不照搬 `drawCountPurchaseScan` 的逐行退避——本链路的每个收敛动作都是单次幂等调用（流水唯一键吸收重复），失败行永远满足扫描条件、下个周期自然重试，因此**无 per-row 状态、无退避、无死信**；扫描判据是**流水行与业务行状态比对**（资金流水已落、对应状态翻转未命中、超阈值），**零新增状态表**——流水唯一键本身就是幂等凭证与对账源。收敛动作：终态订单补解冻 / 补入账 / 补扣分（全部按原幂等键重放）。
- **重试安全**：两段各自幂等（流水唯一键 + 条件 UPDATE），MQ 重投 / 客户端带 token 重试 / 扫描 job 三路重试全部安全，与既有终态短路语义兼容。
- **购买次数链路（marketing `PointsDeductPort`）**：改「购买单先落 `CREATED` → 扣分 → 推进 `DONE`」三段。购买单的表形态、唯一键、Stripe 幂等入口、`drawCountPurchaseScan` 均不变（ADR-0014 的表形态决策保留）；「单在 ⇒ 分已扣」的承诺降级为「单在 ⇒ 扣分结果未知、扫描收敛」。ADR-0014 当初否掉「先落 CREATED」的理由（补偿逻辑分叉）被新纪律覆盖：跨仓储事务已不可用，分叉是必须接受的形态。

## Considered Options

| 方案 | 为什么否 |
|---|---|
| 维持组合事务端口（ADR-0015 现状） | 直接违反「事务边界 = 仓储」纪律；用户拍板废止 |
| Seata / TCC / 2PC | 同库单体用不到；无反向补偿需求；重量与收益不成比例（同 ADR-0015 口径） |
| RocketMQ 事务消息 | 回查要求本地可查事务状态，状态表省不掉，只多一跳 Broker |
| 为每条链路建独立状态单据表 | 流水表已是事实源与幂等凭证；「流水有、翻转无」的比对判据零新表，状态表是重复建设 |

## Consequences

**正向**

- 事务边界回到仓储，模型自洽：一域一仓储一事务，「域 A 不写域 B 的表」在事务层有了对应物。
- `CompositeTxExecutor` 与三个组合端口删除，infrastructure 收敛。
- 两段式本来就是跨服务形态——未来微服务拆分（errand/wallet 分库分服）时资金链路零改造。

**负向（已接受）**

- 中间态真实存在（钱冻着单待支付、单已取消钱仍冻结等），窗口 = 扫描周期；「已发布必然带着真实冻结额」的强承诺降级为「最多一个扫描周期内成立」。
- 每条链路一个孤儿扫描 job 的维护成本；段落顺序逐链路复核是重构票里的实打实工作量。
- `draw_count_purchase_order` 多出 `CREATED` 态，补偿逻辑分叉（ADR-0014 已预见的代价，现被纪律强制接受）。
- 死信仍零自动信号（只 ERROR 日志 + 人工 SQL 重置），与 `alarm_email` 为空的现状一致。

## Migration

1. **票 A（已完成，2026-11-22）**：`IErrandFundPort` / `ErrandFundPort` 删除，`ErrandOrderCase` 四条链路两段化
   （支付 prepare→freeze→confirm；取消/超时/自动完成翻转先行、case 层补资金段）+
   `ErrandFundOrphanScanCase` / `ErrandFundOrphanScanJob`（xxl `errandFundOrphanScan`，钱包侧
   `listOrphanFrozen` + 订单侧 `listRecentSettled` 两个扫描查询）+ `errand.md` 宣称改写。
2. 票 B：`ProductPayCompositeTxPort` 两方法两段化 + 商品孤儿扫描（可与票 A 共用扫描 job 按 `biz_type` 分派）。
3. 票 C：marketing `PointsDeductPort` 三段化 + `drawCountPurchaseScan` 口径扩展。
4. 文档随票：`backend/better-school/docs/api/trade.md`、`backend/docs/architecture/marketing-services.md` 中
   「经组合事务端口并入一个物理事务」的宣称改写为两段式口径（票 A 的 `errand.md` 已随票完成）。

## References

- `docs/adr/0014-draw-count-purchase-eventual-consistency.md`（模式源头；Decision 的组合事务一条被本 ADR 修正，表形态与幂等口径保留）
- `docs/adr/0015-fund-flow-composite-tx-rollout.md`（被本 ADR 取代）
- Seata Saga 文档《缺乏隔离性的应对》「宁可长款，不可短款」（段落顺序判据的业界口径）
- `.scratch/fund-consistency-rollout/`（历史设计记录，不追改）
