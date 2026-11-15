# RocketMQ 参照实现细节（ServiceThread 修复版 + GroupCommitService）

- **id**: 001
- **type**: wayfinder:research
- **status**: resolved
- **blocked-by**: —
- **assignee**: agent（2026-11-16 建图会话）

## Question

为"每分片常驻 flush 线程"重构提供实现级参照。需要从 RocketMQ 原始源码（apache/rocketmq，develop 分支）提取并沉淀为参照文档：

1. **ServiceThread 修复版（#10543 之后）**：`LockSupport.park/unpark` + deadline 循环 + `hasNotified` 重查的完整实现，以及"许可粘性保证唤醒不丢"的机制说明；
2. **GroupCommitService**（store/CommitLog.java 内部类）：`putRequest` / `swapRequests`（双 list 交换）/ `doCommit` / `run` 主循环的逐行结构；
3. **停机模式**：`shutdown()` 的 stopped 标志 + wakeup + join(90s) 组合；
4. **竞态考古**：#5710（2022）与 #10543（2026）的丢失唤醒机制与修复方式，作为"为什么我们选 Condition 而不是 latch"的证据链。

产出写入 `docs/wayfinder/research/001-rocketmq-reference.md`，供 ticket 002（实施）引用。

## Resolution

已解决（2026-11-16）。参照文档：[research/001-rocketmq-reference.md](../research/001-rocketmq-reference.md)。要点：

1. ServiceThread 修复版（develop，#10543 之后）弃用 CountDownLatch2：`hasNotified` CAS 去重 + `LockSupport.unpark`，`waitForRunning` 为"thread 发布 → CAS 快路径 → deadline 循环（每次 park 醒来重查标志与剩余时间）"，靠 unpark 许可粘性 + 循环重查双保险保证唤醒物理不丢；
2. GroupCommitService：双 LinkedList + 短临界区 putRequest + O(1) swap + 锁外 doCommit + run 退出后 swap+doCommit 收尾 flush；
3. 停机：started CAS 幂等 + stopped=true → wakeup → join(90s)；
4. 竞态考古：#5710（2022，建议换 Condition，stale 关闭未修）→ #10543（2026，丢失唤醒定性为 bug，LockSupport 修复）——证据链支持我们选 Condition；
5. 提炼 5 条借鉴（双缓冲 O(1) swap、粘性许可、signal→join 顺序、统一 deadline、线程发布）与 5 条坑（勿用可重置计数器当通知原语、勿让标志与谓词脱锁、勿把丢唤醒当噪声、join 必须带超时、flush 期间勿持锁收请求）。
