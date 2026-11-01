# 06: Nacos 配置源与热更新端到端

**What to build:** spec §5 的 `ConfigSource<T>` 第二实现（Nacos）+ 规则热更新全链路验证。端到端行为：**在 Nacos 上改一条规则的 `rate`，从回调触发立即续租到本地桶 rescale，一个 `refresh_interval` 内全网生效；规则版本错位窗口只观测不阻断**。文件轮询实现（票 02）继续可用，二选一配置、不是主备链。

机制逐条照 spec §5：

- **Nacos 实现**：订阅 dataId（push），Maven 依赖 **optional**（落 **`gatekeeper-config`**——2026-09-22 结构修订后配置源的实现归该共享件、不在 `gatekeeper-client`；它保持零 Spring、可选零 Nacos，Nacos 依赖只在选了 nacos source 的部署里进闭包）；版本 = 内容 md5 + 单调序号，与文件实现同一套 `ConfigSource<T>` 契约。⚠️ 因为依赖是 optional、不传递，**两端各自的取用处要各自声明要哪份实现**（SDK 侧在 `gatekeeper-client`、中心侧在 `center-infrastructure`）。
- **两个配置对象**：`gatekeeper-rules.yml`（全部规则一份）/ `gatekeeper-topology.yml`（占位，拓扑内容归票 07）；版本各自独立自增，**不合并**。
- **热更新语义**：份额没变 → 只延有效期；变了（含规则改动）→ 换租约立即生效 + 本地桶按容量比例缩放；客户端收到变更回调 → 主动触发一次立即续租（接线票 05 已落，本票端到端验证 + 复核抖动防惊群）。
- **规则版本错位**（客户端 v9、中心 v8）：接受一个 `refresh_interval` 的窗口、**只做观测不阻断**；错位计数进指标（票 09 归拢，本票保证计数器在场）。
- 规则源不可用：中心继续旧份规则 + 重试 + 告警；SDK 空规则集全放行 + 持续重试（行为票 02/03 已落，本票在 Nacos 源上验证一遍）。

**Blocked by:** 02、05（`ConfigSource` 契约与回调→立即续租接线就位，热更新才能端到端验证）

**Status:** resolved

- [x] Nacos 配置源单测：订阅变更 → 原子快照更新、md5+单调序号版本推进、非法整份拒收用旧份
- [x] 热更新端到端 IT（Nacos 环境）：改 `rate` ⇒ 一个 `refresh_interval` 内份额 rescale 生效；改完删规则 ⇒ `unmatched` 收敛为放行
- [x] 规则源断连 IT：Nacos 不可达时中心用旧份、SDK 空规则集全放行 + 持续重试
- [x] 依赖纪律：未选 nacos source 时 Nacos 依赖不进接入方闭包
- [x] `mvn test` 全绿

## Answer

**落地点**：

| 模块 | 新增/改动 | 装什么 |
|---|---|---|
| `gatekeeper-config`（主） | `NacosConfig` / `ConfigSubscription` / `NacosSubscription` / `NacosConfigSource` / **`SnapshotStore`** | Nacos 订阅实现（push）；`ConfigSubscription` 是**包内 seam**（读一次 + 订阅两件事），真身是 nacos-client 的薄适配，假身让单测能脚本化推内容/演失败 |
| `gatekeeper-config`（改） | `FileConfigSource` | 把「内容 md5 + 单调序号 → 原子快照 + 分发」抽成 `SnapshotStore`，两种实现共用——**spec §5 的「同一套版本语义」由此是同一处代码**，不是两条各自维护的约定 |
| `gatekeeper-client` | pom 的 nacos 声明（optional） | SDK 侧的取用处 + 给接入方的信号（optional = 「想要就自己加」） |
| `center-infrastructure` | `RuleConfigSources.nacos(...)` + pom | 中心侧的取用处；与 `file(...)` 共用 `failFast(...)`（spec §5 启动 fail fast） |
| `center-app` | `RulesProperties`（`gatekeeper.rules.source=file\|nacos` 部署期开关）+ `GatekeeperConfig` 装配 | 选哪一支是**部署期**的事，照 spec §9 的 yaml 形状 |

**验收**：`mvn test` **196 测试全绿**（protocol 25 / config 36 / client 67 / center 33+25+2+8），本票新增 21 条：config 12（10 单测 + 2 真 Nacos）、client 4、infra 2、app 3。真 Nacos 的三条用例打在 `bs-nacos-test`（127.0.0.1:18848，backend 的 `test-env.sh` 同一套栈）上，**实测全过**；栈没起时按 `assumeTrue` 跳过（实测：`docker stop bs-nacos-test` 后三条全 skip、套件仍绿）。

**⚠️ 端到端 IT 按架构边界**拆成两半（票面写的是「改 rate ⇒ 份额 rescale」与「删规则 ⇒ unmatched」两件事）：**没有任何模块能同时依赖 center 与 client**（enforcer「闭包永不含 center」反向也成立），所以客户端那一半落在 `gatekeeper-client`（真 Nacos push → 客户端规则热更新 → 回调触发立即续租 → rescale 生效，用 `StubCenter` 当真中心的对端），中心那一半落在 `center-app`（`rules.source=nacos` 真起 Spring + 真 HTTP：改 rate ⇒ 按新 rate 发份额；删规则 ⇒ `unmatched`）。两半合起来才是票面那两句，**真·两端同进程**的跑法归票 10 的 harness。

**实测平台事实（本票最值钱的几条）**：

1. **Nacos 2.x 新建客户端在 gRPC 连接就绪前的那次 `getConfig` 会静默返回 null**（不抛不等待；探针：同进程里第一个查询客户端读到 `null`、第二个才读到内容）。⇒ 配置源必须把「读到 null」当成**读不到**处理并继续重试，否则**中心会偶发地以「配置不可用」拒绝启动**——最不该有的那种间歇性故障。已按此实现并写进 `NacosConfigSource` 的 javadoc。
2. **`ConfigService.shutDown()` 不会打断同 JVM 里的兄弟实例**（探针：A、B 两个客户端，A `shutDown()` 后 B 照常读）——这一点本来担心会影响「一个 JVM 两个配置对象（rules / topology）」，实测不成立。
3. **enforcer 的 `bannedDependencies` 看不见 optional 子树的传递依赖**（探针三连：直接依赖 micrometer ⇒ FAIL；nacos-client **非** optional ⇒ FAIL 并点名 micrometer；nacos-client **optional** ⇒ PASS）。⇒ 「零观测依赖」这条纪律交给 optional 兜，而它**同时就是「谁把 optional 去掉」的绊线**（去掉即构建红）。这条也解释了为什么 client 侧用 optional 而不是 test 作用域：test 作用域 enforcer 看得见，会逼出一个只会削弱绊线的 micrometer 排除项。
4. **nacos-client 不自带 SLF4J 门面**（只声明日志适配器）——Spring Boot 应用天然有，裸 JVM 的测试/部署要自己补（症状是 `NacosFactory.createConfigService` 直接抛）。
5. 类名 `*IT` 会被 surefire **静默跳过**（默认只收 `*Test`）——本仓既有做派是 IT 也以 `Test` 结尾（`CommunityApiTestBase` 那一套），三支真 Nacos 用例因此命名 `RealNacos*Test`。

**自行拍板的空白（都可反转）**：

1. **`ConfigSubscription` 这个 seam**（包内可见）：票面只要「Nacos 实现 + 真 Nacos IT」，但版本推进/整份拒收/持续重试这些语义**拿真 Nacos 演不出来**（演不出「订阅恰好失败 1 次」）。抽一个只有两个方法的窄面，单测用假订阅面钉语义、真 Nacos 只验传输——与 client 用 `StubCenter` 验通道是同一分工。
2. **`SnapshotStore` 顺带重构了 `FileConfigSource`**（票面没要求）：不抽的话两种实现各写一份 md5 + 序号，「同一套版本语义」就只是约定；抽完 `FileConfigSource` 的 9 个用例一字未改照样绿。
3. **`loaded` 字段删掉、重试条件只认 `readable`**：读得到内容（哪怕内容非法）就不再轮询，读不到才每秒重试。这是照文件实现那条「同一份坏内容不反复解析、不刷日志」（spec §5）定的口径。
4. **中心侧 nacos-client 不标 optional**（SDK 侧两处都标了）：中心是**部署单元**、两种源是部署期配置、单份 fat jar 两种都要能跑；「没选 nacos 就不进闭包」这条约束的对象是**接入方闭包**（protocol+config+client+spring），中心是提供方。
5. **v1 只支持 public 命名空间、未开鉴权的 Nacos**（`NacosConfig` 无 namespace/username 两个分量）：spec 的配置源模型只讲到「订阅 dataId」，加命名空间是加概念，留给真需要时（`NacosConfig` 加一个分量 + 一个 `PropertyKeyConst`）。
6. **`RulesProperties` 用 `@ConfigurationProperties` 记录**而不是继续堆 `@Value`：选 source 要连地址/dataId/group/timeout 一起进来，6 个 `@Value` 参数是票 05 已经点过名的 data clump。

**code-review 采纳项**（两轴）：

- **Standards 轴**：① `NacosConfigSource.loaded` 写而不读 ⇒ 删；② `StubSubscription.content(String)` 没人调 ⇒ 删；③ 三份 `awaitUntil` + 三份 `reachable()` 逐字重复 ⇒ client 改用既有 `TestSupport.awaitUntil`、config 两份收进新的 `ConfigTestSupport`（跨模块那三份保留：为 ~30 行测试脚手架引 test-jar 不划算）；④ `RulesProperties` javadoc 自称「NacosConfig 是默认值唯一出处」而 dataId 默认值其实住在它自己这儿 ⇒ 提成 `Nacos.DEFAULT_DATA_ID` 并改口径；⑤ `StubSubscription` javadoc 引用改名前的类名 ⇒ 订正。
- **Spec 轴**：① 真 Nacos 用例「改了但没跑」的信号（surefire 报告里 `Tests run: 0`）⇒ 确认是 `*IT` 命名被 surefire 静默跳过，已全部改名并在 javadoc 写明这条坑；② 票面机制条里「复核抖动防惊群」原先零覆盖 ⇒ 新增 `RuleChangeStormTest`（5 连变更最多补一轮，合并 + 抖动两机制）；③ 「两个配置对象」那条用例名实不符（用的是两个 String 源）⇒ 改名并写明拓扑内容归票 07、本票落的是「一个 dataId 一个实例」这条形状。
- **两轴都点到、判为 judgement call 不采纳**：`client` 的 nacos 声明用 optional 而非 test（理由见上面第 3 条实测）；`SdkClosureDependencyTest` 读 pom 声明而非解析真实闭包（真实闭包由 optional 语义保证、由 enforcer 绊线兜，能解析 `dependency:tree` 的路子要引进 maven 解析器，不值）。

**⚠️ 已知波及 / 不在本票范围（code-review 揪出，需人工拍板）**：

- **`rule_version` 把 `#<单调序号>` 带上了线**：`LeaseCase` 拿客户端请求里的 `rule_version` 与**中心自己**的 `rules.version()` 比（`observeRuleVersionLag`），而序号是**各进程自产**的（两边各自 `md5#N`，N = 本进程加载过几次）。⇒ 只要两端启动时刻/加载次数不同，**同一份规则也会被判成版本错位**，`RULE_VERSION_MISMATCH` 常态非零，spec §8.6 指望的「持续非零 = 配置传播滞后」这个信号失效。与 spec §5「规则版本（协议里带）= **规则内容哈希、只做相等比较**」相悖。**不是本票引入的**（版本格式来自票 02、比较来自票 04、上线来自票 05；本票只是把 Nacos 也接进同一套格式）。**2026-09-23 用户拍板：落点放票 09，修法选「让 `ConfigSnapshot.version` 直接就是内容哈希、去掉 `#seq`」**——两条候选里否掉「比较时取 `#` 前一段」，因为那只补了比较、发出的字段（请求与响应双向）仍不合 §6.2；且 `#seq` 现零消费者。票 09 的票面已加专节写明要动的地方、判据用例，以及**票 02 的版本格式按反转处理**（票 02 Answer + spec §5 要同步回填）。
- 「运行中 Nacos 不可达 ⇒ 中心继续用旧份」这条，IT 里验的是**把 dataId 删掉**（推送空内容被整份拒收）；对 push 型配置源，「不可达」与「配置没了」是**同一条代码路径**（本地快照不清、也不再有重读），所以两者等价——但严格说用例名说的是前者、做的是后者，已在用例注释里写明。
- 「持续重试后恢复」在**真 Nacos** 上验不了（要停/起容器），由假订阅面的单测兜（`首次读不到不拒构造_get抛_服务端恢复后后台重试自动加载`）；真 Nacos 那条只验了「不可达时 SDK 不拒启动 + 空规则集全放行」。
- 拓扑那份配置（`gatekeeper-topology.yml`）只有机制（一个 dataId 一个实例），内容归票 07。
