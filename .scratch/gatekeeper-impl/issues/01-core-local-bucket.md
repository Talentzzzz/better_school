# 01: 核心本地桶（gatekeeper-api + gatekeeper-client 模块地基）

**What to build:** spec §3.3 的本地桶完整落地，纯单元测试验收（零网络、零规则依赖）。新建仓顶 `gatekeeper/` Maven 工程，两个模块：`gatekeeper-api`（协议对象、规则/拓扑模型占位）与 `gatekeeper-client`（本地桶 + 放行 API）。交付端到端行为：**业务代码调 `tryAcquire(finalKey)` 拿到 `Probe{allowed, remainingTokens, nanosToWaitForNextToken}`，放行路径零网络往返、锁内零 I/O**；`acquire(timeout)` 纯本地等待（睡在锁外）。双 API 即 spec §9「显式 API 是底座」的底座。

机制逐条照 spec §3.3：

- **微令牌整数域**：1 req = 10⁶ 微令牌，`refillPeriodNanos` 固定 1 秒；补令牌用 Bucket4j「余数存回」手法（`divided = refillTokens × duration + roundingError; refill = divided / period; roundingError = divided % period`，`refill == 0` 时整份 `divided` 存回余数）——整数域零漂移，份额 3.3 req/s 类实数量化相对误差 ≤ 3×10⁻⁷。**不抄 Guava `nextFreeTicketMicros` 锚点**（桶不预支、不欠账）。
- **容量公式含下限**：`容量 = max(份额 × burstSeconds, 1 个请求)`；下限**只作用容量、不作用补令牌速率**（微份额行为 = 每 1/份额 秒放 1 个、无囤积）。
- **改份额 rescale**：`stored = stored × newMax / oldMax`，先乘后除、余数进 `roundingError`，缩放比例用**新旧容量（含下限）**算；满度不跳变、缩容不凭空多放。
- **时间口径**：`System.nanoTime()` 差值、防回拨一行 `max(0, now - last)`（`now <= last` 按时间没走处理）。
- **并发**：桶表 `ConcurrentHashMap`，每 key 一把 `ReentrantLock`（**不用 `synchronized`**——虚拟线程 pin 载体线程）；锁内只做令牌数学。
- **驱逐**：桶表上限 10k（可配），近似 LRU 逐出**整条**（不重置满桶/空桶）；逐出丢的余量上界 = `max(份额 × burstSeconds, 1 请求)`，方向保守（少发）；**绝不因表满拒绝请求**。

**Blocked by:** None (can start immediately)

**Status:** resolved

- [x] `tryAcquire` / `acquire(timeout)` 双 API + `Probe` 三字段语义成立；微令牌量化与余数存回有单测（含 `refill == 0` 分支、3.3 req/s 类实数份额）
- [x] 容量下限单测：份额 × burstSeconds < 1 请求时能放行（每 1/份额 秒 1 个）、不恒拒
- [x] rescale 单测：满度不跳变、缩容不凭空多放、roundingError 不丢
- [x] 时间口径单测：时钟回拨/不动时桶不异常；并发 `tryAcquire` 压测下 Σ放行 ≤ 补令牌速率上限
- [x] 桶表驱逐单测：超 10k 逐最旧整条、绝不因表满拒绝、逐出后重遇走新建路径
- [x] `mvn test` 全绿；两模块依赖纪律成立（api/client 均**零 Spring 依赖**）

## Answer

**落地要点**（2026-09-22，commit 见 `票 01 resolved`；`mvn test` 32 个测试全绿：api 3 + client 29）

- **工程形态**：仓顶 `gatekeeper/` 独立 Maven 工程（父 pom `io.gatekeeper:gatekeeper`，模块 `gatekeeper-api` / `gatekeeper-client`），Java 21；父 pom 挂 `spring-boot-starter-parent:3.3.5` 只为与本仓同栈、统一 dependencyManagement / pluginManagement（**不往 classpath 加任何 Spring artifact**）。`.gitignore` 补 `gatekeeper/target/`、`gatekeeper/*/target/`。
- **依赖纪律有守卫、不是声明**：两模块各挂 `maven-enforcer-plugin` 的 `bannedDependencies`（api 禁 `org.springframework:*`/`org.springframework.boot:*`；client 另禁 `io.micrometer:*`——票 09 的 SDK 零观测依赖同一条纪律）。版本钉在父 `pluginManagement`，规则按模块声明（center / spring 本来要 Spring，别给它们挂）。`mvn dependency:tree` 实测：client 编译期依赖闭包 = `gatekeeper-api` 一条，测试期 = junit-jupiter 5.10.5 + assertj 3.25.3。
- **本地桶**（`LocalBucket`，包私有）：微令牌整数域、`refillPeriodNanos` 固定 1 秒、余数存回（`refill == 0` 时整份进 `roundingError`）；容量 = `max(份额 × burstSeconds, 1 个请求)`（下限只作用容量、不作用补令牌速率）；`setShare` 先按旧速率结算已过时间再缩放存量（`stored × newMax / oldMax`，先乘后除）。时间是**调用方传入的 `nowNanos`**（纯状态机，桶内不读时钟、不做 I/O），每 key 一把 `ReentrantLock`。
- **新建的桶是满的**（= 容量那部分突发额度可立即用掉）：对齐 spec §9 演示口径（rate=2/s、burst=1.0、半秒连打 10 ⇒ 放行 ≤3），也与 Guava `doSetRate` 的「新桶 = maxPermits」形状一致。
- **`Probe.remainingTokens` 的单位 = 微令牌**（1 请求 = `MICRO_TOKENS_PER_REQUEST`），无桶路径 = `Long.MAX_VALUE` + `isUnlimited()`；三字段形状照 spec 不扩字段。
- **`GatekeeperClient` 的对外面**：`tryAcquire(finalKey)`（无桶 = 无规则/未在册 ⇒ 放行）、`acquire(finalKey, timeout)`（纯本地等待，`LockSupport.parkNanos` 睡在锁外，中断即返回拒绝判定且不吞中断标志）、`setShare` / `remove`（份额落地与逐出，票 02/05 的接入点）、`trackedKeyCount()`。
- **桶表**：`ConcurrentHashMap` + 上限（默认 `DEFAULT_MAX_TRACKED_KEYS = 10_000`，spec §8.4）；溢出时逐「最近一次放行判定最久远」的**整条**；扫描 O(n) 但只在「插入新 key 且表已满」时发生（不是逐请求成本）。
- **测试口径**：时间全部经 `NanoClock` 注入口喂 `TestClock`（并发上界那测用冻住时钟 ⇒ 上界是**确定值**而非概率断言）；`TestBuckets.drain` 是共享测试手脚。

**code-review 采纳项**（两轴，`/code-review` since HEAD）：

- Standards 轴：① 类名与文档用词对齐 CONTEXT.md 词条表——`LocalTokenBucket → LocalBucket`、javadoc 里「整形令牌桶」→「整形桶」（`令牌桶` 是词条表的 `_Avoid_`）；② 测试名错字（`不驼` → `不带`）；③ 三处「花光桶」测试循环抽成 `TestBuckets.drain`；④ enforcer 版本上提父 `pluginManagement`（规则仍按模块声明）；⑤ 文档补一句「父 pom 是 Spring Boot 只是统一管理、不进 classpath」。
- Spec 轴：① 揪出「并列判定时刻下新插入的那条可能被自己这次插入逐出」（会让该 key 白白 fail-open 到下一次续租）——已修（`oldestKey` 跳过 `justInstalled`）+ 加测；② 其余两轴意见见下。
- 两轴一致**反驳并留档**：`Probe` 的 `Long.MAX_VALUE` 哨兵不改成第四字段（spec 把三字段形状写死了；哨兵有 `isUnlimited()` 与 javadoc 兜）；不引入 `Share(rate, burstSeconds)` 值类型（两个 double 是最轻的 clump，真规则类型归票 02，先造就撞车）；LRU 时间戳留在桶内（「最近一次被判定」是桶自己的属性，挪进表要多一层包装）；`LocalBucket` 命名之外的 `令牌桶` 泛称不算违规（spec §1/§2 正文自己就这么写）。

**已知波及 / 不在本票范围**：

- **首租三分法只落了第一格**（无桶 ⇒ 放行）。第二格「有规则但首租失败 ⇒ 拒」要等票 02 的规则匹配 + 票 05 的同步首租；本票不碰网络、不碰规则。
- `gatekeeper-api` 目前只有 `Probe`——票面「规则/拓扑模型占位」**故意不建空壳类**（没有消费者的占位类型是投机，真形状归票 02 / 票 07）。
- `roundingError()` 是包私有、给测试与后续观测用的唯一注入口；`acquire` 的等待语义不进中心协议（spec §6.2：等待归本地桶）。
- 未跑 backend 全量测试：本票只新增 `gatekeeper/` 与 `.gitignore` 两处，backend 无改动。

**⚠️ 2026-09-22 结构修订（本票之后，[ADR-0007](../../docs/adr/0007-gatekeeper-layering-and-protocol-module.md)）**：

- `gatekeeper-api` 已改名 **`gatekeeper-protocol`**（Java 包 `io.gatekeeper.api` → `io.gatekeeper.protocol`）。
- **`Probe` 已从契约模块迁入 `gatekeeper-client`**（包 `io.gatekeeper.client`，`ProbeTest` 同迁）。理由：`Probe` **不出进程**、不跨任何边界（spec §6.2 逐字「等待语义归本地桶 `nanosToWaitForNextToken`」），留在协议模块会破坏「`protocol` 里每个类型都跨边界」这条**可判定**的准入标准。`GatekeeperClient.tryAcquire` 的返回类型随之变成 `io.gatekeeper.client.Probe`。
- `gatekeeper-center` 已建成**内层聚合 pom + 7 个 DDD 模块**（`-types/-api/-domain/-case/-infrastructure/-trigger/-app`）；`client`/`protocol` 的 enforcer 新增「禁依赖 `io.gatekeeper:gatekeeper-center-*`」。
- 分层规范见 [`gatekeeper/CLAUDE.md`](../../gatekeeper/CLAUDE.md)。本票 Answer 里出现的模块名按此读。修订后 `mvn test` 32 个测试全绿（`ProbeTest` 现属 `io.gatekeeper.client`）。
