# Spec：分布式限流器 gatekeeper

_Status: final（票 01–12 全 resolved，2026-09-22 收口）_

本 effort 的终点文档：定义一个**通用分布式限流器**——客户端本地令牌桶 + 中心配额分配（速率租约口径）——的设计规格。上游裁决散在 [map](map.md) 与票 01–12 的 `## Answer` 里，本文把它们合成一份可照着实现、可照着验收的契约。本文是**决策文档**：只写裁决、理由（内嵌证据）、诚实声明与验收口径，**不含代码改造步骤**——落地票在 spec 定稿后另起 `.scratch/gatekeeper-impl/` 拆（§10 只写拆分倾向，本 effort 不立票）。

**工程形态**：仓顶 `gatekeeper/` 目录，**家庭层 6 个模块**（`gatekeeper-protocol` / `gatekeeper-config` / `gatekeeper-client` / `gatekeeper-center` / `gatekeeper-spring` / `gatekeeper-demo`）+ **`gatekeeper-center` 内层再拆 7 个 DDD 模块**（`gatekeeper-center-{types,api,domain,case,infrastructure,trigger,app}`，照 `backend/CLAUDE.md` 的约定）。Java 21 + Spring Boot 3.3.5（与本仓其余工程同栈，虚拟线程已落地、吃红利）；预期 `git subtree split` 可独立拆出。**不读写 better-school 的任何代码**（业务 key 只作示例）。

> ⚠️ **2026-09-22 结构修订**（原 `gatekeeper-api` 已改名 `gatekeeper-protocol`、`Probe` 已迁入 `gatekeeper-client`、center 拆 DDD 模块、新增共享件 `gatekeeper-config` 装配置源实现）：裁决与理由见 [ADR-0007](../../docs/adr/0007-gatekeeper-layering-and-protocol-module.md)，分层规范见 [`gatekeeper/CLAUDE.md`](../../gatekeeper/CLAUDE.md)。本文其余部分提到的模块名按此读。

**诚实声明（贯穿全文，不许包装）**：本文结论按来源分四档标注——

1. **一手来源（源码阅读，未运行）**：research/01 / 02 的业界对照与落码参考。全部为静态源码阅读、未跑任何实现；除 bucket4j 外各仓 commit SHA 未取到，行号对应 2026-09-22 快照、会漂。Doorman 有两条关键依据**只见于 `doc/design.md`、未在 `server.go` 核实**（§4 的 redirect 先例、§3 的续租硬下限），引用处原样带 ⚠️。
2. **一手来源（纸面证明 + 离散事件仿真，可重跑）**：[research/12](research/12-rate-lease-bounds.md)（`measure/sim_rate_lease.py` + `results/r-*.json`，产物不入库）。本文标 **【仿真】** 的数字出自它。
3. **推演（纸面推导、未实测）**：标 **【推演】**。主要有：过渡窗 `2R` 的形态推导（降级段读数已被 s4 仿真复核为 1.1R，但分片交互、非 Poisson 流量、`min_renew_interval`/`throttled` 瞬态未被仿真覆盖）、规则版本错位窗口的超发上界、内存账 91 B/条（手算 ±20%；**2026-09-24 票 12 已 JOL 复核，实测 ≈190 B/条**——模型漏了键 String 及其数组、值对象、内层槽位，见 §3.5/§11）。
4. **自研（无一手参照）**：§4 的分片 / 槽位表 / 重定向 / 冷启动收敛这一套——Envoy RLQS 无开源服务端实现、Sentinel 状态全堆内存、Doorman 不持久化，**不得读成"业界通行做法"**。

另有整个设计层面的一条：**全部结论已落码并逐项验收**（票 01–12 全 resolved；§8 的四验收项／性能三项／§8.5 全表 11 行都有实测读数与出处，见 §11 的未实测清单逐行状态）。纪律不变：**机制正确性由纸面证明 + 仿真背书，实现正确性靠 §8 的验收与 IT**，二者不可互相冒充。

---

## 1. 定位与边界

**定位**：通用中间件——给多实例服务提供"每个 key 一个全局速率上限"的能力。核心模型 = 客户端本地令牌桶（按中心下发的份额速率自补）+ 中心按 key 分片记账与份额分配。

**支持面按"调用边界"定义**：HTTP 入口 / RPC 调用边 / 业务代码里的显式 `acquire()` 都算。**MQ 消费与定时任务扇出是"不推荐接入"而非"不支持"**——语义错配：拒绝一条消息 = nack → 重试 → DLQ 放大；job 扇出的施压者是自己的调度器，加限流只会让它变慢。SDK 的 `acquire()` 对它们开放。

**语义边界（划死于图绘期，Out of scope）**：

- **per-key 语义**：每条规则声明"每个 key 的速率"，命中同一规则的不同 key 各自独立限流。**"聚合一组 key 共享一份 R"在边界之外**——那要把中心账本与份额分配的记账单位从 key 变成组，直接牵动分配算法与账本形状，是另一族语义（与下一条同纪律）。
- **熔断 / 并发数限流 / 自适应限流不做**：rate limiting 与 concurrency limiting 是两族技术，混进来会让 spec 失焦。边界之外是什么：本件只管"单位时间放行多少"，不管"同时处理多少"（并发限流）与"下游坏了怎么办"（熔断）。
- 其余 Out of scope（管理端 UI、客户端鉴权、多语言 SDK、规则审批审计、多租户、服务端主动召回额度）：见 [map](map.md) 的 Out of scope 节，本文不重复。"协议即契约"是它们不做的代价的补偿——其他语言照 §6 协议自实现，v1 只出 Java SDK。

## 2. 总体架构

```
                    ┌─────────────────────────────┐
                    │   配置源（Nacos / 本地文件）  │
                    │  gatekeeper-rules.yml       │
                    │  gatekeeper-topology.yml    │
                    └──────┬───────────────┬──────┘
                     直连（同一条路）   直连
                           │               │
┌──────────┐  按分片拆批  ┌┴───────┐  ┌─────┴──┐  ┌──────────┐
│ 业务代码  │  POST /v1/ │  SDK    │  │ center │  │ center   │
│ tryAcquire│──lease────▶│ client  │─▶│ 分片 A  │  │ 分片 B … │
│ (虚拟线程)│            └────────┘  └────────┘  └──────────┘
└──────────┘            本地桶表       账本 (key,instance,generation)³
                        + renewer        各自独立、不共享状态
```

**组件与模块**：

| 模块 | 职责 | 依赖纪律 |
| --- | --- | --- |
| `gatekeeper-protocol` | 线上协议对象、`ConfigSource<T>` **接口**、规则/拓扑模型 | 零 Spring；**不依赖任何其他 gatekeeper 模块**（只被依赖）。判据：里面每个类型都**跨边界**（线上协议 / 配置分发） |
| `gatekeeper-config` | 规则/拓扑配置源的**实现**（文件轮询 / Nacos 二选一，§5） | 零 Spring、零观测、不依赖 center / spring。**为什么独立成模块**：配置源两端都要连同一份（§5「客户端与中心直连同一配置源」）⇒ 实现**跨两端**，不属于任何一端 |
| `gatekeeper-client` | 本地桶、放行 API、续租守护线程、规则/拓扑订阅 | 零 Spring、零观测依赖（票 09/10 同一条纪律）；**不依赖 center**（包内平铺 + package-private，不套 DDD） |
| `gatekeeper-center` | 分片账本、份额分配、权威校验、`/metrics`；**内层拆 7 个 DDD 模块**（`-types/-api/-domain/-case/-infrastructure/-trigger/-app`，业务域 = lease / allocation / topology） | 引 `micrometer-registry-prometheus`（新独立服务、观测是交付物的一部分——与本仓四工程"零 metrics"先例的**差异是有意为之**，票 09 事实自核）；**不依赖 client / spring**（服务端与 SDK 是两个平等且互相独立的实现） |
| `gatekeeper-spring` | 注解 + 拦截器 + 合一 starter（§9） | Spring 依赖全收在这里；不依赖 center。**不套 DDD**（接入件，没有域） |
| `gatekeeper-demo` | 演示（§9） | —；**只套两节**：§6 根包 `Application` + §4 `trigger/http` |

> 分层**按 artifact 形态分开定**：只有 `gatekeeper-center` 套 `backend/CLAUDE.md` 的 DDD 分模块约定（它是唯一一个真服务）。`protocol`/`client`/`spring` 不套、`demo` 只套两节——理由与例外清单见 [`gatekeeper/CLAUDE.md`](../../gatekeeper/CLAUDE.md)。
>
> ⚠️ **`/v1/lease` 的请求/响应对象（`LeaseRequest`/`LeaseResponse`/`LeaseResult`/`Usage`/`LeaseStatus`/`ReturnRequest`）归 `gatekeeper-protocol`，不归 `gatekeeper-center-api`**：客户端要构造与解析它们，落在 center 侧会让接入方依赖闭包含 center（见下条）。`center-api` 只装客户端不需要的那一半。

**接入方依赖闭包 = protocol + config + client + spring，永不含 center**（写死成规则，票 10 Q3-子；由 enforcer 机械守住——`protocol`/`config`/`client` 的 pom 里 `bannedDependencies` 禁 `io.gatekeeper:gatekeeper-center-*`）。

**一次请求的完整生命周期**（本地命中 → 租约 → 放行 → 租约过期回收）：

1. **冷启动**：SDK 启动生成 `generation`（UUID）、从配置源拿到规则集（拿不到 = 空规则集全放行 + 告警 + 持续重试，§5）。首个请求带某 key 进来，本地无条目 → 调用方线程**同步首租**一次（进合批窗口，默认 10ms；端到端预算 50ms；按分片拆子批），拿到份额即建桶；之后交给后台续租。首租三分法兜底见 §7。
2. **本地命中/放行**：`tryAcquire(key)` 只做本地令牌数学（微令牌整数域、每 key 一把 `ReentrantLock`、锁内不做任何 I/O），返回 `Probe{allowed, remainingTokens, nanosToWaitForNextToken}`。**放行路径零网络往返**。
3. **租约维持**：一条守护虚拟线程每 `refresh_interval`（默认 5s，客户端侧加抖动）醒来，把该实例所有在册 key 的增量用量三字段（`time_elapsed_ms` / `allowed` / `denied`）按分片拆成 ≤ S 个子批，`POST /v1/lease`（每子批一个幂等键）。
4. **中心处理**（每分片独立）：权威校验（非本分片回 `421 redirect`）→ 幂等去重（重复 ⇒ 跳过用量增量、照常重算）→ `min_renew_interval` 过频判（续租命中 ⇒ 返回现有租约状态；首租命中 ⇒ `throttled`）→ 对每 key 跑 **PROPORTIONAL_SHARE** 份额重算（纪律：`minF(gets, unusedCapacity)`，且 unused 必须排除请求者自身——见 §3）→ 返回 per-key 三值状态（`granted` / `unmatched` / `throttled`）。
5. **份额落地**：份额没变 → 本地桶什么都不动、只延有效期；份额变了 → 立即生效 + 本地桶按容量比例缩放存量令牌（`stored = stored × newMax / oldMax`，先乘后除、余数进 `roundingError`）。
6. **回收**：优雅关停 `POST /v1/lease/return` 即删；崩溃未 return 的幽灵条目 ≤ `ttl`（默认 2 × `refresh_interval`）内到期；冷 key（`ttl` 内无任何续租）由中心逐出整条。

## 3. 租约与配额模型

### 3.1 口径：速率租约（票 04）

中心下发的是**份额速率 + 有效期**，不是令牌数。客户端本地桶按份额自补令牌、桶容量 = 份额 × `burstSeconds`。**不做令牌租约，也不做混合**。依据：

1. **同形最近邻下发的正是速率**：Envoy RLQS 的 `QuotaAssignmentAction` 里只有 `rate_limit_strategy`，协议里没有"令牌数"字段；客户端上报也是三个量（`time_elapsed` / `num_requests_allowed` / `num_requests_denied`）。Doorman 的 `Lease` 是 `float64` 的 `Has`/`Wants`（req/s）。
2. **悬浮量由构造上界保证、与实例数无关**（见 §3.3）；令牌口径下在飞量 `f·N·B` 随实例数线性涨。
3. **图绘期的"在飞量上限（默认 2×）"机制在算术上做不到**：可行条件 `B ≤ (λ−1)·K/(f·N)` 在 N=5000 时给 B<1（机制直接失效）；中心租约 QPS 下界 `N/(C·W)` 与 R 无关（5000 实例时每 key 5000 QPS，哪怕该 key 只有 1 QPS 流量）。给"已发出未消耗"设上限的业界只有 Bucket4j 一家；Doorman 承认 `shortfall`（仿真读数逐字：`The maximum capacity handed out was 530.24 (106.05%)`）并明确放弃治理（`Combatting shortfall would mean changing the system in a way which also slows down convergence.`）。⇒ 该机制**反转撤销**（[票 04](issues/04-grill-lease-model.md)）；本设计选的就是它论证不成立的 plan B 完整形态（Doorman 路线）。

**代价先讲清**（票 04）：① 中心账本 O(key × 在册实例数)——正是 [research/03](research/03-algorithm-pitfalls.md) §6.2 判"不可行"的"设计①"形状，选速率租约即认下这笔账（量级见 §3.4）；② 协议必须带身份 + 用量上报 + 按实例聚合的批量；③ 每次续租重跑分配算法；④ SDK 需要后台续租线程。

### 3.2 突发由谁决定：`burstSeconds`（票 04 Q2 / 票 07 Q1）

规则字段 `burstSeconds`（默认 1.0，照 Guava `maxBurstSeconds` 界面："允许几秒的量"）。全局桶容量 K = `R × burstSeconds` 是**派生量，规则里不写裸容量数字**。续租周期（`refresh_interval`）是 SDK/协议参数，**不是限流语义**、不归规则模型。

### 3.3 本地桶与悬浮量上界（票 05 + 票 12）

**本地桶 = 按份额自补的整形桶**（每个实例自己匀速，非脉冲式搬运桶）：

- **微令牌整数域**：1 req = 10⁶ 微令牌，`refillPeriodNanos` 固定 1 秒；补令牌用 Bucket4j 的"余数存回"手法（`divided = refillTokens × duration + roundingError; refill = divided / period; roundingError = divided % period`，`refill == 0` 时把整份 `divided` 存进余数）——整数域零漂移。份额 3.3 req/s 这类实数因此不再是障碍（量化相对误差 ≤ 3×10⁻⁷）。**不抄 Guava 的 `nextFreeTicketMicros` 锚点**——那是为"欠账推到未来"设计的，我们的桶**不预支**。
- **容量公式（含下限，票 12 乙案、用户裁决 2026-09-22）**：`容量 = max(份额 × burstSeconds, 1 个请求)`。起因：份额 × burstSeconds < 1 请求时（条件 = 该 key 实例数 > R × burstSeconds）`tryAcquire` 恒拒——**【仿真】** s5（R=1000/N=5000）实测 99.98% 实例恒拒、翻转前全网放行 = 0。下限**只作用容量、不作用补令牌速率**（微份额行为 = 每 `1/份额` 秒放 1 个、无囤积）；rescale 的缩放比例用**新旧容量（含下限）**算。
- **改份额**：`stored = stored × newMax / oldMax`（Guava `doSetRate` 形状，先乘后除、余数进 `roundingError`）——满度不跳变、缩容不凭空多放。
- **时间口径**：本地用 `System.nanoTime()` 差值；租约只传时长不传绝对时刻；两端各自闭环、不对齐时钟。防回拨一行 `max(0, now - last)`（`now <= lastRefillNanos` 按时间没走处理）。
- **并发**：桶表 `ConcurrentHashMap`，每 key 一把 `ReentrantLock`（不用 `synchronized`——虚拟线程 pin 载体线程）；锁内只做令牌数学；`acquire(timeout)` 睡在锁外。无锁 CAS 留档不做。
- **驱逐**：本地桶表上限 10k（可配），近似 LRU 逐出**整条**（不重置为满桶/空桶）；逐出丢的余量上界 = `max(份额 × burstSeconds, 1 请求)`，方向保守（少发）；**绝不因表满拒绝请求**——拒绝是限流语义的输出，不该被内部容量误触发。

**悬浮量（在飞量）定义**：各实例本地桶里尚未消耗的令牌之和。**上界（票 12 公式 (1')）**：

```
瞬时突发 ≤ Σ max(份额 × burstSeconds, 1 个请求)
正常路径：Σ生效份额 ≤ R（硬不变量）⇒ ≤ R × burstSeconds
降级期（fail-open 陈旧份额 + 再分配并存）：≤ 2R × burstSeconds
```

- Σ租约 ≤ R 是**硬不变量**，其成立的全部是分配算法里"未用量排除请求者自身"这一处——Doorman `algorithm.go:239`（master 快照，2026-09-22 逐字核对）：`unusedCapacity := capacity - store.SumHas() + old.Has`。**落实现时必须逐字写对，否则续租会把已有份额清零。****【仿真】** s1/s2/s3/s3b 四场景全程贴 1.0000000×R。
- "上一轮高份额 + 本轮新份额"的放大**机制上不可能**：份额落地是覆盖式改写（rescale），不存在两轮份额相加的时刻。
- 降级期上界：**【仿真】** s4 全饱和 20 实例断租，降级段稳定 1.1R、与手算逐位吻合。
- ⚠️ **该式不是 `max(R × burstSeconds, 实例数)`**——混合区间两项都存在：s5b（热点 282 + 其余 4999 实例取下限 1）的上界 ≈ **5281 > max(1000, 5000)**，即对错等式的反例；正常区间（份额 ≥ 1/burstSeconds）与原式逐字相同，s5b 实测最坏持有 3572 = **3.6 × (R × burstSeconds)**（5000 实例 × 下限 1 的满闲状态）。
- Doorman 的 106.05% 超发出自其**层级部署**（中间层"上游新额度 < 已发下游的在租额度"）；本设计单层，等价通道只有 fail-open 陈旧份额（≤ 2R 封顶）。

**窗口 Δ 放行量上界（票 12）**：`放行量(Δ) ≤ Σ生效份额 × (burstSeconds + Δ)`（正常 = `R(Δ+burstSeconds)`，降级 ≤ `2R(Δ+burstSeconds)`）——超额是固定一项 `R × burstSeconds`、不随 Δ 增长。准确度验收由它定（§8）。

### 3.4 份额分配算法（票 04 Q13 + 票 12）

**PROPORTIONAL_SHARE（O(n)）**：先保底均分，未用满的余额按需求比例分给超额者（Doorman 逐字：`gets = equalSharePerClient + (r.Wants-equalSharePerClient)*(extraCapacity/extraNeed)`），配两条纪律：`minF(gets, unusedCapacity)`（绝不发出超过当前未用量）+ **服务端以自己为准**（客户端自报 `has` 参与计算但只记日志——Doorman：`client %v is confused: says it has %v, was assigned %v.`）。

- **不选 Sentinel 的 AVG_LOCAL**（静态 R/N）：会饿死吃掉大流量的实例——**【仿真】** s2b 实测：静态 R/N 下热实例钉死 5/s、45% 的 R 闲置。
- **输入 = 上报换算出的实测需求**：上报是计数（RLQS 三字段），中心除以经过时间换算成速率再进公式；`wants` 字段留给**配置基线**（应用自声明），分配取"实测需求"与"配置基线"的较大值。**v1 不做客户端自适应估量**（不抄 Doorman `AdaptiveQPS`）。
- **收敛**（票 12 Q4）：**收敛 = 1 个续租周期**（请求者自己的份额下轮续租即到位）、热实例 ≤ 2 轮、**无震荡**（每轮零基重算 + minF 封顶，无正反馈回路；不会"新份额 = 旧份额 + 被拒量"的欠账累加）。**【仿真】** s2：翻转后 5.01s 达终值 95%、全局放行率 3.77s 回 R±2%。
- **闲置清零（票 12 新发现二，固有性质、不新增机制）**：某实例一个上报窗口零到达 ⇒ wants=0 ⇒ 份额清零 + 存量令牌 ×0，恢复期 ≤ 1 轮拒绝（Doorman 同款；**【仿真】** s5 中 37.2% 实例间歇清零）。缓解 = 配置基线（突发型 key 应声明基线，使用建议见 §9）。

### 3.5 中心账本与容量账（票 04 Q5 / Q8、票 06、票 12 §3/§5）

- **账本形态**：每 `(key, instance_id, generation)` 一条租约记录（分配速率、最近上报**换算出的需求** `wants`、续租时刻、到期时刻）。**单一真相源 = 租约表本身，不养派生计数器**（不抄 Redisson 的 value+ZSET 双线——那要付"全表重算"修复分支）；任何缓存必须标"派生、可重建、不参与分配决策"。
  ⚠️ **2026-09-22 票 04 落地回填**：原来是"最近上报用量"，实现按 §3.4（"输入 = 上报换算出的实测需求"）与 [research/12](research/12-rate-lease-bounds.md) §5.1 的内存账落成 `{share, wants, lastRenew, expiry}`——**原始计数器不落账本**（`allowed/denied/time_elapsed_ms` 进算法前就换算成速率，三个 `long` 换一个 `double`）。
- **内存账**（**2026-09-24 票 12 已 JOL 复核**，`measure/run-memprobe.sh` 可重跑）：**实测 ≈ 190 B/条**（合批 500 key/请求的用法；一次请求一个 key 时 ≈ 285 B/条）。**原手算模型 91 B（Lease 48 + Node 32 + 槽位 11）低估了约一半**——它漏掉三笔：`key` String 及其 backing `byte[]`（≈56 B）、`LeaseIdVO` 对象（24 B）、**内层 map 的槽位**（每 key 只有 n_k 条、table 是 2 的幂 ⇒ 实测 ≈20 B/条而不是按大表负载因子摊的 11）。零件实测：`LeaseEntity` 48 / `LeaseIdVO` 24 / `String` 24 + `byte[]` 32（64 位 HotSpot + 压缩指针、8 字节对齐；JOL 0.17 `magicFieldOffset` 模式）。
  **量级重算**：10k key × 200 实例 = 2M 条 ≈ **380 MB** → 8 分片 **47 MB/实例，仍可行**；5000 实例 × 10k = 50M 条 ≈ **9.5 GB，明确超配**。**两个 10k 默认值的结论不变，数字翻倍**（警示更强）。
  ⚠️ **2026-09-22 票 04 落地补充（数字同步复核）**：上面这条账**不含** §6.3 那两张请求级内存表——**幂等去重表实测 ≈ 190 B/条**（模型 80–120，同样漏了键 String + backing 数组 + 队列结点 + 装箱时间戳），按 10 万条硬上限 ⇒ **最坏 ≈ 19 MB**；`min_renew_interval` 的过频锚点按"窗口内的活跃三元组"计，量级与账本条数同阶、由 Sweep 自清。三张表都在同一批内存预算里，读数见票 04 / 票 12 的 `## Answer`。
- **QPS 账**：全中心 ≤ `实例数 × min(订阅 key 数, 分片数) / refresh_interval`（5000 实例 × 8 分片 / 5s = **8000 QPS**），**与 key 数无关**（封顶于分片数）——批量接口是**硬要求不是优化**（按 (key, 实例) 打点在 10k key × 200 实例下是 200,000 QPS）。
- **CPU 账**（票 12）：`Σ_k n_k²/T` 次条目扫描/秒。**【仿真】** 单 key N=5000 实测 4.69M/s vs 理论 5M/s 吻合；满配算例 ≈ 每分片 2% 核（T=2s 时 ×2.5）。**CPU 不是瓶颈，R 完全不进该式；真约束是内存。** 优化空间留档："份额未变只延有效期"可做 O(1) 判定（代价是再分配延迟一轮），v1 不依赖。
- **持久化**：**v1 不落盘**（重启后账本清空、第一轮按"仅有的上报"保守发放 + 短有效期、靠客户端上报 ≤ 1 个 `refresh_interval` 收敛）；**不抄 Doorman 的 learning mode**（它"不知道 has 就发 0"与我们的 fail-open 默认直接冲突）。重启前给在册客户端下发 `ttl_ms = 0` 预告降级。
  ⚠️ **2026-09-22 票 04 落地回填**：原文写的是"按**均分**保守发放"——那是票 03 占位实现（`R / 在册实例数`）的口径，票 04 换成真算法后**不再均分**：冷 key 的第一个请求者拿到的是 `min(它的需求, R)`，需求报 0 就拿 0。**"保守"全部落在短有效期上**（票 03 已裁：保守 = 1×`refresh_interval` 的 ttl，不是一个更保守的份额估计）；需求侧兜底仍是配置基线（§3.4 / §9）。
  ⚠️ **2026-09-22 票 05 落地回填（首租报什么窗口）**：上面"需求报 0 就拿 0"那句留下了一个空缺——首租那一刻客户端手上**没有**任何上报窗口。票 05 拍的形态是：**首租报"这个 key 从被看见到合批发出"的观测窗口**（`allowed` = 该窗内到达数、`time_elapsed_ms` **至少记 1ms**）。理由：报零（或报 `time_elapsed_ms = 0`，中心按 `DemandVO` 读成实测 0）会让**每个冷 key 被饿一个 `refresh_interval`**；报观测窗口把"这个 key 正在被真的打"这件事带上去，估高的方向由 `minF` 封顶、且下一轮就按真实窗口收敛。代价如实记：**冷启动/多实例同时首触时，这一轮的需求是估高的**（一台实例可能一时拿到接近 R 的份额），≤ 1 个 `refresh_interval` 内自愈。被否的两条：**延后建桶**（首个请求必拒，与 §9 演示口径"半秒 10 个放行 ≤ 3"和 §8.5"首租合批 → 建桶放行"直接冲突）、**拿规则 rate 当 `wants` 报上去**（`wants` 是应用自声明的配置基线，SDK 替应用声明等于把每台实例都写成"我要整个 R"，会把 §3.4 明拒的静态 `R/N` 饿死热实例那一路复活）。
- **续租频率硬下限**：中心侧 `min_renew_interval`（默认 5s）、按 `(key, instance_id, generation)` 逐 key 判（Doorman 文档声明 5s 静默忽略的形状；⚠️ 该条只见于 `doc/design.md`、未在 `server.go` 核实）。具体化语义见 §6。
- **可选旋钮留档（v1 不实现、默认不开，票 06 交接）**："首轮发放总额封顶 `α·R`"（α 默认 1.0、可配 0.5 ⇒ 过渡窗上界收紧到 `(1+α)·R`，代价是恢复期整体偏保守）。仅在重启/迁移过渡窗实测超配时启用。

## 4. 中心的分片与高可用

> ⚠️ **本节核心机制无一手参照可抄**（票 06 Q8，正面声明）：Envoy RLQS 无开源服务端实现（`Open source reference implementation of the rate limiting service is currently unavailable`）；Sentinel 集群流控状态全在堆内存（重启即清空），其 wiki 把分片与 failover 明确推给使用方；Doorman 不持久化不复制、分片归属依赖外部 LB。**槽位表 / 拓扑版本号 / 中心侧权威校验与重定向 / 冷启动收敛这一套是自研**，不得写成"业界通行做法"。可引的两条 Doorman 先例都是**文档声明、未在 `server.go` 核实**，引用处已带 ⚠️。

**裁决**：中心 = 多实例 + **固定槽位分片**（M = 1024，`slot = murmur3(key) % M`）+ 显式「槽→实例」映射表带拓扑版本号 + 中心侧权威校验与重定向 + 客户端按分片拆批续租 + 单槽无副本 + 中心自身只做过载保护。

- **为什么固定槽位**：根决策"中心之间不共享状态"把分片归属逼成纯函数——同一份拓扑所有参与方算出同一个答案。`hash(key) % N` 在 N 变化时全量重排（【手算】6→8 时 3/4 的 key 换家，理想应约 1/4）；一致性哈希环要自写 vnodes 与再平衡、买不到映射表之外的任何东西。**槽位数固定 1024、分片数 = 中心实例数 S 动态**（一个实例就是一个分片，不引入"逻辑分片 id"间接层）。映射表 = 自动均分生成 + 手工覆盖；迁移逐槽搬、可分批灰度；**否掉 `slot % 实例数` 机械派生**（把实例数塞回公式，固定槽位白设）。
- **路由错的防线 = 中心侧权威校验 + 重定向**（本票最关键的一问）：中心先算 `slot(key)` 是否归自己，不是就**绝不接**，回 `421 + {authority, topo_version}`。**不能容忍双发**——票 04 撤销在飞上限后，"两个分片都以为自己是权威"的后果（份额重复计入）没有任何机制兜。客户端跟随判据 = **目标 `topo_version` ≥ 自己的才跟、同一 key 一轮最多一跳**，第二次直接放弃本轮（fail-open）+ 后台强制刷拓扑——封死 `A→B→A` 环（配置源发布顺序错位时必然出现的场景）。先例：Doorman `doc/design.md:208-216` 逐字 `If a non-master task gets a request if will respond with the address of the master, so that the client knows who to talk to.` ⚠️ **仅文档声明、未在 `server.go` 核实**。
- **客户端按分片拆批**：一个 pod 的 key 散在 S 个分片 ⇒ 一次续租拆 ≤ S 个子请求并发发出、每子批一个幂等键。**否掉中心间转发**（N² 耦合、转发风暴，会悄悄破掉"不共享状态"）。
- **单槽无副本、不重路由、不做接管**（已知限制，见 §11）：分片挂掉时该部分 key 的存量 pod 按最后份额 fail-open、**新起 pod 首租失败 ⇒ 拒**；实例回来按冷启动语义重建（客户端上报本来就是分配算法的输入，≤ 1 个 `refresh_interval` 收敛）。将来可扩展形状留档：槽→逻辑分片→实例两层绑定（分区时仍需世代号 fencing，v1 不做）。
- **扩缩容 = 改拓扑、不搬状态**：账本是可在 ≤ 1 个 `refresh_interval` 内从续租请求重建的派生状态；新权威空账本起步、按上报收敛。部署建议（票 06 交接）：**中心实例数 ≥ 3；单实例部署 = 分片退化为 1 片**。
- **重启语义**：见 §3.5（不落盘 + `ttl=0` 预告 + 保守首轮）。重启与单分片挂掉走同一条冷启动收敛路径，区别只在触发面与是否可预告。
- **中心自身保护 = 过载保护、不内建限流器**：① 有界队列 + 快速失败（503 ⇒ 客户端 fail-open）；② 每请求 key 数上限 500（可配）；③ 每实例订阅 key 数上限 10k（可配）。中心 QPS 与 key 数无关（§3.5），"一批塞 10 万个 key"才是能打爆中心的写法——②③比对症得多。

## 5. 规则模型与热更新

**字段集**：每条规则 `{ id?, match, rate, burstSeconds = 1.0, fallback = fail-open }`。

- `match` = `matchType: exact | prefix` 两值 + `pattern`，**不发明通配符语法**；前缀 = 朴素 `startsWith`，用"pattern 建议以分隔符结尾"的文档惯例消歧（加载时对"pattern 是另一 pattern 的前缀且未以分隔符结尾"打告警，不拒绝）。
- `fallback: fail-open | fail-closed`，**只作用于"曾有租约"的断租情形**（fail-closed ⇒ 停止自补、存量花完；**首租失败不分档一律拒**——fail-open 的规则在从未拿到份额时也没有速率可跑）。不做 Doorman `safe_capacity` 式显式保底份额字段。
- **不做"默认规则"对象**：无匹配规则 = 放行本身就是默认语义。

**核心 API 只收最终 key 字符串**：占位符模板与路径规范化归参考接入器层（§9）——解析层放进核心会把"规范化"变成限流器的正确性问题。**前缀匹配不做分段边界判定**（key 是自由字符串，核心不解析内容）。

**规则源与拓扑源**：规则与拓扑共用 `ConfigSource<T>` 抽象（`get()` 原子快照 + `addListener` 变更回调），**客户端与中心直连同一配置源、不从中心拉**——fail-open 要求中心挂掉时规则仍可得，从中心拉会把规则可得性绑死在中心可用性上。双实现**二选一、不是主备链**：Nacos 订阅 dataId（push，依赖 Maven optional）/ 本地文件轮询 mtime+size（1–2s，不用 `WatchService`）。⚠️ **接口在 `gatekeeper-protocol`、两个实现在 `gatekeeper-config`**——因为两端都要直连同一份配置源，实现跨两端、不属于任何一端（裁决见 [ADR-0007](../../docs/adr/0007-gatekeeper-layering-and-protocol-module.md)）。配置对象两个、版本各自独立（由 ConfigSource 实现自产：**两个实现的版本都 = 内容 md5**，与"怎么发现内容变了"无关——文件那支的 size+mtime 只是**指纹**、不是版本）：`gatekeeper-rules.yml`（全部规则一份）/ `gatekeeper-topology.yml`（槽→实例映射表 + 拓扑版本号）。规则版本（协议里带，见 §6）= **规则内容哈希、只做相等比较**；拓扑版本 = **显式声明的单调递增数字**（redirect 跟随判据要比较序，哈希给不了）——两个版本**不合并**。⚠️ **2026-09-23 反转（票 09）**：这条原写「Nacos 用内容 md5 + 单调序号」——那个序号是**各进程自产**的，两端加载次数不同就给出不同版本，同一份规则也常态判「版本错位」（`RULE_VERSION_MISMATCH` 恒增、§8.6 的信号失效），且它被双向发到线上、与本节「版本 = 内容哈希」相悖。序号无任何消费者（变更判定比的是 md5 本身；比序需求归拓扑那支的显式数字）⇒ **去掉，`ConfigSnapshot.version` 直接就是内容哈希**。

**热更新**（继承票 04/05，不设"版本号 + 主动作废额度"机制）：

1. 份额没变 → 只延有效期、视为租约未变（RLQS 语义）；份额变了（含规则改动）→ 换租约立即生效 + 本地桶按容量比例缩放（§3.3）。
2. **客户端收到规则变更回调 → 主动触发一次立即续租**（复用客户端侧抖动防惊群）——没有这条，"热更新立即生效"只剩半截。
3. **规则版本错位**（客户端 v9、中心 v8）→ **接受一个 `refresh_interval` 的窗口、只做观测不阻断**：后果 = 全局放行 ≤ `max(R_old, R_new) × 一个 refresh_interval` **【推演】**，与票 06 的 `2R` 过渡窗同成因、同自愈窗口、同量级，不值得为此养协议状态机（拓扑 redirect 那套硬机制服务的是"双发"这个**正确性**问题，规则错位只是超发瞬态）。"先中心后客户端"的部署纪律不靠。

**缺失 / 冲突 / 写错**：

- **缺失**：没匹配到规则 = **放行**（漏配一条规则不该打死接入方应用）。
- **冲突**：无 `priority` 字段，确定性匹配序 = **精确 > 前缀按 pattern 长度降序 > 声明顺序**，第一条命中即用；加载期能静态检出"永远打不中的死规则"并告警。
- **写错**：**整份 all-or-nothing**——任何一条非法（`rate ≤ 0`、`burstSeconds ≤ 0`、match 非法、id 重复）⇒ 整份拒收 + 告警 + 继续用旧份。**启动行为分裂**：中心 = **fail fast**（配置非法拒绝启动；运行中配置源短暂不可达 ⇒ 继续旧份 + 重试 + 告警）；SDK = **永不因规则拒绝启动**（无规则/首份非法/连不上 ⇒ 空规则集 = 全放行 + 告警 + 持续重试）——库不打死宿主应用。

**规则删除绝不让 key 从"限流"翻成"被拒"**：中心对不匹配任何规则的 key 在续租响应里回显式**「未匹配」状态**（`unmatched`）⇒ 客户端停止续租 + 逐出在册条目 + 该 key 放行。结果：删除规则 = 全网一个 `refresh_interval` 内收敛为不限流、无拒绝窗口。

## 6. 协议契约（协议即契约）

其他语言照本节自实现（v1 只出 Java SDK）。**两个端点** + **下行搭续租响应的便车、不建第二通道**（SSE / WebSocket / 长轮询都不做——所有依赖下行的窗口都已接受 `refresh_interval` 量级，"份额变了"由续租响应天然携带 + §5 的变更回调触发立即续租；`ttl_ms = 0` = 重启预告。**下行的全部内容 = 响应字段值，没有额外消息类型**）+ **路径大版本 `/v1/`** + **snake_case**。

### 6.1 端点

- **`POST /v1/lease`** 兼做首租与续租：首租在中心眼里就是"账本里没有 `(key, instance_id, generation)` 记录的续租"。分两个端点只会让首租合批与续租批走两条码路。
- **`POST /v1/lease/return`** 只做下线归还：批量、天然幂等（退不存在的记录 = no-op）、**不加幂等键**、fire-and-forget（关停钩子 best-effort 发一次、不等响应、失败即弃）。同样受权威校验（421，客户端跟随一跳即可，跟不动就弃——条目由 ttl 到期兜底）。中心收到即删条目并立即重算份额。
- **规则查询端点不做**（客户端直连配置源，§5）。

### 6.2 字段

请求（`POST /v1/lease`）：

```json
{
  "instance_id": "pod-7f3c",
  "generation": "9a1b2c3d-…",
  "topo_version": 12,
  "rule_version": "8f3a91c2…",
  "idempotency_key": "d4e5f6a7-…",
  "keys": [
    { "key": "api:/orders/create",
      "usage": { "time_elapsed_ms": 5000, "allowed": 4200, "denied": 37 } }
  ]
}
```

响应（200）：

```json
{
  "topo_version": 12,
  "rule_version": "8f3a91c2…",
  "refresh_interval_ms": 5000,
  "results": [
    { "key": "api:/orders/create", "status": "granted", "share": 12.5, "ttl_ms": 10000 },
    { "key": "api:/orders/cancel", "status": "unmatched" },
    { "key": "api:/orders/pay",    "status": "throttled" }
  ]
}
```

| 字段 | 裁决 |
| --- | --- |
| `instance_id` | 来源链 `POD_NAME` → `HOSTNAME` → IP 回落 |
| `generation` | SDK 启动 UUID；账本键三元组之一。⚠️ **不带"作废"语义**（票 08 反转了票 04 Q11② 的"世代作废"机制：速率口径下幽灵条目只稀释份额、安全侧少发，≤ ttl 内到期；优雅关停有 return + `ttl=0`；主动作废是第二套机制买同一样东西） |
| `usage` 三字段 | RLQS 逐字、**增量语义**（票 06 Q13：不改报累计值——用户否掉了"累计值天然幂等"的推荐，选付一张去重表保 RLQS 字段语义） |
| `wants`（**逐 key 可选**） | **配置基线**（应用自声明的基础需求速率，req/s）：分配取"上报换算出的实测需求"与它**较大值**（§3.4）。⚠️ **2026-09-22 票 04 落地补进本表**——上面的请求示例没列它，是 §6.2 的缺口而不是新裁决（§3.4 逐字有"`wants` 字段留给配置基线"、CONTEXT.md 有词条；按 §6.4「只增可选字段」上线，不带它照旧工作）。客户端自报 `has` 那条**不上线**：中心账本是份额的唯一真相源，"服务端以自己为准"由结构保证（算法只读账本），Doorman 那条 `client %v is confused` 日志因此没有输入 |
| `share` | double（JSON 友好）；SDK 内部 10⁶ 定点化、**量化一律向下取整**（舍入方向永远保守） |
| `ttl_ms` | 只传时长不传绝对时刻；默认 = 2 × `refresh_interval`、每次成功续租重置（容忍丢一轮续租）；重启后短有效期 / 新权威首轮短 TTL 落此字段；**`ttl_ms = 0` = 主动降级信号**（客户端停止自补、存量花完——与"被动超时无响应 = 继续按最后份额跑"是两种可区分状态，否则 `ttl=0` 在 fail-open 口径下退化成空操作） |
| `refresh_interval_ms` | **仅观测**——客户端不信任中心的临时状态，节奏以自己配置为准 |
| per-key 状态 | **三值枚举**：`granted`（`share = 0` 是合法值、无 `retry-after`——速率口径下"建议等待"没有锚，等待语义归本地桶 `nanosToWaitForNextToken`）/ `unmatched`（逐出 + 放行，§5）/ `throttled`（首租命中 `min_renew_interval` 过频窗；**绝不能被读成 share=0——误停自补——也不能读成 unmatched——误逐出**） |

### 6.3 超时、重试与幂等

- **续租**：每子批超时 **2s**（< `refresh_interval`），周期内最多重试 1 次（退避 ~1s + 抖动），仍败走 fail-open 等下一周期——不做重试风暴。**首租**：在业务请求同步路径上 ⇒ **端到端预算 50ms**（合批窗口默认 10ms 封在其内），重试复用同一幂等键，仍失败按 §7 拒。
  ⚠️ **2026-09-22 票 05 落地实测（进程内首次往返）**：JDK `HttpClient` 在**一个进程里的第一次** HTTP 往返要付一笔一次性初始化，本机实测 **~400ms**（同进程后续客户端实例首次往返 ~7ms）——比首租那 50ms 预算大一个量级。⇒ SDK 起来时在后台**垫一次往返**（`GET /v1/lease` ⇒ 中心只有 POST 映射、回 `405`；**gatekeeper 域内不改任何状态、不记任何计数**，虽说通用 HTTP 指标（如 actuator 的 `http.server.requests`）照样会把它算进去），把平台冷启动挪出业务路径。**残余风险如实记**：SDK 起来之后立刻就有冷 key 到达时（中间没有间隔），那一批仍会按 §7 三分法被拒一次——方向安全（拒而不是超发），但发版后头一批冷 key 会被拒一回，属已知形态。
- **幂等键** = 每个按分片拆出的子批一个 UUID（客户端生成、重试复用、新周期新键）。
- **中心侧去重**：条目 = `idempotency_key → 时间戳`（≈ 80–120 B/条 **【推演，未 JOL】**），保留期 = **一个 `refresh_interval`**（重试跨度上界 ≈ 3s，足盖）+ 硬上限 10 万条、逐出最旧。量级 **【推演】** ≈ 4 万条 ≈ 4 MB。该表**不参与分配决策**（是请求级缓存，不是派生计数器）。**重复到达 ⇒ 跳过用量增量记账、其余照常走**（重算份额 + 返回当前状态）；收到请求即插表、不区分处理成败（漏记增量的方向 = 中心低估需求 ⇒ 少发，安全侧）。
- **`min_renew_interval`（默认 5s）**：按 `(key, instance_id, generation)` 逐 key 判。**续租命中 ⇒ 不重算份额 + 用量照记 + 返回现有租约状态**（真返空响应会被客户端误读成超时触发 fail-open 判定）；**首租命中 ⇒ `throttled`**。契约：客户端 `refresh_interval` 不应低于 `min_renew_interval`。
- **请求级错误**：`421 + {authority, topo_version}`（跟随判据见 §4）；`400` = 超每请求 key 数上限 500（客户端理应不触发，触发即契约违约、走 fail-open）；`503` 与一切 5xx / 超时 ⇒ fail-open。

### 6.4 版本化与兼容承诺

大版本在路径（`/v1/`），body 不带协议版本号。**v1 兼容承诺三条：只增可选字段 / 不改既有字段语义 / 不删字段；消费方义务 = 忽略未知字段**（"只增不破坏"成立的前提）；不兼容变更 = `/v2` 并存过渡、不原地改。

## 7. 降级与故障行为（矩阵）

| 故障 | 行为 | 收敛/恢复 |
| --- | --- | --- |
| **中心整体不可用**（宕机/网络分区） | 续租失败 → 周期内 1 次重试 → **fail-open：继续按最后份额自补、继续放行**；规则本地仍可得（§5），限流语义不丢 | 中心回来 ≤ 1 个 `refresh_interval` 收敛 |
| **主动 `ttl_ms = 0`**（中心活着且明确说该租约作废，如重启预告） | **停止自补（份额视作 0）、存量允许花完** | 下次续租带回新份额即恢复 |
| **单分片不可用** | 存量 pod = 上行 fail-open；**新起 pod 首租失败 ⇒ 拒**（已知限制）；客户端**不重路由**、不做槽级接管 | 实例回来后 ≤ 1 个 `refresh_interval` 冷启动收敛 |
| **中心重启** | 重启前 drain 窗口内所有响应带 `ttl_ms = 0`（预告降级）；重启后第一轮均分保守发放 + 短 `ttl_ms` | 客户端上报回来后 ≤ 1 个 `refresh_interval` 收敛 |
| **规则源不可用** | 中心：继续旧份规则 + 重试 + 告警（运行中）；SDK：空规则集 = 全放行 + 告警 + 持续重试 | 配置源恢复即收敛 |
| **规则热更新（版本错位）** | 只观测不阻断（§5） | 一个 `refresh_interval` 窗口自愈 |
| **规则删除** | 中心回 `unmatched` ⇒ 逐出 + 放行；**无拒绝窗口** | 一个 `refresh_interval` 收敛 |
| **客户端重启** | 新 `generation` 在册；旧幽灵条目 ≤ `ttl` 内到期，期间全局份额被**稀释（安全侧少发）** | 幽灵过期后一个 `refresh_interval` 内份额恢复 |
| **客户端优雅关停** | `POST /v1/lease/return` 即删 + 不再续租 | 即时 |
| **fail-closed 规则断租** | 停止自补、存量花完（规则级 `fallback` 字段，只作用"曾有租约"情形） | 断租恢复即恢复 |

**首租三分法**（贯穿上表的兜底，三格都可测）：

| 情形 | 动作 |
| --- | --- |
| key 不在任何规则里（或 SDK 本地无规则集） | **放行** |
| key 有规则、但首次在册失败（中心不可达 / 首租超时 / `throttled`） | **拒绝 + 记指标**（不分 `fallback` 档；无份额可依据，"按规则自跑"没有速率可用） |
| 曾经在册过、后续断租 | 继续按最后份额（= fail-open；fail-closed 规则见上表末行） |

**残余风险如实记**：① 分区前份额远大于 `R/N` 的实例（它是该 key 唯一活跃者）在 fail-open 期间会按那个大份额跑；② 闲置清零恢复期 ≤ 1 轮拒绝（§3.4）；③ 挂掉分片上的新 pod 在实例回来前拿不到份额。

## 8. 可观测与验收

### 8.1 设施（分裂式，票 09）

- **center**：引 `micrometer-registry-prometheus`，`/metrics` 常开；另挂持续 JFR（照本仓 `bs-vt-recording.jfc` 手法新建 gatekeeper 版）。
- **SDK 零观测依赖，三件套**：内置原子计数器 + 默认 **MXBean**（开箱能看）+ **`GatekeeperObserver` SPI**（能接走：放行/拒绝/降级/续租结果/逐出事件）。
- **验收不起 Prometheus**：窗口起止各抓一次 `/metrics` 文本算差值（client 侧读 MXBean）；Prometheus/Grafana 归部署/展示面。
- **key 维度不进指标**（tag 基数爆炸）；tag 只允许 `rule`（人工管理的个位～两位数）。key 级排查 = 日志采样（续租/拒绝事件带 key）+ `GET /admin/keys?top=N` 快照端点（按水位/份额排序，中心从账本直读）。

### 8.2 指标清单（每项回答一个问题，票 09 定稿）

**中心侧**（命名 `gatekeeper.<域>.<名>`）：份额发放合计（tag=rule，与客户端放行合计对照、偏差暴露幽灵条目/逐出异常）；首租结果计数 `{result=granted|throttled|fail}`（⚠️ **2026-09-23 票 09 落实现时核过**：**中心侧只会出现 `granted` 与 `throttled`**——过频判那两支。`fail` 那一格是"首租没能完成"（中心不可达 / 超时），它**不发生在中心里**，读数只有客户端侧有（上面的"首租延迟 + 失败率"，票 05 落的）；中心侧凭空注册一条永远为 0 的序列比不注册更坏——照它建告警的人会以为自己在盯一个会动的东西）；在册条目数/在册实例数（gauge，分片维度）；冷驱逐/ttl 过期/return 回收计数（按 cause 分）；`421` 重定向计数（持续非零 = 拓扑不同步）；幂等去重命中计数；`400` key 超限计数；规则版本错位计数 + 配置整份拒收计数；续租请求处理时延（timer）。

**客户端侧**（counter + MXBean，实例级聚合）：放行计数（**准确度验收主观测点**）；被拒计数；桶水位 `remainingTokens`（sum + max；**sum = 悬浮量**，上界 `Σ max(份额 × burstSeconds, 1)`）；`acquire` 等待分布；首租延迟 + 失败率；续租失败/降级计数（常态应为 0，非零即中心/网络问题）；桶表条目数 + 逐出计数。

### 8.3 准确度验收（票 09 框架 + 票 12 回填数值）

- **判据**：稳态窗口 Δ 内平均放行率 ∈ `[R, R×(1+ε)]`，**不按秒断言**。**Δ 写公式不写常数：`Δ ≥ burstSeconds / ε`**（窗口超额 ≤ `R × burstSeconds` 固定一项，票 12）；默认参数（`burstSeconds=1.0`、ε=2%）下 **Δ = 60s、ε = 2% 定稿**（Δ ≥ 50s，取 60s 留余量；`burstSeconds` 配 10 时要 500s）。降级窗口不进本验收（归 §8.5 故障验收，恢复口径 ≤ 1 个 `refresh_interval`）。
- **观测点**：各客户端实例放行计数**合计**为主（用户感知口径）+ 中心份额发放为**对照**（偏差本身就是诊断信号）。
- **瞬时突发**：栅栏同步起跳后 1s 窗口放行增量 ≤ `Σ max(份额 × burstSeconds, 1)`——构造上界由 §3.3 保证，测的是实现没把它做穿。⚠️ harness 的 rate 配置要让每实例份额 ≥ 1/burstSeconds（4 JVM 时 rate ≥ 4/s），否则落在微份额区间、读数对不上正常区间的式子。
- **过渡窗超发 ≤ `2R`**：中心重启后一个 `refresh_interval` 内的放行总量——**实测验收项**（不是纯推导；票 12 复核：`2R` 指速率上界，"窗口"读作收敛时间，分区情形态持续到恢复）。
- **世代交替**：崩溃重启后稀释窗口内**不超发**、幽灵条目过期（≤ 2 个 `refresh_interval`）后一个 `refresh_interval` 内份额恢复。
- **实测读数（票 10 长档，`measure/results/harness-20260923-211518`；R=40/s、4 实例、单分片、每臂 300s 稳态）**：准确度 **39.983 req/s** ∈ `[39.867, 40.8]`（60s 窗 2399 次、270s 长窗 39.996；中心份额发放合计 2457.8 作对照）；整臂 Σ份额 **40.0 ≤ R**；瞬时突发 1s 窗口 **77 ≤ 80**（水位峰值 **39 ≤ 44**——那一格的上界取混合区间的保守式 `Σ份额 + 实例数`，见臂 A 的 `tokensSumPeakBound`；同判据的 §8.3 原文式 = `Σ max(份额×burstSeconds,1)` = 40）；过渡窗 **42 ≤ 2R = 80**（断租期 fail-open 39.7/s、重启后一轮回到 40.1）；`ttl=0` **0.149/s** vs 断租 **10.0/s**；世代交替稀释窗 **32 ≤ 40**、幽灵过期后 **5000ms** 内恢复到 40.0。逐项证据与脚本口径见票 10 的 `## Answer`。⚠️ **突发那一项判据按 §3.3 的窗口式**（`Σ份额 × (burstSeconds + Δ)`）：本节原文的上界 `Σ max(份额×burstSeconds,1)` 是 Δ→0 的"瞬时"上界，用在 1s 窗口上漏了补令牌项（差 `R×Δ`）——`measure/README.md` 坑 15 有完整推导。

### 8.4 harness 与标定复核（票 08 拍定值 → 票 09 复核方法）

- **harness**：单机 1 center 进程（单分片起步）+ 4 个 SDK 客户端 JVM（可调档 2–8），`POD_NAME` 显式身份；`CountDownLatch` 栅栏起跳的闭环突发 + 固定速率稳态段，**每臂稳态 ≥ 5 分钟**（≥ 60 个 `refresh_interval`）。一个 harness 覆盖四个验收项（突发 / 过渡窗 / `ttl=0` 主动停 vs 断租被动继续 / 分区 fail-open）。
- **性能值实测三项**（`gatekeeper/measure/` 微基准）：① 首租端到端 p99 ≤ 50ms（合批窗 10ms，空载 + 带载各一档）；② 满配 500 key/请求的续租批端到端 ≪ 2s 子批超时；③ 中心单分片续租吞吐 ≥ 200 QPS（1000 实例 ÷ 5s 算例）。
- **策略值走场景语义四项**（不另立基准）：`refresh_interval` 5s / `min_renew_interval` 5s / `ttl = 2×` / 去重窗——判据即 §8.5 场景行为成立。
- **容量默认值**（经票 12 §5 内存账复核通过）：每实例订阅 key 数上限 **10k**（触发 = 显式拒绝 + 指标可见）；本地桶表上限 **10k**（同值一个旋钮两处引用；超限 LRU 逐出、绝不因表满拒绝）。
- **实测读数（票 10 微基准，长档 `results/microbench-20260923-203410`）**：首租端到端 p99 **32.45ms（空载）/ 17.38ms（带载）** ≤ 50ms；500 key/请求的续租批 p99 **20.74ms** ≪ 2s 子批超时；单分片续租吞吐 **233.69 QPS ≥ 200**（按高于算例的 240 QPS 派发、失败 0）。长档第二轮复核（`-213648`）32.84 / 17.07、19.86、239.96，同判。逐项见票 10 的 `## Answer`。
- ⚠️ **容量默认值的复核口径修正（票 12，2026-09-24）**：上面两个 10k **仍然成立**，但"每条多少字节"的**手算模型低估了约一半**——JOL 实测中心账本 ≈ **190 B/条**（模型 91）、去重条目 ≈ **190 B/条**（模型 80–120）、客户端桶表 ≈ **239 B/条**（模型 92）。逐条分解与结论见 §11 的未实测清单行。

### 8.5 故障验收：两档执行形态

**能进 IT 的进 IT 才有回归防线**（virtual-thread effort 票 10 的教训）；真进程级故障脚本化 + 人工走查，读数留票面。

| 场景 | 执行形态 | 期望行为 |
| --- | --- | --- |
| 同步首租拦截（冷启动第一个请求） | 自动化 IT | 有规则但首租失败 ⇒ 拒 |
| 规则热更新 | 自动化 IT | 份额变立即生效 + 桶按容量比例缩放 + 回调触发立即续租（带抖动） |
| 规则删除 | 自动化 IT | 一个 `refresh_interval` 内收敛为放行、无拒绝窗口 |
| `ttl=0` 主动停 vs 被动到期继续 | 自动化 IT | 两行为可区分 |
| 世代交替（崩溃重启） | 自动化 IT | 稀释窗不超发、幽灵过期后一个 `refresh_interval` 恢复 |
| 客户端重启 / 桶表逐出后重遇 | 自动化 IT | 重遇走冷启动首租路径 |
| 杀 center | chaos 脚本 + 人工走查 | 按最后份额继续；center 回来 ≤ 1 个 `refresh_interval` 收敛 |
| 双 center 起两分片再杀一个 | chaos 脚本 + 人工走查 | 存量 pod fail-open；新起 pod 首租失败 ⇒ 拒；恢复 ≤ 1 个 `refresh_interval` |
| 网络分区 + 恢复 | chaos 脚本 + 人工走查 | 分区期间按最后份额跑；恢复后一轮内收敛 |
| 中心配置非法 | 自动化 IT | fail fast 拒启动 |
| SDK 首份配置非法/规则源连不上 | 自动化 IT | 空规则集全放行 + 告警 + 持续重试 |

**执行证据**：全表 **11 行** = **8 行自动化 IT**（票 11 的常态化回归套件 `Spec85ClientRegressionTest` / `Spec85CenterRegressionTest` / `RuleConfigSourcesTest`；票 11 的"七格"口径把两行"配置非法"算作一格——中心半档与 SDK 半档各落在一个套件里）+ **3 行 chaos**（`measure/run-chaos.sh`，票 12，2026-09-24 跑通、读数与人工走查记录见该票 `## Answer`）。**走查口径的一处修正（票 12）**："center 回来 ≤ 1 个 `refresh_interval` 收敛"**只能在中心侧量**（账本 `Σ份额` 回到 `Σ需求`）——客户端在断租期走 fail-open、放行率一秒都不掉，"按客户端放行率恢复"去量这一项会读到 0ms 的假通过。

### 8.6 资产与"坏的时候怎么看"

- **资产**：脚本 + README（坑清单）落 `gatekeeper/measure/`（随 subtree split 走），产物不入库；**验收不常态化**（一次性跑、读数留票面、脚本可重跑），**常态化的是观测**（JFR + `/metrics` + MXBean）。**2026-09-24 票 12 补全**：chaos 脚本（`run-chaos.sh`，三个真进程级故障场景）与内存账复核（`run-memprobe.sh`，JOL）同批落 `measure/`，读数与人工走查记录见票 12 的 `## Answer`。
- **人工手册：坏的时候怎么看**（按 §8.2 清单组织，逐条给"看哪个读数 → 它长什么样 → 指向什么 → 下一步查什么"）——

#### 8.6.1 接入方侧（MXBean + `GatekeeperObserver` SPI）

读数两个去处：**MXBean**（拉，现状）——`jconsole` 或按 `io.gatekeeper.client:type=GatekeeperClient,id=<n>` 从平台 MBeanServer 取本实例那一个；**SPI**（推，刚刚发生了什么）——五类事件（放行/拒绝/降级/续租结果/逐出），接在 `Builder.observer(...)` 上（回调在热路径上，实现要快）。

| 读数 | 常态 | 异常形态 → 指向 → 下一步 |
| --- | --- | --- |
| `AllowedCount` / `DeniedCount` | 被拒非零且随流量涨 | **这就是限流在工作**（预期）。被拒非零而这条 key 本不该被限 ⇒ 规则配小了（`rate` / `burstSeconds`），或同一 key 有多个实例在分份额 |
| `RenewFailureCount` / `DegradeCount` | **0** | 非零 = 中心不可达 / 网络问题。此时仍按最后份额 fail-open 放行（用户感知不到），但**份额不再更新**：规则改了不生效、拓扑改了不跟随 ⇒ 查中心存活与网络路径 |
| `FirstLeaseFailureCount` | 0（冷启动头一两笔在 50ms 预算上贴边，不算） | **持续增长** = 分片挂了或中心不可达 ⇒ 该 key 一律被拒（§7 三分法第二格）。查 `/admin/keys` 有没有它的条目、查分片存活 |
| `AcquireWaits` / `AcquireWaitMaxMillis` | 只有走 `acquire(timeout)` 的调用方会记 | 等待变长 = 放行延迟体验变差（份额不够、或流量结构变了）。这不是拒绝，是慢 |
| `BucketEvictions` | 0 | 非零 = key 面超出桶表上限（默认 10k），LRU 逐出整条 ⇒ 被逐出的 key 下次到达走冷启动首租（多一次同步往返、可能被拒一次）。调大上限，或把 key 收敛到规则允许的粒度 |
| `RedirectCount` / `RedirectGiveupCount` | 0 | 持续非零 = 客户端拓扑与中心不一致（配置传播滞后）；**Giveup** = 跟不动（目标版本更旧 / 一轮一跳用尽）⇒ 这些 key 本轮被拒，查拓扑源分发 |
| `BucketTokensSum` / `BucketTokensMax` / `BucketCount` | 随流量波动 | `TokensSum` = **悬浮量**（上界 `Σ max(份额×burstSeconds, 1)`，§3.3）；长期贴着上界 = 份额明显大于实际到达（额度浪费）；恒为 0 = 桶被抽干（份额不够） |

#### 8.6.2 中心侧（`/metrics` + JFR）

抓法：窗口起止各抓一次 `/metrics` 文本、**算差值**（§8.1 的验收口径，不引 Prometheus）；JFR 持续录制（profile 在 `measure/gatekeeper-recording.jfc`）。

| 读数 | 异常形态 → 指向 → 下一步 |
| --- | --- |
| 份额发放合计（tag=rule）对照客户端放行合计 | 两者应大致同步。**偏差持续** = 幽灵条目 / 逐出异常（中心账面在发、客户端没有对应放行，或反之） |
| 在册条目数 / 在册实例数（gauge，分片维度） | **不收敛（只涨）** = ttl 过期或驱逐没生效，或客户端异常退出后条目没被回收 |
| 回收计数（按 cause：`ttl 过期` / `冷驱逐` / `return`） | `ttl 过期`偏高 = 客户端异常退出或长时间不可达（正常续租不会让条目过期）；`冷驱逐`偏高 = 拓扑/规则频繁变化；`return` 高 = 下线/滚动发布多 |
| `421` 重定向计数 | 持续非零 = 拓扑不同步（还有客户端按旧拓扑发） |
| 幂等去重命中计数 | 持续偏高 = 重试量 / 重复发送量（网络慢到子批 2s 超时在重试） |
| `400` key 超限计数 | 非零 = 有接入方一次塞超过 500 个 key（契约违约，那一批走 fail-open） |
| 规则版本错位计数 | 持续非零 = 配置传播滞后（两端加载的规则内容不同）。⚠️ 该信号**只在 `rule_version` = 内容哈希之后才可信**（§5 的反转：带序号时它恒非零） |
| 配置整份拒收计数 | 非零 = 有人往规则/拓扑里写了非法内容（那份被整份拒收、仍在用旧份，§5 的 all-or-nothing） |
| 续租请求处理时延（timer） | 中心侧变慢 ⇒ 客户端子批 2s 超时更易触发 ⇒ 续租失败率上升 |

#### 8.6.3 单 key 排查（日志采样 + `GET /admin/keys?top=N`）

- **日志**：续租与拒绝事件带 key。拒绝日志每实例每规则每 `refresh_interval` **≤ 1 条 WARN**（rule / finalKey / 窗口内拒绝计数 / `Probe` 三字段）——防刷靠**计数**不靠日志洪水，所以"日志里没有"不等于"没被拒"，要看计数。
- **快照**：`GET /admin/keys?top=N` 按份额降序给出该 key 的**中心视角**：几条在册、各条份额、`instances`。
- 三个常见结论：① **中心查不到该 key** = 客户端没在续租它（规则不匹配、或桶已被逐出）；② **份额远小于预期** = 同 key 有多个实例在分（份额**按 key** 分，§3.4），或需求报 0 被闲置清零（§3.4 的固有性质）；③ **中心有份额而客户端仍在拒** = 两端规则内容不一致（`rule_version` 对不上）或那个客户端把该 key 逐出了。

## 9. 参考接入器

> 本节是"形状"不是 better-school 改造步骤（落地票另拆）。**用建议**（票 12 新发现二的落点）：**突发型 key（到达率波动大、会被闲置清零伤到的）应声明配置基线**（`wants`），分配取实测与基线的较大值——不新增机制，只是规则作者的使用建议。

**三者无第二套放行路径**：显式 API 是底座（`client.tryAcquire(finalKey)` → `Probe`；`acquire(timeout)` 纯本地等、只给非 Web 场景）→ 注解是显式 API 的语法糖（`@RateLimit(rule=…, key=…)`，**只做方法级**）→ 拦截器是注解的宿主 + URL 级规则的承载者（方法上有注解用注解、没有按 URL 规则匹配 → 解析最终 key → `tryAcquire` → 拒绝走 `RejectionHandler`）。**Filter 不做**（拿不到 handler method、会把路径模板化弄丢）；**只做 Spring MVC（Servlet）侧，WebFlux 不做**。

**key 规范化**：路径模板化**读 MVC 现成的最佳匹配模板**（`request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE)`），不自写分段判定；身份 = **`KeyResolver` SPI + 两内置**（`PrincipalKeyResolver` 读 Spring Security 上下文 / `AnonymousKeyResolver` 客户端 IP 兜底），占位符两类 = 路径变量 + 身份（`{postId}` / `{userId}` / `{ip}`）可同用；注解不写 key 时默认 = 身份占位符。

**Spring 接法**：第四模块 `gatekeeper-spring` **合一 starter**（`@AutoConfiguration` + `@ConditionalOnClass(GatekeeperClient)` + `@ConditionalOnProperty(gatekeeper.enabled, matchIfMissing=true)`）；`gatekeeper-client` 保持零 Spring 依赖；**接入方依赖闭包 = protocol + config + client + spring、永不含 center**。配置：

```yaml
gatekeeper:
  enabled: true
  center:
    addresses: http://center-1:8080,http://center-2:8080
  rules:
    source: file          # file | nacos（票 07 双实现二选一，Nacos 依赖 optional）
    path: classpath:gatekeeper-rules.yml
  refresh-interval: 5s
```

**拒绝响应**：`RejectionHandler` SPI（接口收 `(request, response, rule, finalKey, probe)`），**通用件不规定业务错误码**；默认实现 = HTTP 429 + `Retry-After: ceil(nanosToWaitForNextToken → 秒)、至少 1` + `{"code":"RATE_LIMITED"}`（不猜业务错误码，换 SPI 即可换任意形状）。拒绝日志每实例每规则每 `refresh_interval` ≤ 1 条 WARN（rule / finalKey / 窗口内拒绝计数 / `Probe` 三字段）——防刷靠计数不靠日志洪水，拒绝计数进 MXBean。

**演示**（`gatekeeper-demo` 模块，gatekeeper/ 仓内、不碰 better-school；README 给 curl 步骤）：一条规则 `rate=2/s, burstSeconds=1.0`（单实例份额 2/s、桶容量 2），10 个请求半秒内连打 → **放行 ≤ 3、其余 429**；单 key（单分片）时约 10s 窗口内 `/v1/lease` 请求 ≤ 3（首租 1 + 两轮续租）。**「本地优先」验收两观测点**：① **比值口径**——放行 N 次 vs 中心 `/v1/lease` 次数 = 首租 1 + 每实例每 `refresh_interval` 每分片子批 1 次，与放行次数无关（从 center `/metrics` 读）；② **断中心口径（更强）**——kill center 后继续按最后份额放行（fail-open 就是"本地优先"的定义本身）。演示口径两步：**拦得下来 + 杀了中心还活着**。

## 10. 落地票拆分倾向（不立票）

spec 定稿后另起 `.scratch/gatekeeper-impl/` 拆（本 effort 不立票）。倾向（按依赖序、可并行的标注）：

1. **核心本地桶**（`gatekeeper-protocol` + `gatekeeper-client`）：微令牌桶、`Probe`、双 API、`ReentrantLock`、时间口径、首租三分法骨架、桶表驱逐。——全设计的地基，先落。
2. **协议 + 中心账本 + 分配算法**（`gatekeeper-protocol` 契约对象 + `gatekeeper-center`）：两端点、三值状态、幂等去重、`min_renew_interval`、PROPORTIONAL_SHARE（**minF 含 `+ old.Has` 逐字**）、账本三元组。——单分片即可全链路跑通（先不拆分片）。
3. **客户端续租通道**：守护虚拟线程 renewer、按分片拆批、抖动、fail-open 衔接、`ttl=0`/被动到期两态。——与 2 合起来即"单分片端到端"。
4. **规则源与拓扑**（与 2/3 可并行）：`ConfigSource<T>` 双实现（Nacos optional / 文件轮询）、规则/拓扑两对象、匹配序与 all-or-nothing、变更回调触发立即续租、热更新 rescale。
5. **分片与拓扑感知**（依赖 2/3/4）：槽位表、权威校验 + `421` redirect、拓扑版本号、客户端拆批。
6. **参考接入器**（依赖 1/4）：`gatekeeper-spring`（注解/拦截器/starter/`RejectionHandler`/`KeyResolver`）+ `gatekeeper-demo`。
7. **观测**（随各票带上，收口票归拢）：SDK 三件套（计数器/MXBean/SPI）、center `/metrics`、JFR。
8. **验收与终验**：`gatekeeper/measure/` harness（§8.4 三项标定 + §8.5 IT 场景）、IT 常态化回归、末票做 §8 全量走查（照 virtual-thread 票 16 终验先例）。

与上游票的接口核对（票 12 交接）：上界公式用 `(1')` 形式（§3.3 ✓）、Δ 写公式不写常数（§8.3 ✓）、配置基线建议进参考接入器节（§9 ✓）。

## 11. 已知限制与未实测清单

**已知限制（设计承认、不是缺陷）**：

1. **单槽无副本、无接管**（§4）：挂掉的槽在实例回来前不重新分配份额，且新客户端拿不到份额（首租失败 ⇒ 拒）。v1 不做副本；将来扩展形状（两层绑定 + fencing）已留档。
2. **过渡窗 `2R`**：重启/迁移/分区期间全局放行速率上界翻倍（fail-open 陈旧份额 + 再分配并存），持续到收敛/恢复为止。可选 `α·R` 旋钮留档未实现（§3.5）。
3. **闲置清零**（§3.4）：零到达轮份额清零、恢复期 ≤ 1 轮拒绝。缓解 = 配置基线声明（§9 使用建议）。
4. **微份额区间的上界放大**：`Σ max(份额×burst, 1)` 的上界在混合区间抬到 `R×burstSeconds + 微份额实例数`，s5b 实测持有峰值 3572 = 3.6×(R×burstSeconds) **【仿真 s5b】**——正常区间不出现；部署上避免"实例数 > R × burstSeconds"的规则即可。
5. **v1 不落盘**：中心重启丢失账本，依赖 `ttl=0` 预告 + 保守首轮 + 上报收敛；宕机（不可预告）期间的形态见 §7。

**⚠️ 2026-09-24 票 12 逐条复核（限制仍在、代价如实）**：

| # | 复核结论 | 证据 |
| --- | --- | --- |
| 1 | **仍在**。新起 pod 撞上分片不可达时**一律被拒**（不是"慢"、不是"降级放行"），实测放行率 **0.0/s**、首租失败 **1534** 次 | 票 12 chaos 场景 2 长档（`run-chaos.sh --scenario=2`，`results/chaos-20260924-232637`） |
| 2 | **仍在，但本仓形态下实测没到 `2R`**：臂 B 的过渡窗峰值 **43 ≤ 2R = 80**；分区场景的账本被 ttl 清空后重建期间合计放行率 **80.0/s = Σ份额**（没有"陈旧份额 + 再分配并存"，因为中心在分区期间根本没拿到新份额）。`2R` 是**上界**，不是稳态读数 | 票 10 臂 B + 票 12 chaos 场景 3 |
| 3 | **仍在**（零到达轮份额清零）。chaos 场景 3 把"账本整片清零后重建"跑了一遍（`minShareDuringPartition = 0.0`、恢复走重新首租）；"恢复期 ≤ 1 轮拒绝"这一格仍只有 **【仿真】**（s5）背书——本仓的 chaos 客户端全程有 fail-open 兜着，看不到拒绝窗 | 票 12 chaos 场景 3 + research/12 §7 |
| 4 | **仍在**（未在真机复现）。harness 与 chaos 的部署都为每实例份额 **40 ≥ 1/burstSeconds**，故意不落进微份额区间 | 票 10 harness（§8.4 的 rate 配置纪律）+ research/12 §7 |
| 5 | **仍在**。kill 后重启账本从空重建：`Σ份额` 从 **0** 回到 `Σ需求` 用时 **2644ms**（场景 1）/ **3326ms**（场景 2）；宕机期间客户端 fail-open 按最后份额跑（**80.0/s = Σ份额**），用户感知不到 | 票 12 chaos 场景 1/2 长档（`results/chaos-20260924-232637`；两轮长档该读数在 147–3326ms 间摆动，取决于各 pod 落在续租周期的哪一段，都远在 6000ms 界内） |

**未实测清单（诚实分档汇总；⚠️ 2026-09-24 票 12 逐行复核后的状态）**：

| 项 | 状态 | 补证途径 |
| --- | --- | --- |
| Σ份额 ≤ R 硬不变量、突发上界 `(1')` | 纸面证明 + **【仿真】**（s1–s3b 贴 1.0×R）+ **【实测】** harness 整臂 Σ份额 ≤ R、突发 77 ≤ 80 | 已复核 |
| 降级期 ≤ 2R×burst | **【仿真】** s4 实测 1.1R + **【实测】** 臂 B 过渡窗 43 ≤ 80 | 已复核 |
| 仿真未覆盖：分片交互、非 Poisson 流量、`min_renew_interval`/`throttled` 瞬态 | **分片交互已实测**（chaos 场景 2：两分片杀一个，幸存分片 40.0/s 不受影响、redirect 0）；`min_renew_interval`/`throttled` 瞬态由票 05 / 票 11 的 IT 覆盖。**仍未覆盖：真业务分布（我们跑的是固定速率 + 闭环突发 + 中途加入/消失的突变，不是真实到达过程）** | §8.5 故障验收实测（票 11/12） |
| ~~内存账 91 B/条（±20%）、去重条目 80–120 B~~ | **已 JOL 复核（票 12）**：中心账本 ≈ **190 B/条**（模型 91）、去重 ≈ **190 B/条**（模型 80–120）、客户端桶表 ≈ **239 B/条**（模型 92）——**模型都低估约一半**（漏了键 String + backing 数组、值对象、内层槽位）。两个 10k 默认值**结论不变、数字翻倍** | §3.5（已回填实测值）；`measure/run-memprobe.sh` 可重跑 |
| 过渡窗"窗口 = 一个 refresh_interval"读作收敛时间；规则错位窗 ≤ `max(R_old,R_new) × 一个 refresh_interval` | 过渡窗**已实测**（臂 B + chaos 三场景的收敛读数）；**规则错位窗仍是推演**（IT 只验到"内容相同时不计数"，见票 09 的判据用例） | §8.3 过渡窗实测项 |
| Doorman redirect 先例、5s 续租硬下限 | 文档声明，**未在 `server.go` 核实**（⚠️ 引用处原样带） | §3.5/§4 |
| 分片/槽位/redirect/冷启动收敛 | 自研，无一手参照（§4 已声明）。**行为已实测**（chaos 场景 1/2/3） | §4 + 票 12 |
| 全部拍定默认值（§6/§8.4） | **已复核**：性能三项实测（票 10）+ 场景语义四项（票 11 的 IT + 票 12 的 chaos） | §8.4 |
| ~~**全部结论未落码**~~ | **已落码**：`gatekeeper/` 六模块 + center 内层 7 模块，票 01–12 全落地；`mvn test` 全绿 | §10 落地票 + §8.5 IT |
