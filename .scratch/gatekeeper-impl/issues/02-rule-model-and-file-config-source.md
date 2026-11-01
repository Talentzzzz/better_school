# 02: 规则模型与文件配置源

**What to build:** spec §5 的规则模型 + `ConfigSource<T>` 文件实现，SDK 侧能从 `gatekeeper-rules.yml` 加载规则并做本地匹配。端到端行为：**写一份合法规则 yml，`tryAcquire` 对命中规则的 key 走「有规则」路径、对没命中的 key 直接放行**——首租三分法的本地侧（key 不在任何规则里 = 放行）就此收口。仍零网络、零 center 依赖。

机制逐条照 spec §5：

- **字段集**：`{ id?, match, rate, burstSeconds = 1.0, fallback = fail-open }`；`match = matchType: exact | prefix` + `pattern`，不发明通配符语法，前缀 = 朴素 `startsWith`。
- **匹配序（确定性，无 priority 字段）**：精确 > 前缀按 pattern 长度降序 > 声明顺序，第一条命中即用；加载期静态检出「永远打不中的死规则」并告警（不拒绝）；对「pattern 是另一 pattern 的前缀且未以分隔符结尾」打告警。
- **写错 = 整份 all-or-nothing**：任何一条非法（`rate ≤ 0`、`burstSeconds ≤ 0`、match 非法、id 重复）⇒ 整份拒收 + 告警 + 继续用旧份。
- **启动行为**：SDK **永不因规则拒绝启动**——无规则/首份非法/连不上 ⇒ 空规则集 = 全放行 + 告警 + 持续重试。
- **`ConfigSource<T>` 抽象**：`get()` 原子快照 + `addListener` 变更回调；本票交付**本地文件轮询实现**（mtime+size 指纹，1–2s，不用 `WatchService`）；版本 = 内容 md5 + 单调序号，由实现自产。Nacos 实现归票 06。
- **规则版本 = 内容哈希、只做相等比较**（协议字段留给后续票消费）。

**Blocked by:** 01（桶与 client 模块骨架）

## 结构硬输入（2026-09-22 起，[ADR-0007](../../docs/adr/0007-gatekeeper-layering-and-protocol-module.md)）

- **规则模型（`Rule` / `RuleSet` / `Match` / `MatchType` / `Fallback`）归 `gatekeeper-protocol`**——两端都要用（center 判 `unmatched`、按 `rate` 均分；client 本地匹配建桶），且 `protocol` 改名+搬空 `Probe` 之后**现在是个空模块**，本票是它的第一个内容提供者。
- **`ConfigSource<T>` 接口归 `protocol`；两个实现归新增的共享件 `gatekeeper-config`**（2026-09-22 已裁，家庭层 5 → 6）。理由：center 也要直连同一份配置源（spec §2 图的配置源框有两条「直连」箭头、§5:157 逐字「客户端与中心**直连同一配置源**、不从中心拉」、票 03 逐字「**复用票 02 的**规则模型 + 文件配置源」）⇒ 实现**跨两端**，放 client 则 center 依赖 client（被 enforcer 禁、也违反「两端独立实现」），放 protocol 则契约 jar 带实现还捎上 optional Nacos。
  **本票的本地文件轮询实现落 `gatekeeper-config`**（包 `io.gatekeeper.config`，模块已建好并挂了 enforcer）；`gatekeeper-client` 已加对它的依赖。
- **client 侧接入点不变**：规则集要能供 `tryAcquire` 做本地匹配；另见票 08 新增的一格——client 要暴露**只读规则集快照**给接入器（别让 spring 自己再订阅一份）。

**Status:** resolved

- [x] yml 加载 + 匹配序单测：精确优先、前缀长度降序、声明序兜底、死规则/前缀歧义告警
- [x] all-or-nothing 单测：一条非法整份拒收且继续用旧份；启动期非法/缺失 = 空规则集全放行 + 持续重试不拒启动
- [x] `ConfigSource` 文件轮询单测：mtime+size 指纹检测变更、`get()` 原子快照、`addListener` 触发
- [x] 端到端（本地）：命中规则与未命中 key 的 `tryAcquire` 行为可区分（限速 vs 放行）
- [x] `mvn test` 全绿

## Answer

> ⚠️ **2026-09-23 反转（票 09 落点，用户拍板）**：本票定的「版本 = `<md5>#<从 1 起的单调序号>`」**已作废**
> ——`ConfigSnapshot.version` 直接就是**内容 md5**（`SnapshotStore` 的 `sequence` 字段随之下线）。
> 理由与判据见票 09 票面的「结构硬输入」节：那个序号是各进程自产的，两端加载历史不同 ⇒ 同一份规则也
> 常态互判「版本错位」；它被**双向**发到线上、与 spec §5/§6.2「版本 = 内容哈希、只做相等比较」相悖；
> 且**零消费者**（变更判定比的是 md5 本身、比序需求归拓扑那支的显式数字、`isBlank()` 只当哨兵）。
> spec §5 已按反转回填。下面正文里凡是写「md5 + 单调序号」的地方，一律按本条读。

**落地要点**（2026-09-22；`mvn test` 80 个测试全绿：protocol 17 + config 24 + client 39，修订前是 32）

- **规则模型进 `gatekeeper-protocol`**（票面硬输入）：`Rule{id?, match, rate, burstSeconds=1.0, fallback=fail-open}` / `Match{matchType, pattern}` / `MatchType{exact, prefix}` / `Fallback{fail-open, fail-closed}` / `RuleSet`。字段名与 yml 字段名逐字一致；校验就地做（`rate ≤ 0`、`burstSeconds ≤ 0`、match 非法、id 空白 → `IllegalArgumentException`），id 重复在 `RuleSet.of` 里整份抛。
- **匹配序**：`Map<pattern, Rule>`（精确 O(1)）+ 前缀列表（长度降序、稳定排序保声明序）两段查，**等价于「精确 > 前缀长度降序 > 声明序、第一条命中即用」**。`rules()` 暴露的是声明序原样，不是匹配序。
- **加载期静态诊断**（`RuleSet.warnings()`，只告警不拒绝）：**死规则 = 同 matchType + 同 pattern 里后声明的那条**——这里推翻了实现的第一版：长前缀先命中，所以**更短的前缀永远盖不住更长的**，唯一能「永远打不中」的只有重复 pattern（票 01 那条「判定时刻并列不逐刚落地的那条」同源的坑，靠单测 `长短前缀并存不算死规则` 钉住）。**前缀歧义** = 某前缀 pattern 是另一 pattern 的前缀、自己又没以分隔符（`/ : . _ -` 之一）结尾。
- **`ConfigSource<T>` 接口在 `protocol`、实现进 `gatekeeper-config`**（ADR-0007）：`get()` 返回 `ConfigSnapshot<T>{version, value}`、`addListener(Consumer)` 只在**内容变化**时回调、`close()` 幂等。「给不出值」用 `ConfigSourceException` 表达——**分裂的消费行为归消费方**（SDK 捕获 ⇒ 空规则集全放行；中心不捕获 ⇒ fail fast），配置源只报告事实。
- **`FileConfigSource<T>`**：mtime+size 指纹 + 1s 轮询（`Thread.ofVirtual()`，不用 WatchService）；版本 = **`<md5>#<从 1 起的单调序号>`**~~（⚠️ **票 09 已反转：版本 = 内容 md5，序号下线**，见本节开头的反转说明；指纹那一半不受影响）~~，序号只在内容 md5 变化时自增（只 touch / 重写同内容不推进版本、不回调）。**构造永不因文件缺失/非法而抛**（首次失败 ⇒ `get()` 抛 + 持续重试），运行中失败 ⇒ 保留旧份、按指纹记账不反复解析刷日志，读失败不记指纹（下轮再试）。
- **`RuleSetParser`**（yml 解码）：snakeyaml `SafeConstructor` + `allowDuplicateKeys(false)`；**未知键忽略**（与 §6.4「消费方忽略未知字段」同一口径，加字段的一版配置落到旧 SDK 不该把限流关掉）；显式空 `rules: []` = 空规则集。**空文档与缺 `rules` 键都判写错**（见下「人工拍板」）。
- **client 接入**：`Builder.ruleSource(ConfigSource<RuleSet>)`（生命周期归接入方，client 不替它关）+ `rules()`（只读快照，票 08 的拦截器用）+ `ruleSnapshot()`（规则与版本**成对**读，票 05 的续租请求要带 `rule_version`）。首取与变更回调**在同一把锁里写**（见下 code-review ①）。`tryAcquire` 三格：有桶 ⇒ 桶数学；无桶但命中规则 ⇒ `Probe.deniedNoShare()`；无桶且不命中 ⇒ 放行（`Probe.unlimited()`）。

**code-review 采纳项**（两轴，`/code-review` since HEAD）：

- **Spec 轴（真货）**：① **首取与回调的竞态**——`this.rules = source.get()` 的读与写不原子，回调若插在中间会被「读到的旧快照」覆盖，SDK 停在旧规则上直到下次变更（原注释声称不漏，是假的）⇒ 两种写入同锁；② 端到端测试名 `…删掉规则后重新放行` **过claim**：已拿到份额的 key 要等中心回 `unmatched` 才逐出（票 05/06），本条只对**无桶**的 key 成立 ⇒ 改名为 `…删掉规则后无桶的key放行` + 方法上写明边界；③ 验收项 3 写的是「mtime**+size** 指纹」而只测了 mtime ⇒ 补 `只变size也认得出`（写完把 mtime 掰回去并断言 mtime 真没动）。另两条留档：~~`md5#序号` 让**同一份内容在不同加载历史下版本不同**（中心 `D#1` 对客户端 `D#3`）——spec §5 只做相等比较、只观测不阻断，故无害，票 03/06 知悉即可~~ ⚠️ **这条"故无害"判错了**：中心 §8.6 正是拿 `RULE_VERSION_MISMATCH` 的"持续非零"当配置传播滞后信号，恒增会让该信号失效；票 09 按反转去掉序号（本节开头）；`Probe.deniedNoShare()` 的 `Long.MAX_VALUE` 让 `acquire(key, timeout)` 对「命中规则但无份额」的 key 睡满超时（此前无桶是立即返回），票 05 接首租时一并定夺。
- **Standards 轴（无硬违背）**：① `Match.matches(key)` 与 `RuleSet` 前缀扫是同一逻辑两处实现、且只有测试调用 ⇒ **删掉 `Match.matches`**（`RuleSet.match` 是两端唯一入口）；② 字段 `rules` 装的是 `ConfigSnapshot` 而 `rules()` 返回 `RuleSet`，名实不符 ⇒ 字段改 `ruleSnapshot`；③ 对外给 `rules()` + `ruleVersion()` 两个独立读，等于把「一个 volatile 装两件事」的封装又拆开 ⇒ 改出 `ruleSnapshot()` 一个成对读入口、去掉 `ruleVersion()`；④ 版本格式（`digest#seq`）之前只在测试里钉住 ⇒ 写进 javadoc；⑤ `RuleSetParser.apply` 是纯转发（Middle Man）⇒ 去掉 `implements Function` + `INSTANCE`，只留 `static parse`，调用点写 `RuleSetParser::parse`；⑥ 测试里拿 `wants`（真词条）当「未知键」例子 ⇒ 换成 `unknownField`。
- 两轴一致/本人**反驳或留档**：`ConfigSource` 不加 `I` 前缀（`gatekeeper/CLAUDE.md` §3.2 逐字写 `ConfigSource<T>`，比 `backend/CLAUDE.md` §8.2 更专门）；跨模块测试手脚（`awaitUntil` / `write`）逐字重复两份**不抽 test-jar**（为两个十行 helper 引 test-jar 打包 + 测试期依赖，机械成本大于重复成本）。

**人工拍板 / 未在票面写死、本票自行定的三处**（都不改 spec 口径，只填空白）：

1. **空文档 / 缺 `rules` 键 = 整份拒收**（保留旧份），不是「空规则集 ⇒ 全放行」。理由：文件是「先截断后写」，轮询可能读到写了一半的空文件，把它读成空规则集等于凭一次写操作把限流关掉；要表达无规则请显式写 `rules: []`。已在 `RuleSetParser` javadoc 与票面记录，并建议发布侧原子替换（写临时文件再 rename）。
2. **yml 用 snakeyaml、且不是 optional**：只带一个无传递依赖的解析器进 SDK 闭包（jackson-dataformat-yaml 会多带 databind/core）；不设 optional 是因为 v1 的规则文件格式就是 yml，设成 optional 等于要求每个用文件源的人自己声明依赖（票 06 的 Nacos 依赖仍按要求 optional）。
3. **分隔符集合 `/ : . _ -`** 用作「前缀歧义」告警的判据（spec §5 只说「建议以分隔符结尾」，未枚举）。

**已知波及 / 不在本票范围**：

- **首租三分法只到第二格**：「无桶 ⇒ 放行」「命中规则但无份额 ⇒ 拒」已落；第三格「曾有租约后断租 ⇒ fail-open/fail-closed」与真正的首租通道（合批、50ms 预算、`throttled` / `unmatched` 消费）归票 05。
- **规则删除的本地收敛只覆盖无桶的 key**：有桶的 key 等中心 `unmatched`（票 05/06）。本地不做「规则没了就逐出」——中心才是权威，且 spec §5 的收敛窗就是一个 `refresh_interval`。
- **热更新「回调 → 立即续租」未接**（票 02 只把规则集更到最新；接线在票 05、端到端验证在票 06）。
- **`Probe.deniedNoShare()` 的等待时长取 `Long.MAX_VALUE`**：票 08 的默认 `RejectionHandler` 上取整会算出巨大 `Retry-After`，若演示需要可在那儿截断——本票不预判。
- 未跑 backend 全量测试：只动了 `gatekeeper/`（protocol/config/client），backend 无改动。
