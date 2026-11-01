# 02 调研：RocketMQ 消费线程池能否虚拟线程化

Type: research
Status: resolved
Blocked by:

## Question

为「23 个 RocketMQ 消费者的线程模型裁决」（[票 06](06-grill-rocketmq-consumer-model.md)）提供事实基础。

现状：`rocketmq-spring-boot-starter` 2.3.6 + broker 5.5.0；**23 个 `@RocketMQMessageListener` 全部没配 `consumeMode` / `consumeThreadNumber` / `messageModel`** → 全部默认 `CONCURRENTLY` + 20 消费线程 + CLUSTERING。单体 21 个（`bs-trigger/.../listener/`），`marketing-asset` 1 个，`marketing-activity` 1 个 → 合计约 **460 条平台消费线程**。

任务里的阻塞形态差异很大：多数是「落库 / 调域服务」的短任务；`AgentReviewDispatchConsumer`（`maxReconsumeTimes = 3`）在消费线程上**同步阻塞最长 120s**打 Python sidecar（`AgentReviewPort` 用 `RestClient` + `SimpleClientHttpRequestFactory`，connect 5s / read 120s）；`ChatPushConsumer` / `ChatReadPushConsumer` / `ChatRecallPushConsumer` / `NotificationBroadcastConsumer` 走 WebSocket 发送（每次至多 5s）。

请调研（只收事实，选型留给票 06）：

1. **`rocketmq-spring` 的注入点**：`RocketMQMessageListenerContainer` / `DefaultRocketMQListenerContainer` 的消费线程池是哪个类、怎么创建、有没有官方或社区认可的替换方式（自定义容器 Bean？`RocketMQConfig`？`consumeThreadNumber` 之外的口子？）。逐个给出**可替换点的类名与方法**
2. **RocketMQ client 侧的支撑**：`ConsumeMessageConcurrentlyService` 的 `ExecutorService` 是否可注入/替换（5.x client 与 broker 5.5.0 的版本对应关系写清）；`processQueue` / 流控（`pullThresholdForQueue` 等）在「线程不再稀缺」后会不会成为新瓶颈
3. **与 ACK / 重试 / 顺序消费的交互**：并发消费的 ack 时机、`maxReconsumeTimes`、重试队列（`%RETRY%`）在虚拟线程模型下是否语义不变；`ORDERLY` 消费换代的价值
4. **`synchronized` / pinning**：消费路径上有没有会把虚拟线程钉住的地方（JDK 21），特别是 ack、trace、`MessageExt` 相关
5. **孤例热点的替代路径**：把 `AgentReviewDispatchConsumer` 的阻塞从消费线程上挪走的方案面（消费者内开虚拟线程 fan-out？改成拉取型？按耗时分层 topic？），只列做法与代价
6. **线程账**：23 个消费者实际默认线程数、堆栈占用，以及「虚拟线程化后省下多少」的量级估算

## Answer

调研报告：[research/02-rocketmq-consumer-executor.md](../research/02-rocketmq-consumer-executor.md)。核心结论：**`rocketmq-spring-boot-starter` 2.3.6 到消费线程池之间没有任何配置口子**——executor 由 client 5.3.2 在 `ConsumeMessageConcurrentlyService` 构造器里 `new ThreadPoolExecutor(min, max, 60s, 无界 LinkedBlockingQueue, ThreadFactoryImpl)`，容器（`DefaultRocketMQListenerContainer.initRocketMQPushConsumer()`，private，且无条件 new consumer）只把 `consumeThreadMin/Max` 两个 int 递进去；**无界队列让 `consumeThreadMax=64` 永不生效，实际并发 = `consumeThreadMin` = 20**（460 条线程就是这么来的，`setConsumeThreadNumber` 还不存在）。可替换点只有三个：覆写 `RocketMQMessageListenerContainerRegistrar.registerContainer(...)`（public，且 `ListenerContainerConfiguration` 带 `@ConditionalOnMissingBean` 可整体接管）、自实现 `RocketMQListenerContainer`、或 `DefaultMQPushConsumerImpl.setConsumeMessageService(ConsumeMessageService)`（public，但要自研整个服务 + 默认实例的 2 个 scheduled executor 会泄漏）。**上游官方能力「已合入 develop 但不在任何发布版本」**：PR #11090（merged 2026-09-11）新增 `DefaultMQPushConsumer.setConsumeExecutor(ExecutorService)` + `AbstractConsumeMessageService`，描述里明示支持 JDK 21 虚拟线程执行器；而专为虚拟线程开的 issue #8062 / PR #8063（`rocketmq.client.consumer.virtualThread` 开关）**未合并**、#8062/#8073 被 stale bot 关闭；5.3.2/5.5.0/5.5.1 三个 tag 逐个 grep `consumeExecutor` 均 0 命中。

其余关键事实：①**pull 流控是虚拟线程化后的新天花板**——`pullThresholdForQueue=1000` / `pullThresholdSizeForQueue=100MB` / `consumeConcurrentlyMaxSpan=2000`（超限 `executePullRequestLater(50ms)`，`DefaultMQPushConsumerImpl.pullMessage:269-302`），线程不再稀缺后瓶颈立刻转到 Hikari 池与下游配额，必须与连接池口径一起定；②**ack/重试/顺序语义逐行不变**——`ProcessQueue.removeMessage` 返回 `msgTreeMap.firstKey()`（最小未确认 offset），`reconsumeTimes > maxReconsumeTimes` 才进 `%DLQ%`，默认 `-1` 在并发下 = 16 次，重试延迟 level = `3 + reconsumeTimes`（broker `AbstractSendMessageProcessor`），`consumeTimeout=15min` 的 `cleanExpiredMsg` 是位点兜底阀门，120s 触发不到；③**pinning 改造量 = 0**——并发消费路径 0 处 `synchronized`、`ProcessQueue` 用 `ReentrantReadWriteLock`、JDK 21 `NioSocketImpl.park` 对虚拟线程强制走 `Poller.poll`（**120s 的 `RestClient` 阻塞读不 pin**）、Spring `ConcurrentWebSocketSessionDecorator` 用 `ReentrantLock`、Tomcat WS 把实际写出刻意移出 monitor，全仓主源码仅 1 处 `synchronized`（且不在消费路径）；唯一会 pin 的是 ORDERLY 的 `MessageQueueLock`，而 ORDERLY 与「抬并发」方向相反；④**线程账**：换 executor 省 460 条，但 client 内部每消费者仍留 2 条 `ScheduledExecutorService` 线程省不掉；⑤**孤例热点（120s sidecar 调用）** 五条替代路径（换 executor／消费者内 VT fan-out 带背压／拆 topic 分层／改 `DefaultLitePullConsumer`／维持现状）已逐条列出做法与代价，其中「一条卡住的消息把该队列位点钉住最长 120s」是模型固有性质，虚拟线程化不改善。全程一手源码（rocketmq-spring `rocketmq-spring-all-2.3.6`、rocketmq `rocketmq-all-5.3.2`、`openjdk/jdk21u`、Spring 6.1.14、Tomcat 10.1.30）逐行核对并附 URL。
