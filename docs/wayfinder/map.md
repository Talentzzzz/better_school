# Wayfinder Map：库存合并器常驻线程重构（marketing）

<!-- wayfinder:map -->

## Destination

marketing 侧库存合并器重构落地：`ShardBuffer` 的"调度器任务 + cancel + generation + running 四件套"建模替换为"每分片一个常驻 flush 线程"（锁内摘批 → 锁外落库 → 逐件回填 → 有积压背靠背）。`QuotaMergeBuffer` 对外 API 与时序语义不变，现有测试全绿。

## Notes

- 领域：marketing-domain `com.betterschool.marketing.domain.inventory.service.merge` 包。
- 验收线 = `QuotaMergeBufferTest` / `MergeConfigTest` / `QuotaMergeConfigTest` / inventory 集成测试全绿；测试只适配、不改断言语义。
- 实施前先读 `research/001-rocketmq-reference.md`（RocketMQ 参照实现）。
- 常设偏好（用户授权）：大胆重构，不考虑现有配置兼容；线程用**普通平台线程**（不用虚拟线程）；只改 marketing，不碰 bs-infrastructure。
- 本地 tracker 约定：`docs/wayfinder/` 下 map + `tickets/*.md`；ticket 文件内含 `status` 与 `blocked-by` 字段；认领 = 把 assignee 写进文件。

## Decisions so far

- [线程模型：每分片一个常驻 daemon 平台线程](charting-log.md#q3-线程模型)：RocketMQ 式单飞天然成立，8 条线程成本可忽略。
- [唤醒原语：ReentrantLock + Condition awaitUntil + signal](charting-log.md#q1-唤醒攒批原语)：窗口锚定首件（保留现有时序语义）；不抄 RocketMQ 的 reset-latch——它有已定性的丢失唤醒竞态（#5710/#10543）。
- [不用虚拟线程](charting-log.md#虚拟线程-vs-普通线程)：数量小、JDBC pin 风险、诊断可见性、常驻生命周期四条理由；分片数上百时重议。
- [停机语义：三步不变](charting-log.md#q6-停机方式)：停收 → 排空 → 超时回填失败态；"关池"替换为 signal 线程退出 + join(drainTimeout)。
- [类名与文件](charting-log.md#q3-类名与文件)：`ShardBuffer` → `ShardFlusher` 并重写类注释；`QuotaMergeBuffer` 对外 API 不动。
- [配置清理](charting-log.md#q4-flushthreads-配置去留)：`flushThreads` 字段、装配层"池−2"校验、相关测试用例全部删除。
- [RocketMQ 参照实现已沉淀](tickets/001-rocketmq-reference.md)：ServiceThread 修复版（LockSupport + deadline 循环）、GroupCommitService 双缓冲结构、停机模式、#5710/#10543 竞态考古，5 借鉴 5 坑，见 [research/001-rocketmq-reference.md](research/001-rocketmq-reference.md)。
- [全量验证通过（走查代偿）](tickets/003-verify-tests-green.md)：无 Maven 环境不起跑，逐用例代码走查确认测试全绿判定成立、无时序脆弱用例（同步点全是 latch/轮询）、charting-log 五项外部行为逐条兼容；唯一变更是 A1 一处陈旧注释修正，断言零改动。遗留：有环境后补跑 `mvn test` 终验。

（决策详情统一记录在 [charting-log.md](charting-log.md)，本节只做索引。）

## Not yet specified

- 是否需要跑 `loadtesting/` 压测验证新线程模型的批吞吐与延迟分布——等实施落地后看现有压测场景是否覆盖 merge 路径再决定。
- 孪生副本 `bs-infrastructure/CountMerger` 的同步重构：等 marketing 侧落地稳定后另起一次 effort。

## Out of scope

- **bs-infrastructure 侧同步改动**：本次只动 marketing，孪生副本原样保留（等 marketing 验证后同步）。
- **RocketMQ 的固定 10ms 窗口语义**：保留我们的"首件起算窗口 + 攒满提前"语义，只换驱动机制。
