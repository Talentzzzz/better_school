# map：资金链路与抽奖预扣的跨事务一致性收口（实施）

## Destination

按 [spec](spec.md)（`Status: ready-for-agent`）完成全部编码落地：把 ADR-0014 模式推广到跑腿 / 商品 / 抽奖三条资金链路——

1. **组合事务端口**：基础设施层新增端口（照营销积分扣减端口形状），单体同库红利下消灭中间态，不新增状态表、不新增资金补偿 job；
2. **跑腿收口**：支付（冻结悬赏+条件扣减+状态翻转+outbox+feed 索引一个物理事务）、取消、超时取消（含信用分扣减）、自动完成（悬赏收入）四处收口；
3. **商品收口**：支付（货款冻结）、取消两处收口；确认收货不改；
4. **孤儿预扣对账 job**：抽奖「预扣→落库」跨合并器窗口的缝，照 `drawCountPurchaseScan` 口径补收敛 job；
5. **文档对齐 + GLOSSARY 词条沉淀**。

## Notes

- **需求问题一律查 spec 对应章节**，本 effort 不重开设计讨论。
- **交付口径（用户明确指示）**：所有链路实现完成后**不运行测试套件**（不执行 mvn），测试代码照常随实现交付，由维护者自行验证。
- **探索笔记**：[research.md](research.md)（实现前必读，含类名 / 表名 / 行号锚点）。
- **git 口径**：集成分支 `fund-consistency-rollout`（自 master 切出）。master 上有 4 个未提交改动（CONTEXT.md / backend/CLAUDE.md / SecurityConfig.java / application.yml），与本 effort 无关，**任何票都不得把它们卷进提交**。
- 跑腿四票（02–05）串行：同在跑腿订单 ServiceImpl，避免同文件合并冲突；商品两票（06–07）同理串行。两线之间以及与 01 / 08 之间可并行。
- 终态短路保持现状语义：重放读到终态直接返回，资金已随终态在同一事务内完成过，唯一键幂等吸收响应丢失窗口的重复。
- 回放样板（确认收货 / 仲裁的终态短路回放结算结果）**不作强制迁移**，保持现状。
- 明确不采用：Seata / TCC / 2PC、RocketMQ 事务消息、为资金链路另建 outbox 状态表。

## 依赖图（Blocked by）

```
01 组合事务端口 ──→ 02 跑腿支付 ──→ 03 跑腿取消 ──→ 04 超时取消 ──→ 05 自动完成
                └──→ 06 商品支付 ──→ 07 商品取消

08 孤儿预扣对账 job（独立）

01…08 ──→ 09 文档对齐 + GLOSSARY 词条
```

**Frontier（可立即开工）**：03、04 不解锁（串行）；07（已开工）、08（进行中）。02 ✅ 06 ✅

## 进度

- 01 ✅ 组合事务组件 `CompositeTxExecutor`（`ec66bad` → 合并 `457b715`）。
- 02 ✅ 跑腿支付收口（`06db8da` → 合并 `221a853`）；03–05 沿用 `IErrandFundPort` 扩展。
- 06 ✅ 商品支付收口（`a3f57dd` → 合并 `b00c5c8`）；07 沿用 `IProductPayCompositeTxPort` 扩展。
- 03 ✅ 跑腿取消收口（`ee247cd` → 合并 `1a1913f`）。
- 04 ✅ 超时取消收口（`4b55bde` → 合并 `be69aec`）；05 ✅ 自动完成收口（`791cc75` → 合并 `c7617bd`）——跑腿线收口完毕。
- 08 ✅ 孤儿预扣对账 job（`2bf738f` → 合并 `2ef6da7`）。
- 07 ✅ 商品取消收口（`4f25a20` → 合并 `b883d3d`）——商品线收口完毕。
- 09 ✅ 文档对齐 + GLOSSARY 三词条 + ADR-0015（`8f3f808`）。

## Code review（2026-11-20，两轴）

**Standards**：依赖方向 / 判据落位 / GLOSSARY 用语全部合规。判断性发现：① `listOrphanDrawPrereserveDetails` 的 NOT EXISTS 跨表谓词与 CLAUDE.md §9.7 单表规则冲突（有先例，补豁免条款→修复票）；② 两端口钱包动作形态不一致（IWalletRepository vs IWalletAccountService，均为 ADR-0014/基线先例，判不修）；③ 端口参数 Data Clump、11 参构造、case 层返回值死重等 judgement call，判不修（churn > 价值）。

**Spec**：核心要求全落地（单物理事务逐端口实读确认、终态短路、确认收货/仲裁/超时中心零改动、Out of Scope 无越界）。待修：① 票 02 支付重放命中 PUBLISHED 抛错而非返回原结果（US3 违约，基线遗留行为）→ 修复票；② 票 02 测试 balance 断言笔误（updateFreeze 不动 balance）→ 修复票；③ 票 08 isActivityNotReplayable 仅映射 4301/4302，需核对活动查询全部失败码 → 修复票；④ 票 07 簿记缺口已补。

**修复票**：`ticket-fix-review` worktree（Fix 1 重放短路 + 测试断言、Fix 2 活动码核对、Fix 3 CLAUDE.md §9.7 豁免条款）。

## 收口（2026-11-20）

票 01–09 全部完成：01 组合事务组件 `CompositeTxExecutor`、02 跑腿支付、03 跑腿取消、04 超时取消、05 自动完成、06 商品支付、07 商品取消、08 孤儿预扣对账 job、09 文档对齐（errand/trade/CONTEXT/inventory/lottery 同事务与对账口径回填 + GLOSSARY 三词条 + ADR-0015）。Code-review 两轴（Standards/Spec）通过，修复票处理 3 项：票 02 测试断言修正（重放短路核实为基线已有，误报澄清）、票 08 活动码核对（4301/4302 已全覆盖，零改动）、CLAUDE.md §9.7 追加只读 NOT EXISTS 豁免。全部票 `Status: resolved`；票分支已删（均并入集成分支）；worktree 已清理。集成分支 `fund-consistency-rollout` 终点 `e3e7c77`，交付口径：**测试随实现交付、未运行 mvn，由维护者自行验证**。本 effort 终点。
