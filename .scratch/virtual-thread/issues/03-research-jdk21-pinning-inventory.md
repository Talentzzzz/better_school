# 03 调研：JDK 21 pinning 雷区清单（本仓库依赖栈）

Type: research
Status: resolved
Blocked by:

## Question

为「框架开关的落地面与背压口径」（[票 05](05-grill-framework-flip-and-backpressure.md)）与「长连接 / WS 推送扇出裁决」（[票 09](09-grill-websocket-fanout.md)）提供事实基础。

背景：JDK 21 下虚拟线程在 `synchronized` 块内阻塞、或进入 native frame 时会**钉住载体线程**（JEP 491 到 JDK 24 才解除）。本仓库已知一处：`FileStoragePort.java:77` 的 `private synchronized void ensureBucket()` 把 MinIO 网络 I/O 包在监视器里（快速路径 `bucketReady` 命中时不进锁）。主源码全仓只有这一处 `synchronized`。

请对下列**依赖栈**逐个排查「这条调用路径会不会钉住载体线程」，每项给出：**会不会 pin、在什么条件下、怎么探测、怎么改（最小改法）**：

1. **MinIO SDK**（`io.minio` 8.5.17，底层 OkHttp）——`putObject` / `bucketExists` 等；重点是 OkHttp 内部的 `synchronized`（连接池、`Dispatcher`）
2. **Spring WebSocket**：`ConcurrentWebSocketSessionDecorator`（5s 发送上限 / 512KB 缓冲，`ChatWsPusher.java:30`）的内部锁类型与持锁范围；Tomcat 侧 WS 端点（`WsRemoteEndpointImplBase` 等）发送时的锁类型
3. **Tomcat 10.1.x / Spring Boot 3.3.5**：`spring.threads.virtual.enabled=true` 到底替换了哪些组件（协议处理器的 executor？哪些自动配置？），NIO poller / acceptor 是否仍是平台线程；Servlet 层与 pinning 相关的行为
4. **MySQL Connector/J**（版本以工程为准）：JDBC 调用的 pinning 状况——驱动内部 `synchronized`、`Object.wait`、native 帧
5. **HikariCP**：`getConnection()` 排队机制（`ConcurrentBag` / `SynchronousQueue` / 锁）在虚拟线程下会不会 pin；池满时的等待语义
6. **Redisson 3.52.0**：`RLock.tryLock`（`PostDetailCache.java:140-166`）在虚拟线程下的行为——线程归属 / reentrancy 是否仍成立、netty `Future.wait` 是否 pin、官方对虚拟线程的支持声明
7. **ShardingSphere-JDBC 5.5.x**：`HintManager`（ThreadLocal）在虚拟线程下是否成立；路由与执行链路上有无 `synchronized`
8. **Dubbo 3.3.6 tri**：消费与 provider 分发路上的 `synchronized`（与[票 01](01-research-dubbo-thread-model.md) 有交叠，可只写 pinning 结论并交叉引用）
9. **RocketMQ client 5.x**：`synchronized` 与 `Object.wait` 分布（与[票 02](02-research-rocketmq-consumer-executor.md) 交叠，同上）
10. **Elasticsearch Java client**：`bulk` / `search` 的 pinning 状况

**探测方法本身也要给**：JFR 事件名（`jdk.VirtualThreadPinned` 的事件字段与阈值参数）、如何在运行时收集与读栈；以及「pin 了但无害」与「pin 了会致命」的**判别标准**（载体线程池大小 vs pin 时长 vs 并发量）。

## Answer

⚠️ **2026-09-20 [票 04](04-task-measure-framework-flip.md) 实测回填**：本票从「MySQL 驱动协议层零 monitor」推出的「该路径不 pin」，在 **0ms 阈值**下不成立——实验组 40s 记录里 59761 个 `jdk.VirtualThreadPinned` **全部**来自 `com.mysql.cj.protocol.ReadAheadInputStream.fill()`（JDBC socket 读 → `NioSocketImpl.implRead → Poller.poll → parkOnCarrierThread`）。**结论不变**（属本票归的「补偿型」，载体池可扩到 256；时长 p50 1.28ms、超默认 20ms 阈值的仅 1.8%，不构成改造项），但措辞要收紧：**「零事件」不是「零 pin」——本票 §3 缺口 1 那条自警在 JDBC 路径上真的成立了**。

⚠️ **2026-09-21 [票 08](08-grill-scheduling-and-existing-fanout.md) 复核回填**（本票 ShardingSphere 一节的两处修正，`javap` 实证 + 本机 JDK 21.0.6 实测）：

1. **`HintManager` 5.5.3 的持有者是 `com.alibaba.ttl.TransmittableThreadLocal`，不是 `InheritableThreadLocal`**（TTL 继承自 `InheritableThreadLocal`）。本票与[票 05](05-grill-framework-flip-and-backpressure.md) 里「`extends InheritableThreadLocal`」的写法不准确。
2. **虚拟线程确实会继承 inheritable 值**（`Thread.ofVirtual()` 子线程可见父值，嵌套亦然；平台线程行为相同）⇒ 真正的雷不是「不会继承」，而是**「扇出边界落在打开的 hint 作用域内」**：子 VT 继承到那个已打开的 `HintManager` 实例后再调 `getInstance()`，会命中 `Preconditions.checkState(null == HOLDER.get(), "Hint has previous value, please clear first.")` **直接抛**。本仓今天没踩——仓储层是「每次 DAO 调用包一次 try-with-resources」，且 timeout-center 扫描扇出的子 VT 只发 Dubbo 不碰 DB。另实测 `close()` → `clear()` → `holder.remove()`（关闭后本线程的值确实被摘掉，之后可再次 `getInstance()`）。

调研报告：[research/03-jdk21-pinning-inventory.md](../research/03-jdk21-pinning-inventory.md)。核心结论：**全仓唯一需要为 pinning 动代码的地方就是本仓库自己那处 `FileStoragePort.ensureBucket()`**（`synchronized` 内包 MinIO 网络 I/O，`bucketReady` 快路径只挡住第二次之后的调用；改法＝`ReentrantLock` + 双检，报告 §4.3 给了完整片段）；10 个依赖项逐个落到「不 pin」或「补偿型/微秒级」，**票 05 的改造清单只剩这一项**。判据要从「有没有 pin」升级为「pin 的类别 × 并发量 × 时长」：JDK 21 只在**两条**情形 pin（`synchronized` 块/方法内、native 帧内，JEP 444 原文），而 `Object.wait`/文件 I/O/DNS 属于**捕获载体但由 `Blocker.begin()`→ForkJoinPool 扩池补偿**的另一类（`Object.java:363` / `Blocker.java` 一手源码），不拖吞吐只涨 OS 线程；载体池大小已核实＝`maxPoolSize = max(parallelism,256)`、`parallelism` 默认 `availableProcessors()`（本机 32），且**全进程共用一个池**（§1.2）。本机 JDK 21.0.6 实测（§1.4）：64 条虚拟线程各自 `synchronized{park(500ms)}` → 1047 ms（无补偿，串行两波，JFR 报 64 个 `jdk.VirtualThreadPinned`）；换成 `synchronized{wait(500)}`、`ReentrantLock+park`、纯 park → 均 507/502 ms 且 JFR 0 事件。**探测口径**：JFR `jdk.VirtualThreadPinned`（`default.jfc` 阈值 20 ms，`threshold=0ms` 可全收；`jfr print --stack-depth 64`；运行中 `jcmd JFR.start/dump`；粗线 `-Djdk.tracePinnedThreads=full`），但它是「park 时无法卸载」的子集——**不能把「零事件」当「零 pin」**（§3 缺口 1）。**给票 09 的锚点**：WS 发送路径**不致命 pin** —— Spring `ConcurrentWebSocketSessionDecorator` 全类零 monitor（`flushLock/closeLock` 是 `ReentrantLock.tryLock()`，拿不到就返回重试），Tomcat `WsRemoteEndpointImplBase` 源码注释明写「Actual write has to be outside sync block」（socket 写在 `messagePartLock` 之外）**但**下一层 Tomcat NIO 阻塞写（慢客户端写满缓冲）是 `synchronized(writeLock){ writeLock.wait(timeout) }`＝补偿型，代价是每个卡住的会话占一个载体、池最多涨到 256，故扇出仍须用并发上限/慢客户端断开兜（§2.2、§2.3）。**给票 05 的其余锚点**：`spring.threads.virtual.enabled=true`（Boot 3.3.5）实测只做三件事——Tomcat Connector `setExecutor(new VirtualThreadExecutor("tomcat-handler-"))`、`applicationTaskExecutor`/`TaskScheduler` 换 VT builder、Reactor `defaultBoundedElasticOnVirtualThreads`，而 **NIO acceptor/poller 恒为 `new Thread(...)` 平台线程**；Hikari 取连接池满等待＝`SynchronousQueue.poll`→park（不 pin，但 `ThreadLocal` 快路径在 VT 上失效、建连在 Hikari 自己的平台线程上）；Redisson `RLock.tryLock` 走 `CommandAsyncService.get`→`CompletableFuture.get`（不 pin；`RFuture.await` 那条 netty `DefaultPromise` 的 monitor+wait 路径本调用不走）；MinIO 强制 HTTP/1.1 + 定长 body 故 OkHttp/`Http2Writer` 雷区不可达；ShardingSphere 执行链路零 `synchronized`，但 `HintManager` 是 **TTL（`extends InheritableThreadLocal`）**，hint 必须在执行 SQL 的那个虚拟线程内 `getInstance()`+`close()`，跨线程靠继承会炸（`getInstance()` 二次调用直接抛）；MySQL 驱动协议层零 monitor；ES 同步等待是 httpcore `BasicFuture.get()` 的 `synchronized+wait`（补偿型，JFR 看不见）。Dubbo/RocketMQ 两项按票面要求只写 pinning 结论并交叉引用[票 01](01-research-dubbo-thread-model.md)/[票 02](02-research-rocketmq-consumer-executor.md)（均为零改造项，本轮另补了 `ResponseFuture`=CountDownLatch、三个热类零 monitor 两条实证）。全程一手来源：本机 `mvn_repo` 精确版本 jar 的字节码双扫（方法级 `synchronized` + 块级 `monitorenter`/`Object.wait`）、JDK 21.0.6 `src.zip` 与 `lib/jfr/default.jfc`、少量 GitHub 标签源码，URL 见报告。
