# Spec：虚拟线程采用规范（virtual-thread）

_Status: final（票 05–10 全 resolved，2026-09-21 收口）_

本 effort 的终点文档：定义本仓库 4 个 Spring Boot 3.3.5 工程（JDK 21）**虚拟线程的采用规则与逐执行上下文裁决**。本文是**决策文档**：只写裁决、理由（内嵌证据）、可回收触发条件与验收口径，**不含代码改造步骤**——落地票在 spec 定稿后另拆（同目录，`Status: ready-for-agent`，照 timeout-center 惯例）。

**总纲**：表内「不采用」= 已裁决；重提前先反驳触发条件，不许当「还没看过」。

---

## 1. 采用规则

图绘期定稿（2026-09-19）的选点规则：

> 某处**并发上限由平台线程数决定**、且工作主体是**阻塞 I/O**（DB / RPC / HTTP / 对象存储），换虚拟线程能抬高上限或消除扇出串行化 → **用**。否则（CPU 密集、并发度本就 ≤ 个位数、并发已被连接池或下游配额兜死）一律**不用**。「能把并发抬高」与「抬高了有用」是两件事。

**判读精化**（票 07 确立，已回填 map；规则原文不改，只补判读）：

1. 「并发上限由平台线程数决定」看的是**上限由谁决定**，不是当前测到的量级——量级会被负载放大，开发机上的演示负载没有参考量。
2. 「并发已被连接池兜死 ⇒ 不用」**不适用于**「该处本就不该承担下游限流」的场合——DB 限流是连接池（+ `connection-timeout`）的事，不是 Dubbo provider 池、也不是 Web 层信号量的事。

**诚实声明（贯穿全文，不许包装）**：票 04 实测本仓翻开关**无吞吐收益**（on/off 差落在噪声内，真上限在连接池与 DB 延迟）；票 06/08 的两处大范围并发化依据的是**用户口径**（2026-09-20「该用就直接用，不用考虑已有的架构，本来就是开发阶段，能上的直接上」／2026-09-21「要改并行，上虚拟线程」），**不是性能论证**——按选点规则字面读，那两处本应判「不换」。spec 各节原样带这条，一切收益表述只许写「消除平台线程上限 + 口径统一」。

**JDK 口径**：留 21，只修会被碰到的 pinning 点；JDK 24 / JEP 491 后 `synchronized` 不再 pin，相关改造可回收（各节触发条件里标）。

## 2. 框架开关与承压口径（票 05）

### 2.1 落地面：翻开关在本仓只做一件事

| 开关可能换的东西（Boot 3.3.5） | 本仓是否生效 | 证据 |
| --- | --- | --- |
| Tomcat Connector `setExecutor(VirtualThreadExecutor)` | **生效**（唯一真实落地面） | `TomcatVirtualThreadsWebServerFactoryCustomizer`，字节码实证 |
| `applicationTaskExecutor` / `TaskScheduler` 换 VT builder | **空转**（零 `@Async`/`@Scheduled`/`TaskScheduler`） | 存在但无用武之地——**将来引入即自动变 VT，这是本表留的伏笔** |
| Reactor `defaultBoundedElasticOnVirtualThreads` | **空转**（不用 Reactor） | 同上 |
| NIO acceptor / poller | **不变**（恒平台线程） | 票 03 §2.3 |

**裁决：四工程统一 `spring.threads.virtual.enabled: true`。** 理由：① Web 层并发上限当前由 Tomcat 平台线程数（默认 200）决定，本仓有 MinIO 上传这类 p50 2.2–2.8s 的长阻塞 handler；② 与既有 5 处手写 VT 扇出口径一致。**必须原样带上：实测收益 ≈ 0**（票 04），不许包装成性能优化。

**两条硬约束**：不许配 `server.tomcat.threads.max`（翻开关后**静默失效**，JMX 读 `-1`）；不许混用 Tomcat 原生 `useVirtualThreads`（与 Boot customizer 两条路只生效一条）。

### 2.2 Dubbo 连带：显式写、不吃默认（值 = `virtual`，票 07 定）

3.3.5 起 `spring.threads.virtual.enabled=true` 会隐式把 `dubbo.protocol.threadpool` 设为 `virtual`。**规则：3 个 provider 工程显式写 `dubbo.protocol.threadpool: virtual`，不许吃默认连带**（值由票 07 定；显式写的意义 = 自文档 + 抗将来只改一边，效果上并非解耦）。连带面：`threadpool` 经注册中心跨应用传播 ⇒ 4 个工程消费端 `streamExecutor` 全变 virtual——**今天零行为变化**（全仓 6 处引用全是 sync unary，根本不进 `streamExecutor`），记为隐患（触发条件见 §4.2）。

### 2.3 承压上限与背压口径

翻开关的实质代价：Web 层并发上限从 200 变成 `server.tomcat.max-connections`（默认 8192）——Tomcat 200 在本仓一直兼任一道粗粒度背压，翻开关后入口限流器没了。

**裁决：连接池是唯一真并发上限，`connection-timeout` 决定慢失败时延，不加任何新的 Web 层 / 扇出 / 消费层信号量。** 三件落地口径：

1. 三个没配 Hikari 的工程把 `maximum-pool-size: 10` + **`connection-timeout: 3000`** 显式化（对齐 timeout-center 的 24 / 3000ms 先例）。⚠️ **这是目标态、不是既成事实**——今天单体与两营销服跑的是 Boot 默认（池 10、`connection-timeout` **30s**），所有「扇出宽度 vs 池」的论证都建立在显式化之后的口径上。
2. **池大小不为了 VT 而调**：池 10 / 24 不动（票 04 已证「抬池」是正交的独立收益，池 10 配 VT 实测不劣化）。池调优不属本 effort。
3. 「池 ≥ 并行度」不再作为设计约束写死——timeout-center 5 处既有扇出复核口径成立（池 24 ≥ 16 槽位），单体侧统一为「**池是唯一闸**」（§4.3）。

### 2.4 pinning 处置：本版只修一处

| 分类 | 项 | 处置 |
| --- | --- | --- |
| **必修（本版）** | `FileStoragePort.ensureBucket()`（`synchronized` 包 MinIO 网络 I/O） | 改 `ReentrantLock` + 双检（票 03 §4.3）。启动期 `@PostConstruct` 已软预热 ⇒ 这是**廉价保险**而非热点；JDK 24 / JEP 491 后可回收 |
| **补偿型，不改** | Tomcat NIO 阻塞写（慢客户端）；ES `BasicFuture.get()`；netty `DefaultPromise.await` | 不拖吞吐、只占载体（池上限 256）；**JFR 看不见**，别拿「零事件」当无反证。WS 那条由 §4.4 口径兜 |
| **已证明无害** | JDBC socket 读 pin（票 04：59761 事件全来自 `ReadAheadInputStream.fill()`，p50 1.28ms、超 20ms 阈值仅 1.8%）；Hikari `borrow` 只 `park`；Dubbo / RocketMQ / Redisson / ShardingSphere / MinIO+OkHttp / Jackson 全零命中 | 记录在案，不设改造项 |
| **硬约束** | `HintManager` 持有者是 `com.alibaba.ttl.TransmittableThreadLocal`；VT **会**继承 inheritable 值 ⇒ 真正的雷是**扇出边界落在打开的 hint 作用域内**（子 VT 再 `getInstance()` 直接抛） | 现契约完好（§4.3 C 节）；模板规矩：hint 不跨线程传递、扇出边界不落在打开的 hint 作用域内 |

**探测口径**：`jdk.VirtualThreadPinned` 必须 `threshold=0ms` 才收全（默认 20ms 会漏短 pin）；「零事件 ≠ 零 pin」——补偿型不产生事件。

## 3. 逐执行上下文裁决表

列结构（票 10 定，四列不动）：**上下文 / 裁决 / 理由（内嵌证据）/ 可回收触发条件**。适用工程写进上下文名，不立状态列。「不采用」行按三段式：证据 → 为什么 → 触发条件。

| # | 上下文 | 裁决 | 理由 | 可回收触发条件 |
| --- | --- | --- | --- | --- |
| 1 | Web 请求 handler（Tomcat，4 工程） | **用**（翻开关） | 上限由平台线程数（200）决定、工作主体是阻塞 I/O；实测吞吐收益 ≈0（票 04），翻的理由 = 消上限 + 口径统一（票 05） | 无——开关即落地；退回防线 = Web seam 断言（§5） |
| 2 | WS 握手（单体 `beforeHandshake`） | **用**（被 #1 开关吸收，无独立改造项） | 属「上限由平台线程数决定」形态，翻开关自动变 VT；`getAndDelete` 的 AQS park 不 pin（票 09） | 握手 P99 恶化归因 Redisson 默认重试叠加（~10s+）⇒ 配 `timeout`/`retryAttempts` |
| 3 | RocketMQ 消费池（单体 21、activity 1、asset 1，共 23 个消费者） | **用**（整体换，4 工程统一接线） | **用户口径**（开发阶段、能上就上、架构展示面），非性能论证；按选点规则本应判不换（20/23 被池兜死）——原样留档（票 06） | `pullThresholdForQueue` 收窄后仍出现成批池饥饿式重试风暴 ⇒ 换回固定平台池（接线点不变，改一行）；上游发布 `setConsumeExecutor` ⇒ 收编官方 API |
| 4 | Dubbo provider 池（单体 / asset / center 三个 5005x） | **用**（显式写 `virtual`） | 与 #1 完全同构：上限由平台线程数（threads=200）决定、工作主体是 DB 阻塞 I/O、实测无收益（票 07） | Dubbo 升到含 #16055 `threads.virtual.core` ⇒ 换有界 VT 池；引入 streaming/原生 async ⇒ 重判传播面；无界接受实测压垮下游且池 + 3s 快失败兜不住 ⇒ 重考有界池；JDK 24 ⇒ pinning 账目回收 |
| 5 | Dubbo consumer 调用线程（4 工程，5 静态 + 1 动态引用） | **无对象可改**（宿主是 VT 就免费） | sync unary 走 `ThreadlessExecutor` 就地完成、不进任何池；`RpcContext` 是 `InternalThreadLocal` 不跨 VT 继承、本仓零使用（票 01/07） | 引入 streaming / 原生 async 回调 ⇒ `streamExecutor` 成真路径，与 #4 触发条件 ② 一并重判 |
| 6 | 调度扇出（单体 7 个 job 入口 + 单体 `onTimeoutBatch` 批处理，共 8 处） | **用**（扇出，宽度 = 批大小） | **用户口径**（「要改并行，上虚拟线程」），非性能论证；语义侧逐条核安全——幂等闸门全保留、无一处判出 scope（票 08） | 关单路径日志出现 `Deadlock found when trying to get lock` ⇒ 提上 `wallet_id` 修法（§4.3 F） |
| 7 | 已异步的 outbox 扫描（单体 ×2、activity ×2、asset ×1） | **不采用** | 证据：全走 `rocketMQTemplate.asyncSend` + 专职标记线程（票 08 #9–#12）。为什么：已异步，无阻塞循环可改。触发条件：无——改造成异步发送本身就消灭了对象 | — |
| 8 | 无循环的批量 SQL（`lotteryDrawCountExpireScan`、`pointsExpireScan`） | **不采用** | 证据：单条批量 UPDATE，无逐行循环（票 08 #14/#15）。为什么：无可扇出对象。触发条件：无 | — |
| 9 | `lotteryDeductMerge`（activity） | **不采用** | 证据：整批明细**一个事务**折算（票 08 #13）。为什么：并发化会拆掉「绝不按 `status=0` 翻状态」这条不变量——拆掉就少扣 ⇒ 超卖。触发条件：业务侧主动拆事务设计时另议（届时属语义变更，出本 effort 范围） | — |
| 10 | WS 推送扇出（单体 `ChatWsPushService.broadcast`，全仓唯一 WS 扇出点） | **不采用**（保持串行） | 证据：宿主是消费侧 VT（#3 落地后），串行只占一条 VT、不占平台线程上限——「消除扇出串行化」触发条件不成立；并行化剩余收益只有单消息尾延迟（≤5s 观察窗），代价 = 消费并发 × 扇出宽度（群上限 500）无界 + 慢客户端阻塞写吃载体，要赔一套有界机制破「不加信号量」口径（票 09）。顺序从来不是判据：基线是同会话顺序**今天就不保证**（客户端靠 `(roomId, seq)` 判重 + 补拉合流） | 引入扇出并行化或下行有状态回放 ⇒ 有界约束复活，并按 256 载体分母重判慢客户端承压面 |
| 11 | chat sweep 守护线程（单体 `chat-ws-session-sweep`） | **不采用** | 证据：并发度 1、工作主体非阻塞 I/O（票 09）。为什么：不满足选点规则任一条。触发条件：无 | — |
| 12 | `NotificationBroadcastConsumer` 并发化（单体） | **不采用**（非线程模型问题） | 证据：全用户单事务逐行 insert，并发上限由**事务边界**决定——要并行就得拆事务 ⇒ 广播部分可见 = 语义变更（票 06）。为什么：属 map Out of scope 第 5 条。触发条件：全用户广播成为实测瓶颈 ⇒ 另开性能 effort（批量/分批 insert） | — |
| 13 | `%DLQ%{group}` 死信监控（4 工程） | **不采用**（不新增设施） | 证据：四工程 pom 零 metrics 设施（票 10）。为什么：`%DLQ%{group}` 无人看是**既有缺口**、非本 effort 引入，不因换线程模型新增监控。触发条件：单独的可观测 effort 立项时一并考虑 | — |

## 4. 四节上下文详述

### 4.1 消息消费（票 06）

**裁决：23 个消费者的消费线程池整体换虚拟线程（4 工程统一）；并发上限不由 `consumeThreadNumber` 表达，改由 client 自己的 `pullThresholdForQueue` 表达；孤例（一个同步阻塞型消费者）不再单列；ack/重试/DLQ 语义逐行不变。**

- **落地路径（裁决级）**：官方低成本口子不存在（`setConsumeExecutor` 只在 develop 未进发布版）⇒ 采用**同包子类覆写 `submitConsumeRequest`** + 覆写容器注册器接线的方案；`processConsumeResult`、ack 时机、重试阶梯（`delayLevel=3+reconsumeTimes`）、`%DLQ%`、15min 过期阀门**全部原样继承**；base 池懒创建 ⇒ 不投它就一条平台线程都不产生，**460 条 `ConsumeMessageThread_*` 归零**。实现步骤归落地票。
- **上限口径**：无界 `newVirtualThreadPerTaskExecutor` + **不加信号量**；用 `container.getConsumer().setPullThresholdForQueue(N)` 表达上限（超限走 client 自带 50ms 延迟重拉，不阻塞线程）。⚠️ 本 spec 只定「用这个旋钮」，**N 的值落地时按池与下游配额定**（锚点：Hikari 10 与下游服务配额）。有界池被否的两个硬理由：client 的私有补投路径投回 base 池；阻塞式 `acquire` 会卡死全实例唯一的 `PullMessageService`。
- **`consumeThreadNumber` = 死参数**（`consumeThreadMax` 本来就是死的）：spec 明写「消费并发上限不由它表达，要限流调 `pullThresholdForQueue`」。
- **孤例（一个同步阻塞型消费者）**（下游一次 HTTP 调用，120s 只是读超时上限、实际耗时未实测）：并入整体换——20 槽位不再是上限、HTTP 阻塞读不 pin；**位点钉住是模型固有、不改善**（不 ack 就钉着）；它是 `pullThresholdForQueue` 收窄**最有用的一处**（下游服务无池兜底，无界会打爆）。
- **WS 消费者**：3 个（`ChatPushConsumer` / `ChatReadPushConsumer` / `ChatRecallPushConsumer`），线程模型并入整体换，扇出口径归 §4.4。
- **失败规模口径**：在飞从 ≈20 抬到 `pullThresholdForQueue` 深度后，下游抖动会成批失败进重试阶梯。两个 client 参数保持默认（并发模式 16 次、延迟 10s→30s→1m→…→2h 递增）；抖动长于阶梯覆盖窗口时成批进 DLQ 是**预期的 at-least-once 行为**，不是新故障模式。`pullThresholdSizeForQueue`（100MiB）与 `consumeConcurrentlyMaxSpan`（2000）不动。

### 4.2 RPC（票 07）

**裁决：provider 池换内置 `virtual`（3 个 provider 工程显式写）；consumer 侧无可改对象；动态引用边零改造。**

- **同构依据**：Tomcat 200 与 Dubbo provider 200 形态相同（上限由平台线程数决定、工作主体 DB 阻塞 I/O、实测无收益）⇒ 同一裁决。`fixed` 会在第 201 个并发请求上凭空拒绝，而 DB 侧本来就有连接池兜着。
- **被否掉的两条论据（原样留档，不许再引用）**：① ~~「provider 并发量级个位到数十 ⇒ 200 打不到 ⇒ 无收益」~~——量级是纸面推理从未实测，判据应是**上限由谁决定**（provider 并发由调用方决定：Web 并发翻开关后上限 8192、超时中心扇出）；② ~~「VT 化把压力下沉到 Hikari 慢失败，比拒绝 + jstack 更糟」~~——DB 限流不该由 Dubbo 池承担，放置本身就错。
- **consumer 侧**：sync unary 根本不进池（`ThreadlessExecutor` 就地完成）⇒ 「VT 化 consumer」没有可改对象，零 Dubbo 配置；宿主是 VT 就免费。本仓不存在「provider 线程里再发 RPC」的嵌套路径（已 grep 核实）。
- **动态 `ReferenceConfig` 边（超时中心回调）**：无新增并发风险、零改造——桶锁只裹纯构造、贵的 `.get()` 在锁外；3.3.6 的 `ReferenceConfig.init()` 用 `ReentrantLock` 非 `synchronized` ⇒ 并发首建不 pin。
- **代价记账（5 项）**：无界无背压（上限口径 = 连接池 + 3s 快失败）；`threads`/`queues` 成**死参数**（spec 禁写，写了没效果且误导后人）；丢「打满即拒 + dump jstack」（替代信号见 §5）；`threadpool` 跨应用传播致 4 工程消费端 `streamExecutor` 变 virtual（今天零行为变化）；provider 层并入 pinning 适用面（结论不变：必修项仍只有 `ensureBucket`）。
- **可回收触发条件**：见裁决表 #4。

### 4.3 调度与批处理（票 08）

**裁决：全仓 17 个 `@XxlJob` + 1 个 Dubbo 批处理入口逐条裁完——8 处扇出（单体 7 个 job 入口：`tradeExpireScan` / `wantToBuyTimeoutScan` / `productTimeoutScan` / `errandTimeoutScan` / `postReviewTimeoutScan` / `dailyCreditJob` / `searchIndexRebuildJob`，＋单体 `onTimeoutBatch`），3 类不采用（裁决表 #7–#9），既有 5 处 VT 复核全部一致。裁决理由 = 用户口径，不是性能收益，也不是规则推导**——按规则字面读「串行循环的上限由循环自己决定」可以答该扇出，但「抬高了有没有用」在规则里没有判据位置；**别把展示面取向包装成规则结论**。被否掉的论据原样留档：~~「单 tick 几百 ms、60s cadence、不占用户路径 ⇒ 抬高了没用」~~（判据本就不该是「我们测到多少」，且量级是纸面推理）；~~「语义侧安全但风险不值得为零收益承担」~~（前提来自前一条，语义安全性是已核的硬事实）。

**A. 逐条裁决**：8 处扇出的语义依据逐条核过（条件 UPDATE / 流水 `uk_order_type` / `dedup_key` / `INSERT IGNORE` 幂等闸门全保留；资金与信用余额更新全是原子 SQL ⇒ 跨行并发无丢失更新）。`onTimeoutBatch` 单列，因其判据不同：回调单次有 **2s 硬上限**，重跑一项 5–10 条语句 ⇒ 200 项串行**健康系统下也走不完一批**，是进度缺陷不是纯性能——扇出把批时长从 `Σ` 变 `Σ/W`。只动「批内并行」：中心侧 `scan.batch-size` 与 2s 超时都不动；批内各项是独立 `(taskType, bizId)` 状态机、无顺序语义，`successList`/`failList` 回主线程汇总、不换并发容器。

**B. 扇出模板（两变体）**。命名规范并入模板（票 10）：扇出一律 `Thread.ofVirtual().name(prefix, 0).factory()` 建工厂，不再裸 `newVirtualThreadPerTaskExecutor()`。

变体 A — 单发一批（任务量有界，或被调度频率 + 幂等收敛兜住；适用 `onTimeoutBatch` 与单体 5 个扫表/提醒 handler，维持现有 `SCAN_BATCH_SIZE = 100` 与单发形状）：

```java
List<Long> ids = xxxDao.selectBatch(before, BATCH);   // LIMIT = BATCH（批大小即并发宽度）
Thread.FactoryOfVirtualThread factory = Thread.ofVirtual().name("xxx-scan-", 0).factory();
try (ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {
    List<Future<R>> futures = ids.stream().map(id -> executor.submit(() -> one(id))).toList();
    for (int i = 0; i < ids.size(); i++) { /* 回主线程按项汇总 */ }
}                                                     // close() = shutdown + 等全部结束
```

变体 B — 循环拉批到空（任务量无界 ⇒ 批 + 游标是唯一终止条件；适用 `dailyCreditJob` / `searchIndexRebuildJob`，**必须先补 `LIMIT` + 游标再扇出**）：

```java
Long lastId = 0L;
Thread.FactoryOfVirtualThread factory = Thread.ofVirtual().name("xxx-rebuild-", 0).factory();
try (ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {
    while (true) {
        List<Long> ids = xxxDao.selectBatchAfter(lastId, BATCH);  // WHERE id > #{lastId} ORDER BY id LIMIT N
        if (ids.isEmpty()) break;
        /* 批内扇出 + 回主线程汇总（同变体 A） */
        lastId = ids.get(ids.size() - 1);
    }
}
```

**四条硬规矩**：

1. **并发宽度 = 批大小**，**不加信号量**——上限由池兜（票 05/06/07 同一条口径）。
2. **执行器即用即关**：粒度 = 一个工作单元（一次 tick / 一次批调用），不是常驻。VT 执行器不是池；常驻的代价不是性能，是「全进程共享的无界池」——任何一处忘了设界就把压力串到别人身上。（将来若落地有界 VT 池，「闸必须共享」才要求常驻——即用即关与「不加闸」是同一枚硬币的两面。）
3. **结果回主线程汇总**，不靠并发容器凑。
4. **项内异常自己吞并记账，不向上冒**（批量信封为部分失败设计）。

⚠️ **caveat 必须随模板走**：`close()` 是**无限等待**——一个卡住的任务会把调用点永远挂住，所以**项内必须自带超时**（Dubbo 2s / DB `connection-timeout` / xxl-job 调度超时）。

**与 timeout-center 已验证形态的刻意差异（模板不能原样搬它）**：① 它是两层扇出（槽位 → 批内 group）且落库回槽位线程串行，单体这些循环落库就是项本身的工作；② 因此它「池 ≥ 并行度」在单体换成「池是唯一闸」；③ 它「失败行被退避推远 ⇒ 天然终止」**不能**当变体 B 的终止依据——变体 B 只认**游标推进到空批**。

**C. 无界查询处置**：`selectUsersActiveOn`（不会自然推进——处理完仍满足条件 ⇒ 尾巴永远漏）与 4 处 `selectAllIds`（只重建前 N 行 = **静默不完整的索引**）必须补 `LIMIT` + 游标；`selectLowScoreNormalUsers` 能自推进，为统一仍走游标。**唯一例外**：`PostDetailCache.initBloom()` 的全量 id 列表**刻意不设限**（布隆预热要全量、一次性 `@PostConstruct`、不是循环）——将来不许被照游标规矩「修」坏。

**D. 既有 5 处 VT 复核**：全部一致、无必修项。三处记账：① `expand()` 注册批宽度无界（潜伏项，改法 = 出口按 `scan.batch-size` 同口径常量截断，**本版只记账不修**，今天实际宽度恒 1）；② ⚠️ `connection-timeout: 3000` 尚未落地（§2.3 目标态）；③ 池仍 10 不动。中心侧 ≤200 并发注册打池 24 属「池是唯一上限」口径内，记账即可。

**E. hint 契约**：完好——`withSlotHint` 每次 DAO 调用一开一关、子 VT（`callGroup`）只发 Dubbo 不碰 DB；全仓 `HintManager` 只在 timeout-center，单体 outbox 发送器不碰 hint，8 处扇出全部不碰 hint。模板规矩：**hint 不得跨线程传递；扇出边界不得落在打开的 hint 作用域内**（VT 会继承 inheritable 值，子线程再 `getInstance()` 直接抛）。

**F. 新可达的既有竞态（扇出真实换来的暴露面；本版不改，用户拍板只落 spec）**：

> 同一用户的多个订单可能被**同时关单**（今天串行，天然不可能）。症状 = 偶发 InnoDB 死锁——回滚其中一项落 `failList`，中心按退避重投；最坏 16 次进死信、人工重发。**不丢数据**（幂等闸门全程兜底），**与虚拟线程无关**（票 04 实测 VT 开/关各臂都出现）。
> **根因**：REPEATABLE READ 下，流水插入 `INSERT IGNORE INTO wallet_transaction ... SELECT ... FROM virtual_wallet WHERE user_id = ?`（`WalletMapper.xml:41`）对源表取**共享 next-key 锁**；同事务随后的 `UPDATE virtual_wallet ...` 要升级**排他**锁。同用户两个事务各持 S、各求 X ⇒ 成环——解释「只在同用户并发出现、与线程模型无关」两个特征。
> **最小修法**：`wallet_transaction.wallet_id` 全仓**无任何读取者**，只因 `NOT NULL` 逼出那条 `INSERT ... SELECT` ⇒ **删列改走 `INSERT ... VALUES`**，S 锁消失。连带须显式化「钱包行必须存在」（那条 SELECT 兼职隐式守卫；且「查不到钱包」与「流水已存在」都返回 0 行、语义撞车，`unfreeze`/`income` 不检查返回值 ⇒ 静默成功，只有 `freeze` 会抛出**指错原因**的「余额不足」）。
> **触发条件**：关单路径日志出现 `Deadlock found when trying to get lock` ⇒ 把这条修法提上来。

**G. scope 判定**：没有一处判出 map Out of scope 第 5 条——并发化未改动任何幂等位或顺序假设；通知「取号与落库同一事务」不变量照旧成立。唯一越界项即 F 条，已按「越界时回来处置」登记。

### 4.4 长连接 / WS（票 09）

**裁决：三处全部「不改代码」。**

1. **握手**：`beforeHandshake` 跑在 Tomcat handler 线程上 = 票 05 开关唯一真实生效点，翻开关自动变 VT、无独立改造项；`wsTicketPort.take` 的 Redisson `getAndDelete`（AQS park）不 pin。**备注一条（不设改造项）**：全仓零 Redisson 超时配置，默认 3s + 重试 3×1.5s ⇒ 单次 GETDEL 最坏 ~10s+——属 Redis 客户端配置面，与线程模型无关。
2. **扇出**：`ChatWsPushService.broadcast` **保持串行**（裁决表 #10）。票 06 的「并行化不破顺序语义」倾向成立且被采纳，但**否掉并行化的是上限/收益判据，不是顺序**。「漏推合法、补拉兜底」语义（`push` 吞异常、只推在线成员、失败不重试）不动。
3. **pinning 落「无害」档**：Spring decorator 全类零 monitor、Tomcat socket 写在锁外；唯一会等的慢客户端 NIO 阻塞写是**补偿型**（需 256 并发卡写才排队，且 decorator 5s/512KB 越限断开形成负反馈——越慢越先断 + sweep 60s + 容器 90s 三层既有兜底）⇒ **不新增并发上限/信号量**。

## 5. 可观测与验收（票 10）

### 5.1 可观测：命名规范 + JFR 常态化 + 人工手册，不引入 metrics 设施

四工程 pom 零 actuator/micrometer/prometheus，为一条线程数指标引整套依赖不成比例 ⇒ **不引入**。

- **命名**：自己 new 的 VT 一律命名——单发 `Thread.ofVirtual().name(prefix).start(...)`（既有 2 处已合规）；扇出走模板 factory（§4.3 B）；消费 executor 子类同规范；框架内部池归框架。收益 = 日志线程列可排障。
- **JFR 常态化**：JVM 启动参数挂持续 recording，含 `jdk.VirtualThreadPinned` **threshold=0ms**；recording 配置以拷贝 `measure/vt-pin-only.jfc` 为底**加线程统计事件**（原文件只含这一个事件，不能直接复用）。启动参数模板随落地票定，落盘位置与轮转由落地票定。
- **平台线程数**：人工看（`jcmd` / 票 04 `HikariProbe.java` 同款 JMX 读数），spec 附「怎么看」手册（本节即）。

**替代信号（合并票 05「Tomcat 失明」与票 07「Dubbo 丢拒绝信号」）**：翻开关后 Tomcat max/busy 读 `-1`、Dubbo 丢「打满即拒 + jstack」⇒ 替代信号同为**连接池等待**——Hikari `active` / `awaiting`（JMX，需 `--spring.datasource.hikari.register-mbeans=true`）+ `connection-timeout` 异常日志（3000ms 快失败触发即在此现形）。

**平台线程数口径分路径、验收别写反**：

| 路径 | 口径 | 依据 |
| --- | --- | --- |
| Web 层 | **下降**（200 平台线程上限消失） | 票 05 |
| RocketMQ 消费层 | **清零**（460 条 `ConsumeMessageThread_*` 不再出现）——不是「下降」 | 票 06 |
| Dubbo provider 池 | **看峰值不看常驻**（空载本就 0） | 票 07 |
| 调度扇出 | **看峰值不看常驻**（xxl-job `JobThread` 本就 1 条，扇出后 VT 不产生平台线程） | 票 08 |

调度路径主观测点 = 连接池等待（Hikari `active`/`awaiting`）；**告警观测点 = 关单路径死锁日志**（出现即触发 §4.3 F 修法）。

### 5.2 验收

**三 seam `isVirtual()` 断言**（测试连常驻栈、继承既有基类，照 `backend/CLAUDE.md` §10；Filter 与断言 helper 的实现归落地票，此处只定口径）：

1. **Web handler**：test 配置注册测试专用 `OncePerRequestFilter` 记录 `isVirtual()`，IT 调任意端点后断言——防「开关被关」最直接一道。
2. **RocketMQ 消费**：监听器内断言 `isVirtual()`。
3. **调度扇出**：job 触发测试中断言**扇出项内** `isVirtual()`。⚠️ 断言对象是扇出项**不是 handler 本身**——`JobThread` 是 xxl 自有池平台线程。
4. **Dubbo provider 不断言**：断言要起跨进程调用，成本不成比例；显式 `threadpool: virtual` 配置行即防线。

**轻量验收两件**：① 落地前后平台线程数各记一个数（启动态 + 一次压测中），按上表分路径读；② `ensureBucket` 修完跑一次 JFR，确认 `jdk.VirtualThreadPinned` 归零（threshold=0ms 口径；「零事件 ≠ 零 pin」的 caveat 针对未改造点，不豁免此检查）。

**吞吐对照不强制重跑**：结论已知「实测无收益」，没有未知假设可验证；`measure/` 脚本保留可重跑，复现先读其 README 的 8 条坑。

### 5.3 回归防线

| 退回路径 | 防线 |
| --- | --- |
| `spring.threads.virtual.enabled` 被翻回 | Web seam 断言红 |
| 扇出被改回串行 | 调度扇出 seam 断言红（既有 job 测试行为亦可见） |
| `ensureBucket` 改造被回滚 | JFR 常态化 + 轻量验收 ② |

**验收节禁令**：不许引入 `@Async` / `CompletableFuture` 并发 / 手写平台线程池（对应 map Out of scope）。**不采用**：ArchUnit 架构测试（禁的是仓里本不存在的模式，空防线）；CI 加 JFR pinning 事件数比对（全量测试已 13.8 分钟，不再加重活）。

## 6. 按工程的适用性表

| 工程 | `spring.threads.virtual.enabled` | Dubbo `protocol.threadpool` | Hikari（目标态） | 形态要点 |
| --- | --- | --- | --- | --- |
| `better-school`（8080，单体） | **翻** | **显式写 `virtual`** | 显式 `maximum-pool-size: 10` + `connection-timeout: 3000` | 主战场：21 个消费者 / 全部 WS / MinIO / ES；7 个 job 入口扇出 + `onTimeoutBatch`；`ensureBucket` 必修在此 |
| `marketing-activity`（8082） | **翻** | 无 `@DubboService`（纯 consumer，无池可配） | 同上 | 1 个消费者；4 个 job（2 个不采用 + 1 个批量 SQL + 1 个不变量保护）；零扇出、零 hint |
| `marketing-asset`（8081） | **翻** | **显式写 `virtual`** | 同上 | 1 个 provider（兑换边）+ 1 个消费者；2 个 job（1 个批量 SQL + 1 个 outbox 不采用） |
| `timeout-center`（8083） | **翻** | **显式写 `virtual`** | `24` / `3000ms`（已有，不动） | ShardingSphere 分片（hint 契约完好）+ 已 VT 的扫描/注册链路；1 个 job 入口；零消费者 |

（Hikari 列为**目标态**——今天除 timeout-center 外三个工程无 Hikari 配置块、跑 Boot 默认 10 / 30s，见 §2.3。）

## 7. Out of scope

照 [map](map.md) 的 Out of scope 节，划死于图绘期：

- **Structured Concurrency / `ScopedValue`**：JDK 21 下为预览 API，需 `--enable-preview`，与「直接使用虚拟线程」无关
- **`ThreadLocal` → `ScopedValue` 重构、MDC 与用户身份上下文的传播重构**：属上下文传播重构，不属线程模型采用（`RpcContext` 附件透传将来若做，归此）
- **响应式改造（WebFlux / R2DBC / Reactor）**：与本 effort 正交
- **引入 `@Async`**：全仓现无，虚拟线程不需要它也能做事
- **业务语义变更**：只做「串行 → 并行、语义不变」；任何会动幂等或顺序假设的并发化不属本 effort（把消息顺序升级为硬保证、拆广播事务均属此）
- **JDK 24 升级 / 依赖 JEP 491**：机器上无 JDK 24，镜像/CI 全 21；升级是独立一件事
- **库内部 I/O 线程的改造**（Redisson netty、RocketMQ client netty、Dubbo netty、XXL-Job 内嵌 Jetty、Tomcat NIO acceptor/poller）：既改不动，也是不该被阻塞的事件线程；正解是把阻塞工作挪走

---

## 落地票拆分口径

spec 定稿后另拆落地票（同目录，`Status: ready-for-agent`）。本 spec 的改造面天然按**上下文**聚簇（开关 + Hikari 显式化 / `ensureBucket` / 消费接线 / 扇出模板 + 无界查询 / 命名与 JFR），拆票以此为准；具体票数与边界由拆票动作定，此处不预设。
