# 01 调研：Dubbo 3.3.x 两侧线程模型与虚拟线程可行性

_调研日期：2026-09-19 ｜ 对象版本：`org.apache.dubbo:*:3.3.6`（本仓库锁定值）｜ 协议：tri ｜ 注册中心：Nacos_

> 本文只收事实与做法，选型判断留给[票 07](../issues/07-grill-dubbo-thread-model.md)。
> 所有断言都尽量落到「源码行 / 官方文档 / 一手 issue」；凡本仓库实测或推断，均显式标注。
> 版本相关的关键源码取自 GitHub tag `dubbo-3.3.6`；行为相关的关键结论另用**本地解包的真实 3.3.6 二进制**（Maven 中央仓下载）复核过。

---

## 0. 结论速览（先看这 8 条）

1. **两侧是两套完全不同的线程模型**。Provider 有真正的业务线程池（`fixed`/`cached`/`limited`/`eager`/`virtual`，默认 `fixed`）；Consumer 的**同步 unary 调用根本不进任何池**——响应由业务线程自己在 `ThreadlessExecutor` 上就地跑完（反序列化 + 回填 Future）。所以「虚拟线程化 Dubbo」在两侧不是同一件事。
2. **Dubbo 3.3.6 已经内置虚拟线程池，零依赖、零自研**。`virtual` 是一个官方 `ThreadPool` SPI 实现（`VirtualThreadPool`，代码在 `dubbo-plugin-loom`，但**已被 shade 进 `org.apache.dubbo:dubbo` 核心 jar**）。开启只需一行 `dubbo.protocol.threadpool: virtual`。
3. **3.3.5 起 `spring.threads.virtual.enabled=true` 会自动把 `dubbo.protocol.threadpool` 设为 `virtual`**（`DubboDefaultPropertiesEnvironmentPostProcessor`）。即在本仓库，**打开 Spring 的 VT 开关 = 同时打开 Dubbo Provider 的 VT 线程池**，两者被官方代码焊死在一起（可用显式 `dubbo.protocol.threadpool` 覆盖，它是默认值优先级最低）。
4. **代价必须讲清**：3.3.6 的 `VirtualThreadPool` 是 `Executors.newThreadPerTaskExecutor(...)`——**每任务新建一个虚拟线程、完全无界、忽略 `threads` / `queues` / `corethreads`**。它把「并发上限」从 Dubbo 线程池**整体转移**给下游（DB 连接池 / 下游配额）。
5. **同时它顺手移除了一个硬保护**：默认 `fixed` 池是 `threads=200` + `SynchronousQueue`（队列长度 0）+ `AbortPolicyWithReport`（拒绝即抛 + dump jstack）。也就是说现状是「200 打满 → 立刻拒绝」，VT 化后变成「无上限接受 → 压力全部下沉」。
6. **`ThreadlessExecutor` + 虚拟线程是天然匹配的**：它用 `LockSupport.parkNanos` 等待，VT 在该点会卸载载体线程，**不 pinning**。同步调用在 VT 上阻塞 Dubbo 调用，代价接近零。
7. **Dubbo 自身的分发热路径上没有 `synchronized`**（本调研对 3.3.6 全 jar 的 `remoting` / `rpc` / `cluster` / `common` 包做了字节码扫描：`TripleInvoker`、`AbstractTripleClientStream`、`AbstractServerCallListener`、`AbstractServerTransportListener`、`DefaultFuture`、`AsyncRpcResult`、`ThreadlessExecutor`、`SerializingExecutor` **全部 0 命中 `monitorenter`**）。命中的都是生命周期/注册/扩展加载类（`FrameworkModel`、`ApplicationModel`、`ExtensionLoader`、`DefaultExecutorRepository` …）与条件启用的 filter。**JDK 21 pinning 的风险不在 Dubbo 里，在本仓库自己的代码与其它依赖里。**
8. **原生 async 在 VT 语境下确实是负资产**：sync 走业务线程、async 走 consumer 的 `streamExecutor`（默认 `cached`）。调用方已经是 VT 时，async 反而**多一次线程切换 + 丢 `RpcContext`（`InternalThreadLocal`，不跨线程）+ 异常静默风险**。timeout-center spec 的判断在 Dubbo 语境下**成立**（见 §6）。

---

## 1. 本仓库事实核对（先固化「现状」）

| 项 | 实测值 | 依据 |
|---|---|---|
| Dubbo 版本 | `3.3.6`，4 个工程各自在 pom 里用 `<dubbo.version>3.3.6</dubbo.version>` 声明 | `backend/*/pom.xml` |
| 协议 / 注册中心 | `dubbo.protocol.name=tri`、`port=50051`、`registry.address=nacos://127.0.0.1:8848` | `backend/better-school/bs-app/src/main/resources/application.yml` |
| 线程参数 | **全仓零配置**：无 `dubbo.provider.threadpool`、无 `dubbo.protocol.threadpool`、无 `threads` / `executes` / `dispatcher` / `executor-management-mode` | 全仓 grep |
| Spring VT 开关 | `spring.threads.virtual.enabled` **零命中** | 全仓 grep（yml/`*.java`） |
| Provider（4 个 `@DubboService`） | `UserQueryDubboService`、`TimeoutTaskCallbackDubboService(group=${spring.application.name})`（单体）；`PointsExchangeDubboService`（marketing-asset）；`TimeoutTaskRegisterDubboService`（timeout-center） | 4 处，与票面一致 |
| Consumer（静态 `@DubboReference`） | 5 处：`TimeoutTaskRegisterPort`(timeout 2000)、`PointsExchangePort`(3000)、marketing-activity 两个 `UserQueryPort`(3000)、marketing-asset `UserQueryPort`(3000) | 比票面「约 6 处」的静态部分是 5 |
| Consumer（动态 `ReferenceConfig`） | 1 处：`TimeoutTaskCallbackReferences`，按 `callbackGroup` 建引用并缓存，`setRetries(0)` / `setCheck(false)` / `setScope("remote")` / `setTimeout(2000)` | `timeout-center-infrastructure/.../port/callback/TimeoutTaskCallbackReferences.java` |
| 已有手写 VT | 5 处（3 个文件）：`TimeoutTaskScanCase:71`（槽位 fan-out，批内组间并行复用同一 executor）、`TimeoutTaskRegisterCase:40`、`TimeoutRegisterOutboxSender:65/90/148` | 主源码 grep |

**关键背景：本仓库没有任何显式线程配置 → 全部走 SPI 默认值 → Provider 是 `fixed` / 200 / 无队列。** 这条推断在 §2.7 用源码坐实。

---

## 2. Dubbo 3.3.x 线程模型全貌

### 2.1 四个可插拔点（先建立坐标系）

| 点 | SPI / 配置 | 管什么 | 我们关心的程度 |
|---|---|---|---|
| 派发策略 | `Dispatcher` SPI，`dubbo.protocol.dispatcher`，默认 `all` | IO 事件在 IO 线程 vs 业务线程的分工 | tri 协议下**几乎无关**（见 §2.6） |
| 线程池种类 | `ThreadPool` SPI，`dubbo.protocol.threadpool` / `dubbo.provider.threadpool`，`@SPI("fixed")` | 造出什么样的 `Executor` | **核心**，`virtual` 就挂在这里 |
| 执行器仓储 | `ExecutorRepository` SPI（`default` / `isolation`） | 池的**数量与归属**（按端口共享 vs 按服务隔离） | 决定有几个池、key 怎么算 |
| 执行器选择 | `ExecutorSupport`（`DefaultExecutorSupport` / `IsolationExecutorSupport`） | 「这次调用该用哪个池」 | 自研线程池时的挂钩点 |

`ThreadPool` 接口定义（3.3.6）：

```java
// dubbo-common/src/main/java/org/apache/dubbo/common/threadpool/ThreadPool.java
@SPI(value = "fixed", scope = ExtensionScope.FRAMEWORK)
public interface ThreadPool {
    @Adaptive({THREADPOOL_KEY})   // THREADPOOL_KEY = "threadpool"
    Executor getExecutor(URL url);
}
```

`dubbo-common` 自带四个实现（SPI 文件原文）：

```
# dubbo-common/src/main/resources/META-INF/dubbo/internal/org.apache.dubbo.common.threadpool.ThreadPool
fixed=org.apache.dubbo.common.threadpool.support.fixed.FixedThreadPool
cached=org.apache.dubbo.common.threadpool.support.cached.CachedThreadPool
limited=org.apache.dubbo.common.threadpool.support.limited.LimitedThreadPool
eager=org.apache.dubbo.common.threadpool.support.eager.EagerThreadPool
```

来源：<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-common/src/main/java/org/apache/dubbo/common/threadpool/ThreadPool.java>、
<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-common/src/main/resources/META-INF/dubbo/internal/org.apache.dubbo.common.threadpool.ThreadPool>

### 2.2 五种线程池种类的语义

| 值 | 实现类 | 语义 | 关键参数来源 |
|---|---|---|---|
| `fixed`（**默认**） | `FixedThreadPool` | 固定大小，启动即建满、不回收 | `threads`（默认 **200**）、`queues`（默认 **0** → `SynchronousQueue`） |
| `cached` | `CachedThreadPool` | 空闲 1 分钟回收，需要时重建 | `corethreads`、`threads`、`queues`、`alive` |
| `limited` | `LimitedThreadPool` | 只增不减（避免收缩后突发流量） | 同上 |
| `eager` | `EagerThreadPool` | 超过 core 先扩容到 max 再排队；队列满才拒绝 | 同上 |
| `virtual` | `VirtualThreadPool`（**需 loom 插件，3.3.6 核心 jar 已含**） | 每任务一个虚拟线程，**无界** | **全部忽略**，只读 `threadname` |

注意 `CommonConstants` 里还留着一个 `DEFAULT_THREADPOOL = "limited"` 常量，但**主源码里无人引用**（仅兼容包 `com.alibaba.dubbo.common.Constants` 与一个测试引用它）——真正生效的默认是 `@SPI("fixed")`。

来源：<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-common/src/main/java/org/apache/dubbo/common/constants/CommonConstants.java>

### 2.3 Provider 侧：池是怎么建出来的

`TripleProtocol.export(...)` 末尾显式建池（**tri 协议专属入口**）：

```java
// dubbo-rpc-triple/.../TripleProtocol.java  (export 方法尾部)
// init server executor
ExecutorRepository.getInstance(url.getOrDefaultApplicationModel())
        .createExecutorIfAbsent(ExecutorUtil.setThreadName(url, SERVER_THREAD_POOL_NAME));
```

到 `DefaultExecutorRepository.createExecutorIfAbsent(url)` 里：executor key = `ExecutorService.class.getName()`（provider 侧固定），second key = `String.valueOf(url.getPort())`（**按协议端口共享**，即整个应用一个 `dubbo-protocol-50051` 池），最终：

```java
protected ExecutorService createExecutor(URL url) {
    return (ExecutorService) extensionAccessor.getExtensionLoader(ThreadPool.class)
            .getAdaptiveExtension()          // ← 读 url 上的 "threadpool" 参数
            .getExecutor(url);
}
```

**这意味着 `dubbo.protocol.threadpool=virtual` 会顺着 protocol 参数进入 provider URL，直接换掉这个池。**

真正派发业务调用的是 `AbstractServerTransportListener`：

```java
// dubbo-rpc-triple/.../h12/AbstractServerTransportListener.java
executor = initializeExecutor(url, metadata);   // = getExecutor(url, url)
...
executor.execute(() -> { onPrepareMetadata(...); setHttpMessageListener(buildHttpMessageListener()); ... });

protected final Executor getExecutor(URL url, Object data) {
    return new SerializingExecutor(ExecutorRepository.getInstance(...)
            .getExecutorSupport(url)          // ← 第二个挂钩点
            .getExecutor(data));
}
```

其中 `DefaultExecutorSupport.getExecutor(data)` 就是 `executorRepository.getExecutor(url)`；`isolation` 模式下换成 `IsolationExecutorRepository`（key 从「端口」变成「服务三元组」，且 `ServiceConfig.executor` 可注入自定义 `Executor`）。外层 `SerializingExecutor` 只保证**同一 stream 内任务串行**，它用 `AtomicBoolean` + `ConcurrentLinkedQueue` 实现，**无 `synchronized`**。

`SerializingExecutor.run()` 每个任务前后会 `InternalThreadLocalMap.getAndRemove()` / 回填，即**框架自己在任务边界清理内部 ThreadLocal**。

来源：<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-common/src/main/java/org/apache/dubbo/common/threadpool/manager/DefaultExecutorRepository.java>、
<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-rpc/dubbo-rpc-triple/src/main/java/org/apache/dubbo/rpc/protocol/tri/h12/AbstractServerTransportListener.java>

### 2.4 Consumer 侧：同步 unary **不进池**（这条最容易被误判）

`TripleInvoker.doInvoke` 原文：

```java
// dubbo-rpc-triple/.../TripleInvoker.java
ExecutorService callbackExecutor =
        isSync(methodDescriptor, invocation) ? new ThreadlessExecutor() : streamExecutor;
ClientCall call = new TripleClientCall(connectionClient, callbackExecutor, ...);

private static boolean isSync(MethodDescriptor md, Invocation inv) {
    return UNARY.equals(md.getRpcType()) && InvokeMode.SYNC.equals(((RpcInvocation) inv).getInvokeMode());
}
```

`ThreadlessExecutor` 的自我说明：

> Tasks submitted to this executor through `execute(Runnable)` will not get scheduled to a specific thread … Those tasks are stored in a blocking queue and will only be executed when a thread calls `waitAndDrain()`, the thread executing the task is **exactly the same as the one calling `waitAndDrain`**.

`AsyncRpcResult.get()` 则：

```java
if (executor instanceof ThreadlessExecutor) {
    while (!responseFuture.isDone() && !threadlessExecutor.isShutdown()) {
        threadlessExecutor.waitAndDrain(Long.MAX_VALUE);
    }
}
```

`waitAndDrain` 内部用 `LockSupport.parkNanos(this, restTime)` 等待。

**结论：一次同步 tri 调用 = 业务线程直发 + 业务线程 park + 业务线程就地反序列化 + 业务线程返回。全程零池。**
（官方文档对 2.7.5 起的消费端模型描述一致：「业务线程将 Task 取出并在本线程中执行：反序列化业务数据并 set 到 Future」。）

> **对 VT 的直接含义**：`LockSupport.park` 在虚拟线程上会卸载载体线程，**不消耗平台线程**。所以「把消费端调用放进虚拟线程」既不额外花钱、也不需要改 Dubbo，业务量能直接涨。

来源：<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-rpc/dubbo-rpc-triple/src/main/java/org/apache/dubbo/rpc/protocol/tri/TripleInvoker.java>、
<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-common/src/main/java/org/apache/dubbo/common/threadpool/ThreadlessExecutor.java>、
<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-rpc/dubbo-rpc-api/src/main/java/org/apache/dubbo/rpc/AsyncRpcResult.java>、
<https://github.com/apache/dubbo-website/blob/master/content/zh-cn/overview/mannual/java-sdk/tasks/framework/threading-model.md>

### 2.5 Consumer 侧的 `streamExecutor`：默认 `cached`，只管 streaming / async

`TripleProtocol.refer` 会**预建**一个 consumer 侧池：

```java
private ExecutorService getOrCreateStreamExecutor(ApplicationModel applicationModel, URL url) {
    url = url.addParameter(THREAD_NAME_KEY, CLIENT_THREAD_POOL_NAME)
             .addParameterIfAbsent(THREADPOOL_KEY, DEFAULT_CLIENT_THREADPOOL);   // "cached"
    return ExecutorRepository.getInstance(applicationModel).createExecutorIfAbsent(url);
}
```

- consumer 侧池的 key 是 **全局共享一个**（`getConsumerKey` 返回 `Integer.MAX_VALUE` 的字符串）。
- 用途：非同步路径（streaming / `SERVER_STREAM` / `BI_STREAM` / 原生 async 回调）。
- 注意是 `addParameterIfAbsent`：**refer URL 上若已有 `threadpool` 就沿用**。

来源：<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-rpc/dubbo-rpc-triple/src/main/java/org/apache/dubbo/rpc/protocol/tri/TripleProtocol.java>、
`CommonConstants.DEFAULT_CLIENT_THREADPOOL = "cached"`

### 2.6 tri 协议与 dubbo 协议的差别（避免照搬 dubbo 协议的结论）

官方文档明确：「Dubbo 协议的和 Triple 协议目前的线程模型还并没有对齐」；**Triple 协议 Provider 端线程模型目前还比较简单，序列化和反序列化操作都在 Dubbo 线程上工作，IO 线程并没有承载这些工作。**

所以：

- dubbo 协议的 `dispatcher`（`all` / `direct` / `execution` / `message` / `connection`）那套 IO 线程 vs Dubbo 线程的五种分工，**对 tri 不适用**；本仓库配 `dispatcher` 没有意义。
- tri 的 Provider 侧只有「IO 线程（netty event loop）」与「业务线程池」两段，业务线程池由 `ThreadPool` SPI 决定 → **换 VT 的收益/代价都集中在这一个池上**。
- tri 的 Provider 池在 `TripleProtocol.export` 时就被创建（**哪怕该应用一个请求都没有**），但 `FixedThreadPool` 的线程是 ThreadPoolExecutor 懒创建的（见 §2.7），所以空载时不会真的占 200 个平台线程。

### 2.7 默认值汇总（本仓库当前实际生效的就是这一列）

| 参数 | 默认值 | 来源 |
|---|---|---|
| `threadpool` | `fixed` | `@SPI(value = "fixed")` |
| `threads` | **200** | `CommonConstants.DEFAULT_THREADS = 200` |
| `queues` | **0** → `SynchronousQueue` | `CommonConstants.DEFAULT_QUEUES = 0` |
| `corethreads` | 0 | `DEFAULT_CORE_THREADS` |
| 拒绝策略 | `AbortPolicyWithReport` | `FixedThreadPool` 构造参数 |
| `dispatcher` | `all` | `ThreadPool` 以外的派发策略，tri 下不适用 |
| consumer 池 | `cached` | `DEFAULT_CLIENT_THREADPOOL` |
| `executor-management-mode` | `default`（按端口共享） | 官方文档「线程池隔离」节 |

`FixedThreadPool` 实际代码（`threads` 同时作为 core 与 max）：

```java
return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
        blockingQueue,                            // queues==0 → new SynchronousQueue<>()
        new NamedInternalThreadFactory(name, true),
        new AbortPolicyWithReport(name, url));
```

`AbortPolicyWithReport.rejectedExecution` 的异常文案（常量池实测）：`"Thread pool is EXHAUSTED! Thread Name: %s, Pool Size: %d ..."`，并会**限流地 dump jstack**（`Semaphore guard` + `TEN_MINUTES_MILLS`）。也就是说：**现状下 provider 池打满是「立刻抛 + 打线程栈」，不是「排队等」**。

> **修正票面一处措辞**：票面写「provider 走默认 `fixed` 200 线程池」——准确说是「**上限 200、队列为 0、线程懒创建（按需增长到 200）**」。空载不占 200 个线程；`200` 是并发上限而不是常驻开销。

来源：<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-common/src/main/java/org/apache/dubbo/common/threadpool/support/fixed/FixedThreadPool.java>、
`CommonConstants.java`、`AbortPolicyWithReport` 字节码实测

### 2.8 `threadpool` 参数会跨进程传播（一个容易被忽略的耦合）

provider 注册到 Nacos 的 URL 里**带着 `threadpool` 参数**；consumer 侧 `RegistryDirectory` 会把 provider URL 与 consumer URL 合并，于是 `TripleProtocol.getOrCreateStreamExecutor` 的 `addParameterIfAbsent` 拿到的是**provider 声明的那个值**。两个官方 issue 直接印证：

- **#13291**「Triple 协议 consumer 端线程池线程数依赖于 provider 端配置」——consumer 的 `streamExecutor` 核心线程数实测等于 provider 的 `threads=1200`。作者结论：「Provider 对自己这边配置的合理线程数，对 Consumer 这一端可能就不合理了 … 可能会存在 OOM 风险。」
- **#13635**「Low version of consumer reports error when dubbo3.3 provider uses virtual threading」——3.3 provider 设 `threadpool=virtual`，3.2 consumer 直接 `IllegalStateException: urls to invokers error`，报错 URL 里可见 `threadpool=virtual`。维护者定位：需要把 `threadpool` 加进 `DefaultProviderFirstParams`。

> **对本仓库**：4 个工程同为 3.3.6，不会踩 #13635 的版本不一致坑；但要意识到「谁设了 `dubbo.protocol.threadpool=virtual`，谁就把消费端的 streamExecutor 也变成 virtual」——这是**跨应用**的副作用，不是本应用内的开关。

来源：<https://github.com/apache/dubbo/issues/13291>、<https://github.com/apache/dubbo/issues/13635>（3.3.6 的 `DefaultProviderFirstParams` 仍只含 `release/dubbo/methods/timestamp/tag`，即该修复**不在 3.3.6**）

---

## 3. 虚拟线程支持现状

### 3.1 官方时间线（全部一手）

| 时间 | 事件 | 版本 |
|---|---|---|
| 2023-09-26 | **PR #13111 "Support project loom as thread pool factory"** 合入 `3.3` 分支 | 3.3.0-beta.1 起 |
| 2023-10 | 3.3.0-beta.1 release notes 列 "Fully JDK 21 support and project loom thread model" | — |
| 2024-01-04 | maintainer AlbumenJ 在 #13608 明确：「Virtual threads will be supported in Dubbo 3.3.x; 3.3.0-beta.1 has supported it」 | — |
| 2025-03-14 | **PR #15220 "autoconfiguration virtual-thread-pool"** 合入 `3.3`（改 `DubboDefaultPropertiesEnvironmentPostProcessor`） | **3.3.5 起可用** |
| 2025-10-17 | **3.3.6 发布** | 本仓库版本 |
| 2026-01-28 | **PR #16055 "feat: enhance VirtualThreadPool with configurable minimum thread count"** 合入 `3.3`（对应 issue #16042） | **晚于 3.3.6，未发布到任何 3.3.x release**（截至调研日最新 3.3.x 仍是 3.3.6） |

issue #10768「Dubbo support project loom」已于 2024-02-18 以 COMPLETED 关闭。

来源：<https://github.com/apache/dubbo/pull/13111>、<https://github.com/apache/dubbo/pull/15220>、<https://github.com/apache/dubbo/issues/13608>、<https://github.com/apache/dubbo/pull/16055>

### 3.2 开启方式 A（显式）：`dubbo.protocol.threadpool: virtual`

issue #15156 里提问者先试了 `dubbo.protocol.executor` / `dispatcher` 等错误写法，最后在评论里给出正解：

```yaml
dubbo:
  protocol:
    threadpool: virtual
```

来源：<https://github.com/apache/dubbo/issues/15156>

### 3.3 开启方式 B（自动，3.3.5+）：`spring.threads.virtual.enabled: true`

`DubboDefaultPropertiesEnvironmentPostProcessor.createDefaultProperties` 里新增的 `setDubboVirtualThreadsProperty`：

```java
public static final String DUBBO_THREAD_POOL_PROPERTY   = "dubbo.protocol.threadpool";
public static final String VIRTUAL_THREAD               = "virtual";
public static final String SPRING_THREAD_POOL_PROPERTY  = "spring.threads.virtual.enabled";

private void setDubboVirtualThreadsProperty(Environment environment, Map<String, Object> defaultProperties) {
    String virtualEnabled = environment.getProperty(SPRING_THREAD_POOL_PROPERTY);
    if (ENABLED_VALUE.equals(virtualEnabled)) {          // "true"
        defaultProperties.put(DUBBO_THREAD_POOL_PROPERTY, VIRTUAL_THREAD);
    }
}
```

这个类是 `EnvironmentPostProcessor`（`getOrder() = LOWEST_PRECEDENCE`），写入的是 **`defaultProperties` 属性源 = 优先级最低**。所以：

- **`spring.threads.virtual.enabled=true` 会自动把 `dubbo.protocol.threadpool` 变成 `virtual`**（无需任何 Dubbo 配置）；
- 但 `application.yml` 里显式写 `dubbo.protocol.threadpool: fixed` 仍然**压得住**它 → 这是「想只 VT 化 Web 层、保留 Dubbo 固定池」的逃生口。

来源：<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-spring-boot-project/dubbo-spring-boot/src/main/java/org/apache/dubbo/spring/boot/env/DubboDefaultPropertiesEnvironmentPostProcessor.java>、
<https://github.com/apache/dubbo/issues/15158>（该 PR 的动机 issue）

### 3.4 本仓库实测：**不需要加任何依赖**

我没有停在文档上，把 Maven 中央仓的发布产物下载后解包核对（本机 `~/.m2` 里只有旧的 3.0.9，故直接取远端 jar）：

```
$ curl -O .../org/apache/dubbo/dubbo/3.3.6/dubbo-3.3.6.jar && unzip -l dubbo-3.3.6.jar | grep -i loom
  META-INF/dubbo-versions/dubbo-plugin-loom
  org/apache/dubbo/common/threadpool/support/loom/VirtualThreadPool.class
  META-INF/maven/org.apache.dubbo/dubbo-plugin-loom/pom.xml

$ unzip -p dubbo-3.3.6.jar "META-INF/dubbo/internal/org.apache.dubbo.common.threadpool.ThreadPool"
  fixed=... / cached=... / limited=... / eager=...
  virtual=org.apache.dubbo.common.threadpool.support.loom.VirtualThreadPool      ← 已注册

$ unzip -l dubbo-spring-boot-autoconfigure-3.3.6.jar   # 无 EnvironmentPostProcessor
$ unzip -l dubbo-spring-boot-3.3.6.jar
  META-INF/spring.factories
  org/apache/dubbo/spring/boot/env/DubboDefaultPropertiesEnvironmentPostProcessor.class
$ unzip -p dubbo-spring-boot-3.3.6.jar META-INF/spring.factories
  org.springframework.boot.env.EnvironmentPostProcessor=\
    org.apache.dubbo.spring.boot.env.DubboDefaultPropertiesEnvironmentPostProcessor
```

依赖链（从发布的 pom 读出）：`dubbo-spring-boot-starter` → `dubbo-spring-boot-autoconfigure` → `dubbo-spring-boot-autoconfigure-compatible` → **`dubbo-spring-boot`（含 post processor）** + **`dubbo`（含 `dubbo-plugin-loom`）**。

> **结论：本仓库 4 个工程只要在 `application.yml` 写一行（任一方式），`virtual` 线程池立即生效，零新增依赖、零自定义 SPI。**
> （`dubbo-plugin-loom` 之所以在核心 jar 里：根 pom 有 `loom` profile，`activation = jdk [21,)`，release 构建在 JDK 21 上跑，所以 shade 时被 includes 进 `dubbo-all`，产物 artifactId 就是 `dubbo`。本仓库 JDK 21 → 永远命中。）

### 3.5 `virtual` 的语义与代价（3.3.6 原文）

```java
public class VirtualThreadPool implements ThreadPool {
    @Override
    public Executor getExecutor(URL url) {
        String name = url.getParameter(THREAD_NAME_KEY, (String) url.getAttribute(THREAD_NAME_KEY, DEFAULT_THREAD_NAME));
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(name, 1).factory());
    }
}
```

逐条代价：

1. **无界**：`newThreadPerTaskExecutor` 不拒任何任务，**没有背压**。原来 `SynchronousQueue + AbortPolicyWithReport` 提供的「200 打满即拒 + jstack 告警」**消失**。
2. **`threads` / `queues` / `corethreads` 全部被忽略**：配 `dubbo.protocol.threads=200` 不会有任何效果（静默无效，容易误判）。
3. **不池化**：依赖 `ThreadLocal` 做缓冲缓存的库（FastJSON / Aerospike 等）在每个新 VT 上重新分配缓冲 → GC 压力上升（issue #16042 的实测数据见下）。
4. 线程名仍是 `dubbo-protocol-<port>` 前缀 + 序号，观测上可区分。
5. **不会 pinning**：VT 池只负责 `execute`，真正阻塞点在业务方法内部（见 §5）。

### 3.6 后续演进：池化变体（3.3.6 之后）

issue #16042 提议把 `VirtualThreadPool` 改成 `ThreadPoolExecutor(core, MAX_VALUE, keepAlive, SynchronousQueue, virtualFactory)`，理由是非池化 VT 让第三方库的 `ThreadLocal` 缓存失效、GC 压力显著上升；PR #16055 按「可选、默认关闭」落地（**不在 3.3.6**）：

```java
int threads = url.getParameter(THREADS_VIRTUAL_CORE, 0);   // 新增常量 THREADS_VIRTUAL_CORE = "threads.virtual.core"
if (threads > 0) {
    return new ThreadPoolExecutor(threads, Integer.MAX_VALUE, 0L, MILLISECONDS,
            new SynchronousQueue<>(), Thread.ofVirtual().name(name, 1).factory());
} else {
    return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(name, 1).factory());
}
```

> 若票 07 想要「有下界复用 + 无上限」的形态，**3.3.6 上没有这个开关**，只能自研（§4）或升级。

来源：<https://github.com/apache/dubbo/issues/16042>、<https://github.com/apache/dubbo/pull/16055>

### 3.7 已知阻塞点 / 坑清单

| 坑 | 内容 | 对本仓库是否相关 |
|---|---|---|
| #13635 混版本 | 3.3 provider 设 `threadpool=virtual` → 老版本 consumer 解析 provider URL 时找不到 `virtual` 扩展，启动期直接失败 | **不相关**（4 工程同为 3.3.6） |
| #13291 consumer 池被 provider 配置绑架 | consumer 的 `streamExecutor` 线程数/种类取自 provider 注册的 URL | 相关（§2.8）：设了 `virtual` 会**跨应用**改变消费端池 |
| #16042 ThreadLocal 缓冲失效 | 非池化 VT 使依赖 `ThreadLocal` 缓存的库反复重新分配（FastJSON 等） | 需要实测，属「收益面」而非阻塞点 |
| #16042 评论（RainYuY） | 「虚拟线程本来就不该被池化；根因是第三方库误用 ThreadLocal」——维护者社区对该 PR 方向本身有分歧 | 仅供票 07 参考 |
| netty / 序列化 / 连接池 | 没有查到「Dubbo VT 与 netty / 序列化 / 连接池」的官方阻塞性 issue；#13804 是「netty 4.100 支持虚拟线程，Dubbo 需适配」一类的跟进诉求，非阻塞 | 不相关 |
| 官方文档 | **Davado 官方文档目前没有任何「虚拟线程」章节**（`threading-model.md` 全文未提 VT）；唯一官方口径是 issue #15156 的评论与源码 | 票 07 若写 spec，需引用 issue/源码而非文档 |

---

## 4. 若要自研：可替换点（类名 + SPI 名）

按「侵入度从小到大」排列：

| # | 做法 | 具体位置（3.3.6） | 适用面 | 代价 |
|---|---|---|---|---|
| 1 | **直接用官方 `virtual`** | `dubbo.protocol.threadpool: virtual` | Provider 池（+ 经 URL 传播影响 consumer streamExecutor） | 无界、忽略 `threads` |
| 2 | **自定义 `ThreadPool` 实现** | SPI：`org.apache.dubbo.common.threadpool.ThreadPool`<br>SPI 文件：`META-INF/dubbo/internal/org.apache.dubbo.common.threadpool.ThreadPool`<br>接口方法：`Executor getExecutor(URL url)`，`@Adaptive({THREADPOOL_KEY})`<br>配置：`dubbo.protocol.threadpool=<你的 key>` | 同上；可做「有界 VT 池」「带并发上限的 Semaphore 包装」等 | 需要一个自己的 jar/模块 + SPI 文件；注意 provider 与 consumer **共用同一个 SPI 扩展 id**，而 consumer 侧会拿到 provider 注册的值（§2.8） |
| 3 | **换 `ExecutorRepository`** | SPI：`org.apache.dubbo.common.threadpool.manager.ExecutorRepository`，已注册 `default` / `isolation`；配置：`dubbo.application.executor-management-mode` | 改池的**数量与 key**（端口共享 vs 服务隔离），不改池的**种类** | 影响所有池；`isolation` 会按服务建池，池数量随服务数增长 |
| 4 | **换 `ExecutorSupport`** | `org.apache.dubbo.rpc.executor.ExecutorSupport`（`DefaultExecutorSupport` / `IsolationExecutorSupport`，由 `DefaultExecutorRepository.getExecutorSupport(url)` 按 `executor-management-mode` 返回） | 「这次调用该用哪个池」的细粒度路由 | 3.3.6 未把它做成 `@SPI`，替换需继承/改 `DefaultExecutorRepository`（侵入 Dubbo 内部） |
| 5 | **`ServiceConfig.executor`** | `executor-management-mode=isolation` 时可为单个服务注入任意 `Executor` | **只对 provider 生效，且必须开 isolation** | 4 个服务要各写一遍；与「按端口共享」的现状不一致 |
| 6 | **只 VT 化 consumer 调用点** | 不改 Dubbo：在业务侧用 VT 包住 dubbo 调用（本仓库已有 5 处先例） | 消费端 | 零 Dubbo 改动；`RpcContext` 需在 VT 内自设（§5.2） |

**动态开关**：`org.apache.dubbo.common.config.Configuration` / `ConfigurationUtils` 是 Dubbo 的配置抽象（`DynamicConfiguration` 可接 Nacos）。但 `ThreadPool` 扩展是在 URL 上按 `threadpool` 参数选择的，**池一旦建好不会热切换**（`DefaultExecutorRepository` 只会在池 shutdown 后重建）——想要「运行时可切」需要自定义 `ThreadPool` 实现内部持有一个可替换的 `Executor`。

---

## 5. `ThreadLocal` / `RpcContext` / pinning

### 5.1 `RpcContext` = `InternalThreadLocal`（不是普通 `ThreadLocal`）

```java
// dubbo-rpc-api/.../RpcContext.java
private static final InternalThreadLocal<RpcContextAttachment> CLIENT_RESPONSE_LOCAL = ...;
private static final InternalThreadLocal<RpcContextAttachment> CLIENT_ATTACHMENT      = ...;
private static final InternalThreadLocal<RpcContextAttachment> SERVER_ATTACHMENT      = ...;
private static final InternalThreadLocal<RpcServiceContext>    SERVICE_CONTEXT        = ...;
private static final InternalThreadLocal<CancellationContext>  CANCELLATION_CONTEXT   = ...;
```

`InternalThreadLocalMap` 的双路实现：

```java
private Object[] indexedVariables;
private static ThreadLocal<InternalThreadLocalMap> slowThreadLocalMap = new ThreadLocal<>();

public static InternalThreadLocalMap getIfSet() {
    Thread thread = Thread.currentThread();
    if (thread instanceof InternalThread) { return ((InternalThread) thread).threadLocalMap(); }
    return slowThreadLocalMap.get();                       // ← VT 走这条
}
```

**虚拟线程不是 `InternalThread`** → 必然走 `slowThreadLocalMap`（一个普通 `ThreadLocal`）→ **每个 VT 首次用到 `RpcContext` 时会分配一个 `Object[]`（`newIndexedVariableTable()`，约 33 槽）**。

- **正确性上没有问题**：每个 VT 有自己的 map，**不会串味**；`SerializingExecutor.run()` 在任务边界 `getAndRemove()`，`AbstractServerCallListener.invoke()` 的 finally 里 `RpcContext.removeContext()`，服务端上下文不会跨任务残留。
- **成本上是每次分配**：这是「不池化 VT」的典型代价（与 §3.6 的 #16042 同源），量级为「每调用一次若干十字节」，不是阻塞点。

### 5.2 不继承：跨 VT 会丢上下文

`InternalThreadLocal` / `slowThreadLocalMap` 都是 `ThreadLocal`（**非** `InheritableThreadLocal`），且 Dubbo 没有任何 VT 传播钩子。因此：

- 在**父线程**（Tomcat 请求线程 / 外层 VT）设置的 `RpcContext` 附件，**不会被新起的 VT 看到**；
- 反过来，Dubbo 在**发起调用的那个线程**上读 `RpcContext` 生成 attachment。所以：**谁调用 Dubbo，谁就要在自己线程上把 `RpcContext` 设好**。
- 本仓库受影响点很小：5 处静态 `@DubboReference` 都是极薄的 adapter（`infrastructure/adapter/port/<域>/`），调用方基本不设 `RpcContext`；`TimeoutTaskCallbackReferences` 走 `ReferenceConfig` 动态引用，同样不依赖 `RpcContext`。**但一旦未来引入「请求级上下文附件」（如灰度 tag、用户身份透传），跨 VT 的丢失就必须显式处理。**
- trace/MDC 同理——这属于 map 的 Out of scope（上下文传播重构），此处只固化事实。

### 5.3 Dubbo 分发路径上的 `synchronized`（JDK 21 pinning）

做法：把 `dubbo-3.3.6.jar` 全量解包，对 `remoting` / `rpc` / `cluster` / `common` 共 **1856 个 class** 跑 `javap -c -p`，统计含 `monitorenter` 的类：

```
命中（次数）: FrameworkModel(19)  ApplicationModel(8)  GlobalResourcesRepository(6)
              TriHealthImpl(5)  ExtensionLoader(4)  DefaultExecutorRepository(4)
              ShutdownHookCallbacks(3)  ScopeModel(3)  ScopeBeanFactory(3)  ConfigurationUtils(3)
              ServletStreamChannel(2)  CancellationContext(2)  ActiveLimitFilter(2)
              AbstractNettyConnectionClient(2)  AdaptiveLoadBalanceFilter(2)  FailbackClusterInvoker(1)
              TagStateRouter(1) / MeshRuleRouter(1) / ... (其余为路由/工具类各 1)
未命中（0 次）: TripleInvoker、AbstractTripleClientStream、AbstractServerCallListener、
              AbstractServerTransportListener、TripleClientCall、UnaryServerCallListener、
              TripleProtocol、DefaultFuture、AsyncRpcResult、ThreadlessExecutor、
              SerializingExecutor、FixedThreadPool、VirtualThreadPool
```

逐条判读（**pinning 只在「持锁期间阻塞」时才发生**）：

- **热路径零命中**：一次同步 tri 调用的收发、反序列化、Future 回填、线程池派发，**没有任何 `synchronized`**。
- `DefaultExecutorRepository`（4 处）：`createExecutorIfAbsent` 是 `synchronized` + `synchronized(LOCK)`，**只在建池时**执行，且池是懒建 + 长期缓存。
- `CancellationContext.cancel` / `notifyAndClearListeners`：**服务端每次调用都会碰**（`AbstractServerCallListener.invoke()` 里 restore/remove），但临界区只做 listener 列表操作，**不含阻塞调用** → 不构成 pinning。
- `AdaptiveLoadBalanceFilter.getExecutor()`：懒建一个 `ThreadPoolExecutor`，**仅自适应负载均衡开启时**才走到。
- `FailbackClusterInvoker.addFailed`：**仅 failback cluster**（本仓库未配）。
- `ActiveLimitFilter`：**仅配了 `actives` 时**生效。
- `AbstractNettyConnectionClient`：**建连接时**。
- 其余（`FrameworkModel` / `ApplicationModel` / `ExtensionLoader` / `Model*`）：**启动与扩展加载**，不在每调用热路径。

> **结论：JDK 21 的 pinning 风险不来自 Dubbo。** spec 要列的 pinning 点应当是**跑在虚拟线程上的业务代码与其依赖**（本项目自己的 `synchronized`、Hikari/Redisson/RocketMQ client 等在 VT 上被调用的部分）——这正是 map Notes 里「只修会被碰到的 pinning 点」的适用范围，但清单必须由 `03-research-jdk21-pinning-inventory.md` 从**本仓库源码**得出，不能从 Dubbo 身上找。

> 附注：JDK 24 / JEP 491 之后 `synchronized` 不再 pinning，与 map Notes 的 JDK 口径一致。

---

## 6. 同步调用 vs 原生 async（在虚拟线程语境下）

### 6.1 原生 async 的机制

`isSync(...)` 为 false（`InvokeMode.FUTURE` / 接口返回 `CompletableFuture` / streaming）时，`TripleInvoker` 用 `streamExecutor` 当回调执行器 —— 即 §2.5 那个 **consumer 全局池（默认 `cached`）**，而不是业务线程。所以 async 的收益是「业务线程不被占用去等」，代价是「多一个池 + 一次线程切换」。

### 6.2 三条论据 vs timeout-center spec 的判断

spec §7 原文口径：「**不用 Dubbo 原生异步**（future 异常不接即静默失败、`RpcContext` ThreadLocal 跨线程丢失，且后台发送场景无线程可释放——负资产）」。

| spec 论据 | 在 Dubbo 3.3.6 语境下成立吗 | 依据 |
|---|---|---|
| 「future 异常不接即静默失败」 | **成立**。sync 下异常以 `RpcException` / 业务异常直接抛；async 下失败进 `CompletableFuture`（或 `FutureContext`），不显式接就是静默；tri 的 `AsyncRpcResult` 还要自己判 `hasException()` | `AbstractServerCallListener`/`AsyncRpcResult` 语义；issue #15156 讨论中同样用法分歧 |
| 「`RpcContext` ThreadLocal 跨线程丢失」 | **成立且机制明确**：`RpcContext` 是 `InternalThreadLocal`，回调跑在 `streamExecutor` 的另一个线程上，**不继承**（§5.2） | `RpcContext.java` + `InternalThreadLocalMap.java` |
| 「后台发送场景无线程可释放 → 负资产」 | **成立**：调用方已是 VT 时，sync 的等待点 `LockSupport.parkNanos` **不占平台线程**；async 反而**额外交给 `streamExecutor` 一个平台线程**去跑回调 → 净亏 | `ThreadlessExecutor.waitAndDrain` + `TripleInvoker.isSync` |

**补充一条 spec 没写但同样成立的**：async 会把反序列化挪到 `streamExecutor`（`cached` 池，无界复用），这在 VT 化后**没有任何并发收益**，只是把工作从「免费的 VT」搬到「稀缺的平台线程」。

> **一句话给票 07**：本仓库所有 Dubbo 调用边都是「发起后要么立刻用结果、要么就是后台发送器」，**没有一处是「发完还要做别的事」**——原生 async 在这两类场景里都是负收益；VT + sync 是更优解。**这条 spec 判断可以原样保留，不需要改。**

---

## 7. 收益面估算

### 7.1 「200 × 4」这笔账要拆开看

- **不是 200 × 4 个常驻线程**：`FixedThreadPool` 是 `ThreadPoolExecutor`，core 线程**按需创建**（`threads` 同时是 core/max，但 `ThreadPoolExecutor` 只在有任务时建线程）。空载 = 各应用 0 个 `dubbo-protocol-*` 业务线程（netty IO 线程另计，且不属于本 effort 范围）。
- **上限是「每应用每协议端口 200」**：4 个工程里真正 export 服务的只有 3 个（better-school 2 个服务、marketing-asset 1 个、timeout-center 1 个）；marketing-activity 只消费不暴露。且因为 `executor-management-mode=default`，**同一应用内 2 个服务共享同一个端口池**（key = `url.getPort()`）。
- **真正的风险不是「线程太多」而是「200 打满即抛」**：`SynchronousQueue`（队列 0）+ `AbortPolicyWithReport` → 第 201 个并发请求不是排队，而是**立即失败 + dump jstack**。VT 化把这个模式换成「无界接受」——**问题从「拒绝」变成「压到 DB / 下游」**。这与 map 选点规则里「并发已被连接池或下游配额兜死 → 不用」的判据是同一件事的两面。

### 7.2 本仓库的实际并发量级（4 provider / 5 静态 consumer + 1 动态）

| 调用边 | 谁调 | 调用方线程 | 量级与特征 |
|---|---|---|---|
| marketing-* → better-school `IUserQueryService` | 抽奖 / 邀请 / 积分页 | **Tomcat 请求线程** | 用户请求驱动，并发 = Web 并发；`getBasic` 是单行查询，极短 |
| marketing-activity → marketing-asset `IPointsExchangeService.deduct` | 抽奖兑换 | **Tomcat 请求线程** | 有 DB 事务，比上面重 |
| better-school → timeout-center `ITimeoutTaskRegister` | 下单/发布的 outbox | **虚拟线程**（`TimeoutRegisterOutboxSender:65/90/148`） | 已在 VT 上；单次 2s 超时 |
| timeout-center → 业务 `ITimeoutTaskCallback` | 扫描 fan-out | **虚拟线程**（`TimeoutTaskScanCase:71` 槽位执行器 / 批内组间并行 `:102`） | **唯一可能把 provider 池打满的边**：一个 tick 内「16 槽 × 每槽循环拉批 200 行 × 批内按 group 并行」→ 对同一业务应用可瞬时打出远大于个位数的并发回调，每组 2s 超时 |

**判读**：前三条边的并发上限由 **Web 并发 / 下单量**决定，量级本来就是个位到数十，远达不到 200 —— 按选点规则「并发上限由平台线程数决定」的先决条件不成立。**只有第四条边（超时中心回调）存在「Dubbo provider 池是真实瓶颈」的形态**，且它的形态与 map 里 `AgentReviewDispatchConsumer` 那类「单个消费者同步阻塞数十秒」不同：这里是**短调用 × 高扇出**。

> 但也要注意：timeout-center 的回调边是**中心 → 业务**，被 VT 化的是**业务侧的 provider 池**；而中心侧发起方**已经是 VT**。所以票价在于「业务侧 provider 池的 200 上限在突发扇出下会不会成为拒绝源」——这需要 §7.3 的实测，不是纸面推理能定论的。

### 7.3 需要实测才能落槌的两个数

1. **突发扇出下 provider 池的 active 峰值**（决定 `fixed` 200 会不会真被打到拒绝）。
2. **VT 化后下游（Hikari / 下游服务配额）承受的并发峰值**（决定「抬高上限」是否反噬）。map 已立判据：**Hikari 池 ≥ 并行度**——VT 化后 Dubbo provider 侧也应纳入同一条约束。

---

## 8. 交给票 07 的事实清单

**可以直接用的（无需自研、无需新依赖）**

- `dubbo.protocol.threadpool: virtual` —— Provider 池换 VT，一行。
- `spring.threads.virtual.enabled: true` —— 自动达到上一条（3.3.5+），但**会连带**把 Web 层（Tomcat）也 VT 化，并**跨应用**影响消费端 `streamExecutor`（§2.8）。
- 逃生口：显式写 `dubbo.protocol.threadpool` 覆盖自动值（它是 defaultProperties，优先级最低）。

**必须一并裁决的代价（写进 spec 的「代价」栏）**

- 无界、无背压、`threads` 静默失效 → **并发上限整体转移到下游**，需要新的上限口径（map 的「并发上限与背压口径」正是这一节）。
- 失去 `AbortPolicyWithReport` 的「拒绝 + jstack」保护。
- consumer 侧 `streamExecutor` 默认 `cached`：同步 unary **根本不经过它**，所以「VT 化 consumer」在本仓库语境下**几乎没有可改的对象**——真要做，只有「在 VT 里发同步调用」（本仓库已有 5 处先例），而那**不需要任何 Dubbo 配置**。
- 池化 VT（`threads.virtual.core`）**不在 3.3.6**；若要「有下界复用」，只能自研 `ThreadPool` SPI 或升级 Dubbo 到含 #16055 的版本。

**票 07 需要回答的、本文无法替他回答的**

1. provider 池虚拟线程化 vs 拒绝（附自研 `ThreadPool` SPI 的成本）；
2. 若采用，`virtual` 还是自研有界 VT 池；以及 `threads` / 背压口径怎么定；
3. `spring.threads.virtual.enabled` 的连带面（Web 层）是否同时纳入本 effort 的裁决范围。

---

## 9. 来源清单

**源码（tag `dubbo-3.3.6`）**

- `ThreadPool` SPI：<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-common/src/main/java/org/apache/dubbo/common/threadpool/ThreadPool.java>
- SPI 注册表：<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-common/src/main/resources/META-INF/dubbo/internal/org.apache.dubbo.common.threadpool.ThreadPool>
- `FixedThreadPool`：<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-common/src/main/java/org/apache/dubbo/common/threadpool/support/fixed/FixedThreadPool.java>
- `VirtualThreadPool`（loom）：<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-plugin/dubbo-plugin-loom/src/main/java/org/apache/dubbo/common/threadpool/support/loom/VirtualThreadPool.java>
- `DefaultExecutorRepository`：<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-common/src/main/java/org/apache/dubbo/common/threadpool/manager/DefaultExecutorRepository.java>
- `IsolationExecutorRepository`：<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-common/src/main/java/org/apache/dubbo/common/threadpool/manager/IsolationExecutorRepository.java>
- `ThreadlessExecutor`：<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-common/src/main/java/org/apache/dubbo/common/threadpool/ThreadlessExecutor.java>
- `SerializingExecutor`：<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-common/src/main/java/org/apache/dubbo/common/threadpool/serial/SerializingExecutor.java>
- `InternalThreadLocalMap`：<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-common/src/main/java/org/apache/dubbo/common/threadlocal/InternalThreadLocalMap.java>
- `CommonConstants`（默认值常量）：<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-common/src/main/java/org/apache/dubbo/common/constants/CommonConstants.java>
- `ProtocolConfig` / `ProviderConfig` / `ConsumerConfig`（`threadpool` 字段）：<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-common/src/main/java/org/apache/dubbo/config/ProtocolConfig.java>
- `ConfigValidationUtils`（`threadpool` 扩展校验）：<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-config/dubbo-config-api/src/main/java/org/apache/dubbo/config/utils/ConfigValidationUtils.java>
- `TripleProtocol`（export 建池 / refer 建 streamExecutor）：<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-rpc/dubbo-rpc-triple/src/main/java/org/apache/dubbo/rpc/protocol/tri/TripleProtocol.java>
- `TripleInvoker`（`isSync` → `ThreadlessExecutor`）：<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-rpc/dubbo-rpc-triple/src/main/java/org/apache/dubbo/rpc/protocol/tri/TripleInvoker.java>
- `AbstractServerTransportListener`（`SerializingExecutor` + `ExecutorSupport`）：<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-rpc/dubbo-rpc-triple/src/main/java/org/apache/dubbo/rpc/protocol/tri/h12/AbstractServerTransportListener.java>
- `AbstractServerCallListener`：<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-rpc/dubbo-rpc-triple/src/main/java/org/apache/dubbo/rpc/protocol/tri/h12/AbstractServerCallListener.java>
- `DefaultExecutorSupport`：<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-common/src/main/java/org/apache/dubbo/rpc/executor/DefaultExecutorSupport.java>
- `AsyncRpcResult`：<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-rpc/dubbo-rpc-api/src/main/java/org/apache/dubbo/rpc/AsyncRpcResult.java>
- `RpcContext`：<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-rpc/dubbo-rpc-api/src/main/java/org/apache/dubbo/rpc/RpcContext.java>
- `DubboDefaultPropertiesEnvironmentPostProcessor`（VT 自动开关）：<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-spring-boot-project/dubbo-spring-boot/src/main/java/org/apache/dubbo/spring/boot/env/DubboDefaultPropertiesEnvironmentPostProcessor.java>
- 根 pom（`loom` profile / `release` 含 `dubbo-plugin-loom`）：<https://github.com/apache/dubbo/blob/dubbo-3.3.6/pom.xml>
- `dubbo-all` pom（shade includes `dubbo-plugin-loom`）：<https://github.com/apache/dubbo/blob/dubbo-3.3.6/dubbo-distribution/dubbo-all/pom.xml>

**官方文档**

- 消费端线程模型，提供者端线程模型（含「Triple 协议 Provider 端线程模型目前还比较简单…序列化和反序列化操作都在 Dubbo 线程上工作」与「线程池隔离」节）：<https://github.com/apache/dubbo-website/blob/master/content/zh-cn/overview/mannual/java-sdk/tasks/framework/threading-model.md>
- 消费端线程池模型（历史快照）：<https://github.com/apache/dubbo-website/blob/master/content/zh-cn/docs/advanced/consumer-threadpool.md>

**Issue / PR**

- PR #13111（loom ThreadPool 工厂，2023-09-26 合入 3.3）：<https://github.com/apache/dubbo/pull/13111>
- PR #15220（`spring.threads.virtual.enabled` 自动配置，2025-03-14 合入 3.3，3.3.5 起）：<https://github.com/apache/dubbo/pull/15220>
- PR #16055（`threads.virtual.core` 池化变体，2026-01-28 合入 3.3，**不在 3.3.6**）：<https://github.com/apache/dubbo/pull/16055>
- PR #16055 的 issue #16042（非池化 VT 的 ThreadLocal 代价 + 社区分歧）：<https://github.com/apache/dubbo/issues/16042>
- #15156「How do I enable the virtual thread function in 3.3?」（正解 = `dubbo.protocol.threadpool: virtual`）：<https://github.com/apache/dubbo/issues/15156>
- #15158「希望 starter 支持 `spring.threads.virtual.enabled`」（PR #15220 的动机）：<https://github.com/apache/dubbo/issues/15158>
- #13608「Has dubbo released a version that supports jdk21?」（maintainer 口径）：<https://github.com/apache/dubbo/issues/13608>
- #13635「Low version of consumer reports error when dubbo3.3 provider uses virtual threading」（跨版本坑 + `threadpool` 参数经注册中心传播的实证）：<https://github.com/apache/dubbo/issues/13635>
- #13291「Triple 协议 consumer 端线程池线程数依赖于 provider 端配置」：<https://github.com/apache/dubbo/issues/13291>
- #10768「Dubbo support project loom」：<https://github.com/apache/dubbo/issues/10768>
- 3.3.6 release（2025-10-17）：<https://github.com/apache/dubbo/releases/tag/dubbo-3.3.6>

**发布产物（二进制核对）**

- `org.apache.dubbo:dubbo:3.3.6`：<https://repo1.maven.org/maven2/org/apache/dubbo/dubbo/3.3.6/dubbo-3.3.6.jar>
- `org.apache.dubbo:dubbo-spring-boot:3.3.6`：<https://repo1.maven.org/maven2/org/apache/dubbo/dubbo-spring-boot/3.3.6/>
- `org.apache.dubbo:dubbo-spring-boot-autoconfigure-compatible:3.3.6`（依赖链中间节）：<https://repo1.maven.org/maven2/org/apache/dubbo/dubbo-spring-boot-autoconfigure-compatible/3.3.6/>

**本仓库**

- `backend/better-school/bs-app/src/main/resources/application.yml`（Dubbo 配置全貌）
- `backend/*/pom.xml`（`<dubbo.version>3.3.6</dubbo.version>`）
- `backend/timeout-center/timeout-center-infrastructure/.../port/callback/TimeoutTaskCallbackReferences.java`（动态引用）
- `backend/timeout-center/timeout-center-case/.../task/TimeoutTaskScanCase.java:71,102`（已 VT 的扇出）
- `backend/better-school/bs-infrastructure/.../mq/TimeoutRegisterOutboxSender.java:65,90,148`（已 VT 的发送器）
- `.scratch/timeout-center/spec.md`（§7 关于「不用 Dubbo 原生异步」的既有论证）
