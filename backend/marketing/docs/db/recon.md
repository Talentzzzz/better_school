# 抽奖库存全量对账轮（drawReconScan）：逐笔孤儿收敛之上的总量兜底

> 业务：抽奖库存一致性兜底（营销域 `backend/marketing/`，draw-recon-round 票）；
> 归属域：`marketing.domain.raffle`（`service/recon`，照 `service/orphan` 的分层纪律）
> **纯旁路**：业务表只读（四条不变量聚合 + 孤儿对账单产物），写面仅 `draw_recon_variance` / `draw_recon_round` 两表——对账轮自身故障完全不影响主链路。
> 与逐笔孤儿预扣收敛 job（`orphanPrereserveScan`，cron `0/30`，见 [inventory.md](inventory.md)「孤儿预扣」）**完全独立**：互不依赖代码路径，只读其产物表（`draw_orphan_prereserve_order`）。
> 结构与根目录 `sql.md` §2 逐表一致（唯一事实源）。DDL 在本工程 `sql/schema.sql`。

| 表名 | 说明 |
| ---- | ---- |
| `draw_recon_variance` | 差错表：对账轮发现的不一致登记（轮次号 + 检查类型 + 定位键 + 期望/实际值快照 + 状态机；`uk_round_check_biz` 幂等） |
| `draw_recon_round` | 对账轮次观测表：每轮起止与计数（一轮一行，纯观测用，最小列） |
| （只读）`draw_strategy_award_inventory` / `draw_strategy_award_inventory_detail` / `draw_record` / `draw_orphan_prereserve_order` | 四条不变量与 DEAD 点名的取数面，见 [inventory.md](inventory.md) / [draw.md](draw.md) |

调度：xxl-job handler `drawReconScan`，执行器 `marketing-executor`，cron `0 0 3 * * ?`（每日 03:00 低峰），注册在 `sql/xxl-job-tasks.sql`。轮次号口径 = `yyyy-MM-dd` 日期字符串；同日重跑零副作用（起轮与差错登记均 INSERT IGNORE 撞唯一键跳过）。数据窗口：对账数据集只取 `update_time < now - 10min` 的行（与孤儿种单阈值对齐，避开主链路在途窗口）；扫描上限 `SCAN_LIMIT = 100`（照 `OrphanPrereserveScanJob` 口径）。

## 一轮五段（编排口径）

实现：`RaffleDrawReconServiceImpl.scan(roundNo)`（编排与口径判断归抽奖域一个对账服务——不注入事务模板，每一步都是仓储/端口自带的原子用例；四条不变量以只读聚合查询落基础设施层 mapper，触发层 xxl-job 薄壳照 `OrphanPrereserveScanJob`——不调 `XxlJobHelper`，handler 正常返回即成功）。

1. **起轮**：`draw_recon_round` 起轮 INSERT IGNORE（撞 `uk_round_no` 幂等跳过）。
2. **轧账登记**：四条不变量只读聚合，差异行逐条 INSERT IGNORE 登记差错表，一律 `PENDING`（快照带期望/实际读数）；本轮出现过的 `(检查类型, biz_id)` 收进比对集（撞键被幂等跳过的键也算「出现」——比对对象是轧账结果，不是插入结果）。
3. **DEAD 孤儿单点名**：死信是逐笔链路重试耗尽后的**已确认事实**（不存在日切误报），不走二次核对——登记即 `CONFIRMED` + 直接告警；每轮新轮次号 → 每轮一条新差错行 → 周期性点名，人工处置死信后下轮自然消停。
4. **二次核对与平账分流**：复查上一轮的 `PENDING` 差错——同键（检查类型 + biz_id）本轮仍在 → 条件更新确认并按分流表处置；未再出现 → `FALSE_ALARM`（消化取数时刻两侧快照不一致的日切假差错）。
5. **终轮回填**：按轮次号回填登记 / 确认 / 自动修复三项计数（一轮一行无状态机，重跑按计数覆盖无害）。

## 四条不变量公式

全部只读聚合，公式出处是现有代码已声明的守恒判据——对账轮只是把它们变成每天真跑一遍的 SQL（此前守恒等式只由集成测试断言锁住，见 [inventory.md](inventory.md) 守恒口径 §0）。差异判断在 Java 侧（SQL 不做 CASE 判平），聚合不进合并器（低频对账动作）。

| # | 检查类型 | 公式 | 出处 |
| --- | --- | --- | --- |
| 1 | `B1_CONSERVATION`（台账守恒） | 限量户（`surplus_qty ≠ -1`）：`surplus_qty + waiting_qty + issued_qty = Σ(flow_type=0 分配转入明细 quantity)`，按 `award_id` 分组；不限量户跳过 surplus 项（`waiting + issued = Σ(flow_type=0)`）；VIRTUAL 行 `waiting_qty` 恒 0 公式自然成立 | 库存 flush 落库守恒判据 B2/B3 注释 + `docs/db/inventory.md` §0；SQL：`InventoryReconQueryMapper.xml` `queryLedgerConservation` |
| 2 | `B3_CONSERVATION`（预扣守恒） | `waiting_qty + issued_qty = Σ(flow_type=1 预扣明细 quantity)`，按 `award_id` 分组（回补是「台账转回 + 删明细」同事务，公式天然平衡） | `AwardInventoryDetailMapper.xml` `deleteDeductDetail` 上方注释（守恒判据 B3）；SQL：同上 `queryPrereserveConservation` |
| 3 | `FORWARD_PAIRING`（正向配对） | 每条 `flow_type=1 AND biz_type='LOTTERY_DRAW'` 的明细 `biz_id`（= record_id）必须命中 `draw_record.record_id` 或 `draw_orphan_prereserve_order.record_id`，否则为漏结（孤儿种单环节漏了） | `AwardInventoryDetailMapper.xml` 孤儿候选扫描 SQL（NOT EXISTS 反查先例）；SQL：`listUnmatchedDeductDetails` |
| 4 | `REVERSE_PAIRING`（反向配对） | 每条 `draw_record`（`inv_id IS NOT NULL AND award_type ≠ 'THANKS'`）必须存在对应预扣明细（`award_id = inv_id` 快照、`biz_id = record_id`），否则为数据破坏级缺失 | 反向守恒口径（记录在、预扣明细不在 = 破坏 B3 的另一侧）；SQL：`DrawRecordReconQueryMapper.xml` `listRecordsMissingDeduct` |

## 差错状态机

`draw_recon_variance.state`（`DrawReconVarianceState`）：

```
PENDING(0) ── 下轮复查仍在 ──▶ CONFIRMED(1) ──┬─ 正向漏结自动补种成功 ──▶ AUTO_FIXED(2)
    │                                          ├─ 人工核对处置完成     ──▶ RESOLVED_MANUAL(3)
    └── 下轮复查消失 ──▶ FALSE_ALARM(4)         └─ 守恒破坏/反向缺失：保持 CONFIRMED 等人工
```

- 所有推进都是**按 id + 起点状态的条件更新**（confirm `0→1`、autoFixed `1→2`、falseAlarm `0→4`、resolvedManual `1→3`），0 行 = 状态漂移，调用方跳过。
- 幂等：`uk_round_check_biz (round_no, check_type, biz_id)`，登记 INSERT IGNORE——调度重复触发 / 同日重跑零副作用。
- `DEAD_ORPHAN_PATROL`（第五类检查）登记即 `CONFIRMED`，不进二次核对（见「一轮五段」段三）。

## 平账分流表（CONFIRMED 后）

| 差异情形（检查类型） | CONFIRMED 后处置 | 终态 |
| --- | --- | --- |
| 正向漏结、查无孤儿对账单（`FORWARD_PAIRING`） | 复用现有孤儿种单入口 `seedOrphanPrereserves` 补种（扫描以 NOT EXISTS + 10 分钟阈值圈定候选，INSERT IGNORE 幂等，不复制种单逻辑），逐笔链路接管 | `AUTO_FIXED` |
| 正向漏结、对账单已死信（`FORWARD_PAIRING`） | 告警人工（逐笔链路救不回），差错保持 CONFIRMED | 人工处置后 `RESOLVED_MANUAL` |
| 正向漏结、对账单在途（ORPHAN）/已收敛（DONE） | 逐笔收敛链路已接管或闭环，保持 CONFIRMED 留观测（置 AUTO_FIXED 会谎报「对账轮修好了」） | 保持 CONFIRMED |
| 守恒破坏（`B1_CONSERVATION` / `B3_CONSERVATION`）、反向缺失（`REVERSE_PAIRING`） | **只告警人工，绝不自动修**（数据破坏级；自动动作会放大事故） | 人工核对后 `RESOLVED_MANUAL` |
| DEAD 孤儿单（`DEAD_ORPHAN_PATROL`） | 登记即 CONFIRMED + 直接告警（周期性点名，dedupKey 含轮次，持续可见不重复打扰） | 人工处置死信后下轮消停 |
| 二次核对复查消失（任意轧账类） | 日切边界假差错收口 | `FALSE_ALARM` |

告警链路（复用现有营销通知链路，不新接通道）：域端口 `IDrawReconAlertPort` → `OutboxMessageStore.saveStandalone`（topic `bs-marketing-recon-alert`）→ 单体 `ReconAlertConsumer` → `MarketingNoticeCase.notifyReconAlert` 落系统账号通知（dedupKey = `MARKETING:RECON:<roundNo>:<checkType>:<bizId>`，消息带轮次、类型、定位键、期望/实际快照）。告警投递失败不中断本轮轧账（ERROR 日志，下一轮对账重新点名兜住）；`PENDING` 差错不发告警。

## 排查 SQL 示例（人工验证口径，替代自动化测试）

人工验证口径（spec Testing Decisions，本期不跑自动化测试）：对开发库手工执行四条不变量 SQL 与预期**空结果**比对；触发一次 job，确认差错表落行 / 状态推进 / 通知发送符合分流表。

```sql
-- ① B1 台账守恒（限量户）：期望 surplus+waiting+issued = allocated（行 = 不平账户）
SELECT i.award_id, i.surplus_qty, i.waiting_qty, i.issued_qty,
       (SELECT COALESCE(SUM(d.quantity), 0) FROM draw_strategy_award_inventory_detail d
         WHERE d.award_id = i.award_id AND d.flow_type = 0) AS allocated_qty
  FROM draw_strategy_award_inventory i
 WHERE i.surplus_qty <> -1;                -- 不限量户跳过 surplus 项：比 waiting+issued 与 allocated

-- ② B3 预扣守恒：期望 waiting+issued = deducted（行 = 不平账户）
SELECT i.award_id, i.waiting_qty, i.issued_qty,
       (SELECT COALESCE(SUM(d.quantity), 0) FROM draw_strategy_award_inventory_detail d
         WHERE d.award_id = i.award_id AND d.flow_type = 1) AS deducted_qty
  FROM draw_strategy_award_inventory i;

-- ③ 正向配对：有预扣明细、记录与对账单都不在 = 漏结（种单环节漏了）
SELECT d.award_id, d.biz_id, d.quantity, d.create_time
  FROM draw_strategy_award_inventory_detail d
  LEFT JOIN draw_record r ON r.record_id = d.biz_id
  LEFT JOIN draw_orphan_prereserve_order o ON o.record_id = d.biz_id
 WHERE d.flow_type = 1 AND d.biz_type = 'LOTTERY_DRAW'
   AND r.record_id IS NULL AND o.record_id IS NULL;

-- ④ 反向配对：记录在、预扣明细丢 = 数据破坏级（只告警，绝不自动修）
SELECT r.record_id, r.inv_id, r.user_id
  FROM draw_record r
 WHERE r.inv_id IS NOT NULL AND r.award_type <> 'THANKS'
   AND NOT EXISTS (SELECT 1 FROM draw_strategy_award_inventory_detail d
                    WHERE d.award_id = r.inv_id AND d.biz_type = 'LOTTERY_DRAW'
                      AND d.biz_id = r.record_id);

-- 差错表：待复查 / 已确认差异（人工核对的入口）
SELECT round_no, check_type, scope_key, biz_id, expect_snapshot, actual_snapshot, state
  FROM draw_recon_variance
 WHERE state IN (0, 1) ORDER BY round_no DESC, state, check_type;

-- 轮次观测：每轮起止与计数
SELECT round_no, scan_start_time, scan_end_time, variance_total, confirmed_total, auto_fixed_total
  FROM draw_recon_round ORDER BY id DESC LIMIT 10;

-- DEAD 孤儿单点名（对账轮也会周期性告警，这里人工复核）
SELECT record_id, award_id, retry_count, update_time
  FROM draw_orphan_prereserve_order WHERE state = 2 ORDER BY update_time;
```

人工收口：核对确属差异且已修复后，置 `RESOLVED_MANUAL`（并写 `finish_time`）；确认为日切假差错则置 `FALSE_ALARM`。

```sql
UPDATE draw_recon_variance SET state = 3, finish_time = NOW(), update_time = NOW()
 WHERE round_no = ? AND check_type = ? AND biz_id = ? AND state = 1;
```

## 不动的部分与二期扩展

- 主链路三事务、合并器、孤儿预扣收敛 job、幂等键口径一律不改；对账轮与其零代码共享（只读其产物表）。
- 检查清单可扩展（`DrawReconCheckType` 枚举即扩展位）：池侧守恒（`inventory_allocation_flow` 的「池转出量 = Σ分配流水」）、次数桶、积分桶对账为二期，公式形状与本轮一致，编排与差错状态机复用。
