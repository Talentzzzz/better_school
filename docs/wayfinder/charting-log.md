# Wayfinder Charting Log（建图决策记录）

2026-11-16 建图会话。grilling 两轮，以下为全部落定决策及其理由。

## Q3 线程模型

每分片一个常驻 daemon 平台线程（选项 a）。三选一比较：全局单线程会分片间背靠背互相拖；共享线程池又引入映射复杂度。RocketMQ `GroupCommitService` 就是单常驻线程形状，单飞天然成立。`shardCount` 当前为 8，线程成本可忽略。

## Q1 唤醒/攒批原语

`ReentrantLock` + `Condition.awaitUntil(deadline)` + `signal`（选项 a）。

- 保留"首件起算窗口 + 攒满提前"的外部时序语义，只换驱动机制（调度任务 → 常驻线程）。
- **不抄 RocketMQ 的 reset-latch**：源码考据发现 `ServiceThread` 的 `waitPoint.reset()` 与 `wakeup()` 存在已定性的丢失唤醒竞态（issue #5710, 2022 优化；#10543, 2026-06 定性为 bug 并改用 LockSupport + deadline 循环修复）。`Condition` 的标准条件等待协议（谓词锁内检查、park 前最后再查、signal 持锁）在协议层面免疫该竞态；#5710 评论区 RocketMQ 社区自己也建议过换 Condition。

## 虚拟线程 vs 普通线程

**普通（平台）线程**。理由：

1. 数量小（每分片一条，~8 条），虚拟线程"海量并发"价值用不上；
2. flush 线程执行阻塞 JDBC，`synchronized`/JNI pin 载体线程的风险面（mysql-connector-j、HikariCP ConcurrentBag 历史）不值得引入；
3. 平台线程有稳定名字与经典栈，卡行锁时 jstack 一眼可见，常驻关键路径线程的排障可见性是硬价值；
4. 常驻 + daemon + 需精确 join 的生命周期与平台线程语义贴合。

例外条件（已入地图 fog）：分片数上百上千时结论翻转，重议虚拟线程。

## Q6 停机方式

保留三步语义：停收（`accepting=false`）→ 排空（`awaitIdle(drainTimeoutMs)`）→ 超时回填失败态（`failRemaining`）。"关池"替换为：signal 全部 flush 线程退出循环 + `join(drainTimeout)`。线程观察到 `closed` 且缓冲空即退出。

## Q3 类名与文件

`ShardBuffer` → `ShardFlusher`（职责从"缓冲区"变为"常驻排空线程"），类注释重写；删除 `flight`/`generation`/`running`/`bringForward` 状态机。`QuotaMergeBuffer` 对外 API（`submitAndWait`×2、`pending`、`awaitIdle`、`close`）不动；`signalIdle` 排空信号保留。

## Q4 flushThreads 配置去留

**全部删除**：`MergeConfig` 字段与构造参数、`QuotaMergeConfig`（marketing-app）"池−2"校验、`MergeConfigTest` / `QuotaMergeConfigTest` 相关用例。测试适配但不改断言语义（行为验收线）。实施时需检查 yml 是否残留 `merge.inventory.flush-threads` 配置项。

## 范围

只改 marketing 侧；`bs-infrastructure` 孪生副本（`CountMerger`）划入 Out of scope，等本次落地稳定后另起 effort 同步。

## Q7 分片数与 flush 并发的耦合（补充决策，2026-11-16 深夜）

用户追问"为什么分片数不会是上百"暴露的设计点：per-shard 常驻线程把 `shardCount` 与 flush 并发绑死（旧设计 64 片共享 2 条 flush 线程，解耦；新模型 线程数=shardCount）。而连接池不变量（flush 并发 <= 池−2 = 8）必须守住。

**决定**：选 (a)——装配层新增校验 `shardCount <= 连接池 - 2`（替代原 flushThreads 校验的位置），默认 shardCount 从 64 降为 8。理由：分片的本职是路由桶（合并粒度由 awardId 分组决定，与分片数无关），8 片足够摊平 offer 争抢；flush 并发直接受连接池封顶，不变量在新模型下启动即校验。同时修正：`DEFAULT_SHARD_COUNT=64`（此前会话误记为 8，8 只是测试参数）。

例外条件（fog 已有）：若未来单分片 offer 成为瓶颈或需要更大路由空间，重议 (b) 线程认领多片模型。
