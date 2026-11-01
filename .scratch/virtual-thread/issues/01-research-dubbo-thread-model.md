# 01 调研：Dubbo 3.3.x 两侧线程模型与虚拟线程可行性

Type: research
Status: resolved
Blocked by:

## Question

为「Dubbo 两侧线程模型要不要/能不能虚拟线程化」（[票 07](07-grill-dubbo-thread-model.md)）提供事实基础。

本仓库现状：4 个工程全部 Dubbo 3.3.6 + tri 协议 + Nacos，**没配过任何线程参数**（全仓无 `dubbo.provider.threadpool` / `threads` / `executes`），provider 走默认 `fixed` 200 线程池；consumer 侧 `@DubboReference` 散在 `infrastructure/adapter/port/<域>/`（timeout 2s / 3s），另有一处**动态 `ReferenceConfig`**（超时中心回调边，按 group 路由、`setRetries(0)`、`setScope("remote")`，见 `timeout-center-infrastructure/.../port/callback/TimeoutTaskCallbackReferences.java:36-52`）。

请调研（只收事实与做法，选型判断留给票 07）：

1. **Dubbo 3.3.x 的线程模型全貌**：provider 侧有哪些线程池种类（`fixed` / `eager` / `cached` / `limited` / `direct`）、各自语义与默认值；consumer 侧调用发生在哪个线程（业务线程直发？独立线程池？）；两者与 tri 协议、Nacos 注册的交互
2. **虚拟线程支持现状**：Dubbo 官方对虚拟线程的支持程度（含 3.3.x / 3.4.x 的发布说明与社区 issue）——有没有可配置的虚拟线程池、有没有官方路线图、有没有已知阻塞点（netty / 序列化 / 连接池）。若需自研（自定义 `ExecutorService` / SPI 替换线程池工厂），给出**可替换点的具体位置**（类名 + SPI 名）
3. **`ThreadLocal` 相关的坑**：`RpcContext`、trace 上下文在虚拟线程分发下会怎样丢失/串味；Dubbo 自身分发路径上有没有 `synchronized`（与 JDK 21 pinning 相关）
4. **同步调用 vs 原生异步**：Dubbo 的 `async` / `CompletableFuture` 返回在虚拟线程语境下是否还有意义——本仓库 timeout-center spec 已论证「后台发送场景无线程可释放 → 原生异步是负资产」，验证这个判断在 Dubbo 语境下成不成立
5. **收益面估算**：provider 默认 200 平台线程 × 4 工程的账；本仓库 Dubbo 调用的实际并发量级（`@DubboService` 4 个、静态与动态 consumer 约 6 处）

## Answer

调研报告：[research/01-dubbo-thread-model.md](../research/01-dubbo-thread-model.md)。核心结论：**两侧是两套线程模型，不能当一件事办**——Provider 走真正的业务线程池（默认 `fixed` / 上限 200 / 队列 0 / `AbortPolicyWithReport` 打满即抛，线程懒创建故空载不占 200）；Consumer 的**同步 unary 调用根本不进池**，响应由业务线程自己在 `ThreadlessExecutor` 上 `LockSupport.park` 后就地反序列化回填（VT 在该点卸载载体，不 pinning）→ 「VT 化 consumer」在本仓库语境下**没有可改的对象**，只需在 VT 里发同步调用（已有 5 处先例），零 Dubbo 配置。**Dubbo 3.3.6 已内置虚拟线程池，零依赖零自研**：`dubbo.protocol.threadpool: virtual` 一行生效（已解包 3.3.6 发布 jar 实证 `VirtualThreadPool.class` 与 SPI `virtual=` 均在核心 `dubbo` jar 内）；且 3.3.5 起 `spring.threads.virtual.enabled=true` 会**自动**把它设为 `virtual`（`DubboDefaultPropertiesEnvironmentPostProcessor`，默认值优先级最低，可用显式配置压回）。**代价必须一并裁决**：3.3.6 的 `VirtualThreadPool` 是 `newThreadPerTaskExecutor` —— 无界、忽略 `threads`/`queues`、每任务新建 VT，等于把并发上限整体转移给下游（DB/下游配额），并**移除**原有「200 打满即拒 + jstack」的硬保护；池化变体 `threads.virtual.core`（PR #16055）**不在 3.3.6**。**JDK 21 pinning 风险不在 Dubbo 里**：对 3.3.6 全 jar 的 `remoting`/`rpc`/`cluster`/`common` 共 1856 个类做字节码扫描，`TripleInvoker`/`AbstractTripleClientStream`/`AbstractServerCallListener`/`DefaultFuture`/`AsyncRpcResult`/`ThreadlessExecutor`/`SerializingExecutor` 等热路径类 **0 命中 `monitorenter`**，命中项集中在校验/建池/建连接/扩展加载等生命周期路径 → pinning 清单只能从本仓库自身源码与其依赖得出（交 03）。**§6 的三条论据全部成立**：timeout-center spec「否掉原生 async」的判断在 Dubbo 语境下**原样保留即可**（sync 等待在 VT 上免费，async 反而多占一个 `streamExecutor` 平台线程、丢 `RpcContext`（`InternalThreadLocal` 不跨线程）、异常易静默）。**给票 07 的锚点**：① provider 池 VT 化 vs 拒绝，② 若采用则 `virtual` 还是自研有界 VT（自研挂钩点：`ThreadPool` SPI / `ExecutorRepository` SPI / `executor-management-mode=isolation` + `ServiceConfig.executor`），③ `spring.threads.virtual.enabled` 会连带 VT 化 Web 层（Tomcat）**且跨应用**改变消费端 `streamExecutor`（provider 的 `threadpool` 参数经注册中心传播，见 issue #13635/#13291），其连带面是否纳入本 effort。
