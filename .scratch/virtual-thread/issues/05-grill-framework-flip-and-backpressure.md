# 05 grilling：框架开关的落地面与并发上限 / 背压口径

Type: grilling
Status: resolved
Blocked by: 03, 04

## Question

4 个工程翻 `spring.threads.virtual.enabled=true` 之后，spec 要把「落地面」与「承压上限」两件事定死：

1. **落地面**：这个开关在 Spring Boot 3.3.5 里到底让哪些组件变虚拟（Tomcat 协议处理器？`TaskScheduler`？其他自动配置？），逐项列出「变 / 不变」；哪些工程翻、哪些组件需要额外配置才生效
2. **开关的连带面（[票 01](../research/01-dubbo-thread-model.md) 揭示，必须在本票裁决）**：Dubbo 3.3.5 起，`spring.threads.virtual.enabled=true` 会**自动**把 `dubbo.protocol.threadpool` 设为 `virtual`——即「翻 Web 层开关」与「VT 化 Dubbo Provider」被焊成一件，且 **provider 的 `threadpool` 参数经注册中心跨应用传播**（决定消费端 `streamExecutor` 种类，见 dubbo issue #13635/#13291）。要定：① 是接受连带、还是用显式配置压回（把两者解耦）；② 3.3.6 的 `virtual` 是**无界** `newThreadPerTaskExecutor`（移除「200 打满即拒」硬保护），这个代价与「翻开关省事」是否对等；③ 跨应用传播的影响面（4 个工程里有几个会因此改变消费端行为）
3. **承压上限**：虚拟线程把并发抬起来之后真实瓶颈落在哪里——单体与两营销服 Hikari **默认 max=10**、timeout-center 24。要不要显式配池？配多大？配套的**背压口径**是什么（扇出加信号量？靠连接池排队兜？还是照 timeout-center「Hikari 池 ≥ 并行度」的先例，把并发度限定在池大小附近）
4. **pinning 处置**：票 03 的清单 + 票 04 的实测里，哪些 pinning 点必须在本版修（如 `FileStoragePort.ensureBucket`）、哪些记录为已知风险、哪些被证明无害
5. **适用性表**：4 个工程各自的开关口径（哪些一起翻、哪些不翻、为什么）——单体是主战场，三个服务按各自形态定

依赖[票 03](03-research-jdk21-pinning-inventory.md)（pinning 清单）与[票 04](04-task-measure-framework-flip.md)（实测数据）。结论落 spec 的「框架开关与承压口径」节。

## Answer

**一句话：四工程统一翻开关（口径统一，但 spec 里诚实写明「实测无收益」）；Dubbo 的连带**显式写、不吃默认**（⚠️ 值由票 07 定为 `virtual`，见下方注记——本句原写「显式压回」，那个**值**已被反转）；把背压落到连接池与「快失败」上；pinning 本版只修 `FileStoragePort.ensureBucket()` 一处。**

### 落地面（票面 ①）：翻开关在本仓只做一件事

「打包产物反编译」核到的一手事实（`bs-app-1.0.0-SNAPSHOT.jar` → `tomcat-embed-core-10.1.31.jar` / `spring-boot-3.3.5.jar` / `HikariCP-5.1.0.jar` / `dubbo-3.3.6.jar`，与票 03 §2.3 口径一致）：

| 开关可能换的东西（Boot 3.3.5） | 本仓是否生效 | 证据 |
| --- | --- | --- |
| Tomcat Connector `setExecutor(new VirtualThreadExecutor("tomcat-handler-"))` | **生效**（唯一真实落地面） | `TomcatVirtualThreadsWebServerFactoryCustomizer.class` 在 `spring-boot-autoconfigure-3.3.5.jar` |
| `applicationTaskExecutor` / `TaskScheduler` 换 VT builder | **空转** | `TaskExecutorConfigurations$SimpleAsyncTaskExecutorBuilderConfiguration` / `TaskSchedulingConfigurations$SimpleAsyncTaskSchedulerBuilderConfiguration` 存在，但全仓零 `@Async` / `@Scheduled` / `TaskScheduler` |
| Reactor `defaultBoundedElasticOnVirtualThreads` | **空转** | `spring-boot-3.3.5.jar!org/springframework/boot/reactor/ReactorEnvironmentPostProcessor.class`，本仓不用 Reactor |
| NIO acceptor / poller | **不变**（恒平台线程） | 票 03 §2.3 字节码实证 |

**票面①问的「哪些组件需要额外配置才生效」**：**没有一项需要额外配置**——Tomcat 那项由 Boot 的 customizer 直接 `setExecutor` 生效；`TaskExecutor`/`TaskScheduler`/Reactor 三项是**零配置即自动 VT**，只是本仓当前无用武之地（一旦将来引入 `@Async` 或 Reactor，它们会自动跟着变，这是本表要留的伏笔）。

**裁决：四工程统一 `spring.threads.virtual.enabled: true`。** 理由不是吞吐（票 04 实测 on/off 差落在噪声内），而是：
1. **Web 层并发上限当前由平台线程数决定**（Tomcat 默认 `maxThreads=200`，全仓零 `server.tomcat.*` 配置）——符合图绘期选点规则的第一条；本仓有 MinIO 上传这类长阻塞 handler（票 04 实测 p50 2.2–2.8s）；ES 检索同属长阻塞但**无时长数据**（票 04 的负载里没有 ES op，票 03 §2.10 只证了它的同步等待是 monitor + `Object.wait`），只能算疑似。「200 个 handler 占满」在高并发下是真实排队点，只是本轮的混合负载没打到。
2. 与已有的 5 处手写虚拟线程扇出（超时链路）口径一致。
3. ⚠️ **spec 必须原样带上「实测收益 ≈ 0」这条事实**（票 04），不许包装成性能优化；翻开关的收益表述只能是「消除平台线程上限 + 口径统一」。

**两条硬约束**（都由本轮字节码实证推得）：

- **不许配 `server.tomcat.threads.max`**：`AbstractEndpoint.setExecutor(非 null)` 会把 `internalExecutor` 置 `false`，而 `getMaxThreads()` 的实现是 `internalExecutor ? maxThreads : -1` ⇒ 翻开关后这个配置**静默失效**，连 JMX/Actuator 上读到的都是 `-1`。
- **不许混用 Tomcat 原生 `useVirtualThreads`**：`AbstractEndpoint.createExecutor()` 里 `getUseVirtualThreads() ? new VirtualThreadExecutor(...) : 平台线程池`——两条路只生效一条，同时配是无意义的分叉。

### Dubbo 的连带面（票面 ②）：显式写、不吃默认（⚠️ 值＝`virtual`，见下方注记；原标题的「压回」已被票 07 反转）

- 3.3.5 起 `spring.threads.virtual.enabled=true` 会自动把 `dubbo.protocol.threadpool` 设为 `virtual`（写进 `defaultProperties`，优先级最低，见票 01 §3.3）。
- 3.3.6 的 `virtual` = `Executors.newThreadPerTaskExecutor(...)`：**无界**、忽略 `threads`/`queues`/`corethreads`、**移除默认 `fixed` 池「200 打满即拒 + `AbortPolicyWithReport` dump jstack」这道硬保护**（票 01 §3.5）。

**裁决：3 个 provider 工程（`better-school` / `marketing-asset` / `timeout-center`）显式写 `dubbo.protocol.threadpool: <值>`，~~把「翻 Web 层开关」与「VT 化 Dubbo Provider」解耦~~（⚠️ 见下方注记：值定为 `virtual` 后并未解耦）。** 票 05 只定这条**规则**（不许吃默认连带），**具体值留给[票 07](07-grill-dubbo-thread-model.md)**——那是它的题（`fixed` 保 200 拒绝 / `virtual` 无界 / 自研有界 VT 池）。

> ⚠️ **票 07 已 resolved（2026-09-21）：值是 `virtual`**（三 provider 显式写）。**规则保留、理由改口径**——值取 `virtual` 后「解耦」在效果上不成立（显式写的意义退化成「自文档 + 抗将来只改一边」）；下面那句理由的两条论据也已被票 07 否掉（见该票「被否掉的两条论据」：① 「provider 池的 200 上限在本仓从没被打到过」只是票 01 §7.2 的**纸面推理**（票 01 §7.3 自陈该数待实测），判据应是**上限由谁决定**；② DB 限流不该由 Dubbo 池承担，所以「压力下沉 Hikari」构不成反对理由）。反转已同步到本票据内六处：上面的一句话摘要、本节标题、本裁决段、「跨应用传播」段、下面的适用性表、末节「转下游」。

~~理由：provider 池的 200 上限在本仓**从没被打到过**（调用边并发量级个位到数十，唯一例外是超时中心的回调扇出，票 01 §7.2），VT 化它换不来收益，却把并发上限整体下沉到 Hikari——而 Hikari 上的等待现在是 **30 秒超时才慢失败**（`HikariConfig` 常量实证：`CONNECTION_TIMEOUT = SECONDS.toMillis(30)`），比「立刻拒绝 + jstack 告警」更糟。~~（**已作废**，见上注；其中「30 秒」一条本票后文已自行改为 `connection-timeout: 3000`。）

**跨应用传播的影响面（票面 ② ③）**：3 个 provider 一旦注册 URL 带 `threadpool=virtual`，其 consumer 的 `streamExecutor` 会跟着变（issue #13291/#13635，票 01 §2.8；3.3.6 的 `DefaultProviderFirstParams` 仍不含 `threadpool`，修不在本版）。**逐条数的话，4 个工程全中**——本仓 5 条静态消费边（`marketing-activity` → 单体 `IUserQueryService` ×2、`marketing-activity` → `marketing-asset` `IPointsExchangeService`、`marketing-asset` → 单体 `IUserQueryService`、单体 → `timeout-center` `ITimeoutTaskRegister`）+ 1 条动态回调边（`timeout-center` → 三条业务回调，按 `callbackGroup` 建引用）**每一条都指向这 3 个 provider 之一**。但本仓 **5 处静态 `@DubboReference` + 1 处动态 `ReferenceConfig` 全是 sync unary，而 sync unary 根本不进 `streamExecutor`**（票 01 §2.4：`TripleInvoker` 用 `ThreadlessExecutor`，响应由业务线程就地跑完）⇒ **今天零行为变化**，实际只是「白拿一个无界池」+ 将来引入 streaming/async 回调时的隐患。→ ~~这也正是「压回」的额外收益：不传播就不会有人将来踩到。~~ ⚠️ **票 07 反转**：本票最终**不压回**（值为 `virtual`），所以传播**会发生**——4 个工程的消费端 `streamExecutor` 都会变成 virtual；因全是 sync unary，今天仍零行为变化，记为**隐患**并进票 07 的可回收触发条件（② 引入 streaming/原生 async 时重判）。

### 承压上限与背压口径（票面 ③）

翻开关的**实质代价**：Web 层并发上限从 200（Tomcat 平台线程）变成 `server.tomcat.max-connections`（默认 8192）——**入口限流器没了**。而票 04 实测真上限在连接池与 DB 延迟（池 10 → ~250 rps，池 64 → ~500 rps；四个臂的等连接线程峰值 231–336——票 04 的 map 索引行只写了其中较小的 280，池 10 的两个臂其实是 280 / 336；**全臂**应用 CPU 峰值仅 30.7%，出自票 04 §①）。「Tomcat 200 平台线程」在本仓其实一直兼任着一道粗粒度背压。

**裁决：口径写成「连接池是唯一真并发上限，`connection-timeout` 决定慢失败时延」，不加新的 Web 层信号量。** 三件落地动作：

1. 三个没配 Hikari 的工程（`better-school` / `marketing-activity` / `marketing-asset`）把 `maximum-pool-size`（值 = 10，现状语义不变）与 **`connection-timeout: 3000`** 显式化——对齐 timeout-center 先例（它已配 24 / 3000ms）。默认 30s 与「无界接受」叠加 = 请求挂 30 秒才 500，比翻开关之前更糟。
2. **池大小不为了 VT 而调**：票 04 已证「抬池」是正交的独立收益（池 10→64 吞吐翻倍），且池 10 配 VT 实测不劣化 ⇒ 本 effort 不动 10 / 24 这两个数。池调优不属本 effort（可回收触发条件：若要抬吞吐，票 04 的 `measure/` 数据是起点）。
3. **所有扇出点的并行度 ≤ 池**，沿用 timeout-center「池 ≥ 并行度」先例（spec §5.3）——逐条核的活归[票 08](08-grill-scheduling-and-existing-fanout.md)。

> ⚠️ **票 08 复核（2026-09-21）：上面第 1 条尚未落地。** 全仓只有 timeout-center 有显式 Hikari 配置块；单体与两个营销工程**都没有 Hikari 配置块** ⇒ 今天跑的是 Boot 默认（池 **10**、`connection-timeout` **30s**）。spec 与下面的适用性表都必须把它记为**目标态**、不许当既成事实——[票 08](08-grill-scheduling-and-existing-fanout.md) 里所有「扇出宽度 vs 池」的论证都建立在这个口径上（见该票 B 节的三处记账）。

**不采用「Web 层加并发信号量」**：池上的等待只 `park` 不 pin（票 03 §2.5：`ConcurrentBag.borrow` 全类零 monitor），池已经是真上限；信号量是重复的限流器，只会多一个要维护的数字。

### pinning 处置（票面 ④）：本版只修一处

| 分类 | 项 | 处置 |
| --- | --- | --- |
| **必修（本版）** | `FileStoragePort.ensureBucket()`（`synchronized` 包 MinIO 网络 I/O，`bucketReady` 快路径只挡住第二次之后的调用） | 改 `ReentrantLock` + 双检（票 03 §4.3 有完整片段）；⚠️ JDK 24 / JEP 491 后可回收。**一处复核修正**：`initBucket()` 带 `@PostConstruct`，启动期已软预热一次（失败被 catch 吞、不阻断启动）⇒ 正常环境下 `bucketReady` 早为真，这条改造是**廉价保险**而非热点（票 04 实测里它一次都没进 pin 榜，由此可解）；改造只是把启动期与运行期共用的那条路径的 `synchronized` 换掉，不是新增预热（已回填票 03 §4.3） |
| **补偿型，不改** | Tomcat NIO 阻塞写（`synchronized(writeLock){ writeLock.wait(...) }`，慢客户端/TCP 背压）；ES `BasicFuture.get()`；netty `DefaultPromise.await`（Redisson 内其它路径） | 不拖吞吐、只占载体（池上限 256）；**JFR 看不见**，别拿「零事件」当无反证。WS 那条由[票 09](09-grill-websocket-fanout.md) 的扇出口径兜 |
| **已证明无害** | JDBC socket 读 pin（票 04 实测：59761 事件全来自 `ReadAheadInputStream.fill()`，p50 1.28ms、超 20ms 阈值仅 1.8%）；Hikari `borrow` 只 `park`；Dubbo / RocketMQ / Redisson / ShardingSphere / MinIO+OkHttp / Jackson 全零命中 | 记录在案，不设改造项 |
| **新揭示的硬约束** | `HintManager` 的持有者是 `com.alibaba.ttl.TransmittableThreadLocal`（⚠️ **票 08 修正**：本行原写 `extends InheritableThreadLocal`，不准确；TTL 继承自它）⇒ hint 必须与执行 SQL **在同一虚拟线程内** `getInstance()` + `close()`，且**扇出边界不得落在打开的 hint 作用域内**（VT 会继承该值，子线程再 `getInstance()` 直接抛） | **本轮已核当前成立**：只在 timeout-center（`TimeoutTaskRepository.java:133` try-with-resources），扫描扇出把落库留在槽位线程（`TimeoutTaskScanCase` 类注释 + `settle` 在槽位线程跑）⇒ 复核既有链路的活归[票 08](08-grill-scheduling-and-existing-fanout.md)（已复核：**契约完好**，见该票 C 节） |

**探测口径**：`jdk.VirtualThreadPinned` 必须 `threshold=0ms` 才收全（`default.jfc` 默认 20ms）；「零事件 ≠ 零 pin」（补偿型不产生事件，票 03 §3 缺口 1 + 票 04 实测修正）。

### 按工程的适用性表（票面 ⑤）

| 工程 | `spring.threads.virtual.enabled` | Dubbo `protocol.threadpool` | Hikari | 形态要点 |
| --- | --- | --- | --- | --- |
| `better-school`（8080，单体） | **翻** | **显式写 `virtual`**（票 07 定的值） | 显式 `maximum-pool-size: 10` + `connection-timeout: 3000` | 主战场：21 个消费者 / 全部 WS / MinIO / ES |
| `marketing-activity`（8082） | **翻** | 无 `@DubboService`（纯 consumer，无 provider 池可配） | 同上 | 1 个消费者 / 只受跨应用传播影响 |
| `marketing-asset`（8081） | **翻** | **显式写 `virtual`** | 同上 | 1 个 provider（兑换边）+ 1 个消费者 |
| `timeout-center`（8083） | **翻** | **显式写 `virtual`** | `24` / `3000ms`（已有，不动） | ShardingSphere 分片 + 已 VT 的扫描/注册链路 |

（表中前三行的 Hikari 列是**目标态**，见上方 ⚠️ 注记。）

### 转下游（收口时一并留痕）

- **[票 07](07-grill-dubbo-thread-model.md)**：`dubbo.protocol.threadpool` 的**值**由它定；本票已定「三 provider 必须显式写、不许吃 3.3.5 的默认连带」。→ ✅ **票 07 已 resolved（2026-09-21）：值为 `virtual`**（规则照旧）。⚠️ 值取 `virtual` 后，本票「压回 / 解耦」两条说法已反转，见上方裁决段的注记。
- **[票 08](08-grill-scheduling-and-existing-fanout.md)**：① 逐条核扇出并行度 ≤ 池；② 复核 `HintManager` 线程亲和是否被既有链路破坏（本票出发时核过一次：成立）。→ ✅ **票 08 已 resolved（2026-09-21）**：① 复核完（既有 5 处全部一致、仅记账；口径统一为「宽度 = 批大小，不加闸」）；② **契约完好**，并修正了本票表格里 `HintManager` 的 Holder 类型（TTL 非 `InheritableThreadLocal`）。⚠️ 本票**反向**得到一条注记：第 ③ 条裁决的 `connection-timeout: 3000` **尚未落地**（见上方 ⚠️）。
- **[票 09](09-grill-websocket-fanout.md)**：慢客户端阻塞写是补偿型 pin（每个卡住的会话占一个载体、池最多涨到 256），本票不设改造项——「并发上限 / 主动断开慢客户端」的扇出口径由它定。
- **[票 10](10-grill-observability-and-acceptance.md)**：⚠️ 翻开关后 **Tomcat 侧指标失明**——`AbstractEndpoint.getMaxThreads()` 变 `-1`（`internalExecutor=false`）、`getCurrentThreadsBusy()` 对 `VirtualThreadExecutor` 也返回 `-1`（它不是任何一种 `ThreadPoolExecutor`）⇒ 可观测**不能**靠 Tomcat 的 max/busy 线程数，得另找口径。
- **[票 11](11-task-write-spec.md)**：本节落 spec 的「框架开关与承压口径」节。
