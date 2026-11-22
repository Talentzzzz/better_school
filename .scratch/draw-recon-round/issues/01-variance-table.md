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

实现者：t01（分支 `draw-recon-round-t01-variance-table`）。全部新增，未动主链路/合并器/孤儿收敛 job。

### 新增/改动文件清单

| 文件 | 内容 |
| --- | --- |
| `backend/marketing/sql/schema.sql` | 追加 `draw_recon_variance`、`draw_recon_round` 两表 DDL（`CREATE TABLE IF NOT EXISTS`，表尾 create_time/update_time 无默认值，uk/idx 带语义 COMMENT） |
| `marketing-types/.../types/raffle/DrawReconVarianceState.java` | 差错状态枚举 PENDING(0)/CONFIRMED(1)/AUTO_FIXED(2)/RESOLVED_MANUAL(3)/FALSE_ALARM(4)，code + desc，javadoc 写明二次核对状态机 |
| `marketing-types/.../types/raffle/DrawReconCheckType.java` | 检查类型枚举五类：B1_CONSERVATION / B3_CONSERVATION / FORWARD_PAIRING / REVERSE_PAIRING / DEAD_ORPHAN_PATROL，javadoc 注明是二期扩展位 |
| `marketing-domain/.../raffle/model/entity/DrawReconVarianceEntity.java` | Entity，state/checkType 为枚举（域侧类型） |
| `marketing-domain/.../raffle/model/entity/DrawReconRoundEntity.java` | Entity，最小列 |
| `marketing-infrastructure/.../raffle/dao/po/DrawReconVariancePO.java` | PO，state Integer / checkType String |
| `marketing-infrastructure/.../raffle/dao/po/DrawReconRoundPO.java` | PO |
| `marketing-infrastructure/.../raffle/dao/IDrawReconVarianceDao.java` | insertIgnoreVariance / listPendingByRound / confirm / markAutoFixed / markFalseAlarm / markResolvedManual / countByRound / countByRoundAndState |
| `marketing-infrastructure/.../raffle/dao/IDrawReconRoundDao.java` | insertIgnoreRound / finishRound |
| `marketing-app/.../mybatis/mapper/raffle/DrawReconVarianceMapper.xml` | INSERT IGNORE + 条件更新（均带起点状态守卫，`update_time = NOW()` 照 RaffleMapper/AwardInventoryMapper 口径）+ 计数；零表别名、无 AS（map-underscore-to-camel-case） |
| `marketing-app/.../mybatis/mapper/raffle/DrawReconRoundMapper.xml` | 起轮 INSERT IGNORE + 终轮回填 |
| `marketing-domain/.../raffle/adapter/repository/IRaffleRepository.java` | 追加对账轮写面方法段（10 个方法） |
| `marketing-infrastructure/.../raffle/adapter/repository/raffle/RaffleRepository.java` | 注入两 DAO + 实现 + Entity↔PO 转换器（INSERT IGNORE 结果 > 0 判布尔） |

### 关键设计决定

- **幂等口径**：差错表 `uk_round_check_biz (round_no, check_type, biz_id)` + 轮次表 `uk_round_no`，均 INSERT IGNORE（扫描种单语义，照孤儿种单/库存明细先例）；所有状态推进是按 id + 起点状态的条件更新（confirm 0→1、markAutoFixed 1→2、markFalseAlarm 0→4、markResolvedManual 1→3），0 行 = 状态漂移，调用方跳过。
- **列设计**：`scope_key` 与 `biz_id` 分列——scope_key 是定位维度（award_id / record_id），biz_id 进幂等键末位；快照两列 VARCHAR(255) 存读数文本。
- **状态/类型落库形态**：state TINYINT 存枚举 code、check_type VARCHAR(32) 存枚举 name，映射收口在 RaffleRepository 转换器（未识别 code 抛 IllegalArgumentException，自己写自己读不会触发）。
- **终轮计数**：finishRound 按 round_no 直接回填三项计数（一轮一行无状态机，重跑幂等无害）；计数来源 countByRound / countByRoundAndState。
- **依赖先例**：本 worktree 的集成分支不含 `draw_orphan_prereserve_order`（该代码在 `chat-ws-layering-rollout`，与集成分支从 2d5a290 分叉，未合入），故形态镜像自集成分支现有先例：DrawRecordPO/IDrawRecordDetailDao/DrawRecordDetailMapper.xml（INSERT IGNORE、一表一 DAO）、RaffleMapper/AwardInventoryMapper 的条件更新（`update_time = NOW()`）、PointsReasonType 的枚举 javadoc 形态。实现要点（insertIgnore 撞 uk 静默跳过、条件更新状态守卫）与探索报告 §1.3/§6 描述的孤儿对账单形态一致。

### 编译外的额外修复（既有阻断）

`InventoryQuotaServiceImpl.degradeConfirm` L165 异常消息引用了作用域外的 `awardId`/`item`（集成分支既有笔误，marketing-domain 整体无法编译）。最小修复为 `request.awardId()`/`request.bizId()`（与 L159 日志同口径），单独 commit `23df5f9`。

### 编译结果

`mvn -q compile -pl marketing-types,marketing-domain,marketing-infrastructure,marketing-app -am`（`backend/marketing/` 下，JDK 21）**通过**（EXIT=0）。未写未跑任何测试（spec Testing Decisions）。
