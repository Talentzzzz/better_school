# 票 02 · RocketMQ 延迟消息与并发消费的确切语义

Type: research
Status: resolved
Blocked by: —

## Question

本仓的 `rocketmq-spring` 是 **2.3.6**（client 4.9 线，见 `backend/marketing/pom.xml`）。在把「1 秒后按 key 合并」落在延迟消息上之前，要一手事实：

1. **延迟等级**：只能走 18 个固定等级吗？`Message.setDelayLevel(1)` 的确切含义；broker 时间轮的实际精度（「1s」到底是 1s 还是 1–2s）。
2. **API 形状**：`RocketMQTemplate` 2.3.6 如何在**异步**发送上带延迟等级？现有 `asyncSend(destination, payload, callback)`（`OutboxMessageSender`）能不能直接加；不能的话最小改法是什么。
3. **重投语义**：消费失败后的重投**是否保留原来的延迟等级**？重投走 `%RETRY%` 还是原 topic？这决定「合并失败」时下一次尝试的间隔。
4. **并发边界**：同一个 topic 里两条消息能否被同时处理？同一个 queue 呢？`consumeMode` 默认值、`consumeMessageBatchMaxSize`、以及本工程自建的虚拟线程消费服务（`VirtualThreadConsumeMessageConcurrentlyService`）各怎么影响这件事。
5. **可观测**：延迟消息在 broker 上存在哪里（`SCHEDULE_TOPIC_XXXX`）？能不能看到「还有多少条待投递」——兜底扫描的告警需要一个读数。

## Answer

全文见 [research/rocketmq-delay-and-concurrency.md](../research/rocketmq-delay-and-concurrency.md)。五条结论：

1. **版本纠偏**：`rocketmq-spring-boot-starter` 2.3.6 的父 POM 定的是 **client 5.3.2**（不是 4.9），broker 镜像 `apache/rocketmq:5.5.0`，`broker.conf` 未覆盖延迟配置。
2. **延迟级别**：API 是 `Message.setDelayTimeLevel(int)`（没有 `setDelayLevel`）；级别 1 = 1s，精度 100ms tick，实际 **≈1.0–1.1s**，积压时更晚。5.x 的任意时间投递可用，但 `rocketmq-spring` 2.3.6 只在**同步**发送暴露（`syncSendDelayTime*`），异步链路要 ~1s 只能用级别 1。
3. **异步 API**：存在 `asyncSend(String, Message<?>, SendCallback, long timeout, int delayLevel)`；没有 Object-payload 的延迟重载。最小改动是 `MessageBuilder.withPayload(payload).build()` + 5 参重载。
4. **重投不保留延迟**：listener 抛异常 → broker 用 `3 + reconsumeTimes` 重设延迟等级、走 `%RETRY%<group>`，阶梯 10s / 30s / 1m … 共 16 次，之后 `%DLQ%`。**1s 触发失败后第一次重投是 10s 后**，不能把重投当快速恢复手段。
5. **同 key 不串行**：默认 `CONCURRENTLY`、`consumeMessageBatchMaxSize=1`、无队列锁；本仓还把消费投给无界虚拟线程池（每队列 3 条在飞），默认发送也不按 key 选队列。**同一 key 的两条消息可以并行处理**，且存在重复投递。

## 对本设计的修正

- **票 01 §5 的合并事务原来有洞**：两个消费者可能读到同一批 `state=0` 明细，各结算一次 → `waiting_qty` 记两次。已改为「翻转带 `AND state = 0` + 检查 affected，翻转先于结算」，见票 01 §5。
- 兜底扫描从「保险」升级为「必需」：重投 10s 起步、16 次后进 DLQ，消息层的自愈不够快也不够全。
- outbox 的延迟级别必须**按消息传入**（新增 `sendAsync(OutboxPO, int delayLevel)` 或给 PO 加字段），默认 0，只有库存合并 topic 传 1——否则 points / raffle 的事件全被延迟。
