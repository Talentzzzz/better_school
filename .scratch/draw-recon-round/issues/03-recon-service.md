Status: ready-for-agent
Type: task
Blocked by: 01, 02, 04

# 03 对账域服务：编排 + 二次核对状态机 + 平账分流

## 目标

新建抽奖域对账服务 `service/recon/`（参照 `service/orphan/RaffleOrphanPrereserveServiceImpl` 形态），完成：轧账 → 差错登记（PENDING）→ 下轮复查确认（CONFIRMED / FALSE_ALARM）→ 平账分流。**不含**告警通路实现（端口接口与实现由票 04 提供，本票注入调用）与 xxl-job 触发器（票 05）。

## 上下文指针

- spec：`.scratch/draw-recon-round/spec.md`（Solution + Implementation Decisions，状态机与分流表全文在此）
- 探索报告：`.scratch/draw-recon-round/exploration.md`（§1.1 域服务分层纪律——**不注入 TransactionTemplate，每一步都是仓储/端口自带原子用例**；§5 端口模式；§1.2 job 口径）
- 依赖票：票 01（差错表落库层）、票 02（四条不变量查询端口）——合入后先读其 Answer。

## 交付内容

1. **接口 + 实现**：`marketing-domain/.../raffle/service/recon/IRaffleDrawReconService.java` + `RaffleDrawReconServiceImpl.java`。主入口形如 `int scan(LocalDateTime roundAnchor)` 或 `scan(String roundNo)`——job 薄壳直调，返回本轮处理差错数。轮次号口径：`yyyy-MM-dd` 日期字符串（同日重跑幂等，撞 uk 静默跳过）。
2. **轧账编排**：按 award_id 分批（分页扫台账账户，避免一次全量；批大小常量参照 `OrphanPrereserveScanJob.SCAN_LIMIT=100` 口径），逐批执行票 02 的四条查询，差异行逐条 `insertIgnore` 登记 PENDING（快照带期望/实际读数）。轮次表起轮/终轮各写一行。
3. **二次核对**：复查上一轮次（round_no = 上一日）的 PENDING 差错——本轮轧账结果中同 `(check_type, biz_id)` 仍出现 → `confirm`（PENDING→CONFIRMED 条件更新）；未再出现 → `markFalseAlarm`。**只有 CONFIRMED 才触发分流与告警**（消化日切假差错）。
4. **平账分流**（CONFIRMED 后，照 spec 分流表）：
   - 正向配对漏结且查无对账单 → **复用现有孤儿种单入口**补种（`IRaffleOrphanPrereserveService.seedOrphanPrereserves` 的扫描本身以 NOT EXISTS + before 阈值圈定候选，直接调用即可覆盖漏结单；补种后在差错行 `markAutoFixed`）。不要复制种单逻辑。
   - 孤儿单已 DEAD → 告警人工（经端口），差错行保持 CONFIRMED 等人工置 RESOLVED_MANUAL。
   - 守恒破坏（B1/B3）与反向缺失 → 只告警人工，**绝不自动修**。
5. **DEAD 孤儿单点名**：每轮扫描窗口内处于 DEAD 状态的孤儿对账单，逐单登记/更新一类独立 check_type 的差错并告警——周期性点名，不等复查确认（DEAD 是已确认事实，可直接告警；是否再走 PENDING→CONFIRMED 由你判断并在 Answer 说明理由，倾向直接告警 + 幂等登记）。
6. **告警端口**：票 04 已在域层定义告警端口接口（合入后读票 04 Answer 拿准确接口签名与调用形状）——本票在服务里注入并调用：CONFIRMED 分流与 DEAD 点名处发告警，消息带 roundNo、checkType、定位键、期望/实际快照摘要。
7. **不动的东西**：主链路三事务、合并器、孤儿收敛 job 自身逻辑零改动；对账服务只读其产物表。

## 验收

- `mvn compile` 通过；**不写测试、不跑测试**（spec Testing Decisions 明确覆盖收尾行为）。
- 类 javadoc 照孤儿服务的分层纪律声明写一段（旁路只读 + 无事务模板 + 幂等唯一键）。
- Answer 里写清：轮次号口径、DEAD 点名是否走复查、复用种单入口的调用方式。

## Answer

（完成后在此追加要点）
