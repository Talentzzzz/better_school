# 04: 锁单超时接入超时中心

**Spec:** `.scratch/errand-lock-review-hub/spec.md` §3（全章）、§2.2 迁移矩阵中「锁单超时 5min（SYSTEM）」一行

**What to build:** 接单方抢到单后 5 分钟不确认，订单自动回落 `PUBLISHED` 让他人可抢——由既有超时中心到点回调完成，不新建扫描 job，中心零代码改动（本 effort 只是新增接入方）。

**计时起点是「锁单时刻」**（5 分钟是接单方的确认窗口，从抢到手那刻起算），与支付超时「从创建时刻起算」**口径不同**，必须写清。注册就在抢单那次条件 UPDATE 的**同一事务**内写注册 outbox，`action_time = 锁单时刻 + 5min`。

**关键裁决——复合 `bizId`**：`bizId = {orderId}:{lockSeq}`，不能只用 `orderId`。因为中心对同 `(task_type, biz_id)` 的**在途**任务再次注册是「幂等成功、不改期」，若 A 抢单（t0）→ 放弃（t1）→ B 抢单（t2 < t0+5），B 的锁会挂到 t0+5 到点、确认窗口被压缩甚至卡死。给每次锁单一个独立键，让每次注册都是新键插入并携带正确的 `action_time`，陈旧任务只影响旧键。

回调侧：拆 `orderId:lockSeq` 后走条件 UPDATE `state='LOCKED' AND lock_seq=?`（**轮次守卫**，这是单靠状态守卫挡不住的），命中 0 行即幂等成功、零副作用；**同事务**落 `LOCK_TIMEOUT` 事件（`LOCKED→PUBLISHED`，actor `SYSTEM`）。释放会清 `taker_id`，所以领域方法必须**在清空前**读出被释放的接单方 id 并返回，供发一条 `ORDER_EVENT` 结果通知（`dedupKey` 带 `lockSeq`，多轮释放各发各的）。

**不做**：提前预警（5 分钟窗口触达不到）、滥用计数与扣分（归票 05）、调 `cancel`（轮次守卫已让多余回调零副作用，`cancelAsync` 是 best-effort 且带竞态面，记为可选优化）。

**Blocked by:** 03

**Status:** resolved

- [x] 新增 taskType 常量 `ERRAND_LOCK_TIMEOUT`，与既有支付超时常量并列；`callback_group` 沿用应用名，不改
- [x] 抢单事务内注册超时：`bizId={orderId}:{lockSeq}`、`action_time=锁单时刻+5min`，与 `PUBLISHED→LOCKED` 同事务
- [x] 回调按 taskType 分发，复合 `bizId` 单独解析（不走既有十进制 id 解析路径）
- [x] 释放：条件 UPDATE `state='LOCKED' AND lock_seq=?` 清 `taker_id`；命中 0 行＝幂等成功零副作用；同事务落 `LOCK_TIMEOUT` 事件（`SYSTEM`）
- [x] 释放后给**接单方**发一条 `ORDER_EVENT` 结果通知，`dedupKey` 含 `lockSeq`（多轮各发各的）
- [x] 集成测试覆盖：到点自动回落 `PUBLISHED` 且可被再次抢；「放弃 → 重抢」后新一轮拿到完整 5 分钟窗口；陈旧轮次回调零副作用；已确认/已取消的单收到回调零副作用

## Answer

落地要点：

- **常量**：`Constants.ERRAND_LOCK_TIMEOUT` 紧邻 `ERRAND_PAY_TIMEOUT`；`callback_group` 仍取 `${spring.application.name}`（`TimeoutRegisterOutboxStore` 内部字段，未改）。注释写明计时起点 = **锁单时刻**（与支付超时「从创建时刻起算」口径不同）。
- **注册（同事务）**：新增仓储原子用例 `grabWithLockTimeout(orderId, takerId, LOCK_TIMEOUT_MINUTES=5)`——`transactionTemplate` 内先 `grab` 条件 UPDATE（未命中返回 `false`、**不写注册 outbox**），再 `selectLockSeqById` 取本次 +1 后的轮次，写 `timeout_register_outbox`（`bizId=orderId+":"+lockSeq`，`action_time=now+5min`）。域 `grabOrder` 改调它（「手慢了」业务错仍由域层抛，故用例返回 `boolean` 而非仓储内抛错）。`scheduleLockTimeout` 与既有 `schedulePayTimeout` 同形并列在仓储接口。
- **回调（轮次守卫释放）**：`ErrandOrderServiceImpl.handleLockTimeout(orderId, lockSeq)` 先 `findById`（非 LOCKED / 不存在 → `null` 幂等忽略），**在清空前读出** `releasedTakerId`，再走 `releaseLockedByTimeout` 条件 UPDATE `state='LOCKED' AND lock_seq=?` 清 `taker_id`，同事务落 `LOCK_TIMEOUT` 事件（`LOCKED→PUBLISHED`，actor `SYSTEM`）；0 行返回 `false` → 域返回 `null`。**不调 `cancel`**（条件 UPDATE + 轮次守卫已使多余回调零副作用，`cancelAsync` 是 best-effort 带竞态面，记为可选优化）。
- **分发**：`TimeoutTaskCallbackDubboService.applyCloseAction` 由「先解析十进制 bizId 再 switch」改为**按 taskType 各自解析**——`ERRAND_LOCK_TIMEOUT` 走新 `parseLockBizId`（`{orderId}:{lockSeq}`，形状不对抛异常进失败清单重投），支付两路走原 `parseNumericBizId`（原名 `parseBizId`，改名以显式化「十进制」）；未接入 taskType 与坏 bizId 的失败隔离语义不变。
- **通知**：case 层 `handleLockTimeout` 编排，释放成功（非 `null`）后给**被释放的接单方**发 `ORDER_EVENT`（scope `MARKET_ERRAND`，ref `ERRAND`/orderId），`dedupKey="ORDER_EVENT:ERRAND_LOCK_TIMEOUT:{orderId}:{lockSeq}"` —— 多轮各发各的，同轮重投被唯一键吃掉。通知失败吞异常记 warn（判断项）：释放（幂等条件 UPDATE）是主效应，重投时释放已命中 0 行、通知也不会重试，故不让通知异常把一次成功的释放报成回调失败（换取零死信噪音）。
- **不做**（票面明列）：提前预警；滥用计数与扣分（归票 05，测试显式断言释放后 `credit_score=100`、无 `ERRAND_TIMEOUT` 明细）；调 `cancel`。**中心零代码改动**，只属新增接入方（超时中心侧文档回填归票 14）。
- **文档**：`docs/db/errand.md`（锁单超时释放链 + 两处超时注册口径）、`docs/api/errand.md`（grab 段补 5 分钟锁单超时）随码更新。
- **验证**：新增 `ErrandLockTimeoutIntegrationTest` 7 例（复合 bizId 注册窗口与即时注册；放弃→重抢新键拿满窗口 + 陈旧轮次零副作用；到点回落且他人可抢 + 通知落到被释放者；多轮各发各的 + 同轮重投不重复；已确认零副作用；已取消与不存在零副作用；坏形状进失败清单不拖垮同批）。单类 7/7 绿；相关 61 例全绿；`mvn test` **254 例全绿**（票 03 的 247 + 新增 7）；`mvn compile` / `test-compile` 干净。⚠️ 集成测试连常驻共享测试库，两条 Maven 测试进程不可并发。
- **两轴复查结论（code-review）**：Standards 轴无文档标准硬违规；Spec 轴六条验收项全部落地、无 scope creep。
  - **判过但未采纳**：①复合 `bizId` 的编码（仓储 `orderId+":"+lockSeq`）与解码（trigger `parseLockBizId`）分处两模块，未抽共享键类型——本仓支付超时的 bizId 就是 `String.valueOf(orderId)` 的字面构造，为一个两字段键立跨模块类型名不副实（Speculative Generality），两处各一行、无第三个解码点，故保持字面。②`releaseLockedByTimeout` 与 `abandonLockedById` 的 SQL 形状同（`LOCKED→PUBLISHED` 清 `taker_id`），但守卫不同（`lock_seq=?` 轮次守卫 vs `taker_id=?` 本人守卫），语义是两条边，合并需加可空入参反而更糊。③`IErrandRepository.grab` 保留在接口上（现仅被 `grabWithLockTimeout` 调用，直接调用会锁单不注册计时）——与 `payById` 仅被 `payWithPaidMessage` 调用同形，按既有约定保留。④集成测试夹具与 `ErrandLockStateIntegrationTest` 有约 10 个同形 helper——本仓集成测试各自独立（`TimeoutTaskCallbackIntegrationTest` 亦然），不抽基类。

