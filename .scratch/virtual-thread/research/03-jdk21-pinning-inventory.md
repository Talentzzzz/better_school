# 03 调研：JDK 21 pinning 雷区清单（本仓库依赖栈）

_票面：[issues/03-research-jdk21-pinning-inventory.md](../issues/03-research-jdk21-pinning-inventory.md)_
_日期：2026-09-19；JDK 口径：21（本机 `JAVA_HOME=C:\Code\JDK21` = 21.0.6+8-LTS），JEP 491/JDK 24 不在本 effort 范围_

## 0. 结论速览

严重度定义见 §1.5。**唯一「致命」项是本仓库自己的 `FileStoragePort.ensureBucket()`**；其余依赖栈要么不 pin，要么属于「占载体但有补偿 / 时长极短」。

| # | 调用路径 | 会 pin 吗 | 条件 | 严重度 | 最小改法 |
| --- | --- | --- | --- | --- | --- |
| 0 | **`FileStoragePort.ensureBucket()`（本仓唯一 `synchronized`）** | **会** | `bucketReady=false` 的窗口内，MinIO 网络 I/O（bucketExists/makeBucket/setBucketPolicy）全在监视器内 | **致命（并发上传时）** | `synchronized` → `ReentrantLock`（双检 + `bucketReady` 快路径不变），或把建桶收敛到启动期单线程预热 |
| 1 | MinIO SDK 8.5.17 / `putObject`、`bucketExists` | 否 | 强制 HTTP/1.1（`HttpUtils` 设 `protocols(HTTP_1_1)`）+ body 定长（`HttpRequestBody.contentLength()` 非 -1）→ 既不进 `Http2Writer`，也不是 chunked；OkHttp 的 `synchronized` 全在内存态簿记，无阻塞 I/O | 无 | 无需改 |
| 2 | Spring `ConcurrentWebSocketSessionDecorator`（5s/512KB） | 否 | 全类零 monitor：`flushLock`/`closeLock` 均是 `ReentrantLock.tryLock()`；被序列化的等待是「立刻返回 + 重试」，不是在锁上等 | 无 | 无需改 |
| 2 | Tomcat WS 发送 `WsRemoteEndpointImplBase` | 否 | 源码注释明确「Actual write has to be outside sync block」：`messagePartLock` 只包状态簿记，`writeMessagePart → doWrite` 在 monitor 外 | 无 | 无需改 |
| 2 | Tomcat NIO 阻塞写 `NioSocketWrapper.doWrite(block=true)`（慢客户端） | 会（但被 JDK 补偿） | 发送缓冲写满 → `synchronized(writeLock){ … Object.wait(timeout) }` | 中：占载体不拖吞吐，但并发慢客户端多时载体池涨到 256 | 无需改代码；票 09 用并发上限（如信号量/会话数）兜住 |
| 3 | Tomcat 10.1.31 线程面 | — | acceptor / poller 是 `new Thread(...)`，**永远平台线程**；`spring.threads.virtual.enabled=true` 只把 **Connector 的 executor** 换成 `VirtualThreadExecutor("tomcat-handler-")` | — | 见 §2.3 |
| 4 | MySQL Connector/J 8.3.0 | 基本否 | 协议层（`NativeProtocol` 等）零 `synchronized`；socket 读写走 `NioSocketImpl`（park，不 pin）。少量 monitor 在 `createNewIO`（建连）/`Blob`/`ConnectionWrapper.isValid`，且建连发生在 Hikari 自己的线程上 | 低 | 无需改 |
| 5 | HikariCP 5.1.0 `getConnection()` | 否 | 池满等待 = `SynchronousQueue.poll(timeout)` → `LockSupport.parkNanos`（不 pin）。`ProxyConnection.trackStatement/closeStatements` 是 monitor，但只包内存表操作 + `Statement.close()` 的短写 | 低（≈无） | 无需改 |
| 6 | Redisson 3.52.0 `RLock.tryLock(0, lease, SECONDS)` | 否 | `CommandAsyncService.get(RFuture)` = `toCompletableFuture().get()`（park）；`tryLock` 的等待分支是 `CompletableFuture.get(timeout)`；`lock()` 的阻塞分支是 `Semaphore.tryAcquire`（AQS park）。**注意**：`RFuture.await()/awaitUninterruptibly()`（netty `DefaultPromise`）才是 `synchronized + Object.wait`，本调用路径不走它 | 无 | 无需改 |
| 7 | ShardingSphere-JDBC 5.5.3 | 否（pinning 面） | `shardingsphere-jdbc` 全 jar + `sharding-core` 的 route/execute 包：方法级 `synchronized` 与 `monitorenter` 双扫零命中。**真问题是上下文**：`HintManager` 持有 `com.alibaba.ttl.TransmittableThreadLocal`（TTL 2.14.2 `extends InheritableThreadLocal`），见 §2.7 | 无（pin）/ 高（语义） | 见 §2.7 |
| 8 | Dubbo 3.3.6 tri | 否 | 与[票 01](01-research-dubbo-thread-model.md) 交叠，此处只记 pinning 结论并交叉引用 | 无 | 无需改 |
| 9 | RocketMQ client 5.3.2 | 否 | 与[票 02](02-research-rocketmq-consumer-executor.md) 交叠；本轮另核：`ResponseFuture` 等待是 `CountDownLatch.await`（park）；`MQClientInstance`/`DefaultMQProducerImpl`/`DefaultMQPushConsumerImpl` 方法级 `synchronized` 与 `monitorenter` 双扫零命中 | 无 | 无需改 |
| 10 | ES Java client 8.13.4（+ rest-client 8.13.4） | 否（但有占载体的 monitor wait） | 真实 I/O 在 Apache HttpAsyncClient 的 reactor 平台线程上；同步等待是 `CloseableHttpAsyncClient.execute(...)` 返回的 `Future.get()` = httpcore `BasicFuture.get()`，实现为 `synchronized` + `Object.wait` | 中：载体被占（有补偿），JFR 看不见 | 无需改；若并发 ES 调用大，用并发上限兜（✅ 2026-09-20 [票 05](../issues/05-grill-framework-flip-and-backpressure.md) 定的口径是**不加新信号量**：Hikari 池就是唯一真上限，「并行度 ≤ 池」的逐条复核归[票 08](../issues/08-grill-scheduling-and-existing-fanout.md)） |

---

## 1. 判据与探测方法（JDK 21 事实）

### 1.1 什么会 pin、什么不会

JEP 444 原文（<https://openjdk.org/jeps/444>，JDK 21 定稿版）：

> There are two scenarios in which a virtual thread cannot be unmounted during blocking operations because it is pinned to its carrier:
> - When it executes code inside a `synchronized` block or method, or
> - When it executes a native method or a foreign function.

> The scheduler does not compensate for pinning by expanding its parallelism. Instead, avoid frequent and long-lived pinning by revising `synchronized` blocks or methods that run frequently and guard potentially long I/O operations to use `java.util.concurrent.locks.ReentrantLock` instead. There is no need to replace `synchronized` blocks and methods that are used infrequently (e.g., only performed at startup) or that guard in-memory operations.

不 pin 的：`ReentrantLock`/AQS 全家（`CountDownLatch`、`Semaphore`、`SynchronousQueue`、`CompletableFuture.get`）、`LockSupport.park*`、`Thread.sleep`、JDK 21 的 socket I/O（`java.net.Socket` 阻塞读写在虚拟线程上走 `Poller` + park，释放载体）——JEP 444「Networking」节：「An operation on a virtual thread that blocks, e.g., to establish a network connection or read from a socket, releases the underlying platform thread to do other work.」

**一条容易被误读的差别（本轮实测拿到的一手事实）**：另有一类「捕获载体但被补偿」的阻塞，JEP 444 同节写明：

> …block both its carrier and the underlying OS thread. This is because of limitations at either the OS level (e.g., many filesystem operations) or the JDK level (e.g., `Object.wait()`). **The implementations of these blocking operations compensate for the capture of the OS thread by temporarily expanding the parallelism of the scheduler.**

补偿机制的源码：`java.lang.Object.wait(long)` 在 JDK 21 中包了一层 `Blocker.begin()/end()`（`src/java.base/java/lang/Object.java:363-375`），`Blocker` 的类注释（`src/java.base/jdk/internal/misc/Blocker.java`）：

> If invoked from a virtual thread and the underlying carrier thread is a CarrierThread then the code in the block runs as if it were run in `ForkJoinPool.ManagedBlocker`. This means the pool can be expanded to support additional parallelism during the blocking operation.

`Blocker.begin()` 的全部调用点（`grep -rn "Blocker\.begin"`，本机 src.zip）＝ `FileInputStream`/`FileOutputStream`/`RandomAccessFile`/`FileDescriptor`/`WinNTFileSystem`（文件 I/O）、`Object.wait`、`ProcessImpl`、`InetAddress`（DNS）、`MappedMemoryUtils`。**因此：`synchronized` 里做 park 类等待 = 真 pin（不补偿）；`synchronized` 里的 `Object.wait()`/文件 I/O/DNS = 捕获载体但扩池补偿，不拖垮吞吐，只涨 OS 线程。**

### 1.2 载体线程池大小（核实结论）

`java.lang.VirtualThread.createDefaultScheduler()`（JDK 21.0.6 源码，行 1145-1181）：

```java
int parallelism, maxPoolSize, minRunnable;
String parallelismValue = System.getProperty("jdk.virtualThreadScheduler.parallelism");
String maxPoolSizeValue = System.getProperty("jdk.virtualThreadScheduler.maxPoolSize");
String minRunnableValue = System.getProperty("jdk.virtualThreadScheduler.minRunnable");
if (parallelismValue != null) { parallelism = Integer.parseInt(parallelismValue); }
else { parallelism = Runtime.getRuntime().availableProcessors(); }
if (maxPoolSizeValue != null) { maxPoolSize = Integer.parseInt(maxPoolSizeValue); parallelism = Integer.min(parallelism, maxPoolSize); }
else { maxPoolSize = Integer.max(parallelism, 256); }
if (minRunnableValue != null) { minRunnable = Integer.parseInt(minRunnableValue); }
else { minRunnable = Integer.max(parallelism / 2, 1); }
```

- `jdk.virtualThreadScheduler.parallelism` 默认 = `Runtime.availableProcessors()` ——**票面要求核实的点，成立**。
- `jdk.virtualThreadScheduler.maxPoolSize` 默认 = `max(parallelism, 256)`；`jdk.virtualThreadScheduler.minRunnable` 默认 = `max(parallelism/2, 1)`。调度器是 FIFO 的 `ForkJoinPool`（`asyncMode = true`）。
- 本机 `availableProcessors() = 32` → 默认并行度 32、池上限 256。
- 另有 `jdk.unparker.maxPoolSize`（定时 unpark 的 `ScheduledThreadPoolExecutor`，默认 1，任务名 `VirtualThread-unparker`）。

> 注意：**「32 个载体」是全局共享的**——`VirtualThread` 的默认调度器是进程级单例，Tomcat 请求、WS 推送、MQ 消费、手写 fan-out 全在同一池里。这是本 effort 各票定并发上限时必须用的分母。

### 1.3 JFR 事件 `jdk.VirtualThreadPinned`：字段、阈值、发射条件

**发射点在 JDK 源码里只有一处**：`java.lang.VirtualThread.parkOnCarrierThread(boolean, long)`（`src/java.base/java/lang/VirtualThread.java:660-694`）：

```java
private void parkOnCarrierThread(boolean timed, long nanos) {
    VirtualThreadPinnedEvent event;
    try { event = new VirtualThreadPinnedEvent(); event.begin(); } catch (OutOfMemoryError e) { event = null; }
    setState(timed ? TIMED_PINNED : PINNED);
    try { if (!parkPermit) { if (!timed) U.park(false, 0); else if (nanos > 0) U.park(false, nanos); } }
    finally { setState(RUNNING); }
    setParkPermit(false);
    if (event != null) { try { event.commit(); } catch (OutOfMemoryError e) { } }
}
```

调用者只有 `park()`（无超时）与 `parkNanos(long)`／`parkNanos(blocker,…)` 的「yield 失败」分支（源码行 607、648、772）——即**只有「虚拟线程 park 时无法卸载」才会发射**。

由此得到的实操推论（本轮实测验证，见 §1.4）：

- `synchronized` + `LockSupport.park*`／AQS 等待 → **报**；
- `synchronized` + `Object.wait()` → **不报**（走 native `wait0`，不进 `parkOnCarrierThread`），也**不致命**（Blocker 补偿）；
- 纯 park / `ReentrantLock` → 不报（能卸载）；
- pin 时长 < 阈值 → 不报。

**字段**（`jfr print` 实测输出）：`startTime`、`duration`、`eventThread`（形如 `"A-sync-park" (javaThreadId = 43, virtual)`）、`stackTrace`（含续体边界帧 `VirtualThread.parkOnCarrierThread` / `Continuation.enterSpecial`，再往下是业务帧）。事件类本体在源码里是空的（`jdk.internal.event.VirtualThreadPinnedEvent extends Event`），字段由 JFR 元数据生成。

**阈值与开关**（`$JAVA_HOME/lib/jfr/default.jfc` 与 `profile.jfc` 第 75-79 行，JDK 21.0.6 实测文件）：

```xml
<event name="jdk.VirtualThreadPinned">
  <setting name="enabled">true</setting>
  <setting name="stackTrace">true</setting>
  <setting name="threshold">20 ms</setting>
</event>
```

即：**默认只报「pin 且 park ≥ 20 ms」**。要连亚毫秒 pin 一起收，把 `threshold` 调到 `0 ms`（或 `1 ms`）：

```bash
java -XX:StartFlightRecording=duration=60s,filename=vt.jfr,\
jdk.VirtualThreadPinned#threshold=0ms  -jar app.jar
# 运行中：
jcmd <pid> JFR.start name=pin duration=60s filename=vt.jfr jdk.VirtualThreadPinned#threshold=0ms
jcmd <pid> JFR.dump name=pin filename=vt.jfr
# 读事件（栈默认会被截断，务必给 --stack-depth）：
jfr summary vt.jfr | grep VirtualThreadPinned
jfr print --events jdk.VirtualThreadPinned --stack-depth 64 vt.jfr
```

**另一条更粗的探测线**（不走 JFR，直接打 stdout，包含 park 与补偿型两类，能拿到「拿监视器的帧」高亮）：

```bash
java -Djdk.tracePinnedThreads=full  -jar app.jar   # short 只打问题帧
```

`tracePinningMode()` 源码（`VirtualThread.java:1203-1212`）：空串或 `full` → 1，`short` → 2，其余 0（关闭）。JEP 444：「Running with `-Djdk.tracePinnedThreads=full` prints a complete stack trace when a thread blocks while pinned, highlighting native frames and frames holding monitors.」

**探测方法的边界（必须写进 spec 验收口径）**：JFR 事件**不是 pin 的全集**。它看不到 `Object.wait`/文件 I/O/DNS 这类「捕获载体但被补偿」的阻塞（实测无事件），也看不到 `synchronized` 内**不进 park** 的阻塞（例如 native 帧里的阻塞、`SocketInputStream` 在 JDK 21 恒走 park 所以能看到）。所以验收口径应为：**JFR 事件数 + 载体占用（`jdk.VirtualThreadPinned` 计数、`Thread.dump_to_file` 里的载体线程数）+ 吞吐对比**三条一起看，而不是「零 JFR 事件 = 零 pin」。

### 1.4 实测：pin 的三种命运（本机 JDK 21.0.6，parallelism=32）

判别标准不能只看「有没有 pin」，要看 **pin 的类别 × 并发量 × 时长**。实测数据（测试程序见 §4）：

| 组 | 64 个虚拟线程各自做 500 ms 的… | 实测墙钟 | JFR `VirtualThreadPinned` 事件数 | 解读 |
| --- | --- | --- | --- | --- |
| F | `synchronized { LockSupport.parkNanos(500ms) }` | **1047 ms** | **64**（duration ≈ 495 ms） | 真 pin：无补偿，64 > 32 → 两波串行 |
| G | `synchronized { o.wait(500ms) }` | **507 ms** | **0** | 捕获载体但扩池补偿 → 不串行，但池涨到 64 |
| H | `ReentrantLock { parkNanos(500ms) }` | **502 ms** | 0 | 正常卸载 |
| I | 纯 `parkNanos(500ms)` | **502 ms** | 0 | 正常卸载 |
| A | 单个 vthread：`synchronized { parkNanos(300ms) }` | — | 1（293 ms） | 有完整栈 |
| B | 单个 vthread：`synchronized { o.wait(300ms) }` | — | 0 | 与 G 一致 |
| C/D | `ReentrantLock`+park / 纯 park | — | 0 | 与 H/I 一致 |
| E | `synchronized { SynchronousQueue.poll(300ms) }` | — | 1（299 ms） | **Hikari 的排队若发生在 monitor 内就会 pin；好在它不在**（§2.5） |

### 1.5 「pin 了但无害」vs「pin 了会致命」的判别标准

用三个量判：

1. **pin 的类别**：`synchronized` 内是 **park 类等待**（AQS/`LockSupport`/`SynchronousQueue`/`CompletableFuture.get`）→ 不补偿，真 pin；是 **`Object.wait`/文件 I/O/DNS** → 补偿，占载体不拖吞吐（上限 `maxPoolSize`，默认 256，超过之后同样退化为排队）。
2. **并发量与载体池之比**：`同时 pin 的线程数 N` vs `parallelism`（默认 = CPU 核数 = 本机 32；maxPoolSize 只在补偿型上生效）。`N ≤ parallelism` 时真 pin 也几乎无感（实测：单条 pin 300 ms 无对手）；`N > parallelism` 时墙钟 ≈ `ceil(N/parallelism) × pin 时长`（实测 F：64/32 × 500 ms ≈ 1047 ms）。
3. **pin 时长 × 频率**：判据取「单次 pin 时长 ≥ 20 ms（JFR 默认阈值即暗合此口径）+ 每小时次数 ≥ 并发度量级」为要修；纳秒~微秒级 monitor（内存态簿记、`ConcurrentHashMap` 式操作）**不需要改**——JEP 444：「There is no need to replace `synchronized` … that guard in-memory operations.」

本仓库代入：本机 32 载体；单体是主战场（WS 扇出/23 个消费者/上传）。**只有 `FileStoragePort.ensureBucket()` 同时满足「非补偿型 + I/O 时长可达秒级 + 并发上传时 N 可 > 1 且会阻塞在首建桶」，其余项目都不满足「致命」。**

---

## 2. 逐项清单

### 2.1 MinIO SDK 8.5.17（`io.minio`，底层 OkHttp 4.12.0）

**结论：不 pin。** 两类证据：

1. **SDK 自身**：对 `minio-8.5.17.jar` 全 jar 扫方法级 `synchronized`，命中仅 6 处，全不在本仓库路径上——`credentials.AssumeRoleBaseProvider/ChainedProvider/IamAwsProvider.fetch()`（本仓库用静态 `credentials(accessKey, secretKey)`，不走它们）、`S3Base$ObjectIterator.populate()`（`listObjects` 懒翻页，若将来用它要注意：网络 I/O 在 monitor 内）、`MinioAsyncClient$1$1.populate()`、`io.minio.MinioProperties.setVersion()`。`monitorenter` 全 jar **零命中**。
2. **传输层**：MinIO 强制 HTTP/1.1 ——`io.minio.http.HttpUtils.newDefaultHttpClient(long,long,long)` 字节码里 `anewarray okhttp3/Protocol` + `getstatic okhttp3/Protocol.HTTP_1_1`，即 `OkHttpClient.Builder.protocols(HTTP_1_1)`；请求体是定长——`io.minio.HttpRequestBody.contentLength()` 返回 `PartSource.size()` 或 `byte[]` 长度（本仓库 `putObject(... .stream(in, content.length, -1))` → `objectSize` 已知 → 单次 PutObject、非 chunked）。
   - 于是 OkHttp 里唯一「socket 写发生在 monitor 内」的两处都用不上：`okhttp3.internal.http2.Http2Writer.*`（全方法 `@Synchronized`，`flush()` 内 `sink.flush()` = socket 写）只在 h2；`Http1ExchangeCodec$ChunkedSink.flush/close`（`synchronized`）只在 chunked body。
   - HTTP/1.1 定长 body 走 `newKnownLengthSink`，非同步。
   - OkHttp 其余 monitor 都是内存态：`Dispatcher.executed/enqueue$okhttp/promoteAndExecute/finished`（列表操作 + `executorService().execute`，无阻塞）、`RealConnectionPool.callAcquirePooledConnection`（扫连接列表）、`RouteDatabase.*`、`RealConnection.trackFailure`。**不含任何阻塞 I/O**；连接建立（`RealConnection.connect`）无 `monitorenter`，且 `ExchangeFinder.find` 只在簿记块里持锁。

**探测**：JFR 按 §1.3 开；若怀疑上传路径，重点看 `jdk.SocketWrite`（谁在写、多长）与 `Http1ExchangeCodec` 帧是否出现在 pinned 栈里。

**最小改法**：无需改。**若将来把 `listObjects` 放进虚拟线程链路，需复核 `S3Base$ObjectIterator.populate`（monitor 内网络 I/O）。**

### 2.2 WebSocket 发送路径

**(a) Spring `ConcurrentWebSocketSessionDecorator`（spring-websocket 6.1.14，`ChatWsPusher.java:30` 用 5s/512KB）：不 pin。**

- 全 jar 扫 `synchronized` **零命中**、`monitorenter` **零命中**、`Object.wait` **零命中**。
- 源码（<https://github.com/spring-projects/spring-framework/blob/v6.1.14/spring-websocket/src/main/java/org/springframework/web/socket/handler/ConcurrentWebSocketSessionDecorator.java>）：`flushLock`/`closeLock` 都是 `ReentrantLock`，且用 `tryLock()`——**锁被占时不等待而是立刻返回**（`sendMessage` 的 `do { if (!tryFlushMessageBuffer()) { checkSessionLimits(); break; } } while(...)`）；`buffer` 是内存队列 `add`。真正落到 `getDelegate().sendMessage(message)` 的 socket 写是在 `flushLock` 下做的，但 `ReentrantLock` 上的等待不 pin。
- 顺带（非 pinning，但对票 09 有用）：5s / 512KB 的越限判定发生在「拿不到 flushLock 的那次 send」和缓冲超限时，判定后 `limitExceeded → close(SESSION_NOT_RELIABLE)`；越限的关闭也在 `closeLock` 下。**发送本身不在 5s 内被打断**——5s 是「观察到的发送已耗时」，不是超时中断。

**(b) Tomcat `WsRemoteEndpointImplBase`（tomcat-embed-websocket 10.1.31）：不 pin。**

- 全 jar 方法级 `synchronized` 只有 `WsRemoteEndpointImplBase$StateMachine` 的 7 个方法（纯状态机，无 I/O）；`Object.wait` 零命中。
- `monitorenter` 命中里唯一沾发送路径的是 `WsRemoteEndpointImplBase.startMessage` / `endMessage`，而源码（<https://github.com/apache/tomcat/blob/10.1.31/java/org/apache/tomcat/websocket/WsRemoteEndpointImplBase.java>，行 384-441）写得明白：

```java
boolean doWrite = false;
synchronized (messagePartLock) {
    ...状态检查/入队...
    if (messagePartInProgress.tryAcquire()) { doWrite = true; } else { messagePartQueue.add(mp); }
}
if (doWrite) {
    // Actual write has to be outside sync block to avoid possible deadlock between
    // messagePartLock and writeLock in o.a.coyote.http11.upgrade.AbstractServletOutputStream
    writeMessagePart(mp);
}
```

即 **socket 写在 monitor 之外**，`messagePartLock` 只包簿记（队列入出 + `Semaphore.tryAcquire`）。
- 真正的阻塞点在下一层：**Tomcat NIO 的阻塞写**（见 (c)）。

**(c) Tomcat NIO 阻塞写 `NioEndpoint$NioSocketWrapper.doWrite(block=true)`：会 pin，但属补偿型。**

字节码（`tomcat-embed-core-10.1.31.jar`）：

```java
if (block) {
    long writeTimeout = getWriteTimeout();               // 默认 20000ms? 由 server.xml/protocol 决定
    ...
    synchronized (writeLock) {
        n = getSocket().write(from);                     // NioChannel.write
        if (n == 0 && (from.hasRemaining() || getSocket().getOutboundRemaining() > 0)) {
            if (!writeBlocking) { writeBlocking = true; registerWriteInterest(); }
            if (remaining > 0) { start = System.nanoTime(); writeLock.wait(remaining); }
            else { writeLock.wait(); }
        }
    }
}
```

- 触发条件：socket 发送缓冲写满（**慢客户端 / 大扇出时的 TCP 背压**）。
- 性质：`synchronized` + `Object.wait` → 按 §1.4 的 G 组，**JDK 21 会补偿（扩池），不拖垮吞吐；但每个卡住的会话吃掉一个载体，池最多涨到 `maxPoolSize`（默认 256），再多就排队**。JFR 的 pinned 事件**不会报**它。
- 探测：JFR 看不到 → 改用「载体线程数增长」+ `jdk.SocketWrite` 时长分布 + Tomcat 侧 `SocketWrapperBase.writeLock` 的线程 dump（`Thread.dump_to_file` 能看到 `Object.wait` 栈）。
- 最小改法：**不改代码**；票 09 的扇出裁决用「并发上限 / 每会话发送队列 + 主动断开慢客户端」兜住（`ConcurrentWebSocketSessionDecorator` 的 5s/512KB 已经在做一部分）。

### 2.3 Tomcat 10.1.31 / Spring Boot 3.3.5：`spring.threads.virtual.enabled=true` 到底换了什么

**版本**：Boot 3.3.5 BOM（本机 `spring-boot-dependencies-3.3.5.pom`）→ Tomcat 10.1.31、Spring Framework 6.1.14、HikariCP 5.1.0、MySQL 8.3.0、OkHttp 4.12.0、Jackson 2.17.2、elasticsearch-client 8.13.4。

**开关的判定**（`org.springframework.boot.autoconfigure.thread.Threading`，<https://github.com/spring-projects/spring-boot/blob/v3.3.5/…/thread/Threading.java>）：

```java
VIRTUAL { public boolean isActive(Environment environment) {
        return environment.getProperty("spring.threads.virtual.enabled", boolean.class, false)
                && JavaVersion.getJavaVersion().isEqualOrNewerThan(JavaVersion.TWENTY_ONE); } }
```

**换掉的东西（逐条给出判定类与证据）**：

| 被换的东西 | 判定/实现类（3.3.5 jar 实测） | 事实 |
| --- | --- | --- |
| **Tomcat Connector 的 executor（请求处理 = socket 处理任务）** | `TomcatVirtualThreadsWebServerFactoryCustomizer`（`@Order(1)`）→ `addProtocolHandlerCustomizers(ph -> ph.setExecutor(new org.apache.tomcat.util.threads.VirtualThreadExecutor("tomcat-handler-")))` | 字节码实证：`new VirtualThreadExecutor` + `ProtocolHandler.setExecutor`。线程名前缀 `tomcat-handler-` |
| `applicationTaskExecutor`（`@Async`/`TaskExecutor`） | `TaskExecutorConfigurations$SimpleAsyncTaskExecutorBuilderConfiguration`（`@ConditionalOnThreading(VIRTUAL)`）→ `SimpleAsyncTaskExecutorBuilder.virtualThreads(Boolean)` | 字节码实证。本仓库无 `@Async`（map Notes），当前是空转 |
| 任务调度器 | `TaskSchedulingConfigurations$SimpleAsyncTaskSchedulerBuilderConfiguration` | 同上机制 |
| Reactor 的 boundedElastic | `org.springframework.boot.reactor.ReactorEnvironmentPostProcessor`（spring-boot 核心 jar） | 字节码实证：读 `spring.threads.virtual.enabled` + Java ≥ 21 → 设系统属性 `reactor.schedulers.defaultBoundedElasticOnVirtualThreads`（本仓库不用 Reactor） |
| 若干 starter 的线程池形态 | `RabbitAnnotationDrivenConfiguration`/`KafkaAnnotationDrivenConfiguration`/`JedisConnectionConfiguration`/`LettuceConnectionConfiguration`/`PulsarAutoConfiguration` 上的 `@ConditionalOnThreading` | 本仓库用 Redisson（无 Lettuce），不涉 |

**NIO acceptor / poller 是否仍是平台线程：是。** 字节码实证（`tomcat-embed-core-10.1.31.jar`）：

- `AbstractEndpoint.startAcceptorThread()`：`new org.apache.tomcat.util.net.Acceptor(endpoint)` 后 `new java/lang/Thread(acceptor, name)` + `setPriority/setDaemon/start` → **`java.lang.Thread`，与 `useVirtualThreads`/protocol handler executor 无关**。
- `NioEndpoint.startInternal()`：`new NioEndpoint$Poller()` 后 `new java/lang/Thread(poller, name)` → 同样是平台线程。
- Tomcat 自身确有 `VirtualThreadExecutor`（`org.apache.tomcat.util.threads`）与 `StandardVirtualThreadExecutor`（`org.apache.catalina.core`，可用 `server.xml` 的 `useVirtualThreads` / `setUseVirtualThreads` 走 Tomcat 原生开关）；`AbstractEndpoint.createExecutor()` 的字节码也显示 `useVirtualThreads ? new VirtualThreadExecutor(...) : (TaskQueue + TaskThreadFactory + ThreadPoolExecutor)`。**Boot 的开关走的是「直接 `setExecutor`」这条路，不经过 Tomcat 的 `useVirtualThreads`**——两条路只生效一条，不要同时配。
- `VirtualThreadExecutor.execute()` = `jreCompat.threadBuilderStart(threadBuilder, command)`（反射调 `Thread.ofVirtual()` 的 builder），**无界、每任务一条虚拟线程**；`shutdown` 只有一个 `CountDownLatch`。→ 与 map 的 out of scope 一致：NIO 事件线程本就不该被虚拟线程化，正解是把阻塞工作从连接器线程上挪走。

**Servlet 层与 pinning 相关的行为**：`org.apache.coyote` 与 `util.net` 的方法级 `synchronized` 命中集中在 `AsyncStateMachine`（async 请求状态机）、`http2.*StateMachine/ConnectionSettings*`、`RequestGroupInfo`（统计）、`OpenSSLEngine`（仅当用 OpenSSL/APR 时才加载）——都是短 monitor，且 `AsyncStateMachine` 里没有阻塞调用。**结论：Tomcat 侧不需要为 pinning 改配置。** 唯一会「等」的写路径是 §2.2(c) 的 NioSocketWrapper 阻塞写（补偿型）。

### 2.4 MySQL Connector/J 8.3.0（版本由 Boot 3.3.5 BOM 管）

- **协议层零 monitor**：`com.mysql.cj.protocol.a.*`（`NativeProtocol`/`SimplePacketReader`/`SimplePacketWriter`/`NativePacketPayload`）方法级 `synchronized` 零命中，`monitorenter` 零命中；`protocol|jdbc` 全包 `Object.wait` 零命中。
- **socket 读写不 pin**：驱动用 `java.net.Socket`（`StandardSocketFactory`）→ JDK 21 虚拟线程走 `NioSocketImpl` + `Poller`（JEP 444「Networking」节；[票 02](02-research-rocketmq-consumer-executor.md) 亦已核实同一条）。
- **存在的 monitor（都不在业务 socket I/O 上）**：
  - `ConnectionImpl.createNewIO(boolean)`：**建连**（socket connect + 握手/认证）在 `ConnectionImpl` 监视器内。但取连接时新建物理连接是 **Hikari 自己的线程**在做（§2.5），不在调用方虚拟线程上 → 不构成我们的 pin。
  - `ConnectionImpl.getServerVersion/getCachedMetaData/registerStatement/closeStatement/...`：单行缓存访问，短 monitor（`registerStatement`/`closeStatement` 会被业务线程间接走到，纳秒级）。
  - `jdbc.Blob` 一票 `synchronized` 方法、`ConnectionWrapper.isValid/unwrap`、`MysqlConnectionPoolDataSource.getPooledConnection`、`ConnectionGroupManager.getConnectionGroupInstance`（static）——本仓库不用 Blob 流式接口/自带连接池。
  - `NativeSession.getCancelTimer()`（`queryTimeout` 用），仅取字段。
- **探测**：JFR 默认口径下应始终 0 事件；若命中，栈会给出具体驱动帧。
- **最小改法**：无需改。

### 2.5 HikariCP 5.1.0：`getConnection()` 的排队语义

- **池满等待不 pin**：`com.zaxxer.hikari.util.ConcurrentBag.borrow(long, TimeUnit)` 字节码：`threadList.get()`（`ThreadLocal` 本地缓存快路径）→ 扫 `sharedList` → `handoffQueue.poll(timeout, NANOSECONDS)`（`SynchronousQueue`）→ 内部 `LockSupport.parkNanos`。**全类零 `synchronized`、零 `monitorenter`、零 `Object.wait`**。等价于 §1.4 的 H/I 组。
- **全jar 的 monitor 只有 6 处，都不在阻塞 I/O 上**：`HikariPool.shutdown/suspendPool/resumePool/fillPool`（生命周期，运行在 Hikari housekeeper 线程）、`HikariPool$PoolEntryCreator.shouldContinueCreating()`（创建者线程）、`HikariJNDIFactory`（JNDI）、以及**唯一会被业务线程走到的** `ProxyConnection.trackStatement/untrackStatement/closeStatements`。
- **`ProxyConnection.closeStatements()`**：`private synchronized`，方法体内对 `FastList` 的每个 `Statement` 调 `Statement.close()`（字节码行 34/46）——即 **monitor 内有个「关语句」的短写（`COM_STMT_CLOSE` 无响应读）**。频率 = 每次归还连接；时长 = 微秒~毫秒级；无并发争用（一条连接同一时刻只有一个使用者）→ 按 §1.5 属「无需改」。
- **两个给票 05 的口径**（非 pinning）：
  1. **`ThreadLocal` 快路径在虚拟线程上失效**：`threadList` 的本地缓存是「该线程上次归还的连接」，虚拟线程每任务一条、用完即弃 → 每次 borrow 都要扫 `sharedList`/走 `handoffQueue`。功能无损（`requite` 会 `handoffQueue.offer`），但 Hikari 的「同线程复用」优化不再生效。
  2. **新建物理连接在 Hikari 自己的 `addConnectionExecutor`（`ThreadPoolExecutor`，平台线程）上**，不占用调用方虚拟线程：`HikariPool` 字段 `addConnectionExecutor`/`closeConnectionExecutor`/`houseKeepingExecutorService` 均为平台线程池；`ConcurrentBag.borrow` 只调 `listener.addBagItem(n)`（提交任务，非阻塞）。**所以「池大小 < 并发」时，代价是等（park）而不是建连**——这也是 spec 里「Hikari 池 ≥ 并行度」判据的机器面依据。

### 2.6 Redisson 3.52.0：`RLock.tryLock`（`PostDetailCache.java:140-166`）

**调用形态**（本仓库）：`lock.tryLock(0, LOCK_LEASE_SECONDS, TimeUnit.SECONDS)`（waitTime=0）+ 失败时 `Thread.sleep(LOCK_WAIT_MILLIS)` 重试 + `lock.unlock()`。

**逐段结论（字节码/源码）**：

| 段 | 等待机制 | pin |
| --- | --- | --- |
| `tryLock(0, lease, unit)` → `tryAcquire(...)` → `CommandAsyncService.get(RFuture)` | `future.toCompletableFuture().get()` —— `CompletableFuture.get` = AQS park | 否 |
| `tryLock` 的等待分支（waitTime>0 时） | `subscribe(threadId)` 后 `CompletableFuture.get(timeout)` | 否 |
| `lock()/lockInterruptibly()` 的阻塞分支（本仓库不用） | `RedissonLockEntry.getLatch()` 的 `Semaphore.tryAcquire/acquire`（AQS） | 否 |
| `Thread.sleep(LOCK_WAIT_MILLIS)` | `VirtualThread.parkNanos` | 否 |
| `unlock()` → `get(unlockAsync(...))` | 同上 `CompletableFuture.get` | 否 |

- 字节码实证（`redisson-3.52.0.jar`）：`org.redisson.command.CommandAsyncService` 的 `get(RFuture)` = `RFuture.toCompletableFuture()` + `CompletableFuture.get()`；`RedissonLock.tryLock(long,long,TimeUnit)` 里可见 `Thread.currentThread().getId()` + `tryAcquire(...)`（不拿到则 `subscribe(...)` + `CompletableFuture.get(timeout)`）；`CommandAsyncService`/`RedissonLock`/`RedissonBaseLock` 三者方法级 `synchronized` 与 `monitorenter` **双扫零命中**。
- **reentrancy / 线程归属仍然成立**：锁身份 = `Thread.currentThread().getId()`（`RedissonBaseLock.getLockName(long)` 字节码中的 `Thread.getId`）；虚拟线程是真 `Thread`、id 唯一 → 同一虚拟线程内可重入；跨虚拟线程不可重入（与平台线程语义一致）。
- **一个必须写进 spec 的坑**：Redisson 的同步等待不走 netty 的 `await`，但 **Redisson 内部其它路径会**——`RFuture.await()/awaitUninterruptibly()` 由 netty `DefaultPromise` 实现，而 netty 4.1.127（Redisson 3.52.0 的 `netty-bom` 版本，本机 `redisson-parent-3.52.0.pom`）源码是 `synchronized (this) { … wait(waitTime/1_000_000, …) }`（`common/src/main/java/io/netty/util/concurrent/DefaultPromise.java` 行 252-290、660、708；类注释直言「We are required to hold the monitor to use Java's underlying wait()/notifyAll()」）。→ 属 §1.4 的 G 组：**补偿型，不拖吞吐但占载体，且 JFR 看不见**。`CommandAsyncService.get` 另外硬性禁止从 `redisson-netty` 线程调用（`IllegalStateException`）。
- **官方对虚拟线程的态度**：`redisson-3.25.1` 发布说明里有且仅有一行 `Improvement - JDK21 Virtual Threads compatibility`（<https://github.com/redisson/redisson/releases/tag/redisson-3.25.1>，GitHub API 实取）；**3.52.0 的发布说明中未检索到虚拟线程相关条目**，也未在 Redisson 官方文档里找到「RLock 在虚拟线程下的行为」的正式声明——此项为明确的资料缺口（见 §3）。
- **探测**：JFR 对 `tryLock` 路径应 0 事件；若出现，栈里会是 `CompletableFuture`/`Semaphore` 帧而非 Redisson 帧。
- **最小改法**：无需改（本仓库 `waitTime=0`，本就不长时间持载体）。

### 2.7 ShardingSphere-JDBC 5.5.3：`HintManager` 与执行链路

- **pinning 面：干净。** `shardingsphere-jdbc-5.5.3.jar` 全 jar 方法级 `synchronized` 零命中；`shardingsphere-sharding-core-5.5.3.jar` 的 `sharding.route/execute` 包方法级 `synchronized` 与 `monitorenter` **双扫零命中**。
- **`HintManager` 不是普通 `ThreadLocal`**：字段声明（字节码）`private static final com.alibaba.ttl.TransmittableThreadLocal<HintManager> HINT_MANAGER_HOLDER`；本机 TTL 2.14.2 的类声明为 `TransmittableThreadLocal<T> extends java.lang.InheritableThreadLocal<T>`。
  - 语义后果（这是本项真正要交给票 05 的结论）：**hint 的传播靠「线程创建时的继承 + TTL 的 transmittee 包装」，不是靠显式参数**。手写 `Thread.ofVirtual().start(...)` 的 fan-out 不经过 TTL 的 `TtlRunnable`/agent 包装；能否继承取决于子线程对象是否在「hint 已设置的父线程」上构造。
  - **硬失败模式**：`HintManager.getInstance()` 先 `Preconditions.checkState(HOLDER.get() == null, "Hint has previous value, please clear first.")` ——同一线程二次 `getInstance()` 直接抛异常。子虚拟线程若继承了父线程的 hint，再调 `getInstance()` 会炸；反之父线程 `close()` 不会清理子线程副本。
  - **最小改法（写进 spec 的口径）**：hint 一律「在真正执行 SQL 的那个虚拟线程内部 `getInstance()` + try/finally `close()`」，跨虚拟线程传递一律显式（不要依赖继承）；fan-out 前后不得假设 hint 存在/不存在。
- ✅ **2026-09-20 [票 05](../issues/05-grill-framework-flip-and-backpressure.md) 已确认本仓库用法**：`HintManager` 只在 `timeout-center`（`TimeoutTaskRepository.java:133`，try-with-resources ⇒ `getInstance()` 与 `close()` 同线程），且扫描扇出把落库留在槽位线程（`TimeoutTaskScanCase` 类注释 + `settle` 在槽位线程跑，只有 Dubbo 调用在组线程）⇒ **hint 线程亲和当前成立，未被破坏**。spec 的「hint 必须与执行 SQL 同虚拟线程」口径照上一条写；后续复合复核归[票 08](../issues/08-grill-scheduling-and-existing-fanout.md)。

### 2.8 Dubbo 3.3.6 tri（与票 01 交叠，只记 pinning 结论）

**pinning 结论：不 pin，改造量 0。** 依据[票 01](01-research-dubbo-thread-model.md)：对 3.3.6 全 jar 的 `remoting`/`rpc`/`cluster`/`common` 共 1856 个类做字节码扫描，`TripleInvoker`/`AbstractTripleClientStream`/`AbstractServerCallListener`/`DefaultFuture`/`AsyncRpcResult`/`ThreadlessExecutor`/`SerializingExecutor` 等热路径类 **0 命中 `monitorenter`**，命中项集中在校验/建池/建连接/扩展加载等生命周期路径。consumer 同步 unary 的响应等待发生在业务线程自己的 `ThreadlessExecutor` 上 `LockSupport.park`（虚拟线程在该点卸载载体）。→ **票 05/07 的 pinning 清单不需要为 Dubbo 留改造项**；Dubbo 侧的决策点在「provider 线程池要不要 VT 化 / 无界代价」，见票 01 与票 07。

- ✅ **2026-09-21 [票 07](../issues/07-grill-dubbo-thread-model.md) 补一处扫描盲区**：上面那次 1856 类扫描只覆盖 `remoting`/`rpc`/`cluster`/`common` **四个包，`dubbo-config-api` 不在其中**——而超时中心的动态引用边（`TimeoutTaskCallbackReferences` 里按 `callbackGroup` 建的 `ReferenceConfig`）正落在这个包。补核结果：`ReferenceConfig.init()` 在 3.3.6 用 **`ReentrantLock`**（`ReferenceConfig.java:166` `protected final transient ReentrantLock lock`，配 `volatile initialized`），**不是 `synchronized`** ⇒ 多个虚拟线程并发首建同一 group 时，后到者在锁上挂起会卸载载体、**不 pin**。本清单**仍不为 Dubbo 留改造项**。

### 2.9 RocketMQ client 5.3.2（与票 02 交叠，只记 pinning 结论）

**pinning 结论：并发消费路径不 pin，改造量 0。** 依据[票 02](02-research-rocketmq-consumer-executor.md)：并发消费路径 0 处 `synchronized`，`ProcessQueue` 用 `ReentrantReadWriteLock`；唯一会 pin 的是 ORDERLY 的 `MessageQueueLock`（与「抬并发」方向相反）。本轮另核两条（`rocketmq-client-5.3.2.jar` / `rocketmq-remoting`）：

- 同步发送/拉取的等待是 `org.apache.rocketmq.remoting.netty.ResponseFuture`：`CountDownLatch.await(timeout)` + `countDown()`（AQS park，**不 pin**）；字段实证 `private final java.util.concurrent.CountDownLatch countDownLatch`。
- `MQClientInstance` / `DefaultMQProducerImpl` / `DefaultMQPushConsumerImpl` 三个热类：方法级 `synchronized` 与 `monitorenter` **双扫零命中**。
→ 票 05 的 pinning 清单不需要为 RocketMQ 留改造项；RocketMQ 侧的决策点在「消费 executor 换不换」，见票 02 与票 06。

### 2.10 Elasticsearch Java client 8.13.4（`elasticsearch-java` + `elasticsearch-rest-client`，Boot 3.3.5 BOM）

本仓库用法：`spring-boot-starter-data-elasticsearch` + `co.elastic.clients.elasticsearch.ElasticsearchClient`（`SearchRepository`：`bulk`/`search`/`index` 等同步方法）。

- **真实网络 I/O 不占我们的虚拟线程**：默认 transport 是 `RestClientTransport` 包 `RestClient`，底层是 Apache `HttpAsyncClient`（httpasyncclient 4.1.5）+ `httpcore-nio`（4.4.16）——I/O 在 reactor 平台线程上，selector 驱动。
- **同步等待是 monitor + `Object.wait`（补偿型）**：`RestClient.performRequest(Request)` → 私有 `performRequest(NodeTuple, InternalRequest, Exception)` 字节码为 `CloseableHttpAsyncClient.execute(producer, consumer, context, callback)` 后 `java.util.concurrent.Future.get()`；该 Future 是 httpcore 的 `org.apache.http.concurrent.BasicFuture`，其 `get()` 与 `get(long, TimeUnit)` 均为 `public synchronized`，内部 `this.wait(...)`。
  - 按 §1.4 的 G 组：**载体被捕获（HTTP 调用全程）+ FJP 扩池补偿**，不拖垮吞吐，但并发 ES 调用多时池涨到 `maxPoolSize`（默认 256）；**JFR 的 pinned 事件不报**。
- **`RestClient` 自身的 monitor 只有 `setNodes`**（改节点列表，配置路径）；请求路径零 `synchronized`/`monitorenter`。
- 序列化：`JacksonJsonpMapper` → Jackson 2.17.2。本轮扫 `ObjectMapper`/`SerializerCache`/`DeserializerCache`：仅 `SerializerCache._makeReadOnlyLookupMap/size/flush` 是 `synchronized`（缓存重建/配置变更时调用，无 I/O）→ **不构成 pin**。同一结论适用于 `ChatWsPusher` 里每次推送的 `objectMapper.writeValueAsString`。
- **最小改法**：无需改。若把搜索调用放进高并发 fan-out，用并发上限兜（补偿池 256 的上限 + 平台 reactor 线程数）——✅ 2026-09-20 [票 05](../issues/05-grill-framework-flip-and-backpressure.md) 已把口径定为「Hikari 池即唯一真上限、不加新信号量」，逐条复核归[票 08](../issues/08-grill-scheduling-and-existing-fanout.md)。
- **探测**：JFR 0 事件属预期；要观测占用就用 `Thread.dump_to_file -format=json` 数载体线程，或直接量 ES 侧并发下的墙钟。

---

## 3. 明确未证实 / 留白（诚实清单）

1. **JFR 事件不是 pin 的全集**（§1.3 边界）：`Object.wait`/文件 I/O/DNS 类捕获载体但被补偿的阻塞不产生事件，JFR 只覆盖「park 时无法卸载」。没有找到一手文档把这条边界写成文字（结论由「发射点源码只有 `parkOnCarrierThread`」+ 本轮 A/B/E 组实测推出）。**建议票 04/10 的验收口径不要写成「零 JFR 事件 = 零 pin」。**
2. **Apache HttpCore `BasicFuture.get()` 的 `Object.wait`**、**Tomcat `NioSocketWrapper` 阻塞写的 `writeLock.wait`**、**netty `DefaultPromise.await0` 的 `wait`** 三处的「补偿后不拖吞吐」结论，来自 §1.4 的**同类机制实测（G 组）**，未在「这三条真实路径 + 真实慢下游」下实测过时长分布。若票 04/09 要做真机数据，这三处是最值得量的。
3. **MySQL 驱动在「连接建立 / 认证慢」「网络抖动重传」下的 `createNewIO` monitor 时长**未实测；本轮只证实它不在调用方虚拟线程上（Hikari 的 `addConnectionExecutor`）。
4. **Redisson 官方对虚拟线程（尤其 RLock）的正式支持声明**：只找到 `redisson-3.25.1` 的一行 release note，`redisson-3.52.0` 的发布说明中未检索到虚拟线程条目；Redisson 官方文档未检索到「虚拟线程下的锁行为」专章。**这是资料缺口，不是「不支持」的证据**；源码事实（§2.6）已足够支撑本仓库的裁决。
5. **ShardingSphere 深度只扫到 route/execute**：`shardingsphere-sharding-core` 的 `merge`/`jdbc` 与 `infra-executor` 等包只做了方法级扫描（零命中），未逐类看 `monitorenter`；`shardingsphere-jdbc`（驱动壳）做了全 jar 双扫。若将来的票要更硬的保证，可把同款脚本对全部 5.5.3 相关 jar 跑一遍（✅ 2026-09-20 [票 05](../issues/05-grill-framework-flip-and-backpressure.md) 判定现有证据够用，未做这轮补扫）。
6. **JDK 24 / JEP 491**：本报告全程 JDK 21.0.6。JEP 491（<https://openjdk.org/jeps/491>）解除 `synchronized` pinning 后，§2.0 的 `FileStoragePort` 改造可回收——与 map 的 JDK 口径一致。

---

## 4. 复现材料（本轮所用方法与最小可复现件）

### 4.1 字节码扫描（本报告全部「零命中」结论的来源）

在 JDK 21 的 `javap` 上跑两个几秒级脚本（本机 `mvn_repo` 里的 jar 就是工程实际解析到的版本）：

```bash
# 方法级 synchronized / native 标志（含 Kotlin @Synchronized）
unzip -Z1 $JAR | grep '\.class$' | grep -v module-info | sed 's/\.class$//;s|/|.|g' \
  | grep -E "$FILTER" | xargs javap -p -classpath "$(cygpath -w $JAR)" | grep -E " synchronized "
# 块级 monitorenter / Object.wait（javap -c -p 后按方法归并）
javap -c -p -classpath "$(cygpath -w $JAR)" <classes...> | grep -E "monitorenter|java/lang/Object.wait"
```

坑：① 必须用 `cygpath -w`（Git Bash 的 `/c/...` 路径 `javap` 不认）；② 一次 `javap` 传多个类名（脚本见 `/tmp/syncscan2.sh`、`/tmp/monitorscan.sh` 思路），逐类起 JVM 会慢十倍；③ 方法级标志抓不到 `synchronized {}` 块，块级抓不到标志，**两个都要跑**（本报告里 OkHttp/Hikari/Tomcat 的关键结论都是块级扫描给的）。

### 4.2 pinning 判别实测（§1.4 数据）

```java
// PinTest.java：单条样本（A 组）
Object lock = new Object();
synchronized (lock) { LockSupport.parkNanos(Duration.ofMillis(300).toNanos()); }

// Bulk.java：并发样本（F/G/H/I 组，各 64 条虚拟线程、各 500ms）
runAll("F sync+park(500ms)",  k -> { Object l = new Object(); synchronized (l) { LockSupport.parkNanos(Duration.ofMillis(500).toNanos()); } });
runAll("G sync+wait(500ms)",  k -> { Object l = new Object(); synchronized (l) { try { l.wait(500); } catch (InterruptedException e) {} } });
runAll("H reentrant+park(500ms)", k -> { ReentrantLock l = new ReentrantLock(); l.lock(); try { LockSupport.parkNanos(Duration.ofMillis(500).toNanos()); } finally { l.unlock(); } });
runAll("I plain park(500ms)", k -> LockSupport.parkNanos(Duration.ofMillis(500).toNanos()));

// 运行 / 读数
// java -XX:StartFlightRecording=duration=8s,filename=bulk.jfr Bulk
// jfr summary bulk.jfr | grep VirtualThreadPinned   # F 组 64、G/H/I 组 0（本机 21.0.6）
```

### 4.3 本仓库已知 pin 点（票面给定事实的复核）

- 主源码 `synchronized` 唯一一处：`backend/better-school/bs-infrastructure/src/main/java/com/betterschool/infrastructure/adapter/port/file/FileStoragePort.java:77` `private synchronized void ensureBucket()` —— 复核成立（`grep -rn synchronized` 全 `src/main` 只有这一行）。
- `ChatWsPusher.java:30`：`new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS=5000, BUFFER_SIZE_LIMIT=512*1024)` —— 复核成立。
- 该 `ensureBucket()` 的最小改法（语义不变，锁换成不 pin 的可重入锁）：

```java
private final ReentrantLock bucketLock = new ReentrantLock();
private volatile boolean bucketReady = false;

private void ensureBucket() throws Exception {
    if (bucketReady) { return; }                 // 快路径不变，且不再进监视器
    bucketLock.lock();
    try {
        if (bucketReady) { return; }             // 双检
        ...原有的 bucketExists / makeBucket / setBucketPolicy 网络 I/O...
        bucketReady = true;
    } finally { bucketLock.unlock(); }
}
```

（`ReentrantLock` 上的争用会让虚拟线程正常卸载；若票 05 认为「首建桶本身就该在启动期单飞」，可改为启动期预热 + 运行期只读 `bucketReady`。）

> ✅ **2026-09-20 [票 05](../issues/05-grill-framework-flip-and-backpressure.md) 采纳上面的 `ReentrantLock` + 双检最小改法**（本版唯一 pinning 改造项）。
> ⚠️ 一处措辞修正（票 05 复核源码时发现）：「启动期预热」这个备选**在本仓已经以「软预热」形式存在**——`FileStoragePort.initBucket()` 带 `@PostConstruct`，启动时就会调一次 `ensureBucket()`，失败被 catch 吞掉（注释原文「测试环境无 MinIO 容器 / compose 未起时静默降级：首次上传时再尝试」）。所以「运行期只读 `bucketReady`」不成立（失败后必须能在首次上传时重试），两个备选不是二选一，改造只是把启动期与运行期共用的那条路径的 `synchronized` 换掉。这也解释了票 04 实测里 `ensureBucket` 一次都没进 pin 榜：正常环境下 `@PostConstruct` 已把 `bucketReady` 置真。
