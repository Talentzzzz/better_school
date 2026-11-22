Status: ready-for-agent
Type: task
Blocked by: 01, 02, 03, 04, 05

# 06 文档收口：GLOSSARY 词条 + DDL 汇总补录

## 目标

合入前的文档同步。零代码改动。

## 上下文指针

- spec：`.scratch/draw-recon-round/spec.md`（Further Notes 节：建议 GLOSSARY 补「对账轮 / 差错表」两词条）
- 探索报告：`.scratch/draw-recon-round/exploration.md` §7（GLOSSARY 现状：无「预扣/回补/台账」独立词条）、§4（`Z:/better_school/sql.md` 是全库 DDL 唯一事实源）

## 交付内容

1. `GLOSSARY.md` 追加两词条（照既有词条格式：正文定义 + `_Avoid_` 反例）：
   - **对账轮（Reconciliation Round）**：每日全量兜底对账 job（`drawReconScan`，03:00）——轧账四条不变量（两条台账守恒 + 正反双向配对）、差错二次核对、平账分流；与逐笔孤儿收敛 job 完全独立（互不依赖代码路径）。_Avoid_: 与孤儿收敛共享代码、自动修守恒破坏/反向缺失。
   - **差错表（Variance Table）**：对账轮发现的不一致登记（`draw_recon_variance`），状态机 `PENDING → CONFIRMED → AUTO_FIXED / RESOLVED_MANUAL / FALSE_ALARM`，唯一键（轮次, 检查类型, biz_id）保证重跑幂等。_Avoid_: 差错当轮直接处置（必须下轮复查确认）。
2. 根目录 `sql.md` 补录 `draw_recon_variance` 与 `draw_recon_round` 两表 DDL（照该文件既有表段落格式，放进营销库节）。
3. `backend/marketing/docs/db/` 下补对账轮文档（新 `recon.md` 或并入 `draw.md`/`inventory.md`，按现有文档粒度判断）：四条不变量公式、差错状态机、分流表、排查 SQL 示例（人工验证口径，替代测试）。

## 验收

- 三处文档更新齐全、术语与 GLOSSARY 现有词条一致。

## Answer

（完成后在此追加要点）
