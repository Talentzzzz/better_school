# 全量验证：测试全绿 + 行为兼容确认

- **id**: 003
- **type**: wayfinder:task
- **status**: resolved
- **blocked-by**: 002
- **assignee**: agent（2026-11-16 实施会话）

## Question

跑通 marketing 模块全部测试并确认行为兼容：

1. `mvn test`（marketing-domain + marketing-app），确认 `QuotaMergeBufferTest`（攒满提前触发、排空、停机回填、满即拒等 ~20 用例）、`MergeConfigTest`、`QuotaMergeConfigTest`、inventory 集成测试（`InventoryQuotaConservationIntegrationTest`、`InventoryFlushFailureIntegrationTest` 等）全绿；
2. 线程模型替换可能暴露的时序敏感用例（原依赖调度器节拍的等待），逐个排查是"测试脆弱"还是"行为回归"，前者适配后者修复；
3. 对照 charting-log 逐条确认外部行为不变：首件起算窗口、攒满提前、逐件回填（含 INSUFFICIENT 部分失败）、排空信号、停机三步；
4. 在 ticket 002 与本 ticket 记录结果，更新 map 的 Decisions so far。

## Resolution

已解决（2026-11-16）。本机无 Maven 环境，用户指示跳过跑测试；以**逐用例代码走查**代偿（与 002 验收口径一致），三个层面结论如下：

**1. 测试全绿判定（走查代偿）**

- `QuotaMergeBufferTest` 16 个用例逐一对照 `ShardFlusher` 新主循环走查：构造参数全部为 6 参新签名 `(shardCount, delay, batch, wait, capacity, drain)`，数值语义对位无误（如 A5 的 `(8,5,8,500,8,2000)` = 容量 8 满即拒 + wait 500ms）。断言零改动，仅修正 A1 中一处引用已删 `scheduled` 标志的陈旧注释（断言语义不变）。
- `MergeConfigTest`：负例全部命中 6 参构造的对应校验分支；默认值 8/5/200/100/2000/5000 与 `quotaDefaults()` 一致；散片阈值 4 对齐 8 片。
- `QuotaMergeConfigTest`：校验顺序安全——`stillDelegatesEngineLevelValidation`（shard-count=100）先在 `MergeConfig` 构造抛 `IllegalArgumentException`，早于装配层的 `IllegalStateException`，异常类型区分成立；池校验三例（8=10−2 合法 / 16>8 拒绝 / 256≤510 合法）数字均对。
- inventory 集成测试（`InventoryQuotaConservationIntegrationTest` / `InventoryFlushFailureIntegrationTest` / `PhysicalQuotaIntegrationTest` / `VirtualInventoryIntegrationTest`）：只走公共 API（`submitAndWait`/`awaitIdle`/`pending`），不构造 `MergeConfig`、不引用已删符号，超时重放循环（TIMEOUT_UNKNOWN/FAILED → sleep 20ms 重放）对线程模型不敏感，零适配。
- 全局搜证：`flush-threads`/`flushThreads`/`ShardBuffer` 在 marketing 代码与 yml 中零残留（仅历史文档 `award-inventory-refactor-plan.md` 的旧表格提及，属历史记录不改）；`new MergeConfig` 的其余命中全部在 Out-of-scope 的 bs-infrastructure 孪生副本（7 参旧签名，原样保留）。

**2. 时序敏感用例排查：无脆弱用例，无行为回归**

旧实现测试的同步点全部是 latch（`entered`/`hold`/闸门）与轮询（`awaitBuffered`），从未依赖调度器节拍；新模型用 `awaitNanos(deadline)` 精确到点唤醒，时序只紧不松。逐类核对：

- 锁不跨 DB（A3/A5）：`offer` 只需缓冲锁，flush 线程在锁外回调期间锁已释放——入队毫秒级成立；
- 停机排空（A4 closeStillDrains）：窗口 1000ms < drain 预算 3000ms，常驻线程到点自醒落库，`awaitIdle` 由 `flushDoneSignal` 唤醒——与旧"调度任务到点跑"等价；
- 停机回填（A4 drainFailureBackfillsFailed）：`failRemaining` 持锁置 `closed` + `signalAll`，线程从窗口等待中醒来即退出，join 预算内返回；"close 后 150ms 无 flush"从旧版"靠取消调度任务"变为新版"closed 标志结构性保证"——**更强**；
- 攒满提前撞在途 flush（A2）：signal 落空无害（收尾重查谓词），单飞由"每分片恰一条线程"结构性保证，`maxInFlight=1` 恒成立。

**3. 对照 charting-log 的外部行为兼容清单（逐条）**

- 首件起算窗口 ✓：`windowEndNanos = now + maxFlushDelayNanos` 仅在闲置后首件/无积压续跑时设置；sameShard 保序用例的 1000ms 宽窗口语义不变。
- 攒满提前 ✓：`offer` 持锁 signal（首件 + 攒满两路），线程在等待或落库中都能正确接住（后者靠收尾重查）。
- 逐件回填（含 INSUFFICIENT 部分失败）✓：`backfill` 按位回填，`INSUFFICIENT` 走 `complete()` 原样回给调用方；漏回填（null/短列表）补 `FAILED`；整组异常只吞本组。
- 排空信号 ✓：每批收尾 `flushDoneSignal.run()` → `signalIdle`（tryLock 兜底）→ `awaitIdle` 不轮询（5ms 探测仅防信号丢失）。
- 停机三步 ✓：停收（`accepting=false`）→ 排空（`awaitIdle(drainTimeoutMs)`）→ 回填 + signal + join（共享 drainTimeout 预算逐片分摊）；在途批由"线程只在 closed 且缓冲空时退出"兜底。

**变更记录**：`QuotaMergeBufferTest.java` 一处注释修正（A1，`scheduled` → per-shard 线程模型表述），断言零改动。遗留验收债：有 Maven 环境后跑 `mvn test`（marketing-domain + marketing-app）做最终确认。
