Status: resolved

# 07 code-review 修复（draw-recon-round 集成分支审查问题）

集成分支 code-review 提出的 7 项问题，全部修复；编译验证通过（不跑测试，照 spec Testing Decisions）。

## 问题 → 修法 → 结果

1. **AUTO_FIXED 虚假置位（最严重）**
   - 问题：`routeForwardPairing` 调 `seedOrphanPrereserves(SCAN_LIMIT)` 全量扫描后无条件 `markReconVarianceAutoFixed`——扫描有 limit 截断 + 候选被 skip（缺扣次明细留 ERROR）时特定 biz_id 未必真种上，差错被错误关单且不再复查。
   - 修法：补种后用已有的 `queryOrphanPrereserveOrderByRecordId(recordId)` 回查；种上 → AUTO_FIXED；未命中 → 保持 CONFIRMED + 照常告警人工（文案说明「补种扫描未命中该单（不在候选序或候选被跳过）」）。
   - 结果：`RaffleDrawReconServiceImpl.routeForwardPairing` 已改，`recon.md` 分流表同步。

2. **复查窗口跨日中断漏洞**
   - 问题：`recheckPendingVariances` 固定 `prevRoundNo = roundNo minusDays(1)`——job 停跑一天后更早轮次的 PENDING 永久滞留。
   - 修法：复查改为全部 PENDING 差错（不分轮次）：本轮轧账 `(checkType, bizId)` 全集中仍在 → CONFIRMED，不在 → FALSE_ALARM；轮次字段保留在差错行快照与证伪日志里供人工溯源。DAO 链 `listPendingByRound` 扩为 `listAllPending`（mapper 去掉 round_no 过滤，零别名不变）。
   - 结果：`IDrawReconVarianceDao` / `DrawReconVarianceMapper.xml` / `IRaffleRepository` / `RaffleRepository` / 服务层全链改完，`recon.md` 段四同步。

3. **「按 award_id 分批扫」未实现（spec L56/L66、US14）**
   - 问题：四条不变量查询为全量单 SQL（无 LIMIT）。
   - 修法：端口新增 `listLedgerAwardIds(cursorAwardId, limit)`（award_id 升序游标分页）；四方法各加 `List<String> awardIds` 批次参数，mapper 加 `AND award_id IN (...)`（B1/B3/正向）/ `AND inv_id IN (...)`（反向，快照口径不变）；服务层按批大小常量 `AWARD_BATCH_SIZE = 100` 逐批跑四检查，批间差异行合并成一份清单后统一登记/复查。全部只读、无 FOR UPDATE，IN 列表 ≤100。
   - 结果：`IDrawReconQueryPort` / `DrawReconQueryPort` / `IInventoryReconQueryDao` / `IDrawRecordReconQueryDao` / 两个查询 mapper / 服务层编排全链改完，`recon.md` 调度段与段二同步。

4. **重复构造块**
   - 问题：`registerVariance` 与 `registerConfirmedVariance` 同形（仅 state 与日志级别不同）。
   - 修法：合并为单方法传 `DrawReconVarianceState state`（CONFIRMED 分支 warn、否则 info），共用 `newVarianceEntity` 构造。
   - 结果：已合并，DEAD 点名调用点同步。

5. **`n(Long)` 改名**
   - 修法：`RaffleDrawReconServiceImpl` 私有方法 `n` → `nullToZero`。
   - 结果：已改名，全部调用点同步。

6. **Speculative Generality（未用 Java API 链）**
   - 问题：`markReconVarianceResolvedManual` 全链路无调用方（IRaffleRepository → RaffleRepository → IDrawReconVarianceDao → mapper `markResolvedManual`）。
   - 修法：删除整条链；人工处置仍走 SQL 直更（`recon.md` 已注明），枚举值与 DDL 状态机保留不动。
   - 结果：四处全删，`recon.md` 状态机段同步说明。

7. **Data Clumps（告警端口 7 参）**
   - 问题：`IDrawReconAlertPort.sendReconAlert` 七个结伴参数。
   - 修法：收拢为传 `DrawReconAlertMessage` 对象（marketing-types 已有，domain 依赖 types 无越层问题）；`DrawReconAlertPort` 实现与 `RaffleDrawReconServiceImpl.sendAlert` 调用点同步；载荷字段与形状不变，单体侧 `ReconAlertConsumer` 无需改动。
   - 结果：端口接口/实现/调用点全链改完。`finishReconRound` 5 参未收拢（非强制项，保持现状）。

## Answer

编译结果：`backend/marketing` 下 `mvn -q compile` 通过（JDK 21 / Maven，退出码 0，无告警输出）。未写测试、未跑测试（照 spec Testing Decisions）。
