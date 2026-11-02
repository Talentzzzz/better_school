# 02 调研：RocketMQ 消费线程池能否虚拟线程化

_对应票：[issues/02-research-rocketmq-consumer-executor.md](../issues/02-research-rocketmq-consumer-executor.md)（为 [票 06](../issues/06-grill-rocketmq-consumer-model.md) 裁决提供事实基础）_
_调研日期：2026-09-19。所有结论均从一手源码逐行核对，版本与本仓库锁定值一致。_

## 0. 一句话结论速览

1. **`rocketmq-spring-boot-starter` 2.3.6 到消费线程池之间没有任何配置口子**。线程池由 RocketMQ client 在 `ConsumeMessageConcurrentlyService` 构造器里 `new ThreadPoolExecutor(...)`，容器只是把 `consumeThreadMin/Max` 两个 int 传进去；换执行器必须**换掉整个 `ConsumeMessageService` 实现**或**换掉整个容器注册器**。
2. **每消费者的并发数是 20 而不是 64**：线程池的任务队列是**无界** `LinkedBlockingQueue`，`maximumPoolSize=64`（注解 `consumeThreadMax`）永远不会被触达。所以 23 × 20 = 460 条消费线程是「默认值」，也是「实际值」。
3. **上游官方支持虚拟线程执行器的能力已经合入 `develop`（2026-09-11，PR #11090），但不在任何发布版本里**；历史上专门为虚拟线程开的 issue #8062 / PR #8063 已被关闭未合并。本仓库的 client 5.3.2 **没有**这个口子。
4. **消费路径上本仓库「必须修的 pinning 点 = 0**：client 并发消费路径 0 处 `synchronized`，`ProcessQueue` 用的是 `ReentrantReadWriteLock`，JDK 21 的 `NioSocketImpl` 对虚拟线程强制走 `Poller.poll`（HTTP 阻塞读不 pin），Spring/Tomcat 的 WS 写出也刻意避开了 monitor。**120s 的 `RestClient` 同步调用在虚拟线程上不会钉住载体线程**——这一点直接改变了票 06 的代价计算。
5. **虚拟线程化抬高的并发，会立刻撞上三重 pull 流控（1000 条 / 100 MiB / span 2000）与下游 Hikari 池**。线程不再是稀缺资源后，稀缺资源变成「Hikari 连接数 + 单队列消费位点」。
6. **ack / 重试 / 顺序语义在虚拟线程下逐行不变**（同一段 client 代码），变的只是同时在飞的消息数；但**一条卡 120s 的消息会把该队列的提交位点钉住最长 120s**，这条性质与线程模型无关，虚拟线程化也不会改善它。

---

## 1. 现场事实复核（仓库侧，均为本次实测）

| 项 | 实测值 | 出处 |
|---|---|---|
| `@RocketMQMessageListener` 总数 | **23** | `grep -rl` 全 backend 源码 |
| 单体 `bs-trigger/.../listener/` | **21** 个 | `backend/better-school/bs-trigger/src/main/java/com/betterschool/trigger/listener/` |
| `marketing-asset-trigger` / `marketing-activity-trigger` | 各 **1** 个（`LotteryDrawnConsumer` / `ReferralGroupedConsumer`） | 同上 |
| 显式设过 `consumeMode` / `consumeThreadNumber` / `messageModel` | **0 处** | `grep -rn` 全 backend 源码命中 0 |
| `rocketmq` 配置 | 仅 `name-server` + `producer.group`，**无 consumer 段** | `backend/better-school/bs-app/src/main/resources/application.yml:40` |
| 版本 | starter **2.3.6**（四工程同版本） / broker 镜像 `apache/rocketmq:5.5.0` | `backend/better-school/pom.xml:37`、`backend/docker-compose.yml:104,123` |
| JDK | 21（无 JDK 24） | `map.md` Notes |

现场细节确认（本次实测）：
- ~~4 个 WS 消费者：`ChatPushConsumer` / `ChatReadPushConsumer` / `ChatRecallPushConsumer` / `NotificationBroadcastConsumer`~~，落地侧 `ChatWsPusher` 用 `ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS=5000, BUFFER_SIZE_LIMIT=512*1024)`。
  > ⚠️ **2026-09-20 回填修正（[票 06](../issues/06-grill-rocketmq-consumer-model.md)）**：上面这句有错。**WS 消费者是 3 个不是 4 个**：`NotificationBroadcastConsumer` 只调 `notificationRepository.insertBroadcast`，在一个事务里对全量用户逐行 insert（`NotificationRepository.java:82-97`），**不走 WS**；WS 扇出点唯一（`ChatWsPushService.broadcast`，`ChatWsPushService.java:59-72`），只有三个 chat 消费者引用它。

---

## 2. rocketmq-spring 层：注入点逐个点名

### 2.1 依赖链与版本配对（先钉死版本）

`rocketmq-spring-boot-parent-2.3.6/pom.xml` → `<rocketmq.version>5.3.2</rocketmq.version>`，即：

```
rocketmq-spring-boot-starter:2.3.6
  → rocketmq-spring-boot:2.3.6
    → rocketmq-client:5.3.2   ← 消费线程池的创建者
broker（本项目）: apache/rocketmq:5.5.0 镜像
```

**client 5.3.2 ↔ broker 5.5.0 是安全的**：两者走 remoting 协议，5.x broker 向下兼容 4.x/5.x client；混版是 RocketMQ 部署常态。但**能力面按 client 版本算**——本仓库能用的注入口子只能是 5.3.2 的，broker 是 5.5.0 也拿不到新的 client API。
来源：<https://github.com/apache/rocketmq-spring/blob/rocketmq-spring-all-2.3.6/rocketmq-spring-boot-parent/pom.xml>

### 2.2 注解默认值（为什么现在是 20 条线程）

`RocketMQMessageListener.java`（2.3.6）逐条：

| 属性 | 默认值 | 影响 |
|---|---|---|
| `consumeMode` | `CONCURRENTLY` | 决定 client 里 new 哪个 `ConsumeMessageService` |
| `messageModel` | `CLUSTERING` | 分摊消费 |
| `consumeThreadNumber` | **20** | → client `consumeThreadMin` → **实际并发** |
| `consumeThreadMax` | **64** | → client `consumeThreadMax` → **死参数**（见 3.2） |
| `maxReconsumeTimes` | `-1` | 并发模式语义 = 16 次；顺序 = `Integer.MAX_VALUE` |
| `consumeTimeout` | `15L`（分钟） | 见 5.4 的兜底阀门 |
| `pullBatchSize` | `32` | 一次 pull 拿 32 条 |
| `consumeMessageBatchMaxSize` | `1` | 每条消息一个 `ConsumeRequest` |
| `delayLevelWhenNextConsume` | `0` | 见 5.2 的重试延迟公式 |
| `enableMsgTrace` | `false` | 本项目未开 → 消费路径无 trace hook |

来源：<https://github.com/apache/rocketmq-spring/blob/rocketmq-spring-all-2.3.6/rocketmq-spring-boot/src/main/java/org/apache/rocketmq/spring/annotation/RocketMQMessageListener.java>

### 2.3 `DefaultRocketMQListenerContainer`（类名/方法名级结论）

类：`org.apache.rocketmq.spring.support.DefaultRocketMQListenerContainer`
来源：<https://github.com/apache/rocketmq-spring/blob/rocketmq-spring-all-2.3.6/rocketmq-spring-boot/src/main/java/org/apache/rocketmq/spring/support/DefaultRocketMQListenerContainer.java>

- 字段：`private int consumeThreadMax = 64;`（:107）、`private int consumeThreadNumber = 20;`（:109）。
- 赋值口子：**只有** `setRocketMQMessageListener(RocketMQMessageListener anno)`（:241），内部 `this.consumeThreadMax = anno.consumeThreadMax(); this.consumeThreadNumber = anno.consumeThreadNumber();`（:245-246）。**没有 `setConsumeThreadNumber` / `setConsumeThreadMax`**（只有 getter，:196-201）。
- `initRocketMQPushConsumer()`（:665）是 **private**，且**无条件 `consumer = new DefaultMQPushConsumer(...)`**（:675-685），随后 `consumer.setConsumeThreadMax(consumeThreadMax); consumer.setConsumeThreadMin(consumeThreadNumber);`（:700-701）。
  → 因此 `setConsumer(DefaultMQPushConsumer)`（:314）这个 public setter **会被 `afterPropertiesSet()` → `initRocketMQPushConsumer()` 覆盖**，不能用来注自己造好的 consumer。
- 整个类 **0 处 `synchronized`**；`handleMessage`（:522）只是 `rocketMQListener.onMessage(...)`。
- 消费失败处理在内部类 `DefaultMessageListenerConcurrently.consumeMessage`（:467-486）：catch 一切 `Exception` → `context.setDelayLevelWhenNextConsume(delayLevelWhenNextConsume)` → 返回 `RECONSUME_LATER`。**异常不会逃逸到 client**。

**结论：容器层没有任何 executor 注入点。** `consumeThreadNumber` 唯一的合法口子是注解属性（只能调数字，调不出虚拟线程）。

### 2.4 可替换点（三层，按代价从低到高）

**(A) 换容器注册器：`RocketMQMessageListenerContainerRegistrar.registerContainer(String beanName, Object bean, RocketMQMessageListener annotation)`——public，可被继承覆写**

- 该类：`org.apache.rocketmq.spring.support.RocketMQMessageListenerContainerRegistrar`（**是 class 不是 interface**），持有 `private final List<DefaultRocketMQListenerContainer> containers`。
- 默认装配链：
  - `RocketMQMessageListenerBeanPostProcessor`（`RocketMQMessageListenerBeanPostProcessor.java:44,80,150`）持有 `ObjectProvider<RocketMQMessageListenerContainerRegistrar>`，对每个带注解的 bean 调 `registrar.registerContainer(...)`，`SmartLifecycle.start()` 时调 `registrar.startContainer()`，`stop()` 时调 stop。
  - 默认 registrar bean 由 `ListenerContainerConfiguration` 提供，该类带 **`@ConditionalOnMissingBean(RocketMQMessageListenerContainerRegistrar.class)`**（`ListenerContainerConfiguration.java:28`）。
  - `RocketMQMessageListenerBeanPostProcessor` 自己由 `RocketMQListenerConfiguration`（`ImportBeanDefinitionRegistrar`）注册，且 `registry.containsBeanDefinition(...)` 时不覆盖 → 用户可预注册自己的 BPP。
- 来源：<https://github.com/apache/rocketmq-spring/blob/rocketmq-spring-all-2.3.6/rocketmq-spring-boot/src/main/java/org/apache/rocketmq/spring/support/RocketMQMessageListenerContainerRegistrar.java>、<https://github.com/apache/rocketmq-spring/blob/rocketmq-spring-all-2.3.6/rocketmq-spring-boot/src/main/java/org/apache/rocketmq/spring/autoconfigure/ListenerContainerConfiguration.java>
- **代价**：一个自定义 `@Bean` 就能接管 23 个 listener 的容器工厂；但容器替换后你仍然只能拿到「容器 → consumer」这一层，executor 还是 client 内部造的（除非同时用 (C)）。

**(B) 自己实现 `RocketMQListenerContainer` 接口**
完全自持 `DefaultMQPushConsumer`（自己 `setConsumeThreadMin/Max`、自己 `start()`），配合 (A) 注册。最彻底，但等于放弃 rocketmq-spring 的注解式装配，23 个 listener 的装配/生命周期/序列化全部自己接管。

**(C) 换 client 的 `ConsumeMessageService`（唯一能碰到 executor 的口子）**
`DefaultMQPushConsumerImpl.setConsumeMessageService(ConsumeMessageService)` 是 **public**（`DefaultMQPushConsumerImpl.java:1582`）。见第 3 节的可行性分析。

---

## 3. RocketMQ client 侧：executor 能不能换

### 3.1 创建点（无 SPI、无工厂）

`DefaultMQPushConsumerImpl.start()`（`DefaultMQPushConsumerImpl.java:971-978`）：

```java
this.consumeMessageService = new ConsumeMessageOrderlyService(this, (MessageListenerOrderly) ...);   // ORDERLY
this.consumeMessageService = new ConsumeMessageConcurrentlyService(this, (MessageListenerConcurrently) ...); // CONCURRENTLY
```

`ConsumeMessageConcurrentlyService` 构造器签名**只有** `(DefaultMQPushConsumerImpl, MessageListenerConcurrently)`（`ConsumeMessageConcurrentlyService.java:63-64`），executor 在构造器内自建（:70-79）：

```java
this.consumeRequestQueue = new LinkedBlockingQueue<>();          // ← 无界
this.consumeExecutor = new ThreadPoolExecutor(
    defaultMQPushConsumer.getConsumeThreadMin(),                  // 20
    defaultMQPushConsumer.getConsumeThreadMax(),                  // 64
    1000 * 60, TimeUnit.MILLISECONDS,
    this.consumeRequestQueue,
    new ThreadFactoryImpl("ConsumeMessageThread_" + consumerGroupTag));
```

来源：<https://github.com/apache/rocketmq/blob/rocketmq-all-5.3.2/client/src/main/java/org/apache/rocketmq/client/impl/consumer/ConsumeMessageConcurrentlyService.java>

### 3.2 关键事实：`consumeThreadMax=64` 是死参数，实际并发 = 20

`ThreadPoolExecutor` 的扩容规则是「核心线程满 → 入队 → 队列满才扩到 max」。这里队列是**无界 `LinkedBlockingQueue`**，永不入满 → `maximumPoolSize` 永不生效。
**所以本仓库 23 个消费者的实际并发 = `consumeThreadMin` = 20**，与票面「约 460 条平台消费线程」一致。

（唯一能改变 corePoolSize 的公开路径是 `DefaultMQPushConsumer.updateCorePoolSize(int)` → `ConsumeMessageConcurrentlyService.updateCorePoolSize`，而它**只降不升**：`corePoolSize < getConsumeThreadMax()` 才生效，且 5.3.2 的 `incCorePoolSize()/decCorePoolSize()` 是**空实现**（:114-125）→ 动态调参机制在 5.3.2 实际是死的。别指望它。）

### 3.3 换 `ConsumeMessageService` 的可行性

`DefaultMQPushConsumerImpl.setConsumeMessageService(ConsumeMessageService)` 是 public，且 `ConsumeMessageService` 是接口（`submitConsumeRequest` / `submitPopConsumeRequest` / `shutdown` / `updateCorePoolSize` / `incCorePoolSize` / `decCorePoolSize` / `getCorePoolSize` / `consumeMessageDirectly` / `start`）。理论上可以自研一个「executor 是 `Executors.newVirtualThreadPerTaskExecutor()`」的实现塞进去。但：

1. 替换必须发生在 `start()` **之后**（`start()` 内部才 new 并 `this.consumeMessageService.start()`），而 `start()` 之前字段是 null。
2. 被换掉的默认实例已经起了两个 `ScheduledExecutorService`（`ConsumeMessageScheduledThread_*`、`CleanExpireMsgScheduledThread_*`），替换后**无人 shutdown 它们**（新服务的 `shutdown()` 只关自己的）→ 泄漏 2 条线程 + 定时任务。
3. 你还得自己 reimplement `processConsumeResult`（send-back / DLQ / stats / hook），70 行语义正确性全靠抄。
4. 反射改 `final` 字段的做法在 JDK 21 下不成立（`Field.set` 对 final 实例字段抛 `IllegalAccessException`），要动 `modifiers` 就得 `--add-opens java.base/java.lang.reflect`，比自研服务更脏。

**结论：client 5.3.2 上「换 executor」= 自研 `ConsumeMessageService`；无低成本官方口子。**

### 3.4 上游官方能力的现状（重点：只在 develop）

- **PR #11090 / issue #11089**（`[ISSUE #11089] Share consumption executors for Proxy internal clients`）**merged 2026-09-11 到 `develop`**，新增：
  - `DefaultMQPushConsumer.setConsumeExecutor(ExecutorService)`（develop 版 `DefaultMQPushConsumer.java:568,589-592`，javadoc：「external executor, or null to use the default dedicated pool」）
  - 抽象基类 `AbstractConsumeMessageService`，构造器里 `ExecutorService externalExecutor = defaultMQPushConsumer.getConsumeExecutor();` → 非 null 就用外部 executor，并明确「caller owns an injected executor's lifecycle」。
  - PR 描述原文点名：**「including virtual-thread executors supplied on JDK 21+」**。
  - 来源：<https://github.com/apache/rocketmq/pull/11090>
- **但该能力不在任何发布版本里**：`DefaultMQPushConsumer` 在 tag `rocketmq-all-5.3.2` / `5.5.0` / `5.5.1` 中**都不含** `consumeExecutor` 字段（本次逐个 grep 确认，0 命中）；`ConsumeMessageConcurrentlyService` 在 5.5.0 与 5.3.2 是**同一个 469 行文件**。
- **历史上为虚拟线程开的口子全被关掉**：
  - issue **#8062**《Virtual thread support for consumer thread》（动机原文：「There are many `ConsumeMessageThread_` threads at work, so I would like to leverage the virtual thread in Java 21」）→ 对应 **PR #8063 closed 未 merged**（方案是加配置项 `rocketmq.client.consumer.virtualThread`）→ issue 2025-04-28 被 stale bot 关闭。
  - issue **#8073**《Virtual thread support in DefaultMQPushConsumerImpl》（指出 `start()`/`shutdown()` 上的 `synchronized` 会 pin）→ 2025-05-01 被 stale bot 关闭。
  - 来源：<https://github.com/apache/rocketmq/issues/8062>、<https://github.com/apache/rocketmq/pull/8063>、<https://github.com/apache/rocketmq/issues/8073>
- **推论**：官方路线在 2026-09 才转向「外部注入 executor」而不是「库内自己开虚拟线程」，且明确把生命周期交给调用方。想用这一口子 → 依赖 `5.6.0-SNAPSHOT`（未发布）或打补丁维护分支，票 06 要把「升 client 到 develop 快照」当成本项。

---

## 4. 线程不再稀缺之后：pull 流控成为新天花板

并发消费的背压有三道，全在 `DefaultMQPushConsumerImpl.pullMessage`（`DefaultMQPushConsumerImpl.java:269-302`），每道超限就 `executePullRequestLater(pullRequest, PULL_TIME_DELAY_MILLS_WHEN_CACHE_FLOW_CONTROL=50ms)`：

| 阈值 | 默认值 | 判据 |
|---|---|---|
| `pullThresholdForQueue` | **1000** | `processQueue.getMsgCount() > 1000`（条数） |
| `pullThresholdSizeForQueue` | **100** (MiB) | `processQueue.getMsgSize()/(1024*1024) > 100` |
| `consumeConcurrentlyMaxSpan` | **2000** | 非顺序消费时 `processQueue.getMaxSpan() > 2000`（消息 offset 跨度） |

（`PullMessageService.java:105` 定义 50ms；`DefaultMQPushConsumer.java:176,182,197` 定义默认值。suspend 场景用的是 `PULL_TIME_DELAY_MILLS_WHEN_SUSPEND=1000ms`。）

**对票 06 的意义**：把 20 条平台线程换成虚拟线程后，单队列在飞消息数会从「≈20」抬到「≈min(1000, 2000/…, 下游容量)」，**天花板立刻变成 Hikari 连接池 / 下游 HTTP 服务并发 / 下游 Dubbo 提供方**。本 effort 的既立判据（同目录 spec：Hikari 池 ≥ 并行度）在这里会反向咬人：线程先涨、池不变 → 表现为 `SQLTransientConnectionException` 之类超时 → 消息 `RECONSUME_LATER` → 重试拥塞。**虚拟线程化必须与连接池/下游配额一起定，不能只改线程。**

另外两条与「批量」有关的实参：`pullBatchSize=32`（一次拉 32 条）、`consumeMessageBatchMaxSize=1`（每条一个 `ConsumeRequest` 提交给 executor）→ 一个 1000 条深的队列理论上会给 executor 排 1000 个任务，靠 20 个 worker 慢慢啃。

---

## 5. 与 ACK / 重试 / 顺序消费的交互（语义不变，但要知道边界）

### 5.1 ACK 时机：位点推进 = 最小未确认 offset

`ConsumeMessageConcurrentlyService.ConsumeRequest.run()` → listener 返回 → `processConsumeResult` → `processQueue.removeMessage(msgs)` → `offsetStore.updateOffset(mq, offset, true)`（`ConsumeMessageConcurrentlyService.java:305`）。

`ProcessQueue.removeMessage`（`ProcessQueue.java:187-223`）在写锁内把已处理消息从 `msgTreeMap` 移除，**返回 `msgTreeMap.firstKey()`**——即「最小未确认 offset」，而不是 `max+1`。

**推论（与本 effort 直接相关）**：并发模式下**一条卡住的消息会把该队列的消费位点钉住**，直到它返回（或 `consumeTimeout` 兜底把它送回去）。一个同步阻塞型消费者最长 120s → 该队列位点最长停滞 120s，重启后会重投这 120s 的消息窗。**虚拟线程化不改变这条性质**（同一条代码路径），要改善只能把长阻塞从「消费线程 + 位点前排」挪走（拆 topic / 异步化不解决位点，因为不 ack 就还是钉住）。

### 5.2 重试与 DLQ：逐行核对

1. listener 抛异常 → rocketmq-spring 的并发监听器 catch 住 → `RECONSUME_LATER` + `context.setDelayLevelWhenNextConsume(0)`（注解默认）。
2. client：`processConsumeResult` → 对 ackIndex 之后的消息 `sendMessageBack(msg, context)` → `DefaultMQPushConsumerImpl.sendMessageBack` → `CONSUMER_SEND_MSG_BACK` 请求发给 broker。
3. broker（`SendMessageProcessor.handleRetryAndDLQ`，`SendMessageProcessor.java:180-229`）：
   - `int maxReconsumeTimes = subscriptionGroupConfig.getRetryMaxTimes();` 若 `requestHeader.getMaxReconsumeTimes() != null`（client ≥ 3.4.9 都会带）**则用 consumer 上报的值覆盖** → 即注解/`DefaultMQPushConsumer` 的 `maxReconsumeTimes` 说话。
   - `if (reconsumeTimes > maxReconsumeTimes || 顺序消息锁未过期) { newTopic = MixAll.getDLQTopic(groupName); }` → **`%DLQ%{group}`**，`DLQ_NUMS_PER_GROUP` 个随机队列。
   - 否则投回 **`%RETRY%{group}`**。
   - 延迟等级：`AbstractSendMessageProcessor` 里 `if (0 == delayLevel) { delayLevel = 3 + msgExt.getReconsumeTimes(); }` → 本项目用默认 0，于是走标准阶梯（level 1=1s、2=5s、**3=10s**，此后 30s/1m/2m/…/2h 递增）→ **重试自带越来越长的退避**。
   - 来源：<https://github.com/apache/rocketmq/blob/rocketmq-all-5.3.2/broker/src/main/java/org/apache/rocketmq/broker/processor/SendMessageProcessor.java>、<https://github.com/apache/rocketmq/blob/rocketmq-all-5.3.2/broker/src/main/java/org/apache/rocketmq/broker/processor/AbstractSendMessageProcessor.java>
4. `maxReconsumeTimes=-1` 语义（`DefaultMQPushConsumer.java:248-254` javadoc 原文）：「In concurrently mode, -1 means **16**; In orderly mode, -1 means Integer.MAX_VALUE」→ **22 个没设该值的消费者 = 16 次重试后进 DLQ**。
5. 失败消息被 `removeMessage` 从 `msgTreeMap` 移除，**位点照常前进**（不会因为失败卡住）。

**虚拟线程下的差异**：语义逐行不变；变的是「同时在飞 → 同时失败」的规模。20 → 数百并发时，一次下游抖动会让成百条消息同时进入重试阶梯；阶梯本身能吸收（10s→2h），但如果抖动长于 `maxReconsumeTimes` 覆盖的时间窗，会成批进 DLQ。**票 06 若选虚拟线程化，应同时给出「下游故障时长 vs 重试阶梯 vs maxReconsumeTimes」的口径。**

### 5.3 顺序消费（`ORDERLY`）：不是本 effort 的答案，且有 pinning

`ConsumeMessageOrderlyService` 与并发服务是同一套结构（`ThreadPoolExecutor` min=20，`ConsumeMessageOrderlyService.java:77`），但 `ConsumeRequest.run()` 里：

```java
final Object objLock = messageQueueLock.fetchLockObject(this.messageQueue);
synchronized (objLock) {      // ← :434-435，listener 调用在这个 monitor 里面
    ... msg = messageListener.consumeMessage(...)
}
```

→ **ORDERLY 会在 JDK 21 上 pin 载体线程**（monitor 里跑全量业务阻塞）。另有 `lockMQPeriodically()` / `unlockAllMQ()` / `lockOneMQ()` 三个 `synchronized` 方法（:114,220,241）跑在定时线程上。
且 ORDERLY 的语义是「单队列串行」，与「抬高并发」诉求相反。**结论：从 CONCURRENTLY 换 ORDERLY 换代 = 反向操作，直接排除。**

### 5.4 `consumeTimeout` 兜底阀门（120s 为什么现在"没事"）

`ProcessQueue.cleanExpiredMsg`（`ProcessQueue.java:75-...`）由每消费者一条 `CleanExpireMsgScheduledThread_*` 每 `consumeTimeout`(=15) 分钟跑一次：取 `msgTreeMap.firstEntry()`，若其 `CONSUME_START_TIME` 距今 > `consumeTimeout*60*1000`，则 `sendMessageBack(msg, delayLevel=3)` 并移出树 → **位点得以前进**。一次最多处理 16 条（`Math.min(msgTreeMap.size(), 16)`）。
→ 120s < 15min，所以那个同步阻塞型消费者的 120s 阻塞**永远触发不了这个阀门**；换句话说，**现在这套 20 线程模型的"安全"是靠 consumeTimeout 兜底 + 20 个槽位**，而不是靠任何针对性的隔离。

---

## 6. Pinning 清单（JDK 21）：消费路径上修 0 处

JDK 21 的 pin 来源：monitor（`synchronized` 块/方法里阻塞）、native frame、`Object.wait()`（`private final native void wait0`，`java/lang/Object.java:378`）。逐点核对本仓库消费路径：

| 位置 | 结论 | 证据 |
|---|---|---|
| `ConsumeMessageConcurrentlyService`（含 `ConsumeRequest.run`、`processConsumeResult`） | **0 处 `synchronized`** | 全文件 grep `synchronized` = 0 命中 |
| `ProcessQueue`（putMessage/removeMessage/getMaxSpan/cleanExpiredMsg…） | 用 **`ReentrantReadWriteLock`**（`treeMapLock`/`consumeLock`），lock/unlock 走 AQS→`LockSupport`，**不 pin** | `ProcessQueue.java:45,49,191,216` |
| `RemoteBrokerOffsetStore.updateOffset`（消费线程调） | 是 `synchronized`，但体内只有 `ConcurrentMap` 读写，**不阻塞 → 不 pin** | 见 `ConsumeMessageConcurrentlyService.java:305` 调用链 |
| `RemoteBrokerOffsetStore.persistAll`（有网络 I/O 的那个 synchronized） | 跑在 `MQClientInstance` 的 5s 定时线程上，**不在消费线程** | — |
| trace hook | 本仓库 `enableMsgTrace=false`（注解默认，yml 无覆盖） | `application.yml:40-42` |
| `MessageExt` / `MessageAccessor.setConsumeStartTimeStamp` | 同线程内 HashMap 写，无锁 | `ConsumeMessageConcurrentlyService.java:409-411` |
| **本仓库自身业务码** | 全仓主源码 `synchronized` **仅 1 处**：`FileStoragePort.ensureBucket()`（MinIO bucket 初始化，不在消费路径） | grep 全 backend `src/main` |
| `RestClient` + `SimpleClientHttpRequestFactory`（HttpURLConnection，120s 阻塞读） | **不 pin**。JDK 21 `sun.nio.ch.NioSocketImpl.park` 显式判虚拟线程走 `Poller.poll(fd, event, nanos, this::isOpen)`；`configureNonBlockingIfNeeded` 对虚拟线程**强制非阻塞**（`timed \|\| Thread.currentThread().isVirtual()`） | `openjdk/jdk21u jdk-21.0.13+7: sun/nio/ch/NioSocketImpl.java:172-183, 205-217` |
| Spring `ConcurrentWebSocketSessionDecorator` | 用 `ReentrantLock`（`flushLock`/`closeLock`，tryLock），**不 pin** | `spring-framework v6.1.14: ConcurrentWebSocketSessionDecorator.java:73-75,181,204` |
| Tomcat `WsRemoteEndpointImplBase`（WS 实际写出） | 有 `synchronized (messagePartLock)`，但源码注释明写「**Actual write has to be outside sync block**」把 `writeMessagePart` 挪出 monitor → 阻塞的 socket 写**不在 monitor 内** | `tomcat 10.1.30: WsRemoteEndpointImplBase.java:385-412, 420-445` |
| `ORDERLY` 的 `MessageQueueLock` | **会 pin**（listener 在 `synchronized (objLock)` 内） | `ConsumeMessageOrderlyService.java:434-435` |

**净结论**：`map.md` 里「JDK 口径：留 21，只修会被碰到的 pinning 点」——**RocketMQ 消费路径需要修的 pinning 点数量 = 0**（前提是继续 CONCURRENTLY）。票 06 不能用「pinning 改造量」当作虚拟线程化的成本项/否决理由；反倒要写清楚「JDK 24 / JEP 491 之后这里同样不需要改」。

---

## 7. 线程账（量级）

**当前（默认值，实测推导）**

| 项 | 数量 |
|---|---|
| `ConsumeMessageThread_{group}_*` 消费线程 | 23 × **20** = **460** |
| `CleanExpireMsgScheduledThread_{group}_*`（`start()` 里 `scheduleAtFixedRate`，必起） | 23 |
| `ConsumeMessageScheduledThread_{group}_*`（`newSingleThreadScheduledExecutor`，懒启动；仅 send-back 重投或流控重投时才有任务） | 0–23 |
| `MQClientInstance` 共享线程（`PullMessageService`、`RebalanceService`、`MQClientFactoryScheduledThread`、`ClientHousekeepingService`…） | 个位数（每实例一份） |
| **合计** | **≈ 483–506 条平台线程，其中 460 条是消费线程** |

**虚拟线程化后省下多少**

- 只换 `consumeExecutor`：**省 460 条**，但每个消费者的 2 条 `ScheduledExecutorService`（CleanExpire / 重投）仍留在 client 内部（`ConsumeMessageConcurrentlyService` 构造器里 `Executors.newSingleThreadScheduledExecutor`），**省不掉**——除非一并自研服务或等上游把这两个也抽象掉。
- 栈占用量级：仓库未设 `-Xss`（`docker-compose.yml` 只给 broker/namesrv 配了 `-Xmx`，不含应用 JVM 参数）→ 平台线程默认保留约 1 MB 地址空间/线程 → **460 MB 保留虚拟地址**（实际 RSS 远小于此，但 JVM 保留/VSZ 与线程元数据是实打实的）。虚拟线程栈是堆上的可增长 chunk（默认 `jdk.virtualThreadScheduler.maxPoolSize` 的载体线程数 = CPU 核数），460 条 VT 的常驻栈量级是**几百 KB–几 MB**，量级上差 2 个数量级。

---

## 8. 一手来源清单

**rocketmq-spring（tag `rocketmq-spring-all-2.3.6`）**
- 依赖版本：<https://github.com/apache/rocketmq-spring/blob/rocketmq-spring-all-2.3.6/rocketmq-spring-boot-parent/pom.xml>
- `DefaultRocketMQListenerContainer`：<https://github.com/apache/rocketmq-spring/blob/rocketmq-spring-all-2.3.6/rocketmq-spring-boot/src/main/java/org/apache/rocketmq/spring/support/DefaultRocketMQListenerContainer.java>
- `RocketMQMessageListener`（注解默认值）：<https://github.com/apache/rocketmq-spring/blob/rocketmq-spring-all-2.3.6/rocketmq-spring-boot/src/main/java/org/apache/rocketmq/spring/annotation/RocketMQMessageListener.java>
- `RocketMQMessageListenerContainerRegistrar`（可覆写 `registerContainer`）：<https://github.com/apache/rocketmq-spring/blob/rocketmq-spring-all-2.3.6/rocketmq-spring-boot/src/main/java/org/apache/rocketmq/spring/support/RocketMQMessageListenerContainerRegistrar.java>
- `ListenerContainerConfiguration`（`@ConditionalOnMissingBean`）：<https://github.com/apache/rocketmq-spring/blob/rocketmq-spring-all-2.3.6/rocketmq-spring-boot/src/main/java/org/apache/rocketmq/spring/autoconfigure/ListenerContainerConfiguration.java>
- `RocketMQMessageListenerBeanPostProcessor`（`ObjectProvider<...Registrar>`）：<https://github.com/apache/rocketmq-spring/blob/rocketmq-spring-all-2.3.6/rocketmq-spring-boot/src/main/java/org/apache/rocketmq/spring/annotation/RocketMQMessageListenerBeanPostProcessor.java>

**RocketMQ client / broker（tag `rocketmq-all-5.3.2`，与本仓库实际依赖一致）**
- `ConsumeMessageConcurrentlyService`：<https://github.com/apache/rocketmq/blob/rocketmq-all-5.3.2/client/src/main/java/org/apache/rocketmq/client/impl/consumer/ConsumeMessageConcurrentlyService.java>
- `DefaultMQPushConsumerImpl`（服务创建点 :971-978、流控 :269-302、setter :1582）：<https://github.com/apache/rocketmq/blob/rocketmq-all-5.3.2/client/src/main/java/org/apache/rocketmq/client/impl/consumer/DefaultMQPushConsumerImpl.java>
- `DefaultMQPushConsumer`（默认值 + `maxReconsumeTimes` javadoc）：<https://github.com/apache/rocketmq/blob/rocketmq-all-5.3.2/client/src/main/java/org/apache/rocketmq/client/consumer/DefaultMQPushConsumer.java>
- `ProcessQueue`（RW 锁、`removeMessage`、`cleanExpiredMsg`）：<https://github.com/apache/rocketmq/blob/rocketmq-all-5.3.2/client/src/main/java/org/apache/rocketmq/client/impl/consumer/ProcessQueue.java>
- `ConsumeMessageOrderlyService`（monitor pinning）：<https://github.com/apache/rocketmq/blob/rocketmq-all-5.3.2/client/src/main/java/org/apache/rocketmq/client/impl/consumer/ConsumeMessageOrderlyService.java>
- broker 重试/DLQ：<https://github.com/apache/rocketmq/blob/rocketmq-all-5.3.2/broker/src/main/java/org/apache/rocketmq/broker/processor/SendMessageProcessor.java>
- broker 延迟等级公式：<https://github.com/apache/rocketmq/blob/rocketmq-all-5.3.2/broker/src/main/java/org/apache/rocketmq/broker/processor/AbstractSendMessageProcessor.java>

**上游虚拟线程相关（用于判断"官方支持"的边界）**
- PR #11090（merged 2026-09-11 到 develop）新增 `setConsumeExecutor` + `AbstractConsumeMessageService`，PR 描述明示支持 JDK 21 虚拟线程执行器：<https://github.com/apache/rocketmq/pull/11090>
- issue #11089（对应上条）：<https://github.com/apache/rocketmq/issues/11089>
- issue #8062 + PR #8063（`virtualThread` 开关方案，**PR 未合并**）：<https://github.com/apache/rocketmq/issues/8062>、<https://github.com/apache/rocketmq/pull/8063>
- issue #8073（`start()`/`shutdown()` 的 `synchronized` pinning）：<https://github.com/apache/rocketmq/issues/8073>

**JDK 21 / 周边框架**
- `sun.nio.ch.NioSocketImpl`（虚拟线程走 `Poller.poll`、强制非阻塞）：`openjdk/jdk21u` tag `jdk-21.0.13+7`，`src/java.base/share/classes/sun/nio/ch/NioSocketImpl.java:172-217`
- `java.lang.Object.wait0` native（`Object.wait()` 必 pin）：同 tag，`src/java.base/share/classes/java/lang/Object.java:378`
- Spring `ConcurrentWebSocketSessionDecorator`（ReentrantLock）：`spring-projects/spring-framework` tag `v6.1.14`
- Tomcat `WsRemoteEndpointImplBase`（写操作刻意移出 monitor）：`apache/tomcat` tag `10.1.30`

**本仓库**
- `backend/better-school/bs-trigger/src/main/java/com/betterschool/trigger/listener/`（21 个消费者）、`backend/better-school/bs-trigger/src/main/java/com/betterschool/trigger/ws/ChatWsPusher.java`
- `backend/better-school/pom.xml:37`、`backend/better-school/bs-app/src/main/resources/application.yml:40`、`backend/docker-compose.yml:104,123`

---

## 9. 给票 06 的"事实清单"（供裁决直接引用）

1. **要换 executor，唯一现实的两条路**：等 client 拿到 `setConsumeExecutor`（未发布）／自研 `ConsumeMessageService` + 换容器注册器（`RocketMQMessageListenerContainerRegistrar`，可自定义 Bean）。**没有 `consumeThreadNumber` 之外的配置口子。**
2. **当前 460 条消费线程是 `consumeThreadMin=20` 定的，`consumeThreadMax=64` 永不生效**（无界队列）——"只调线程数"连现有模型都调不动。
3. **换虚拟线程后立刻被 pull 流控（1000 条 / 100 MiB / span 2000）与 Hikari 池接管天花板**；不配连接池口径就是重试风暴。
4. **ack / 重试 / DLQ / 顺序语义逐行不变**；但「一条 120s 消息钉住该队列位点 120s」是模型固有，虚拟线程化不改善。
5. **pinning 改造量 = 0**（并发消费路径无 monitor、无 native 阻塞点、HTTP/WS 均已核实不 pin）；唯一会 pin 的是 ORDERLY 的 `MessageQueueLock`，而 ORDERLY 与"抬并发"方向相反。
6. **线程账**：换 executor 省 460 条，client 内部每个消费者还留 2 条 `ScheduledExecutorService` 线程（CleanExpire/重投），省不掉。
