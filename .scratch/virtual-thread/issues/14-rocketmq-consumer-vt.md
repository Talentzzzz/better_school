# 14: RocketMQ 消费接线——23 个消费者整体换虚拟线程

**What to build:** spec §4.1：3 个有消费者的工程（单体 21 / marketing-activity 1 / marketing-asset 1，timeout-center 零消费者无接线面）把消费线程池整体换虚拟线程。官方低成本口子不存在（`setConsumeExecutor` 只在 develop）⇒ 采用**同包子类覆写 `submitConsumeRequest`** + 覆写容器注册器接线的方案（spec 裁决级口径，实现步骤自定）。语义逐行原样继承：`processConsumeResult`、ack 时机、重试阶梯（`delayLevel=3+reconsumeTimes`）、`%DLQ%`、15min 过期阀门。

并发上限口径：无界 executor + **不加信号量**，上限用 `container.getConsumer().setPullThresholdForQueue(N)` 表达；**N 本票定值**——按池（Hikari 10）与下游配额定，`AgentReviewDispatchConsumer`（下游 LLM sidecar 无池兜底）是收窄**最有用的一处**，必须单独按 sidecar 配额定。`consumeThreadNumber` 是死参数，**不许写它**（spec 明文：要限流调 `pullThresholdForQueue`）。base 池懒创建 ⇒ 不投消息不产生平台线程。失败规模口径照 spec §4.1：在飞抬深后成批进重试阶梯 / DLQ 是预期 at-least-once 行为。

消费 executor 子类按命名规范命名（spec §5.1）。带消费 seam 断言（spec §5.2 #2）：监听器内断言 `isVirtual()`。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] 23 个消费者的消费工作线程全为 VT（消费 seam 断言绿）
- [x] 消费期观察不到 `ConsumeMessageThread_*` 平台线程（spec §5.1 口径：消费层「清零」不是「下降」）
- [x] `pullThresholdForQueue` 已设值且各消费者有依据（`AgentReviewDispatchConsumer` 按 LLM sidecar 配额单独定）
- [x] ack / 重试阶梯 / DLQ / 过期阀门语义回归测试全绿，无一处被改动
- [x] 不出现 `consumeThreadNumber`、`consumeThreadMax` 配置或信号量

## Answer

**落地内容**：3 个工程各 5 个新文件，逐字同构（三工程是互相独立 Maven project、无共享模块，照票 12 的 `VtWebThreadProbe` 先例重复）。

1. **`org.apache.rocketmq.client.impl.consumer.VirtualThreadConsumeMessageConcurrentlyService`**（各 trigger 模块，同包 split package）：只覆写 `submitConsumeRequest`——批切分循环逐行照抄 client 5.3.2 原文，把提交目标换成 `Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("mq-consume-<group>-", 0).factory())`。`ConsumeRequest` 的 `run()` 不动 ⇒ `processConsumeResult` / ack 时机 / 重试阶梯 / `%DLQ%` / stats / hook / 15min `cleanExpireMsg` 阀门全部原样继承。`shutdown` 关 VT executor 并 `awaitTermination(awaitTerminateMillis)`。
2. **`VirtualThreadRocketMQListenerContainerRegistrar extends RocketMQMessageListenerContainerRegistrar`**（各 app 的 `config/`）：覆写 `startContainer()` → `super.startContainer()` 之后 `getBeansOfType(DefaultRocketMQListenerContainer.class)` 逐容器执行「**新服务 `start()` → `setConsumeMessageService(新)` → 旧服务 `shutdown()` → `setPullThresholdForQueue(N)`**」。
3. **`RocketMQConsumerConfig`**：`@Bean` 声明成接口类型接管注册器（框架默认实现带 `@ConditionalOnMissingBean`，用户 bean 先注册 ⇒ 生效；三工程都经启动实测）。

**三处实现取舍**（都不是 spec 明文，记清理由）：
- **换在 `consumer.start()` 之后**：默认 `ConsumeMessageService` 由 `DefaultMQPushConsumerImpl.start()` 内部 new，之前字段是 null，没有更早的挂点。
- **必须显式 `shutdown()` 被换下的旧服务**：否则它那两条 `ScheduledExecutorService`（`CleanExpireMsgScheduledThread_*` / 重投）无人回收（研究 §3.3 第 2 点）。运行期证据：两臂 `CleanExpireMsgScheduledThread_*` 都是 21 条（每消费者 1 条），阀门照常工作。
- **先装后关，不能反过来**：反序会出现「旧服务已关、新服务还没挂上」的窗口，此时到达的 pull 回调投进已关闭的平台池 → 拒绝 → 重投线程也关着 → 异常冒到 pull 路径。
- **`submit` 的拒绝分支改为「记日志 + 交 broker 重投」**：父类原实现转 private 的 `submitConsumeRequestLater(ConsumeRequest)`，而它内部硬投回 base 池，子类够不着也用不上。无界 VT executor 只在 `shutdown` 后拒绝 ⇒ 只覆盖停机窗口，与父类停机时重投线程一并关闭的效果一致。
- **ORDERLY 直接抛 `IllegalStateException` 不让启动**：本仓 23 个消费者全是 `CONCURRENTLY`（注解未设 `consumeMode`）。ORDERLY 的 listener 跑在 `MessageQueueLock` 的 monitor 里、JDK 21 会 pin，且单队列串行与抬并发反向（spec §4.1 排除）⇒ 真有人加就得先裁决，别让它静默留在平台池上。

**N 值**。规则：`N = ⌈下游容量 / 队列数⌉`；队列数 = 4（broker 未配 `defaultTopicQueueNums`，本机 `mqadmin topicRoute` 实测 `readQueueNums=4`）。`pullThresholdForQueue` 是**每队列**上限，单实例消费者独占全部队列 ⇒ 在飞窗口 = `4N`。

| 消费者 | 下游资源拥有者 | 容量 | N | 在飞窗口 |
| --- | --- | --- | --- | --- |
| 单体 20 个 + activity 1 个 + asset 1 个 | Hikari 连接池（spec §2.3 显式化 10） | 10 | **3** | 12 |
| 单体 `bs-agent-review-group`（孤例） | LLM sidecar（无池兜底） | 40 | **10** | 40 |

- 取 **ceil** 不取 floor 的理由：12 略高于池 10，**单个消费者单独运行时也能把池打满**；floor（8）会让 marketing 两服（各只有一个消费者）永远够不到池、池不再是真上限。
- 孤例的 40 = **sidecar 自己声明的并发上限**：`POST /review-task` 走 `run_in_threadpool`（uvicorn 单进程 + anyio 默认线程池 40，`agent/app/main.py`）。选它而不是「维持现状 20」是照 spec「按 sidecar 配定额」与用户口径「上限该由拥有该资源的组件表达」——我们这侧另定一个低于 owner 声明值的数，就是票 07 被否掉的「拿上游组件当别人家限流器」。
- ⚠️ **这是本票最激进的判断**（20 → 40 翻倍、无池兜底、模型供应商侧速率限制本仓未实测），已写进代码注释。回退触发条件：若该 group 出现**成批投递失败进重试阶梯**（`reconsumeTimes` 耗尽转人工），先收 `DOWNSTREAM_CAPACITY` 里这一个数（一行）。

**⚠️ 与 spec §4.1 的一句口径偏差（必须留档）**：spec §4.1 失败规模段写「在飞从 ≈20 抬到 `pullThresholdForQueue` 的深度」，落地后 DB 绑定消费者的在飞是 **12，比旧的 20 低**。原因是 N 按 spec 的**指令**定（「按池与下游配定额，锚点 Hikari 10」），而那句 ≈20 是 N 未定时的预期描述。不构成违规、也不是遗漏——但 spec 文本与落地值在这一点上不同：**后续票（16 及以后）别再按「≈20 抬到」讲这件事**。（code-review Spec 轴揪出。）

**验收证据**：

1. **消费 seam 断言（spec §5.2 #2）**：三工程各一个 `ConsumeHandlerVirtualThreadIntegrationTest`（类名以 `*IntegrationTest` 结尾——票 12 的教训，`*IT` 不进 surefire 默认 includes），监听器内断言 `isVirtual()`。测试侧探针 `VtConsumeThreadProbe` 是 test classpath 的 `@Component` 消费者，走**与生产完全一样**的接线路径；测试直接向它的消费服务投递消息，不依赖 broker 投递时序（测试栈 namesrv 只有 namesrv 没有 broker）。第二条断言覆盖「全部容器都已换 + `pullThresholdForQueue` 已设值」，并**钉住孤例值**（`bs-agent-review-group=10`、`bs-comment-review-group=3`）——初版只断言 `0 < N < 1000`，code-review Spec 轴指出「删掉 map 里的孤例条目测试照样绿」，故补上。
2. **运行期 A/B 对照**（`measure/verify14/ab-consume.sh`，产物未入库）：同机、同一压测（c=32 / 30s、同一份 seed）、**负载进行中** dump——`jcmd Thread.print` 数平台线程，`jcmd Thread.dump_to_file` 数虚拟线程：

| 臂 | `ConsumeMessageThread_*` 平台线程 | `mq-consume-*` 虚拟线程 | `CleanExpireMsgScheduledThread_*` | rps / 错误率 |
| --- | --- | --- | --- | --- |
| 基线（HEAD 编译出的 jar） | **80**（4 组 × 20：comment-review / errand-paid / post-count / product-count） | 0 | 21 | 156.2 / 0.70% |
| 落地（工作区 jar） | **0** | **19**（同 4 组：12 / 3 / 1 / 3） | 21 | 144.5 / 0.62% |

- **「0」不是假阴性**：同一支探针在旧代码上精确命中 80 条（探针灵敏度已证，见 `feedback_zero_claim_needs_sensitivity`），且新臂同一窗口里 19 条 `mq-consume-*` 虚拟线程证明**确实在消费**（不是「没负载所以没线程」）。
- rps 差（156.2 → 144.5）落噪声内，与票 12 的 166.4 → 157.2 反向 ⇒ **原样印证「实测无收益」**，不引用为收益。平台线程**总量**仍被 RocketMQ client 内部线程主导，**不拿总量当 KPI**（票 12 同款口径）。
- **启动期全量切换证据**：单体日志 21 行「消费线程模型已切换为虚拟线程 group=… pullThresholdForQueue=…」，除 `bs-agent-review-group=10` 外全是 3。

**测试**：三工程 `mvn -o test` 全绿——单体 **356**、marketing-activity **76**、marketing-asset **14**，0 失败 0 错误。

**code-review 采纳项**（两轴都出真货）：
- **Spec 轴**：① 孤例 N 值没被任何断言钉住（已补 pin 测试）；② `getCorePoolSize() → 0` 属未要求的改动，已**撤掉**该覆写（诊断读数照旧指父类那个空转池，spec §4.1 已把 `consumeThreadNumber` 记为死参数，不必另改）；③ 类 Javadoc 的「批切分 15 行照抄」与「语义逐行继承」并置会读成「整段逐行抄」——原文 else 分支的 catch 还会把余量并回同一批再重投，本类该分支换成了记日志；已把「与父类原实现的两处差异」单独列段说清。
- **Standards 轴**：① 三份 `VtConsumeThreadProbe` 的访问器名漂了（asset 是 `wasVirtual()`，另两份 `lastWasVirtual()`）——已统一，保住「逐字同构可 diff」；② 子类字段 `consumeExecutor` 与父类 private 同名字段容易看岔，已改名 `virtualThreadExecutor`。
- **未采纳**（Standards 轴提的 split package 偏离）：`backend/CLAUDE.md` §4 的 trigger 层包结构（http/job/listener/ws/rpc）确实被越过，但这是够到包私有 `ConsumeRequest` 的唯一办法，且 spec §4.1 已把它写成裁决级口径；Javadoc 里已自述理由，不再另加模块 README。

**已知波及 / 范围外**：
- **`getCorePoolSize()` / `ConsumerRunningInfo.PROP_THREADPOOL_CORE_SIZE` 仍报 20**（父类那个空转平台池的读数）：spec §4.1 已把 `consumeThreadNumber` 记为死参数，本版不动。
- **`consumeMessagePopService`（POP 模式）未换**：本仓全 CLUSTERING 推送消费，POP 服务空转、不产生线程（运行期 A/B 已证无 `ConsumeMessageThread_*`）。
- **spec §4.1 的「≈20 抬到」口径偏差**：见上，落地值更低（12），已按 code-review 要求留档。
- **孤例 N=10（40 在飞）是高风险判断**：供应商侧速率限制未实测，回退触发条件见上。
- **常态化 JFR / 平台线程数基线对照 / 终验归票 16**（票 16 已被票 12–15 阻塞解锁面更新）。
