Status: ready-for-agent
Type: task
Blocked by:

# 01 差错表 `draw_recon_variance` + 轮次表落库层

## 目标

新建对账差错表与轮次表的完整落库层：DDL → 枚举 → PO → DAO → Mapper XML → 仓储端口方法。本票只做存储层，不含对账服务逻辑。

## 上下文指针

- spec：`.scratch/draw-recon-round/spec.md`（Implementation Decisions 节的差错表设计）
- 探索报告：`.scratch/draw-recon-round/exploration.md`（§1.3 对账单表先例、§4 落点表、§6 幂等/唯一键惯例、命名口径）
- 必读规范：仓库根 `CLAUDE.md`（Java/SQL 编码规范——SQL 零别名、无 AS 迁就、Entity 参数命名）

## 交付内容

1. **DDL**（`backend/marketing/sql/schema.sql` 追加，`CREATE TABLE IF NOT EXISTS`，照既有表形状：`id BIGINT AUTO_INCREMENT` 开头、业务键单独成列、表尾 `create_time/update_time DATETIME NOT NULL` 无默认值、`ENGINE=InnoDB DEFAULT CHARSET=utf8mb4` + 表 COMMENT、唯一键带语义 COMMENT）：
   - `draw_recon_variance`：`round_no`（轮次号）、`check_type`、`scope_key`（定位键：award_id 或 record_id）、`biz_id`（业务单号）、`expect_snapshot` / `actual_snapshot`（期望/实际值快照，VARCHAR 足够）、`state TINYINT`、`fixed_time`（自动修复时间，可 NULL）、`finish_time`（人工处理时间，可 NULL）。`UNIQUE KEY uk_round_check_biz (round_no, check_type, biz_id)`（幂等键，重跑零副作用）+ `KEY idx_state_round (state, round_no)`（复查扫描用）。
   - `draw_recon_round`：纯观测轮次表，最小列——`round_no`（`uk_round_no`）、`scan_start_time`、`scan_end_time`、`variance_total`、`confirmed_total`、`auto_fixed_total`。不要加超出此范围的列。
2. **状态/类型枚举**（`marketing-types/.../raffle/`）：差错状态 `DrawReconVarianceState`（PENDING(0)/CONFIRMED(1)/AUTO_FIXED(2)/RESOLVED_MANUAL(3)/FALSE_ALARM(4)）；检查类型 `DrawReconCheckType`（至少含 B1 台账守恒、B3 预扣守恒、正向配对、反向配对、DEAD 孤儿单点名五类，命名照仓库惯例）。照 `DrawOrphanPrereserveState` 的形态写 javadoc。
3. **PO + Entity + DAO + Mapper XML**：照 `draw_orphan_prereserve_order` 全套先例（exploration §1.3）——PO 在 `marketing-infrastructure/.../raffle/dao/po/`，DAO 接口一表一个，Mapper XML 在 `marketing-app/src/main/resources/mybatis/mapper/raffle/`。DAO 至少需要：
   - 差错表：`insertIgnore`（撞 uk 静默跳过，扫描种单语义）、`listPendingByRound`、`confirm`（`state 0→1` 条件更新）、`markAutoFixed`、`markFalseAlarm`、`markResolvedManual`、按 round 统计计数；
   - 轮次表：`insertIgnoreRound`（起轮）、`finishRound`（终轮 + 计数回填）。
4. **Entity**（`marketing-domain/.../raffle/model/entity/`）+ **仓储端口方法**：在 `IRaffleRepository` 追加对应方法（照孤儿对账方法段 L207-236 的形状），实现在 `RaffleRepository`（Entity↔PO 手工映射，INSERT IGNORE 返回 `> 0` 判布尔）。

## 验收

- `mvn -pl marketing-types,marketing-domain,marketing-infrastructure,marketing-app -am compile`（在 `backend/marketing/` 下）通过（只编译，不跑测试）。
- schema.sql 追加段落与既有表风格一致（注释齐全、无多余列）。
- 不写任何测试（spec Testing Decisions：本期零测试）。

## Answer

（完成后在此追加要点）
