# 11: IT 常态化回归（§8.5 自动化场景进测试套件）

**What to build:** spec §8.5 表中 7 个自动化 IT 场景从各票的验证状态**收敛成常态化回归套件**（能进 IT 的进 IT 才有回归防线——virtual-thread effort 票 10 的教训）。端到端行为：**`mvn test` 一条命令把 7 个故障/热更新场景全部回归——语义已在票 03–07 的 IT 里各自落过，本票收拢、补缺、去重，保证它们长期跑在套件里而不是散在历史票面**。

7 个场景（spec §8.5，判据以该表为准）：

1. 同步首租拦截（冷启动第一个请求）：有规则但首租失败 ⇒ 拒；
2. 规则热更新：份额变立即生效 + 桶按容量比例缩放 + 回调触发立即续租（带抖动）；
3. 规则删除：一个 `refresh_interval` 内收敛为放行、无拒绝窗口；
4. `ttl=0` 主动停 vs 被动到期继续：两行为可区分；
5. 世代交替（崩溃重启）：稀释窗不超发、幽灵过期后一个 `refresh_interval` 恢复；
6. 客户端重启 / 桶表逐出后重遇：重遇走冷启动首租路径；
7. 中心配置非法 fail fast 拒启动；SDK 首份配置非法/规则源连不上 ⇒ 空规则集全放行 + 告警 + 持续重试（两档）。

约束：

- 场景全部单分片可覆盖（不依赖票 07 的分片），跑进常规测试套件、时长可控（稳态窗口可缩短到以若干个 `refresh_interval` 计，判据语义不变）；
- chaos 级真进程故障（杀 center / 双分片宕一个 / 网络分区）**不在本票**，归票 12 脚本 + 人工走查；
- 与票 10 的 harness 分工：harness 是一次性量级读数，本票是长期回归，两者不互相冒充。

**Blocked by:** 05（单分片端到端闭环就位即可开写；不等分片与观测票）

## 结构硬输入（2026-09-22 起，[ADR-0007](../../docs/adr/0007-gatekeeper-layering-and-protocol-module.md)）

- **center 侧场景（①⑦ 的 fail-fast、④ 的 `ttl=0`、③ 的 `unmatched`）建议走进程内调用而不是真 HTTP**：`gatekeeper-center-api` 的 `ILeaseService` 就是给这个用的——生产由 `LeaseController`（`center-trigger`）实现，测试/回归可以直接拿它调。这样常规 `mvn test` 不必每次起 Web 容器，**前提是 controller 不做接口之外的逻辑**（有就说明该逻辑属于 `case` 层）。这与 backend 的 `IXxxService` 形状同构（一个契约、多个消费者）。
- **客户端侧场景**（②③④⑤⑥）落在 `gatekeeper-client` 的测试里；配置源相关的场景（⑦ 两档）依赖票 02/06 的 `ConfigSource` 实现，落点见该票的结构输入。
- 场景语义判据仍以 spec §8.5 表为准（本条只定「跑在哪一层」，不改判据）。

**Status:** resolved（2026-09-23）

- [x] 7 个场景全部在套件内跑绿、`mvn test` 常态可跑（时长与稳定性核过）
- [x] 与票 03–07 已落的 IT 去重（同名场景收敛为一处、其余删除）
- [x] 每个场景断言的是 spec §8.5 表的「期望行为」栏原文语义，不是实现细节

## Answer

**落点（三个套件，按"语义在哪一层看得见"分）**

| §8.5 场景 | 落在哪 | 用例 |
| --- | --- | --- |
| ① 同步首租拦截（有规则·首租失败 ⇒ 拒） | `gatekeeper-client` `Spec85ClientRegressionTest` | 三分法三格：中心不可达 / 超预算 / `throttled` |
| ② 规则热更新（份额立即生效 + 容量比例缩放 + 回调触发立即续租**带抖动**） | 同上 | 4 条：份额当场生效 + 回调触发立即续租 + **抖动窗**（新）+ 文件源接线 |
| ③ 规则删除（一轮内收敛为放行、无拒绝窗口） | 客户端套件 + `center-case` | 客户端：`unmatched` 逐出并放行且拒绝计数不增；中心半：**新**（规则源换代 ⇒ 回 `unmatched`、不建条目） |
| ④ `ttl=0` 主动停 vs 被动到期继续 | 客户端套件 | 两态可区分（主动停自补存量花完 vs 被动按最后份额继续） |
| ⑤ 世代交替（稀释窗不超发 + 幽灵过期后一轮恢复） | `center-case` `Spec85CenterRegressionTest` | **新** 2 条 |
| ⑥ 客户端重启 / 桶表逐出后重遇（重遇走冷启动首租） | 客户端套件 | **新** 2 条：桶表逐出后重遇**真的再问一次中心**；重启 = 新 `generation` 再首租 |
| ⑦ 中心配置非法 fail fast；SDK 首份非法 / 规则源连不上 | `center-infrastructure` + 客户端套件 | 中心：`RuleConfigSourcesTest` **补**"规则内容非法"（原先只有文件缺失 / Nacos 不可达）；SDK：**新**"首份配置非法" + 搬来的"连不上"，两条都断言了**告警**（JUL handler 抓 `GatekeeperClient` 的 WARN） |

**去重**：从 `FirstLeaseChannelTest`（4）、`RenewerTest`（4）、`GatekeeperClientTest`（1）、`GatekeeperClientRulesTest`（2）把场景用例**搬进**客户端套件，原处删除；客户端模块净 −12 +14。**删掉 `RuleChangeStormTest`**——它的断言 `leaseCallCount() <= 2` 是**空转**的：用例不推注入时钟（`TestClock` 冻在 0），而续租循环有"上报窗口没走过就不报"那条守卫（`Renewer.renewCycle`），于是它**一次请求都没发出去**、改回"一次变更一次往返"照样绿。合并成的那一条（②抖动用例末段）**推了时钟**才真的在数往返。

**⚠️ 量出并回修一条真缺陷（用户 2026-09-23 拍板"本票回修"）：规则变更触发的立即续租不带抖动，而且多补一条。**

- **根因链条**：`Renewer.loop` 在**每轮开头**算等待时长（`immediateRenewal.getAndSet(false) ? jitterNanos() : intervalNanos + jitterNanos()`），而 `parkUntilWake` 里有 `if (immediateRenewal.get()) return;`——这条**必须留**（`LockSupport.unpark` 只能让 `parkNanos` 醒一次，没有它内层循环立刻睡回去、那句话等于没喊），但它让"改配置"这一下**直接穿过 park**，紧跟的 `renewCycle()` 当场就发；抖动被算在了**下一轮**开头（标志已被消费），那一轮往往因为窗口没走过什么都不发，真跑起来则补发一条带抖动的。
- **实测形态**（一次性探针）：一次规则变更 → `+6ms`（无抖动）+ `+206ms`（抖动后）两条往返。防惊群要挡的正是头一条。
- **修法**（`Renewer`，+/-8 行）：`parkUntilWake` 改返回"是否被立即续租叫醒"；`loop` 里被叫醒的那一轮**先睡抖动窗再发**，周期等待被打断则回轮首走抖动分支。副作用是**顺带修好**了那条多余的往返（抖动窗里再来的变更会重新睡 = 合并）。
- **灵敏度**：把修复退回去，② 抖动用例的 `max(delay) > 抖动窗/5` 与新增的"一次变更只出一条往返"两条会挂（前者量的是"第一条到底有没有等"，后者量的是"有没有多补"）；只留"延迟在抖动窗内"那一条则两边都绿——所以那两条才是这一格的灵敏度所在，已在用例注释里点明。

**另修两条** `mvn test` 常态可跑所必需的时序抖动（都不是本票引入）：
- `NacosConfigSourceTest.订阅失败持续重试...` 断言 `subscribeCount() == 1` —— keeper 虚拟线程在构造返回后**立刻**再订阅一次，这个瞬时值本身就是竞态（本机同一份代码 `-Dtest=` 单跑 5/5 量到 2、整模块跑约 1/3 量到 1）。改成断言语义（"重试发生过" + 推送能到）。
- `DemoObservationTest` 的比值上界 `[2,4]` → `[2,6]`（票 10 交接点名的那条）。量出窗口里的租约数由三块拼成、每块都有界抖动：首租 ≤ 2（50ms 端到端预算在本机 loopback 上贴边，忙时头一次超时 ⇒ 某个后续请求再首租一次）+ 续租 2 轮 ×（1 次 + 最多 1 次退避重试）。上界按机制推、不是拍的；真退化成"每个请求打一次中心"时是 ~10 次，照样拦得住。

**拍板与偏离**
- **⑤ 与 ③ 的中心半落在 `center-case`，不是客户端**：⑤ 的"稀释""幽灵""Σ份额"全是**账本量**，客户端看不见；③ 的"无拒绝窗口"在客户端侧、`unmatched` 在中心侧，两边各测各的一半。
- **进程内驱动 `LeaseCase` 而不是票面点名的 `ILeaseService`**：结构硬输入要的是"常规 `mvn test` 不必每次起 Web 容器"，这一点等价达成；而 `center-api` 在 `case` **之上**（`gatekeeper/CLAUDE.md` §2 依赖图），`case` 的测试够不着它，够得着的 `center-app` 又得起整个 Spring 上下文。取前者：不引容器、不新增依赖边（理由写进了套件 javadoc）。
- **`RealNacosHotReloadTest` 保留不并**：它和客户端套件的 ②⑦ 是同场景，但走的是**真 Nacos 传输**（`assumeTrue` 网关掉、不进常态时长），与"套件里用 stub 源断言语义"是互补的两层，不是同一处重复。
- **中心夹具抽成同包共享件**：`TestClock` / `TestRuleSource` / `TestTopologyService`（`usecase.lease` 包内），`LeaseCaseTest` 与中心套件共用——两处逐字重复的时钟本来就该是一份。客户端侧同理把 `write` / `fileRuleSource` / `capacityAfterOneSecond` 收进 `TestSupport`；`StubCenter` 把"请求 + 到达时刻"并成一个 `LeaseCall` 记录，不再靠两个平行列表的下标对齐。

**核过**：`mvn test` **271 全绿、0 失败、3 跳过（真 Nacos 三条，栈没起）**，单次 **~63s**（大头是 `DemoObservationTest` 的 16s 与 `LeaseEndpointTest` 的 5s）；改动后连跑 4 次全绿，另对 `gatekeeper-config` 整模块 6 连跑、`DemoObservationTest` 4 连跑、`Spec85ClientRegressionTest` 逐条跑绿。

**不在本票**：chaos 级真进程故障（杀 center / 双分片宕一个 / 网络分区）归票 12 脚本 + 人工走查；与票 10 的 harness 分工不变（harness 是一次性量级读数，本票是长期回归）。
