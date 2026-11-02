# 06 grilling：RocketMQ 消费者线程模型裁决

Type: grilling
Status: resolved
Blocked by: 02

## Question

23 个消费者（约 460 条平台线程）最终采用什么线程模型？待[票 02](02-research-rocketmq-consumer-executor.md) 调研定案后 grilling 拍板：

1. **整体换池 vs 只修孤例**：把并发消费线程池整体换成虚拟线程（按票 02 给出的可替换点），还是只把一个同步阻塞型消费者那 120s 阻塞从消费线程上挪走？两者的收益与代价对齐
2. **若整体换**：怎么落地（自研容器 / SPI / 配置）、`consumeThreadNumber` 的语义怎么收场、ack 与重试语义是否不变
3. **若不换**：写清理由（是「不满足选点规则」还是「代价不成比例」），并给出**可回收的触发条件**
4. **孤例热点的具体改法**（无论整体换不换都要定）：一个同步阻塞型消费者的阻塞怎么消——消费者内开虚拟线程 fan-out / 按耗时分层 topic / 改拉取型
5. **长连接类消费者**（4 个走 WS 发送的：`ChatPushConsumer` / `ChatReadPushConsumer` / `ChatRecallPushConsumer` / `NotificationBroadcastConsumer`）与[票 09](09-grill-websocket-fanout.md) 的裁决对齐，避免两处打架

结论落 spec 的「消息消费」节。

## Answer

**一句话：23 个消费者的消费线程池整体换成虚拟线程（4 工程统一，自己接线——同包子类覆写 `submitConsumeRequest`）；并发上限不再由 `consumeThreadNumber` 表达，改由 client 自己的 `pullThresholdForQueue` 表达；孤例（一个同步阻塞型消费者）不再单列；WS 扇出口径整体归[票 09](09-grill-websocket-fanout.md)。**

### 事实自核：票面与研究稿的前提修正

| # | 票面/研究稿的写法 | 实测 | 出处 |
| --- | --- | --- | --- |
| 1 | 「4 个走 WS 发送的消费者」（票面 ⑤、[研究 §1](../research/02-rocketmq-consumer-executor.md)、票 09 问 ④） | **3 个**：`ChatPushConsumer` / `ChatReadPushConsumer` / `ChatRecallPushConsumer`。`NotificationBroadcastConsumer` **不走 WS**——它只调 `notificationRepository.insertBroadcast`，在**一个事务里对全量用户逐行 insert**（`NotificationRepository.java:82-97`） | `ChatWsPushService.broadcast`（`ChatWsPushService.java:59-72`）是唯一 WS 扇出点，只有三个 chat 消费者引用它 |

### 票面 ① 整体换 vs 只修孤例：**整体换**

**裁决依据是用户口径**（2026-09-20：「该用就直接用，不用考虑已有的架构，本来就是开发阶段，能上的直接上」），**不是性能论证**——按图绘期选点规则，20/23 个消费者的工作主体是短 DB 写、并发上限本来被 Hikari 池（10）兜死，换虚拟线程**在吞吐上换不来收益**（研究 §4 + [票 04](04-task-measure-framework-flip.md) 实测）。spec 必须**原样带上这条**，只许写成「线程模型统一 + 消费层不再吃平台线程」，不许包装成性能优化（对齐[票 05](05-grill-framework-flip-and-backpressure.md) 对框架开关的同款口径）。

- **范围**：4 工程统一（`timeout-center` 零消费者；代码在单体 + 两营销服各一份）——理由与票 05 的「规则不因工程而异」一致，不为 1 个消费者留两套线程模型。
- **pinning 改造量 = 0**（研究 §6 逐行核过：并发消费路径 0 处 `synchronized`、`ProcessQueue` 用 RW 锁、120s 的 HTTP 阻塞读对虚拟线程走 `Poller.poll`）——本项**既不是成本也不是否决理由**；JDK 24 / JEP 491 后同样为 0。
- **诚实的代价**：`consumeThreadNumber` 变**死参数**（`consumeThreadMax` 本来就是死的，研究 §3.2）；消费层的并发上限从「平台线程数」搬到了「client 自己的流控参数」，这一点必须写进 spec。

### 票面 ② 怎么落地、`consumeThreadNumber` 语义、ack/重试语义

**落地方式（三选一定案）**：

| 方案 | 判定 |
| --- | --- |
| 升级 client 到含 `setConsumeExecutor` 的版本 | ❌ 该能力只在 develop（PR #11090）；最新**发布**版 5.5.1 无此字段（本轮复核 `repo1.maven.org` 元数据），本仓引 `5.6.0-SNAPSHOT` 不划算 |
| 自研整个 `ConsumeMessageService` | ❌ 要改写整个服务实现（`ConsumeRequest` + submit + `processConsumeResult` + 发回 + 超时阀门 + stats，合计 ~300 行，纯风险）。注：研究 §3.3 说的「抄 70 行」只指其中 `processConsumeResult` 一段，与本行的 ~300 行不矛盾 |
| **同包子类 + 覆写 `submitConsumeRequest`** | ✅ **采用** |

**采用方案的机理（全部一手源码核对）**：executor 是 `private final ThreadPoolExecutor consumeExecutor`（`ConsumeMessageConcurrentlyService.java:57`）⇒ 反射与 setter 都够不着（JDK 21 对 final 实例字段 `Field.set` 抛 `IllegalAccessException`），只能**换整个服务**。而：

- `ConsumeRequest` 是**包私有内部类**（:358）⇒ 把子类放在 `org.apache.rocketmq.client.impl.consumer` 同包即可 `new ConsumeRequest(...)`；
- `submitConsumeRequest` 是 **public**（:187）⇒ 覆写它、把提交目标换成 VT executor，**batch 切分那 15 行照抄**；
- `processConsumeResult`（public :242）、ack 时机、重试阶梯（`delayLevel=3+reconsumeTimes`）、`%DLQ%`、stats、15min `consumeExpireMsg` 阀门**全部原样继承** ⇒ 票面问的「ack 与重试语义是否不变」答案是**逐行不变**；
- base 池**懒创建**（`ThreadPoolExecutor` 不 prestart）⇒ 我们不投它就**一条平台线程都不产生**；每个消费者那 2 条 `ScheduledExecutor`（CleanExpire/重投）本来就有，且 S0 的 `cleanExpireMsg` 阀门照常工作（`ProcessQueueTable` 共享）。

**接线点**：`DefaultMQPushConsumerImpl.setConsumeMessageService` public；容器 `getConsumer()` public（`DefaultRocketMQListenerContainer.java:310`）；容器 bean 在 `registerContainer` 内即完成 `afterPropertiesSet()`（consumer 已 new、未 start），而 `startContainer()` 才 `consumer.start()` ⇒ **覆写 `RocketMQMessageListenerContainerRegistrar`（带 `@ConditionalOnMissingBean`，可整体接管；~25 行）**即可，不必自研容器。⚠️ 换服务必须发生在 `consumer.start()` **之后**（start 内部才 new 默认服务）。

**落地位置**：同包子类放 `xxx-trigger`（`rocketmq-spring-boot-starter` 已在 `bs-trigger/pom.xml:43-45` 声明，rocketmq-client 编译期可见），注册器 `@Bean` 放 `xxx-app/config/`（与 `XxlJobConfig` / `WebSocketConfig` 同款）。

**遗留代价**：同包 split package（classpath 下合法）；升级 client 若改 `ConsumeRequest` 签名会是**编译期**报错（失败模式可见，不是静默失效）；`getCorePoolSize()`/`updateCorePoolSize` 指向那个空池（本就半死）。

**可回收触发条件**：上游 `setConsumeExecutor` 发布（5.6.0+）后，把同包子类那段换成官方 API——**容器 seam 不变**，接线代码不扔。

**并发上限（有界 vs 无界）**：**无界 `Executors.newVirtualThreadPerTaskExecutor()`**（与 `TimeoutTaskScanCase.java:71` 同款）+ **不加信号量**。

- 上限改用 client 自己的流控：`container.getConsumer().setPullThresholdForQueue(N)`（同一个容器 seam 里设），超限走 client 自带的 50ms 延迟重拉，**不阻塞任何线程**。这与票 05 的裁决口径一致（票 05 原文：「连接池是唯一真并发上限，`connection-timeout` 决定慢失败时延」，不加新的 Web 层信号量）。
- ⚠️ **本票只定"用这个旋钮"，不定 N 的值**（对齐票 05 → 票 07 的分工方式）：默认值 1000 **比 Hikari 池（10）大两个数量级**，换池后必然偏大；N 的具体值落地时按池与下游配额定（锚点是 Hikari 10 与下游服务配额，不是"与池同量级"这种说法——那需要算，不是拍）。
- **不用有界的理由（不只是口径一致性）**：有界要自己造拒绝路径——client 的 `submitConsumeRequestLater(ConsumeRequest)` 是 **private 且投回 base 池**（:346-356），无界 executor 则永不 `RejectedExecutionException`、现成路径直接复用；且阻塞式 `acquire` 会卡住整实例唯一的 `PullMessageService`（危险）。
- **`consumeThreadNumber` 语义收场 = 死参数**：spec 明写「消费并发上限不由它表达，要限流调 `pullThresholdForQueue`」。

### 票面 ③ 不换的理由与触发条件：**本问因 ① 反转为「换」而作废**

反向留档（供将来回看）：按图绘期选点规则本应判「不换」——20/23 个消费者被 Hikari 池兜死；唯一「若口子存在就该换」的是一个同步阻塞型消费者（它的**长阻塞主体是 HTTP 调用**，`reviewService.reviewDetail` / `loadAuthorProfile` 两处 DB 读只是毫秒级前置，不构成上限；并发上限真在 20 个槽位）。本票的实际裁决依据是用户口径（开发阶段、能上就上、架构展示面）。

**回退触发条件**（票面 ③ 要求的"可回收"这一面，方向反转后仍成立）：① 若 `pullThresholdForQueue` 收窄后仍实测出现**成批 `SQLTransientConnectionException`→`RECONSUME_LATER` 的池饥饿式重试风暴** ⇒ 把 VT executor 换回固定平台池（接线点不变，改一行）；② 上游发布后按票面 ② 的可回收触发条件收编官方 API。

### 票面 ④ 孤例的具体改法：**并入整体换，不再单列**

- **已顺手解决**：20 个槽位不再是并发上限（120s 阻塞在虚拟线程上不 pin，研究 §6），票面说的"占 1/20 槽位"消失。
- **未解决、且写清不解决**：**位点钉住 120s 是模型固有**（`ProcessQueue.removeMessage` 返回最小未确认 offset，研究 §5.1）——不 ack 就还钉着，虚拟线程化不改善。
- **专属限流**：该消费者下游是一个 HTTP 服务（**无池兜底**），无界 VT 会把它打爆 ⇒ 用票面 ② 的 `pullThresholdForQueue` 收到更小值，这是那个旋钮最有用的一处。

### 票面 ⑤ 长连接类消费者与票 09 对齐

- **3 个**（非 4 个，见事实自核 1）：`ChatPushConsumer` / `ChatReadPushConsumer` / `ChatRecallPushConsumer`——它们的**线程模型并入本次整体换**（不单列）。
- **边界切法**：票 06 只裁「消费线程模型」，**WS 扇出口径整体归[票 09](09-grill-websocket-fanout.md)**，spec 的「消息消费」节只留一条指针。给票 09 三条约束：
  1. 扇出若并行化，**必须有界**（票 05 已立口径，原文：「**所有扇出点的并行度 ≤ 池**，沿用 timeout-center「池 ≥ 并行度」先例」）；
  2. **不得改变「漏推合法、补拉兜底」语义**（`ChatWsPusher.push` 吞异常、`broadcast` 只推在线成员）；
  3. **顺序基线**：同会话内消息顺序**今天就已不保证**（20 个并发消费线程本可乱序处理同一队列），客户端靠 `(roomId, seq)` 判重 + 补拉合流 ⇒ 票 09 问的「并发发送会不会乱序」必须以现状为基线，不当新风险；把顺序升级为硬保证 = 反转现状（语义变更，不属本 effort）。
- **票 06 给票 09 的倾向**（用户 2026-09-20「都按你推荐的」）：扇出**并行化（虚拟线程逐成员并发写帧）本身不破顺序语义**——同一条消息推给不同成员之间没有先后问题，跨消息顺序的基线见上条。最终裁判权在票 09。
- `NotificationBroadcastConsumer` 不是长连接消费者（见事实自核 1）：它是**全用户单事务逐行写**，并发上限由**事务边界**决定（要并行就得拆事务 ⇒ 广播部分可见 = 语义变更，属 map 的 Out of scope 第 5 条）⇒ spec 记一条**「已看、不是线程模型问题」**，不设改造项；可回收触发条件 = 若全用户广播成为实测瓶颈，另开性能 effort（批量/分批 insert）。

### 已知风险与两条待办口径（研究 §4/§5 明确要求本票给出）

- **在飞并发抬高后的失败规模**（研究 §4「对票 06 的意义」）：在飞消息数从 ≈20 抬到 `pullThresholdForQueue` 的深度，下游抖动时会**成批同时失败**进入重试阶梯。口径：**两个 client 参数保持默认不动**（`maxReconsumeTimes = -1` ⇒ 并发模式 16 次；重试延迟 `3 + reconsumeTimes` ⇒ 10s→30s→1m→2m→…→2h 递增）。若抖动长于阶梯覆盖的窗口，成批进 `%DLQ%{group}` 是**预期的 at-least-once 行为**（今天 20 并发同样会失败，只是规模小），不是本票引入的新故障模式。
- **本 effort 不新增死信监控设施**；spec 记一条「`%DLQ%{group}` 只是 broker 上的 topic、无人看」→ 是否纳入可观测口径由[票 10](10-grill-observability-and-acceptance.md) 定（本票只负责说清"它是既有缺口，不是换虚拟线程引入的"）。
- **收窄在飞的旋钮**（研究 §4 的另一半：流控会成为新天花板）：按票面 ② 用 `pullThresholdForQueue` 收窄即可，**不动 `pullThresholdSizeForQueue`（100MiB）/ `consumeConcurrentlyMaxSpan`（2000）**——它们与新线程模型无关。

### 转下游（收口时一并留痕）

- **[票 09](09-grill-websocket-fanout.md)**：WS 扇出口径归它（三条约束 + 票 06 的倾向见上）。⚠️ 已 resolved（2026-09-21）：裁决**不并行化**——本票「并行化不破顺序语义」的倾向成立但不是判据，否掉它的是「扇出宿主已随本票换 VT、上限问题已被吸收，并行化只买单消息尾延迟还要赔一套有界机制」；三条约束的落点见该票 Answer（约束 1 vacuous、约束 2/3 照单）。
- **[票 10](10-grill-observability-and-acceptance.md)**：① 消费层 **460 条平台消费线程应归零**，可断言（`ConsumeMessageThread_*` 不再出现 / 监听器内 `Thread.currentThread().isVirtual()` 为 true）；② ⚠️ 票 05 已转出的「翻开关后 Tomcat 侧 max/busy 指标失明（读 `-1`）」与本票叠加后，「平台线程数应当是下降的」这条口径**只剩 Web 层与扇出层可看**——消费层不是"下降"而是"清零"，验收别写反。
- **[票 11](11-task-write-spec.md)**：本节落 spec 的「消息消费」节。⚠️ 落稿口径：spec 只写**裁决**（「消费池换虚拟线程；采用同包子类 + 容器注册器 seam 的接线路径；ack/重试语义逐行不变；并发上限由 `pullThresholdForQueue` 表达；`consumeThreadNumber` 为死参数」），**不搬实现步骤**——map 的 Destination 明写 spec 不含代码改造步骤，实现细节留给 spec 之后另拆的落地票。
- **[票 07](07-grill-dubbo-thread-model.md)**：无依赖（Dubbo provider 池与本票正交）。
