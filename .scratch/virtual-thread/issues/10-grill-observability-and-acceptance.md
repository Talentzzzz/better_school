# 10 grilling：可观测与验收口径

Type: grilling
Status: resolved
Blocked by:

## Question

怎么证明虚拟线程「真的在用」且「用对了」？这决定 spec 里「验收」一节写什么，也决定未来的落地票做到什么程度算完成。

1. **可观测**：线程命名（虚拟线程默认无名，要不要立 `Thread.ofVirtual().name(...)` 规范）、JFR 事件常态化采集（`jdk.VirtualThreadPinned`）、线程数指标——**平台线程数应当是下降的**，这个怎么暴露 / 怎么看、日志追踪
2. **验收口径**：集成测试能不能断言 `Thread.currentThread().isVirtual()`？在哪些 seam 上断言（trigger 层是仓库既有测试 seam，见 `backend/CLAUDE.md` §10：继承既有基类、连常驻栈、不用 Testcontainers）？票 04 的一次性实测要不要固化成可重跑的压测脚本
3. **「不采用」的记录口径**：spec 怎么表达「这里我们看过、决定不用」，才不至于让后来人反复重问
4. **逐上下文裁决表的形状**：本票定义 spec 主体那张表的列（建议：上下文 / 裁决 / 理由 / 可回收触发条件），后面的 spec 汇总票照此落稿
5. **回归防线**：怎么防止虚拟线程被无意间退回平台线程（某次改动把开关关了、或扇出被改回串行）

结论落 spec 的「可观测与验收」节，并定下裁决表结构。

## Answer

（2026-09-21 收口。问题在 grilling 中拆为七问，Q1–Q7 编号见各节标题；全按助手推荐定稿；票 05/06/07/08 四票转来的口径逐条吸收，见各节。结论落 spec 的「可观测与验收」节 + 裁决表结构，由[票 11](11-task-write-spec.md) 落稿。）

### 1. 可观测（Q1 + Q2）：命名规范 + JFR 常态化 + 人工手册，不引入 metrics 设施

**线程命名（Q1，选项 a）**：自己 new 的虚拟线程一律命名——

- 单发：`Thread.ofVirtual().name(prefix).start(...)`（既有两处已合规：`TimeoutRegisterOutboxSender.java:65` 的 `timeout-register-immediate`、`:90` 的 `timeout-register-cancel`）
- 扇出：模板统一 `Thread.ofVirtual().name(prefix, 0).factory()` + `Executors.newThreadPerTaskExecutor(factory)`，**并入[票 08](08-grill-scheduling-and-existing-fanout.md) 的扇出模板定义处**（是模板组成，不新增「硬规矩」条目）；落地票顺手覆盖既有 3 处无名扇出（`TimeoutTaskScanCase.java:71`、`TimeoutTaskRegisterCase.java:40`、`TimeoutRegisterOutboxSender.java:148`）
- [票 06](06-grill-rocketmq-consumer-model.md) 自接线的消费 executor 子类按同规范命名
- Tomcat / Dubbo 等框架内部池的命名归框架，不立规范

（日志走 Spring Boot 默认 pattern 含线程名，无名 VT 在日志线程列是空串——命名的直接收益就是日志可排障。）

**观测设施（Q2，选项 b）**：四工程 pom 零 micrometer / actuator / prometheus（grep 无命中），为一条线程数指标引入整套 metrics 依赖不成比例，且本 effort 口径是「实测无收益」、更不许包装成性能设施 ⇒ **不引入**。替代：

- **JFR 常态化**：JVM 启动参数挂持续 recording，含 `jdk.VirtualThreadPinned`（**threshold=0ms**，默认 20ms 会漏短 pin——票 03/04 已证）。recording 配置以 `measure/vt-pin-only.jfc` 为底**拷贝后加线程统计事件**（原文件只含这一个事件，不能直接复用）；spec 写明启动参数模板，落盘位置与轮转由落地票定
- **平台线程数**：人工看（`jcmd` / [票 04](04-task-measure-framework-flip.md) 的 `HikariProbe.java` 探针同款 JMX 读数），spec 附「怎么看」手册

**替代信号手册**——[票 05](05-grill-framework-flip-and-backpressure.md) 转来的「Tomcat max/busy 翻开关后读 `-1` 失明」与[票 07](07-grill-dubbo-thread-model.md) 转来的「Dubbo 丢『池打满即拒 + jstack』」**合并成一节**：两处的替代信号同为**连接池等待**——Hikari `active` / `awaiting`（等连接线程数；压测与排障时按 `measure/HikariProbe.java` 口径经 JMX 读，需 `--spring.datasource.hikari.register-mbeans=true`）+ `connection-timeout` 异常日志（背压触发即在此现形，票 05 定的 3000ms；票 07 原文说的「失败率」即这类异常的频次，同一信号）。

**平台线程数的验收口径分路径不同，别写反**（吸收票 06/07/08 三处转来的同款提醒）：

| 路径 | 口径 | 依据 |
| --- | --- | --- |
| Web 层 | **下降**（200 平台线程上限消失） | 票 05 |
| RocketMQ 消费层 | **清零**（460 条 `ConsumeMessageThread_*` 不再出现）——不是"下降" | 票 06 |
| Dubbo provider 池 | **看峰值不是常驻**（空载本就 0） | 票 07 |
| 调度扇出 | **看峰值不是常驻**（xxl-job 的 `JobThread` 本就 1 条，扇出后 VT 不产生平台线程） | 票 08 |

调度路径的**主观测点 = 连接池等待指标**（Hikari `active`/`awaiting`，票 08 转来）；**告警观测点 = 关单路径死锁日志**（出现即触发票 08 的 `wallet_id` 修法）。

**DLQ（票 06 转来的一问）**：票 06 已定「本 effort 不新增死信监控设施」，未给倾向的只是「是否纳入可观测口径」——按本票「不新增设施」同口径裁：**不新增死信监控设施**，spec 照票 06 原文记「`%DLQ%{group}` 只是 broker 上的 topic、无人看」为**既有缺口**（非本 effort 引入），进裁决表「不采用」行。

### 2. 验收（Q3 + Q4）：三个 seam 的 `isVirtual()` 断言 + 轻量验收

**断言 seam（Q3，选项 i+ii+iii）**——正好覆盖三个改造面各一道：

- **(i) Web handler**：test 配置注册测试专用 `OncePerRequestFilter` 记录 `isVirtual()`，IT 调任意端点后断言。防「开关被关」最直接的一道。
- **(ii) RocketMQ 消费**：监听器内断言 `isVirtual()`（票 06 同款口径）。
- **(iii) 调度扇出**：job 触发测试中断言**扇出项内** `isVirtual()`。⚠️ 断言对象是扇出项**不是 handler 本身**——xxl-job 的 `JobThread` 是它自有池的平台线程，翻开关不变虚拟（票 08 裁决即「handler 内扇出」）。
- **(iv) Dubbo provider 不断言**：断言要起跨进程调用，成本不成比例；[票 07](07-grill-dubbo-thread-model.md) 显式写 `threadpool: virtual` 的配置行即防线。

测试连常驻栈、继承既有基类（`backend/CLAUDE.md` §10）。Filter 与断言 helper 等**实现放落地票，spec 只定口径**。

**验收步骤（Q4，选项 b，轻量）**：

1. 落地前后平台线程数各记一个数（启动态 + 一次压测中），按上表分路径口径读
2. `FileStoragePort.ensureBucket()` 修完后跑一次 JFR，确认 `jdk.VirtualThreadPinned` 归零（threshold=0ms 口径；验证对象是改造点本身，「零事件 ≠ 零 pin」的 caveat 针对未改造点，不豁免此检查）

**吞吐对照不强制重跑**：结论已知「实测无收益」，没有未知假设可验证，全臂重跑是纯仪式（数小时 + 灌脏联调库）。`measure/` 脚本保留可重跑，复现先读其 README 的 8 条坑。

### 3. 「不采用」记录口径（Q5，选项 b）：三段式

裁决表内「不采用」行必须写全三段：

1. **看过什么证据**——链到票/调研的具体数字或实测（本 effort 的「不采用」几乎全有实测背书：票 02/03/04）
2. **为什么不成立 / 不值得**
3. **什么条件变了就该重问**——可回收触发条件

spec 加总纲一句：**「表内『不采用』= 已裁决；重提前先反驳触发条件，不许当『还没看过』。」**三段落同一行内，不额外加节。

### 4. 裁决表结构（Q6）：四列不动

**上下文 / 裁决 / 理由 / 可回收触发条件**。两处约定：

1. **理由列内嵌证据引用**（`（票 NN §x / research/NN §y）`），与第 3 节三段式合并——证据进理由列比独立列好读
2. **适用工程不立列**，写进上下文名（如「WS 扇出（单体）」）；跨工程行用表下脚注

不立「状态」列——spec 是决策文档非进度文档，落地进度由落地票表达。裁决值域：「用」/「不采用」。

### 5. 回归防线（Q7，选项 b）：三条退回路径各配一道

| 退回路径 | 防线 |
| --- | --- |
| `spring.threads.virtual.enabled` 被翻回 | Web seam `isVirtual()` 断言红 |
| 扇出被改回串行 | 调度扇出 seam `isVirtual()` 断言红（既有 job 测试行为亦可见） |
| pinning 改造（`ensureBucket`）被回滚 | JFR 常态化 + 验收步骤 2 |

验收节写**禁令**：不许引入 `@Async` / `CompletableFuture` 并发 / 手写平台线程池（引用 map 的 Out of scope 对应条）。

**不采用的两道候选防线**：ArchUnit 架构测试（禁的是仓里本不存在的模式，空防线）；CI 加 JFR pinning 事件数比对（全量测试已 13.8 分钟，不再加重活）。

### 决策依据（事实自核）

- 既有 VT 5 处：2 处单发已命名（`TimeoutRegisterOutboxSender.java:65/:90`）、3 处 executor 扇出无名（`TimeoutTaskScanCase.java:71`、`TimeoutTaskRegisterCase.java:40`、`TimeoutRegisterOutboxSender.java:148`）——逐处 grep 核实
- 四工程 pom grep `micrometer|actuator|prometheus` 零命中；日志无自定义 pattern（Spring Boot 默认，含线程名）
- `measure/` 已是可重跑形态：6 个入口脚本 + `vt-pin-only.jfc`（threshold=0ms）+ `parse_pins.py` + README 8 条坑
- 测试 seam：`backend/CLAUDE.md` §10（常驻栈、既有基类、不起容器）
- 上游转来的口径全部有落点：票 05 Tomcat 失明 → 替代信号节；票 06 清零口径 + DLQ → 验收表与 §1；票 07 替代信号 + provider 峰值 → 替代信号节与口径表；票 08 连接池主观测点 + 死锁观测点 + JobThread → 口径表。**无与推荐冲突者**；唯 DLQ 一问——票 06 已定「不新增设施」，未给倾向的只是是否纳入可观测口径，按同口径裁（见 §1 末）
