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

实现者：t03（分支 `draw-recon-round-t03-recon-service`）。未动主链路、合并器、孤儿收敛 job 自身任何代码。

### 新增/改动文件清单

| 文件 | 内容 |
| --- | --- |
| `marketing-domain/.../raffle/service/recon/IRaffleDrawReconService.java` | 新增：对账轮域服务接口，唯一入口 `int scan(String roundNo)` |
| `marketing-domain/.../raffle/service/recon/RaffleDrawReconServiceImpl.java` | 新增：编排实现（起轮 → 轧账登记 → DEAD 点名 → 二次核对与分流 → 终轮回填） |
| `marketing-domain/.../raffle/adapter/repository/IRaffleRepository.java` | 追加两个只读方法：`queryOrphanPrereserveOrderByRecordId`（正向分流判单）、`listDeadOrphanPrereserveOrders`（DEAD 点名扫描） |
| `marketing-infrastructure/.../raffle/adapter/repository/raffle/RaffleRepository.java` | 实现上述两方法（复用既有 `toOrphanPrereserveOrderEntity` 转换器） |
| `marketing-infrastructure/.../raffle/dao/IDrawOrphanPrereserveOrderDao.java` | 追加 `selectByRecordId` / `listDead` 两个只读 DAO 方法 |
| `marketing-app/.../mybatis/mapper/raffle/DrawOrphanPrereserveOrderMapper.xml` | 追加对应两条只读 select（零表别名，只读不写该表） |

### 关键决定与理由

- **轮次号口径**：`yyyy-MM-dd` 日期字符串（票内给定），入口 `scan(String roundNo)`，job 薄壳传 `LocalDate.now().toString()` 即可。同日重跑幂等由撞 `uk_round_no` / `uk_round_check_biz` 的 INSERT IGNORE 承担；二次核对的"上一轮次"= `LocalDate.parse(roundNo).minusDays(1)`。
- **二次核对判据**：轧账阶段把本轮出现过的 `(checkType, bizId)` 收进 Set（撞 uk 幂等跳过的键也算"出现"——比对对象是轧账结果不是插入结果）；复查上一轮 PENDING 时同键仍在 → `confirm`（条件更新 0→1，false 跳过）→ 按分流表处置；消失 → `markFalseAlarm`。
- **DEAD 点名不走复查**：死信是逐笔链路重试耗尽后的已确认事实，不存在日切误报，二次核对只会把点名推迟一轮而无收益——登记即 `CONFIRMED` + 直接告警。每轮新轮次号 → 每轮一条新差错行 → 周期性点名（dedupKey 含轮次，持续可见不重复打扰）；人工处置死信后下轮自然消停。
- **正向漏结复用种单入口**：CONFIRMED 的 FORWARD_PAIRING 先查 `queryOrphanPrereserveOrderByRecordId`：查无对账单 → 调 `IRaffleOrphanPrereserveService.seedOrphanPrereserves(SCAN_LIMIT)`（扫描本身以 NOT EXISTS + 10 分钟阈值圈定候选，INSERT IGNORE 幂等，逐笔链路接管）→ `markAutoFixed`；对账单 DEAD → 告警人工、保持 CONFIRMED；对账单 ORPHAN/DONE → 逐笔链路在途/已闭环，保持 CONFIRMED 留观测（置 AUTO_FIXED 会谎报"对账轮修好了"）。种单调用按差错逐条触发（每次全量扫描幂等无害），未复制任何种单逻辑。
- **守恒破坏（B1/B3）与反向缺失**：只告警（scopeKey/bizId/期望/实际快照全量入文案），绝不自动修，差错保持 CONFIRMED 等人工置 RESOLVED_MANUAL。
- **告警投递失败不中断轧账**：照票 04 端口 javadoc 的约定，`sendAlert` 内 try/catch 留 ERROR，下轮重新点名兜住。
- **分批口径**：票 02 端口的四条查询本身不 paginate——B1/B3 天然按 award_id 一行（行数 = 台账账户数，有界）、正向/反向只返回差异行（正常为空），结果集天然有界；`SCAN_LIMIT=100`（照 `OrphanPrereserveScanJob` 口径）用于种单入口与 DEAD 点名扫描的上限。台账账户数增长后如需真分页，扩展端口签名即可，编排不变。
- **返回值口径**：本轮处理的差错数 = 二次核对确认数 + 复查证伪数 + DEAD 点名数；新登记的 PENDING 尚未"处理"不计入。
- **快照文案**：B1 按限量/不限量分流拼（`surplus(x)+waiting(y)+issued(z)=n` / `waiting+issued=n（不限量户跳过 surplus）`），B3/正向/反向/点名各拼带读数的短文案（VARCHAR(255) 内）。

### 编译结果

`mvn -q compile`（`backend/marketing/`，JDK 21）**通过**（EXIT=0）。未写未跑任何测试（spec Testing Decisions）。

### 遗留风险

- 调度跨日中断（某日 job 未跑）时，更早轮次的 PENDING 差错不会被复查（复查只看"上一日"），会悬置——按票口径实现；如需兜底可改为复查"当前轮之前的全部 PENDING"，属一行查询条件的变化。
- 正向漏结补种后直接 `markAutoFixed`：若种单扫描与确认之间记录恰好落库（候选消失），种单不会覆盖该单但差错仍置 AUTO_FIXED——窗口极窄（10 分钟在途期外 + 确认后瞬时），下轮反向配对会给出真实读数。
