# 01: bs 版合并器（`CountMerger` + `ShardBuffer` + 五条不变量单测）

**Spec:** `.scratch/hot-row-merge-l2w256/spec.md` §2.1–§2.8；判据见 `acceptance.md` §2 A 组

**What to build:** 在 better-school 侧立起一台进程内分片缓冲合并器：调用方按「要去更新的那一行的主键」提交一件事件，窗口到点（或攒满、或被背压提前唤醒）后，同一分片的件作为一组交给一个落库方法；等结果的人拿到结果对象，不等的人连 future 都不分配。

两个入口分开，不做 `submit(key, payload, waitMode)` 那种合并签名：`submitAndWait` 返回 `MergeOutcome<T>`（五态：`COMPLETED / TIMEOUT / BUFFER_FULL / CLOSED / FAILED`），`submitAsync` 只返回 `ACCEPTED / BUFFER_FULL / CLOSED`。`shardKey` 由调用方显式给，合并器不解析 payload。

本票只交付引擎本身与建实例的配置（参数、启动日志、线程数校验），不接任何业务调用点——所以它可独立验证：A1–A7 全绿即完成。落库方法本票用测试替身，业务真身由 03 / 04 接入。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] A1–A7 单测全绿，用例名与 `acceptance.md` §2 A 组逐字一致（`everyAcceptedItemGetsExactlyOneTerminalState` / `singleFlightNeverTwoConcurrentFlushes` / `flushBlockingDoesNotBlockSubmit` / `closeStillDrainsBufferedItems` / `closeRejectsNewSubmissions` / `drainFailureIsCounted` / `rejectsWhenFullWithoutTouchingQueue` / `acceptedItemsSurviveRejection` / `batchNeverExceedsMaxBatch` / `sameShardKeepsSubmissionOrder`）
- [x] 五条不变量各有一条断言锁死：不丢件且恰一个终态（`Pending.complete` 幂等、漏回填补 `FAILED`）、同分片单飞（`running` + `generation` 双保险，**不用 `Future.cancel()` 判单飞**）、锁不跨 DB（`ReentrantLock`，临界区只碰内存）、停机三步（停收 → 排空 → 关池，排空超时显式回填并计入 `drainedFailed`）、背压有界（满即 false，不阻塞不扩容不挤掉已入队件）
- [x] `MergeConfigTest`：`shardCount` 非 2 的幂 / `waitTimeoutMs ≤ windowMs` / `shardCapacity < maxBatch` 在构造时即失败
- [x] 参数落默认值 `shardCount 256 / windowMs 5 / maxBatch 200 / waitTimeoutMs 100 / shardCapacity 5000 / flushThreads 4`；`shardCapacity` 只当「降级触发点 + 内存上界」，不做扩容
- [x] 分片路由用高位折叠（`key ^ (key >>> 32)` 再取 mask），高位 ID（雪花 / 分表拼接）不会全挤 0 号片；折叠是内部实现，不外泄
- [x] 定时器 `setRemoveOnCancelPolicy(true)`，攒满提前触发不会积压已取消的定时任务
- [x] `flushThreads ≤ poolSize − 2` 的校验写在建实例的配置里（`MergeConfig` 只校验 `≥ 1`），并把 `flushThreads` 与连接池上限一起打进启动日志
- [x] `MergeMetrics` 快照字段齐：`pending / accepted / flushed / batches / rejectedFull / rejectedClosed / timedOut / failed / drainedFailed / maxFlushNanos`；不依赖 micrometer、不开端点
- [x] 停机后可观测：`close()` 之后提交拿 `CLOSED`，`awaitIdle` 能等到排空
