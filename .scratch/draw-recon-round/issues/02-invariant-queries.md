Status: resolved
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

### 实现要点（t02，2026-11-22）

**新增文件清单**（全部为新增，未改任何既有业务代码，除一处基线编译错误修复，见末尾）：

- marketing-domain
  - `domain/raffle/model/valobj/InventoryLedgerConservationVO.java` — B1 读数（awardId + surplus/waiting/issued + allocatedQty）
  - `domain/raffle/model/valobj/InventoryPrereserveConservationVO.java` — B3 读数（awardId + waiting/issued + deductedQty）
  - `domain/raffle/model/valobj/UnmatchedDeductDetailVO.java` — 正向配对漏结候选（awardId + bizId + quantity）
  - `domain/raffle/model/valobj/RecordMissingDeductVO.java` — 反向配对缺失候选（recordId + invId + userId）
  - `domain/raffle/adapter/port/recon/IDrawReconQueryPort.java` — 只读查询端口（四方法，纯只读不写表）
- marketing-infrastructure
  - `inventory/dao/IInventoryReconQueryDao.java`（1/2/3 三条）+ `raffle/dao/IDrawRecordReconQueryDao.java`（4）
  - `inventory/dao/po/InventoryLedgerConservationPO|InventoryPrereserveConservationPO|UnmatchedDeductDetailPO.java`、`raffle/dao/po/RecordMissingDeductPO.java`
  - `raffle/adapter/port/recon/DrawReconQueryPort.java` — 端口实现，纯透传 DAO，填 `InventoryBizTypes.LOTTERY_DRAW`，PO→VO 翻译
- marketing-app
  - `mybatis/mapper/inventory/InventoryReconQueryMapper.xml`（B1 / B3 / 正向配对；1/2/3 主表是库存台账与明细）
  - `mybatis/mapper/raffle/DrawRecordReconQueryMapper.xml`（反向配对；主表是 draw_record，故落 raffle 目录）

**四条 SQL 最终形状摘要**（全部带 `update_time < #{before}` 窗口，差异判断在 Java 侧，SQL 无 CASE 判平）：

1. **B1 台账守恒**：`SELECT award_id, surplus_qty, waiting_qty, issued_qty, (标量子查询 Σ(flow_type=0) ...) AS allocated_qty FROM draw_strategy_award_inventory WHERE update_time < #{before}`。限量户（surplus≠-1）比三列之和、不限量户跳过 surplus 项的分流在 Java 侧；VIRTUAL 行 waiting 恒 0 无需特判（XML 注释已确认）。标量子查询按 `award_id = draw_strategy_award_inventory.award_id` 关联——两侧需同窗口，台账与明细同事务落库 NOW() 同批，边界错位由 PENDING→复查消化。
2. **B3 预扣守恒**：同形状，`flow_type = 1` 投影 `deducted_qty`，返回 waiting/issued 两列。公式出处 B3（`AwardInventoryDetailMapper.xml` 删预扣明细注释 + `docs/db/inventory.md` §0）已写进 XML 注释与 VO javadoc。
3. **正向配对**：`SELECT award_id, biz_id, quantity FROM 明细 WHERE flow_type=1 AND biz_type=#{bizType} AND update_time<#{before} AND NOT EXISTS(draw_record) AND NOT EXISTS(draw_orphan_prereserve_order)`，零别名，双重 NOT EXISTS 反查。
4. **反向配对**：`SELECT record_id, inv_id, user_id FROM draw_record WHERE inv_id IS NOT NULL AND award_type != 'THANKS' AND update_time<#{before} AND NOT EXISTS(明细 award_id=inv_id AND biz_type=#{bizType} AND biz_id=record_id)`，零别名。THANKS（inv_id='0'）已排除。

**规范符合性**：SQL 零表别名（B1/B3 的标量子查询用全表名限定外层列，`AS` 仅用于表达式投影）；VO/PO 字段名 = 列名驼峰，map-underscore-to-camel-case 直映射；对账聚合不进合并器、纯只读。

**编译结果**：`mvn -q compile -pl marketing-types,marketing-domain,marketing-infrastructure,marketing-app -am` 通过。

**遗留依赖（重要）**：本分支基于 `draw-recon-round`（= master + spec 提交），其上**没有**孤儿预扣收敛特性（`draw_orphan_prereserve_order` 表、`orphanPrereserveScan` job，只存在于 `chat-ws-layering-rollout` 的 commit 2bf738f）。正向/反向配对 SQL 按 spec 引用 `draw_orphan_prereserve_order`——DDL/编译均不校验，待该特性合入集成分支后语义才完整。已如实交付按 spec 形状。

**基线编译错误修复**：`InventoryQuotaServiceImpl.degradeConfirm` 异常消息引用不存在的 `awardId`/`item`（commit cecfb7d 遗留，基线本就编不过），改为 `request.awardId()`/`request.bizId()`（与本方法上方 log 行同口径），单独 commit，行为无变化（该行从未编译通过过）。
