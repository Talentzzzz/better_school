# RocketMQ 延迟消息与并发消费事实核查（marketing 库存合并用）

> 版本前提：`rocketmq-spring-boot-starter` 2.3.6 的父 POM 把 `rocketmq.version` 定为 **5.3.2**（不是 4.9）；
> `backend/docker-compose.yml` 的 broker 镜像是 **apache/rocketmq:5.5.0**，`backend/docker/rocketmq/broker.conf`
> 未覆盖延迟级别/定时器配置。下文以 5.3.2 client + 5.5.0 broker 为准，并单独标注 4.9 的差异。

## 结论

1. **延迟级别与精度**：`Message` 上没有 `setDelayLevel`（写了编译不过），4.9/5.3.2 都是 `setDelayTimeLevel(int)`；
   `setDelayTimeLevel(1)` 只是把属性 `__DELAY_TIME_LEVEL=1` 带给 broker，级别 1 = **1s**（broker 默认
   `messageDelayLevel="1s 5s 10s 30s 1m 2m 3m 4m 5m 6m 7m 8m 9m 10m 20m 30m 1h 2h"`，>18 会被钳到 18）。
   改写发生在 broker put 时：`HookUtils.transformDelayLevelMessage` 把 topic 换成 `SCHEDULE_TOPIC_XXXX`、
   queueId=级别−1，真实 topic/queueId 存进 properties。精度是**百毫秒级 tick、无硬保证**：每个级别一条定时任务，
   首次 1000ms 后、之后每 100ms 检查，`deliverTimestamp = storeTimestamp + 级别毫秒`，所以级别 1 通常 ≈1.0–1.1s
   到达，积压时更晚（单次 `executeOnTimeUp` 串行搬完该级别到期消息）。4.9.8 的 `Message` 只有 `setDelayTimeLevel`、
   broker 只有固定级别表，**4.9 确实只支持这 18 个固定级别**；但本仓是 5.x：client 5.3.2 有 `setDelayTimeSec/Ms`、
   `setDeliverTimeMs`，broker 5.5.0 默认 `timerWheelEnable=true`、`timerPrecisionMs=1000`，**任意时间投递可用**——
   只是 rocketmq-spring 2.3.6 只在**同步**发送暴露它（`syncSendDelayTimeSeconds/Mills`），异步发送只有 delayLevel
   入参。因此 outbox 异步链路要“约 1s”，级别 1 是唯一现成方案；要任意毫秒得绕开 `RocketMQTemplate` 或改同步发送。

2. **async API（2.3.6）**：`javap` 确认存在 `asyncSend(String, Message<?>, SendCallback, long timeout, int delayLevel)`，
   以及 3 参/4 参重载；**没有** `asyncSend(String, Object, SendCallback, long, int)`。最小改动是先把 payload 包成
   Spring `Message` 再走 5 参重载：`MessageBuilder.withPayload(payload).build()` →
   `rocketMQTemplate.asyncSend(dest, msg, callback, rocketMQTemplate.getProducer().getSendMsgTimeout(), 1)`。
   原 `asyncSend(dest, payload, callback)` 内部把 payload 包成 Message 后 delayLevel=0，无法带延迟。
   `asyncSendOrderly(..., hashKey, ..., delayLevel)` 也存在，但 hashKey 只决定进哪个队列，不等于串行消费（见 4）。

3. **重试**：**不保留原延迟级别**。listener 抛异常 → 集群模式 `sendMessageBack`：
   `ConsumeConcurrentlyContext.delayLevelWhenNextConsume` 默认 0（`@RocketMQMessageListener` 同名属性默认也是 0）→
   broker 端用 `delayLevel = 3 + reconsumeTimes` 重新设置。重试 topic 是 `%RETRY%<consumerGroup>`
   （`MixAll.getRetryTopic`，retryQueueNums 默认 1），以新消息写回并 `reconsumeTimes+1`；消费端
   `resetRetryAndNamespace` 让 listener 仍看到原 topic。阶梯为级别 3..18 = **10s、30s、1m、2m、3m、4m、5m、
   6m、7m、8m、9m、10m、20m、30m、1h、2h** 共 16 次；并发模式 `maxReconsumeTimes` 默认 -1→16，第 17 次失败进
   `%DLQ%<group>`。所以 1s 触发的消息一旦消费失败，第一次重投是 **10s 后**，不是 1s；重试消息同样再走
   `SCHEDULE_TOPIC_XXXX`。

4. **并发边界**：默认 `CONCURRENTLY`（client 按 listener 类型选 service；spring 注解 `consumeMode` 默认
   CONCURRENTLY），默认 `consumeMessageBatchMaxSize=1`、`pullBatchSize=32`；spring 容器把 `consumeThreadMin=20`、
   `consumeThreadMax=64`（裸 client 默认 20/20）。concurrent service 对每个 batch 直接提交线程池，**没有队列锁**；
   只有 ORDERLY 才用 `MessageQueueLock` + 单队列单线程。本仓的
   `VirtualThreadConsumeMessageConcurrentlyService` 把每个 batch 投给无界虚拟线程 executor 并忽略
   `dispatchToConsume`，并发上限只剩 `pullThresholdForQueue`（registrar 设 `ceil(10/4)=3`/队列，4 队列 → 单实例
   约 12 条在飞）与 DB 连接池（10）。**明确结论：默认配置下同一 key 的两条消息可以并行处理**；默认发送也不按 key
   选队列，同 key 甚至不保证同队列，且 registrar 对 ORDERLY 直接抛异常——本仓没有“同 key 串行消费”的开关。

5. **可观测**：delay-level 消息物理上写进 `SCHEDULE_TOPIC_XXXX`（18 个队列，queueId=级别−1；该 topic 在
   `TopicConfigManager` 注册为 18 读写队列），到期才搬回真实 topic。待投递量 = 每级 `maxOffset − delayOffset`：
   `ScheduleMessageService.buildRunningStats` 把 `scheduleMessageOffset_<level>` = `"delayOffset,maxOffset"` 放进
   runtime info，`mqadmin brokerStatus -b <brokerAddr>` 可看到（`brokerStatus` 即 BrokerStatusSubCommand，打印 broker
   runtime stats）；`mqadmin topicStatus -t SCHEDULE_TOPIC_XXXX` 可看每队列 min/max offset；delayOffset 落盘在
   `config/delayOffset.json`。任意时间（timer wheel）消息在 `rmq_sys_wheel_timer`（queueId=0），其 pending 不进
   brokerStatus 的 runtime info（TimerMessageStore 内部 `TimerMetrics`/日志），mqadmin 无直接命令。用 delay level
   时观测最完整；注意“发送成功”只代表进了 schedule topic，不代表已投递/已消费。

## 对本仓的落点

- `OutboxMessageSender` 被 points 与 raffle 共用：延迟级别必须**按消息传入**（如新增
  `sendAsync(OutboxPO, int delayLevel)` 或给 PO 加字段），默认 0；只有库存合并 topic 传 1，否则会把其它 topic 的
  消息统统延迟。outbox scan 重发也带 1s，SENT 会晚约 1s，可接受。
- 异步加延迟必须构造 Spring `Message` 并调 5 参 `asyncSend`（`Message` 重载才有 delayLevel 参数）；不要改走
  `syncSendDelayTime*`，它会破坏现有 `SendCallback` + outbox 标记链路。
- 失败重投从 10s 起、最高 2h、16 次后 DLQ：**不能把“1s 后重投”当恢复手段**；merge 要幂等，并另设兜底
  （定时扫描/对账），否则一次异常会让某个 key 很久不合并。
- 同 key 不串行（并发消费 + 无界 VT + 每队列 3 条在飞，另有重复投递/乱序）：merge 必须在 DB 层并发安全
  （单条原子 UPDATE/UPSERT 或行锁），语义应是“按当前库存重算”而非“累加增量”；`SCHEDULE_TOPIC_XXXX` 的积压
  不计入业务 topic 的消费 lag，排障要看 schedule 队列。

## 来源

- 本地版本与代码：`backend/marketing/pom.xml`（`rocketmq.version=2.3.6`）、`backend/docker-compose.yml`
  （broker `apache/rocketmq:5.5.0`）、`backend/docker/rocketmq/broker.conf`（无 timer/delay 覆盖）、
  `backend/marketing/marketing-infrastructure/src/main/java/com/betterschool/marketing/infrastructure/common/outbox/OutboxMessageSender.java`、
  `backend/marketing/marketing-trigger/src/main/java/org/apache/rocketmq/client/impl/consumer/VirtualThreadConsumeMessageConcurrentlyService.java`、
  `backend/marketing/marketing-app/src/main/java/com/betterschool/marketing/config/VirtualThreadRocketMQListenerContainerRegistrar.java`、
  `backend/marketing/marketing-app/src/main/resources/application.yml`。
- Maven Central（本地 `C:\Users\a1368\.m2\repository\org\apache\rocketmq` **不存在**，故把 jar 下载到
  `%TEMP%\rmq\jars` 后用 `javap` 核对签名）：[rocketmq-spring-boot-parent-2.3.6.pom](https://repo1.maven.org/maven2/org/apache/rocketmq/rocketmq-spring-boot-parent/2.3.6/rocketmq-spring-boot-parent-2.3.6.pom)
  （`rocketmq.version=5.3.2`）、[rocketmq-spring-boot-2.3.6.jar](https://repo1.maven.org/maven2/org/apache/rocketmq/rocketmq-spring-boot/2.3.6/rocketmq-spring-boot-2.3.6.jar)、
  [rocketmq-client-5.3.2.jar](https://repo1.maven.org/maven2/org/apache/rocketmq/rocketmq-client/5.3.2/rocketmq-client-5.3.2.jar)、
  [rocketmq-common-5.3.2.jar](https://repo1.maven.org/maven2/org/apache/rocketmq/rocketmq-common/5.3.2/rocketmq-common-5.3.2.jar)、
  [rocketmq-broker-5.5.0-sources.jar](https://repo1.maven.org/maven2/org/apache/rocketmq/rocketmq-broker/5.5.0/rocketmq-broker-5.5.0-sources.jar)、
  [rocketmq-store-5.5.0-sources.jar](https://repo1.maven.org/maven2/org/apache/rocketmq/rocketmq-store/5.5.0/rocketmq-store-5.5.0-sources.jar)、
  [rocketmq-spring-boot-2.3.6-sources.jar](https://repo1.maven.org/maven2/org/apache/rocketmq/rocketmq-spring-boot/2.3.6/rocketmq-spring-boot-2.3.6-sources.jar)。
- 上游源码（GitHub tag）：[RocketMQTemplate 2.3.6](https://github.com/apache/rocketmq-spring/blob/rocketmq-spring-all-2.3.6/rocketmq-spring-boot/src/main/java/org/apache/rocketmq/spring/core/RocketMQTemplate.java)、
  [RocketMQMessageListener 2.3.6](https://github.com/apache/rocketmq-spring/blob/rocketmq-spring-all-2.3.6/rocketmq-spring-boot/src/main/java/org/apache/rocketmq/spring/annotation/RocketMQMessageListener.java)、
  [DefaultRocketMQListenerContainer 2.3.6](https://github.com/apache/rocketmq-spring/blob/rocketmq-spring-all-2.3.6/rocketmq-spring-boot/src/main/java/org/apache/rocketmq/spring/support/DefaultRocketMQListenerContainer.java)；
  [Message 5.3.2](https://github.com/apache/rocketmq/blob/rocketmq-all-5.3.2/common/src/main/java/org/apache/rocketmq/common/message/Message.java)、
  [ConsumeMessageConcurrentlyService 5.3.2](https://github.com/apache/rocketmq/blob/rocketmq-all-5.3.2/client/src/main/java/org/apache/rocketmq/client/impl/consumer/ConsumeMessageConcurrentlyService.java)、
  [DefaultMQPushConsumerImpl 5.3.2](https://github.com/apache/rocketmq/blob/rocketmq-all-5.3.2/client/src/main/java/org/apache/rocketmq/client/impl/consumer/DefaultMQPushConsumerImpl.java)、
  [DefaultMQPushConsumer 5.3.2](https://github.com/apache/rocketmq/blob/rocketmq-all-5.3.2/client/src/main/java/org/apache/rocketmq/client/consumer/DefaultMQPushConsumer.java)；
  [HookUtils 5.5.0](https://github.com/apache/rocketmq/blob/rocketmq-all-5.5.0/broker/src/main/java/org/apache/rocketmq/broker/util/HookUtils.java)、
  [ScheduleMessageService 5.5.0](https://github.com/apache/rocketmq/blob/rocketmq-all-5.5.0/broker/src/main/java/org/apache/rocketmq/broker/schedule/ScheduleMessageService.java)、
  [AbstractSendMessageProcessor 5.5.0](https://github.com/apache/rocketmq/blob/rocketmq-all-5.5.0/broker/src/main/java/org/apache/rocketmq/broker/processor/AbstractSendMessageProcessor.java)、
  [TopicConfigManager 5.5.0](https://github.com/apache/rocketmq/blob/rocketmq-all-5.5.0/broker/src/main/java/org/apache/rocketmq/broker/topic/TopicConfigManager.java)、
  [AdminBrokerProcessor 5.5.0](https://github.com/apache/rocketmq/blob/rocketmq-all-5.5.0/broker/src/main/java/org/apache/rocketmq/broker/processor/AdminBrokerProcessor.java)、
  [MessageStoreConfig 5.5.0](https://github.com/apache/rocketmq/blob/rocketmq-all-5.5.0/store/src/main/java/org/apache/rocketmq/store/config/MessageStoreConfig.java)、
  [TimerMessageStore 5.5.0](https://github.com/apache/rocketmq/blob/rocketmq-all-5.5.0/store/src/main/java/org/apache/rocketmq/store/timer/TimerMessageStore.java)、
  [BrokerStatusSubCommand 5.5.0](https://github.com/apache/rocketmq/blob/rocketmq-all-5.5.0/tools/src/main/java/org/apache/rocketmq/tools/command/broker/BrokerStatusSubCommand.java)、
  [TopicStatusSubCommand 5.5.0](https://github.com/apache/rocketmq/blob/rocketmq-all-5.5.0/tools/src/main/java/org/apache/rocketmq/tools/command/topic/TopicStatusSubCommand.java)。
- 4.9 对照：[Message 4.9.8](https://github.com/apache/rocketmq/blob/rocketmq-all-4.9.8/common/src/main/java/org/apache/rocketmq/common/message/Message.java)（只有 `setDelayTimeLevel`）、
  [MessageStoreConfig 4.9.8](https://github.com/apache/rocketmq/blob/rocketmq-all-4.9.8/store/src/main/java/org/apache/rocketmq/store/config/MessageStoreConfig.java)（同一份 18 级默认表）。
- 【未核实】`apache/rocketmq:5.5.0` 镜像内是否带 `mqadmin`（本机 docker daemon 不可用，未实际执行）；上文命令语义来自
  tools 源码，未在运行中的 broker 上验证输出。
