Status: resolved
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

### 实现要点（t06，2026-11-22，分支 `draw-recon-round-t06-docs`）

零代码改动，纯文档。改动文件清单：

| 文件 | 内容 |
| --- | --- |
| `GLOSSARY.md` | 新增「库存对账兜底（draw-recon-round）」小节，追加两词条：**对账轮（Reconciliation Round）**、**差错表（Variance Table）**，正文定义 + `_Avoid_` 反例，措辞照既有词条风格（术语沿用：孤儿预扣收敛、死信、预扣、回补、台账） |
| `sql.md` | §2 营销库节追加 `draw_recon_variance` / `draw_recon_round` 两表 DDL 段落（从 `backend/marketing/sql/schema.sql` 追加段逐字同步，与真实 DDL 一致）；两处表数声明同步修正（头注 18→25、§2 21→25） |
| `backend/marketing/docs/db/recon.md`（新建） | 对账轮主文档：一轮五段编排口径、四条不变量公式（含 SQL 出处 mapper 落点）、差错状态机、平账分流表、告警链路、排查 SQL 示例（人工验证口径，替代自动化测试）、二期扩展位 |
| `backend/marketing/docs/db/inventory.md` | 「孤儿预扣」节尾加一行指向 recon.md（总量兜底） |
| `backend/marketing/docs/db/draw.md` | 「对账窗口」第 1 条补注：`不建 job、不建巡检` 旧口径自 draw-recon-round 起被逐笔收敛 + 全量对账轮取代 |

### recon 文档落点决定

选**新建 `recon.md`**，不并入 draw.md / inventory.md：对账轮是跨两个既有文档边界面的旁路机制（取数面横跨库存台账/明细、`draw_record`、孤儿对账单四组表），且自带两张旁路表与独立告警链路——并入任一文档都会打破这两个文件「各管一个有界上下文」的粒度（draw.md = 抽奖决策侧 + 主链路，inventory.md = award-inventory 两层模型）。draw.md / inventory.md 各加一行交叉引用保持可发现性。

### 附带发现（超票范围，未处理，仅记录）

- `sql.md` §2 此前**缺**资金链路两张状态表（`draw_count_purchase_order` / `draw_orphan_prereserve_order`）的展开段——fund-consistency 系列票未回写，本节表数声明「21 张」早已过期（schema.sql 收录前即 23 张）。本票把计数修正为 25 并在「未展开」括注里如实点名这五张（库存三张 + 资金链路两张），未补写它们的展开段（超出本票范围）。
- `backend/marketing/sql/xxl-job-tasks.sql` 在本 worktree 尚无 `drawReconScan` 注册行（票 05 并行实现中）；recon.md 的调度段按票 05 既定参数书写：handler `drawReconScan`、cron `0 0 3 * * ?`、执行器 `marketing-executor`，与 spec / xxl-job-tasks.sql 既有格式一致，票 05 合入后无需改文档。
