# ShardFlusher 重构实施

- **id**: 002
- **type**: wayfinder:task
- **status**: resolved
- **blocked-by**: —
- **assignee**: agent（2026-11-16 实施会话）

## Question

按 charting-log.md 的六项决策实施重构（建议单会话完成，文件量可控）：

1. 新建 `ShardFlusher`（替换 `ShardBuffer`）：每分片一个常驻 daemon **平台**线程（命名如 `quota-merge-flush-<shardNo>`）；主循环 = `lock` → 若 `closed` 且缓冲空则退出 → 若有积压（背靠背）或首件已到则取截止时刻 → `awaitUntil(deadline)`（攒满或新件时被 `signal` 提前唤醒）→ 锁内 swap 摘批 → `unlock` → 锁外 `flushCallback` → 逐件回填（引擎职责）→ `flushDoneSignal.run()`。状态变量收敛为 `pending` + `size` 镜像 + `closed`；删除 `scheduled`/`flight`/`running`/`generation`/`bringForward`/`scheduleNext` 整套任务替换机制。
2. `QuotaMergeBuffer` 适配：删除 `ScheduledThreadPoolExecutor` 与 `RemoveOnCancelPolicy`；分片数组改为创建 `ShardFlusher`；`close()` 三步语义不变，收尾 signal + join(drainTimeout)；`signalIdle`、`flushBatch` 及落库/回填路径不动。
3. 配置清理与重约束：`MergeConfig` 删 `flushThreads` 字段与构造参数，`DEFAULT_SHARD_COUNT` 64 → 8；`QuotaMergeConfig`（marketing-app）删"flush-threads <= 池−2"校验，**改为校验 `shardCount <= 连接池 - 2`**（per-shard 线程模型下 flush 并发 = shardCount，不变量换对象，见 charting-log Q7）；检查 yml 残留 `merge.inventory.flush-threads`。
4. 测试适配（不改断言语义）：`MergeConfigTest`、`QuotaMergeConfigTest` 删除 flushThreads 相关用例；其余测试构造器改参。

## Resolution

已解决（2026-11-16，随 marketing 库存域 WIP 一并提交，commit `07f3a44`）。四项逐条落地：

1. `ShardFlusher.java` 新建于 `marketing-domain/.../inventory/service/merge/`（`ShardBuffer.java` 删除）：主循环为标准 Condition 等待协议——谓词锁内检查、`awaitNanos(剩余)`（≡ `awaitUntil`）等窗口、offer 首件/攒满持锁 signal、failRemaining 停机持锁 signalAll；窗口语义 = 首件起算 + 攒满提前 + 摘批积压（carryOver）背靠背立即续跑，与旧实现「攒满续跑 0 延迟 / 非满再等一窗」兼容。线程用 `Thread.ofPlatform().daemon(true).name("quota-merge-flush-" + shardNo)`，构造即启动。
2. `QuotaMergeBuffer`：调度器与 RemoveOnCancelPolicy 全删，`shards` 改 `ShardFlusher[]`；`close()` = 停收 → `awaitIdle` → 逐片 `failRemaining`（置 closed + signalAll，回填 FAILED）→ 共享 `drainTimeoutMs` 预算逐片 `join`（线程只在 closed 且缓冲空时退出，join 返回即在途批已收尾）。`submitAndWait`×2 / `pending` / `awaitIdle` 对外签名不动。
3. `MergeConfig` 删 flushThreads 字段/参数/校验，`DEFAULT_SHARD_COUNT=8`（附例外条件注释）；`QuotaMergeConfig` 校验换对象 `shardCount <= 池 − 2`（IllegalStateException，口径与数字进日志）；application.yml 删 `flush-threads` 行、`shard-count: 8`，头部注释同步改写（顺带清掉了指向已删 MergeMetrics 的陈旧调参注释）。
4. 测试：`MergeConfigTest` 删 flushThreads 用例、默认值 8 / mask 7、散片阈值 32→4（对齐 8 片）；`QuotaMergeConfigTest` flushThreads 两例换 shardCount=池−2 合法 / shardCount>池−2 拒绝两例（`readsEveryProperty` 池调 512 以容纳 256 片）；`QuotaMergeBufferTest` 构造器全部改参（7→6 参），断言零改动。

遗留（评审判断项，非阻塞）：`MergeConfig` 六个位置参数的 Data Clumps / Shotgun Surgery、`flushDeductGroup`/`flushClaimGroup` 同构，建议另起 effort 收敛；yml Redis 段「Lua 原子预扣」注释是票 12 之前的陈旧话术，属库存域旧账未动。

验收注记：本机无 Maven 环境，用户指示跳过跑测试；并发正确性以人工走查 + 双轴评审代偿（Spec 轴确认主循环 / Condition 协议 / 停机三步 / isIdle-by-join 均成立，未发现丢件或自旋风险）。bs-infrastructure 孪生副本（CountMerger）按 charting-log 范围决策未动。

后续验证（票 003，2026-11-16）：测试侧走查通过——16 个 `QuotaMergeBufferTest` 用例 + `MergeConfigTest` / `QuotaMergeConfigTest` + inventory 集成测试均在新模型下成立，无时序脆弱用例、无行为回归（时序同步点全是 latch/轮询，不依赖调度器节拍）；仅修正 A1 一处引用已删 `scheduled` 标志的陈旧注释，断言零改动。明细见 ticket 003 的 Resolution。
