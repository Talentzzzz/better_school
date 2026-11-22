Status: ready-for-agent
Type: task
Blocked by:

# 02 四条不变量只读聚合查询

## 目标

把四条守恒/配对不变量落成只读聚合 SQL（mapper + 端口方法）。只读，不写任何业务表。

## 上下文指针

- spec：`.scratch/draw-recon-round/spec.md`（Implementation Decisions 节"四条不变量"，公式原文在此）
- 探索报告：`.scratch/draw-recon-round/exploration.md`（§2 全节：表结构、列语义、B3 注释出处、聚合 SQL 先例、孤儿候选扫描的跨表 NOT EXISTS 先例）
- 必读规范：仓库根 `CLAUDE.md` SQL 规范（零表别名、表达式投影才写 AS、`map-underscore-to-camel-case`）

## 交付内容

四条查询（全部带数据窗口过滤 `update_time < #{before}`，`before = now - 10min` 与孤儿种单阈值对齐）：

1. **B1 台账守恒**（按 award_id 分组）：限量户（`surplus_qty != -1`）`surplus_qty + waiting_qty + issued_qty` vs `Σ(flow_type=0 quantity)`；不限量户跳过 surplus 项（比较 `waiting + issued` vs `Σ(flow_type=0 quantity)`）。VIRTUAL 行 `waiting_qty` 恒 0，公式自然成立，无需特判——但请在 SQL 里确认这一点。
2. **B3 预扣守恒**（按 award_id 分组）：`waiting_qty + issued_qty` vs `Σ(flow_type=1 quantity)`。
3. **正向配对**：`flow_type=1 AND biz_type='LOTTERY_DRAW'` 且窗口内明细的 `biz_id`（= record_id）既不在 `draw_record.record_id` 也不在 `draw_orphan_prereserve_order.record_id` → 漏结候选（NOT EXISTS 反查，先例见 exploration §2.2 L68）。返回 `award_id + biz_id + quantity`。
4. **反向配对**：窗口内 `draw_record`（`inv_id IS NOT NULL AND award_type != 'THANKS'`）不存在对应预扣明细（`award_id = inv_id AND biz_type='LOTTERY_DRAW' AND biz_id = record_id`）→ 数据破坏级缺失候选。

## 实现约束

- Mapper XML 新文件放 `marketing-app/src/main/resources/mybatis/mapper/`（raffle 或 inventory 目录按表归属判断——跨表查询落哪个文件由你判断并在 Answer 说明；聚合主表是 inventory 明细/台账的 1/2 落 inventory 目录，3 落 inventory 目录，4 主表是 draw_record 可落 raffle 目录）。
- 1/2 是"按 award_id 返回两侧读数"的查询——期望/实际两侧在一次查询里返回（一条 SQL 返回 `award_id + 期望列 + 实际列`，差异判断在 Java 侧做，SQL 不做 CASE 判平），这样快照能带完整读数。
- 3/4 是漏结/缺失行清单查询，返回行即差异。
- 查询结果以值对象（VO）返回：在 `marketing-domain` 的 inventory 或 raffle model/valobj 下新建（照 `OrphanPrereserveCandidateVO` 形态）。
- 端口：新建只读查询端口接口（域层声明，infrastructure 实现，纯透传 DAO/mapper）。不进合并器、不写任何表。

## 验收

- `mvn compile`（marketing 相关模块）通过；不写测试、不跑测试（spec Testing Decisions）。
- 每条 SQL 在 XML 里带 javadoc 注释标明公式出处（B3 注释出处 `AwardInventoryDetailMapper.xml` L79 / `marketing/docs/db/inventory.md` §0）。

## Answer

（完成后在此追加要点）
