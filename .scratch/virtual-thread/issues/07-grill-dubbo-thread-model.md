# 07 grilling：Dubbo 两侧线程模型裁决

Type: grilling
Status: resolved
Blocked by: 01

## Question

Dubbo 两侧（provider 默认 `fixed` 200 池 × 4 工程、consumer 调用线程、超时中心的动态 `ReferenceConfig`）是否 / 如何虚拟线程化？待[票 01](01-research-dubbo-thread-model.md) 调研定案后拍板：

1. **provider 侧**：换不换、换成什么（票 01 给的可替换点）、4 个工程是否一致
2. **consumer 侧**：调用线程随宿主（宿主若是虚拟线程则自动受益，无需改造）——确认这个判断成不成立，以及有没有需要单独处理的地方
3. **动态 `ReferenceConfig` 边**（超时中心回调）：`TimeoutTaskCallbackReferences` 在并发 `computeIfAbsent` 下创建 `ReferenceConfig`，虚拟线程化后有无新的并发风险
4. **若判为不采用**：理由写清 + 可回收的触发条件

结论落 spec 的「RPC」节。

## Answer

**一句话：provider 池也虚拟线程化（`virtual`，3 个 provider 工程显式写）、consumer 侧没有可改对象、动态引用边无新增风险；代价（5 项）照实记账，可回收触发条件 4 条。**

> ⚠️ **本票经历过一次推荐反转**（2026-09-21）：初稿推荐 `fixed`（保 200 拒绝保护），被用户否掉两条论据后翻转为 `virtual`。被否掉的那两条论据原样留档在「决策依据」节——**不许再被引用**。

### 票面 ① provider 侧：**换** —— 3 个 provider 工程显式写 `dubbo.protocol.threadpool: virtual`

| 工程 | 落地 | yml 位置 |
| --- | --- | --- |
| `better-school`（8080，2 个 `@DubboService` 共享 50051 池） | `threadpool: virtual` | `dubbo.protocol`（`bs-app/src/main/resources/application.yml:54-56`） |
| `marketing-asset`（8081，1 个 `@DubboService`，50052） | `threadpool: virtual` | `dubbo.protocol`（`marketing-asset-app/src/main/resources/application.yml:45-47`） |
| `timeout-center`（8083，1 个 `@DubboService`，50054） | `threadpool: virtual` | `dubbo.protocol`（`timeout-center-app/src/main/resources/application.yml:48-50`） |
| `marketing-activity`（8082） | **无 `@DubboService` ⇒ 无 provider 池可配**（只作为 consumer 受「跨应用传播」影响） | — |

- 用**内置** `virtual`（`dubbo-plugin-loom` 已 shade 进核心 `dubbo` jar，票 01 §3.4 解包实证）：零依赖、零自研、一行生效。四个工程是否一致：**是**（3 个有池的都写同一个值）。
- ⚠️ `threadpool` **必须显式写**（票 05 的规则）：不写的话，票 05 翻的 `spring.threads.virtual.enabled=true` 会经 `DubboDefaultPropertiesEnvironmentPostProcessor` 把它设成 `virtual`（**结果相同但是隐式**）。显式写的意义见[票 05](05-grill-framework-flip-and-backpressure.md) 「Dubbo 的连带面」节里那条 ⚠️ 注记。

### 决策依据（含被否掉的两条论据）

**采纳 `virtual` 的理由 = 与票 05 翻 Tomcat 开关完全同构**：

| | Tomcat 200（票 05 已翻） | Dubbo provider 200（本票） |
| --- | --- | --- |
| 并发上限由什么决定 | 平台线程数（`server.tomcat.threads.max`） | 平台线程数（`threads` 默认 200） |
| 工作主体 | 阻塞 I/O（MinIO 上传 p50 2.2–2.8s / ES / DB） | 阻塞 I/O（DB） |
| 实测吞吐收益 | ≈0（票 04） | 无实测 |
| 翻的理由 | 消除平台线程上限 + 口径统一 | 同 |

留下的唯一实质差别是：**`fixed` 会在第 201 个并发请求上凭空拒绝**，而 DB 侧本来就有连接池兜着。

**⚠️ 被否掉的两条论据**（初稿的推荐依据。用户 2026-09-21 原话：「并发量级没有参考量，因为这只是在我笔记本上测的而已；并且压力下沉数据库连接池也不对，因为数据库限流不应该由dubbo来做」）：

1. ~~「provider 并发量级个位到数十 ⇒ 200 打不到 ⇒ 无收益」~~ **作废**。那个量级来自票 01 §7.2 的纸面推理，而票 01 §7.3 的标题就是「需要实测才能落槌的两个数」（该数从未实测）。更根本的是判据放错了地方：provider 的并发**由调用方决定**（三条边是 Web 并发、翻开关后上限是 Tomcat `max-connections` 8192；一条是超时中心扇出），不由「我们测到多少」决定 ⇒ 200 是**可打到的**，取消它才有意义。
2. ~~「VT 化把压力下沉到 Hikari 的慢失败，比立刻拒绝 + jstack 更糟」~~ **作废**。DB 限流不该由 Dubbo 池承担——放置本身就错了。且票 05 刚用「池已经是真上限；信号量是重复的限流器」否掉 Web 层信号量，同一个话术不能反过来用来保 Dubbo 池。

**选点规则的判读精化**（本轮确立，已回填 map 的 Notes 规则条）：判「并发上限由平台线程数决定」要看**上限由谁决定**，不看当前测到的量级；「并发已被连接池兜死 ⇒ 不用」**不适用于**「该处本就不该承担下游限流」的场合。

### 票面 ② consumer 侧：判断**成立**，无可改对象，零 Dubbo 配置

`TripleInvoker.doInvoke` 对 `isSync`（unary + SYNC）用 `ThreadlessExecutor`：发请求、`LockSupport.park` 等待、**就地**反序列化、回填 Future——**全程在调用方线程、不进任何池**（票 01 §2.4）⇒ 宿主是 VT 就免费，宿主是平台线程就照样占一条。所以「VT 化 consumer」在本仓**没有可改的对象**；能做的只有「把调用点放到 VT 上」，本仓已有 5 处先例（3 处 outbox 发送器 + 1 处扫描 + 1 处注册），且**零 Dubbo 配置**。

四条已核实的边界：

- 5 处静态 `@DubboReference`（主干）的宿主 = Tomcat 请求线程（票 05 翻开关后自动变 VT）⇒ 不改也受益；1 处动态引用的宿主 = 超时中心扫描的 VT。
- **本仓不存在「provider 线程里再发 RPC」的嵌套路径**：marketing-asset 的 provider（`PointsExchangeDubboService`）只调域内 `IPointsAccountService`；该工程唯一的 consumer 端口 `IUserQueryPort` 只被 `PingController`（HTTP）用（已 grep 核实）⇒ 不存在「平台线程里同步等 RPC」的隐藏占用。
- `RpcContext` / trace 是 `InternalThreadLocal`（**非** Inheritable）⇒ **不跨 VT 继承**（票 01 §5.1/§5.2）。本仓今天零使用（5 处都是薄 adapter，不设附件）⇒ 无影响；⚠️ 将来做请求级附件透传必须显式处理（属 map 的 Out of scope「上下文传播重构」）。
- 将来引入 streaming / 原生 async 回调，回调才落进 `streamExecutor` ⇒ 见可回收触发条件 ②。

### 票面 ③ 动态 `ReferenceConfig` 边（超时中心回调）：**无新增并发风险，零改造**

三条实据（前两条是现有设计，第三条是本轮**新核**）：

1. `references.computeIfAbsent(group, this::createReference).get()`（`TimeoutTaskCallbackReferences.java:33`）——映射函数 `createReference`（:36-52）是纯构造 + setter：**无 I/O、不碰 map、不递归 `callbackOf`** ⇒ 桶锁只持有微秒级；`ConcurrentHashMap` 保证每 key 恰好建一次，不存在死锁 / 递归 / 重复建。
2. 真正贵的 `ReferenceConfig.get()`（建代理、订阅注册中心）在 `computeIfAbsent` **返回之后**求值 ⇒ 落在**桶锁之外** ⇒ group A 首次建引用不阻塞 group B。VT 化不改变这个结构。
3. **`ReferenceConfig.init()` 在 3.3.6 用的是 `ReentrantLock`**（`ReferenceConfig.java:166` `protected final transient ReentrantLock lock`，配 `volatile initialized`），**不是 `synchronized`** ⇒ 多个 VT 并发首建同一 group 时，后到者在 `ReentrantLock` 上挂起会**卸载载体、不 pinning**。VT 化带来的唯一差别是「等待从占平台线程变成免费」，方向是变好。

附带核实：`setCheck(false)` 确实生效——`checkInvokerAvailable` 首行 `if (!shouldCheck()) { return; }`（`ReferenceConfig.java:717`）⇒ 业务方未上线时 init 不等、不炸，`TimeoutTaskCallbackReferences.java:40-42` 那条「顺序问题靠自愈」的注释成立（其注释自引的 timeout-center spec §7.5「降级照常、恢复追平」口径不变）。

### 票面 ④ 代价记账（本问因 ① 判「采用」而作废，转为「采用的代价」）

「不采用的理由 + 可回收触发条件」这一问的前提（判不采用）不成立；替代产出是这张代价表 + 下一节。

| 代价 | 处置 |
| --- | --- |
| **无界、无背压**（`newThreadPerTaskExecutor`） | 记账：上限口径 = **连接池 + `connection-timeout: 3000`**（票 05 已定，不加信号量）；**调用方**的并发由调用方自己管——超时中心那侧的扇出并行度归[票 08](08-grill-scheduling-and-existing-fanout.md) |
| `dubbo.protocol.threads` / `queues` **静默失效** | 记账：spec 记为**死参数**、禁写（写了没效果，且误导后人） |
| 丢 `AbortPolicyWithReport`（拒绝 + dump jstack） | 记账：丢掉的是「用 Dubbo 池给下游兜底」这件事本身（放置错误，见被否论据 2）。替代可观测口径归[票 10](10-grill-observability-and-acceptance.md)（与票 05 转出的「Tomcat 侧 max/busy 指标失明」同一节处理）。本仓未开 Dubbo metrics（全仓无 `metrics` 配置）⇒ 没有池指标口径需要平移 |
| `threadpool` 经注册中心**跨应用传播** ⇒ 4 个工程消费端 `streamExecutor` 全变 virtual | 记账：**今天零行为变化**（5 处静态 + 1 处动态引用全是 sync unary，根本不进 `streamExecutor`，票 01 §2.4/§2.8）；记为隐患，触发条件见 ② |
| provider 方法体从此跑在 VT 上 ⇒ 票 03 的 pinning 清单适用面**从 Web 层扩到 provider 层** | 结论不变：清单里唯一必修项仍是 `FileStoragePort.ensureBucket()`；JDBC socket 读 pin 已实测无害（票 04：59761 事件全来自 `ReadAheadInputStream.fill()`，p50 1.28ms、超阈值仅 1.8%） |

### 可回收触发条件

1. **Dubbo 升级到含 PR #16055（`threads.virtual.core`）的版本** ⇒ 一行换成**有界** VT 池，把上限表达能力拿回来。3.3.6 没有这个开关，所以本版**不自研**有界 VT：要往 4 个互相独立、无 reactor 的工程各放一份 `ThreadPool` SPI 实现 + SPI 文件（或另发一个制品），而扩展 id 会随 `threadpool` 参数经注册中心传播——哪个消费方没带这份 SPI，#13635 那种「启动期解析不到扩展」就会重演。为「有界」一个属性付这些不值。
2. **引入 streaming / 原生 async 回调** ⇒ `streamExecutor` 成为真路径，跨应用传播与池口径都要重判（票 01 §6 对本仓「没有一处是「发完还要做别的事」」的判断也要一并复核）。
3. **有代表性负载下观测到「无界接受把下游压垮 / RT 恶化」**，而连接池 + 3s 快失败兜不住 ⇒ 回头考虑自研有界池或信号量（此时触发条件 ① 的成本才被收益覆盖）。
4. **JDK 24 / JEP 491**（与 map 的 JDK 口径一致）：`synchronized` 不再 pin 之后，本票与票 03 的 pinning 账目可整体回收。

### 事实自核与三处修正

1. **票 05 的「provider 池的 200 上限在本仓从没被打到过」是纸面推理**（票 01 §7.3 明说该数待实测）⇒ 不作为任何结论的依据（见被否论据 1）。
2. **票 01 §5.3 的 `monitorenter` 扫描没覆盖 `dubbo-config-api`**（只扫了 `remoting`/`rpc`/`cluster`/`common`）⇒ 票面 ③ 那条边的 `ReferenceConfig` 是本轮补核的（`ReentrantLock`，不 pin）；已回填[票 03](../research/03-jdk21-pinning-inventory.md) 的 Dubbo 一节。
3. **provider 侧真正「一个线程被占满」的形态在批量回调的开销里，不在池的种类**：`TimeoutTaskCallbackDubboService.onTimeoutBatch`（`bs-trigger/.../rpc/TimeoutTaskCallbackDubboService.java:51`）对整批（最多 200 行）**逐项串行**做 DB 写，一个 provider 线程被占满**整批时长**。这是本仓唯一「provider 方法体长阻塞」的实例 ⇒ 转[票 08](08-grill-scheduling-and-existing-fanout.md)。

另：`timeout-center-app` 的测试源里有 2 个 `@DubboService` stub provider（`StubCallbackGroupAProvider` / `StubCallbackGroupBProvider`）与 1 个测试 `@DubboReference`——同受所在工程的配置面影响，无新增裁决。

### 转下游（收口时一并留痕）

- **[票 05](05-grill-framework-flip-and-backpressure.md)**：⚠️ **本票反转了票 05 第 ② 条裁决的落点**——票 05 的**规则保留**（三 provider 必须显式写、不许吃 3.3.5 的默认连带），但它给的**理由**（「压回」⇒ 解耦 Web 层开关与 VT 化 Dubbo Provider、且不传播就不会有人踩）在值为 `virtual` 后不成立：两者效果上没有解耦，显式写的意义退化成「自文档 + 抗将来只改一边」。已在票 05 的 `## Answer` 逐处加 ⚠️ 注记（一句话摘要 / 本节标题 / 裁决段 / 跨应用传播段 / 适用性表 / 转下游），反转后本票据内不再有残留的「压回」断言。
- **[票 08](08-grill-scheduling-and-existing-fanout.md)**：`TimeoutTaskCallbackDubboService.onTimeoutBatch` 的逐项串行循环是本仓唯一「一个 provider 线程占满整批」的形态——若那张票的「串行扫表/循环并发化模板」要覆盖 Dubbo provider 的批量处理，这是唯一实例；语义上可并发（逐项隔离 + 三个关单动作都是「读状态 → 条件 UPDATE」的状态机，同批各项互不影响），但**顺序语义要按现状为基线**判。
- **[票 10](10-grill-observability-and-acceptance.md)**：① 与票 05 转出的「Tomcat 侧指标失明」合并成一节——Dubbo 侧同样失去「池打满即拒 + jstack」这个信号，替代口径（连接池等待 / `connection-timeout` 失败率）由它定；② provider 层现在是 VT，「平台线程数应当下降」的验收要把 provider 池算进去——但空载本来 0，所以看的是**峰值**不是常驻。
- **[票 11](11-task-write-spec.md)**：本节落 spec 的「RPC」节。⚠️ 落稿口径：只写**裁决**（3 个 provider 显式 `virtual`；consumer 侧无可改对象；动态引用边无新增风险；`threads`/`queues` 为死参数；上限口径 = 连接池 + `connection-timeout: 3000`）+ `virtual` 的代价表 + 四条可回收触发条件，**不搬落地步骤**（spec 不含代码改造步骤）。
