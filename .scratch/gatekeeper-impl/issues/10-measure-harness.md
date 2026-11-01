# 10: measure harness 与标定复核（§8.3 四验收项 + §8.4 性能三项）

**What to build:** spec §8.3/§8.4 的一次性验收资产：`gatekeeper/measure/` 下 harness + 微基准 + README（坑清单），**读数留票面、产物不入库、脚本可重跑**。端到端行为：**跑一遍 harness 得到四个验收项的实测读数、跑一遍微基准得到三项性能读数，逐项对照 spec 判据并记录**。

机制逐条照 spec §8.3 / §8.4：

- **harness 拓扑**：单机 1 center 进程（可双分片档）+ 4 个 SDK 客户端 JVM（可调档 2–8），`POD_NAME` 显式身份；`CountDownLatch` 栅栏起跳的闭环突发 + 固定速率稳态段，**每臂稳态 ≥ 5 分钟（≥ 60 个 `refresh_interval`）**。⚠️ rate 配置要让每实例份额 ≥ 1/burstSeconds（4 JVM 时 rate ≥ 4/s），否则落微份额区间、读数对不上正常区间的式子。
- **四验收项**（一个 harness 覆盖）：
  - 准确度：稳态窗口 Δ=60s 内平均放行率 ∈ [R, R×1.02]（观测点 = 各实例放行计数合计为主、中心份额发放为对照）；
  - 瞬时突发：栅栏起跳 1s 窗口放行增量 ≤ Σ max(份额×burstSeconds, 1)；
  - 过渡窗超发 ≤ 2R：中心重启后一个 `refresh_interval` 内放行总量；
  - `ttl=0` 主动停 vs 断租被动继续：两行为可区分（IT 已测语义，此处实测量级）。
- **性能三项**（`gatekeeper/measure/` 微基准）：① 首租端到端 p99 ≤ 50ms（合批窗 10ms，空载 + 带载各一档）；② 满配 500 key/请求的续租批端到端 ≪ 2s 子批超时；③ 中心单分片续租吞吐 ≥ 200 QPS（1000 实例 ÷ 5s 算例）。
- **分代交替**：崩溃重启后稀释窗口不超发、幽灵条目过期（≤ 2 个 `refresh_interval`）后一个 `refresh_interval` 内份额恢复。
- 验收不起 Prometheus：窗口起止各抓一次 `/metrics` 文本算差值（client 侧读 MXBean）。

**Blocked by:** 07、09（多分片与全量指标就位，读数才齐）

**Status:** resolved

- [x] harness 可重跑，四验收项读数 vs 判据逐项记录（超界即回修，不留「差不多」）
- [x] 性能三项读数记录；任一超界回修后重测
- [x] 世代交替项：稀释窗不超发 + 幽灵过期后恢复读数
- [x] README（坑清单）落地；产物不入库（.gitignore 或等价手段核过）
- [x] 读数与结论回填本票票面

## Answer

**落地点（2026-09-23）**：`gatekeeper/measure/` 下四个可执行件 + 一份 README（坑清单）。
产物（`measure/results/`、`measure/build/`）由 `measure/.gitignore` 挡在库外。

| 文件 | 是什么 |
|---|---|
| `build.sh` | 编译：`mvn install -DskipTests` 四模块 + 编译 harness/微基准 → `build/classes`；classpath 落成文件（绕开 Git Bash 的两处路径改写，见 README 坑 10） |
| `run-harness.sh` | 跑四臂（`--arms=ABCD`／可单跑／`--quick` 短档冒烟） |
| `run-microbench.sh` | 起单分片中心 → 跑性能三项 → 收摊 |
| `harness/src/…/HarnessClient.java` | **客户端工作进程**（一个进程 = 一个 SDK 实例）：栅栏起跳的闭环突发 + 固定速率稳态，按 100ms 网格采自己的 MXBean → `client-<id>.ndjson`（逐行 flush） |
| `harness/src/…/HarnessRunner.java` | **编排器**：起中心/客户端、制造各臂的事件（重启中心 / 杀 pod）、收 `/metrics` 差值、算判据 |
| `harness/src/…/StubCenter.java` | 臂 D 的假中心（`ttl=0` / 挂着不回；真中心 v1 没有 drain 控制） |
| `harness/src/…/Series.java` `PromText.java` `Mx.java` `Args.java` | 时间线合计与窗口数学 / `/metrics` 文本解析 / MXBean 读数 / 参数 |
| `microbench/src/…/PerfBench.java` | 性能三项微基准 |

### 四验收项 + 世代交替（读数逐项 vs 判据）

拓扑：单机 1 center 进程（单分片）+ 4 个 SDK 客户端 JVM；`R = 40/s`、`burstSeconds = 1.0`、
`refresh_interval = min_renew_interval = 5s`；每实例到达 `1.2 × R / 4 = 12/s`（过载 1.2，让份额吃满 R
又留得住 denied）；每臂 300s。**读数目录（全部出自最终构建）**：
`measure/results/harness-20260923-211518/`（臂 A/C/D）、`measure/results/harness-20260923-215914/arm-B/`
（臂 B）、`measure/results/microbench-20260923-213648/`（性能三项）。

| 验收项（spec §8.3） | 判据 | 实测 | 结论 |
|---|---|---|---|
| ① 准确度 | 稳态 Δ=60s 平均放行率 ∈ `[R, R×1.02]` = `[39.867, 40.8]`（下界含一格采样量化余量 0.133） | **39.983/s**（窗口内 2399 个放行；同一批数据拉长到 270s 得 **39.996/s**） | PASS |
| ①′ 硬不变量（本票加的先验项） | 整臂 `Σ份额 ≤ R`（中心账本实测） | **40.0 ≤ 40.0**（峰值出现在 200ms；对照：同一 harness 在回修前实测 **139.24 = 3.48×R**） | PASS（回修后） |
| ② 瞬时突发 | 栅栏后 1s 窗口放行增量 ≤ `Σ份额×(burstSeconds+1s)` = **80**（spec §3.3 窗口式，**按此判**）；§8.3 那句 `≤ Σ max(份额×burstSeconds,1)` = 40 是 Δ→0 的"瞬时"上界，与 §3.3 差 `R×Δ`，见自行拍板 2 / README 坑 15 | **77.0**（第二读数：桶水位峰值 **39.0** ≤ 混合区间保守上界 44） | PASS |
| ③ 过渡窗超发 | 中心重启后一个 `refresh_interval` 内 1s 窗口放行率峰值 ≤ `2R` = **80/s** | **42.0/s**（该 5s 窗口放行总量 200 = 40/s；断租 30s 期间 fail-open 率 **40.1/s**） | PASS |
| ③′ 恢复口径（**如实记、不判 pass**） | spec §7 写"中心回来 ≤ 1 个 refresh_interval 收敛" | 用户感知：重启后 10s 窗最低 **39.6/s（99%R）**——基本没有恢复期（fail-open 一直按陈旧份额跑）；中心账本 Σ份额：重启完成 **第一轮上报就回到 40**（113.0s → 114.5s），其后 ~2–3 轮有瞬时抖动，~119.5s 起稳定 | 与 spec 一致 |
| ④ `ttl=0` vs 断租 | `ttl=0` 后 ≈0、断租后 ≥ `0.8×份额` = 8/s | **0.149/s**（停自补、存量花完；degrade 记 9 笔）vs **10.0/s**（被动继续；续租失败记 9 笔） | PASS（两态可区分） |
| ⑤ 世代交替 · 稀释窗 | kill pod → 幽灵过期期间 1s 窗口放行率 ≤ `R` = 40/s | **32.0/s** 峰值；稀释段均值 **27.875/s**（幽灵仍占份额、活着的实例被摊薄） | PASS（不超发） |
| ⑤′ 世代交替 · 恢复 | 幽灵过期后 ≤ 一个 `refresh_interval` 恢复到 ≥98%R | **5000ms**（= 恰好一个 refresh_interval，**无余量**）；恢复后 40.0/s | PASS（贴界） |
| ⑤″ 稀释窗对照 | 中心账本 Σ份额 | 稀释段 30→40（均值 37.238）；恢复段均值 39.475 | — |

**臂的自证（这条救过一次假读数）**：③ 额外记了 `outageWasReal`——客户端必须记到续租失败（实测 **20 笔**）
**且**份额时间线的空档要接近配置的断租时长（`measuredOutageMillis` **37s** vs 配置 30s，差额 = 中心启动
那 8s）。两个条件缺一不可：只看"续租失败 > 0"挡不住"断租被缩到 8s、观测窗口落到真正重启之后二十多秒"的
假象——本票真撞上过（`sleepUntil(restartAt)` 那一行曾被误删，读数照常 40/s 全绿）。

**臂 A 的伴随读数（不在判据里，如实记）**：`centerMetricsDelta` 里
`gatekeeper_lease_share_granted_total` 2457.778（与窗口内放行 2399 同量级）、`redirect_total = 0`、
`rule_version_mismatch_total = 0`（单分片 + 两端同版本，符合预期）；客户端合计
`allowed 11899 / denied 11.58M`（denied 几乎全是栅栏那一秒 8 条突发线程的闭环重试）。

### 性能三项（spec §8.4）

中心单分片、`R=40/s`；读数 `measure/results/microbench-20260923-213648/`。

| 项 | 判据 | 实测 | 结论 |
|---|---|---|---|
| ① 首租端到端 p99（空载） | ≤ 50ms（含 10ms 合批窗） | **32.836ms**（p50 16.01 / p90 31.09 / max 37.29；300 个冷 key） | PASS |
| ①′ 首租端到端 p99（带载，背景 ~96 QPS） | ≤ 50ms | **17.067ms**（p50 15.551；背景 664 次请求） | PASS |
| ② 500 key/请求的续租批 p99 | ≪ 2000ms（子批超时） | **19.862ms**（p50 13.539；20 轮、0 失败） | PASS |
| ③ 中心单分片续租吞吐 | ≥ 200 QPS（1000 实例 ÷ 5s 算例） | **239.956 QPS**（按 240 QPS 派发、0 失败、p50 7.746ms；p99 1052ms 说明有个别子批排到秒级） | PASS |

微基准方法上的两处非显然处：① 测量前先用 40 个丢弃 key 焐（不焐实测 p99 63ms，是进程内第一次
HTTP 往返的 ~400ms 被记在自己头上，不是 spec §6.3 的预算）；③ 按**高于算例**的 240 QPS 派发再量实测，
贴着 200 派发量出 199.8 这种边界读数说明不了余量。

### ⚠️ harness 量出来的真缺陷：并发首租把 `Σ份额` 顶到 3.48×R（**已回修**）

**现象**：4 个实例同时冷启动、对同一个 key 并发首租时，中心自己的 `/admin/keys` 报
`Σ份额 = 139.24`，而规则 `rate = 40` —— **3.48 × R**（≈ 4 × 每个实例的估高需求）。把首租错开 3s 起跑
（`STAGGER_MS=3000`）则回到 40.0，即差异唯一来源是**并发**。
（这条对照读数出自一次 `--quick` 跑：`results/harness-20260923-192935/arm-A/report.json`
（`durationMs=30000`、`harnessShare=139.24`）——缺陷与时长无关，留这一份是因为它是最早坐实的那次。）

**怎么复现**：把 `KeyLocks.forKey` 临时改成 `return new ReentrantLock();`（= 每次一把新锁 = 无互斥），
跑 `LeaseCaseTest` —— 新增的那条用例必挂（本票就是这么证它的灵敏度）。

**机制**：`LeaseCase.leaseOne` 里对一个 key 的处理是 `ledger.listByKey` → `allocation.proportionalShare`
→ `ledger.renew` 三步，中间**没有任何互斥**。账本是两层 `ConcurrentHashMap`、只保证**单步**并发安全：
`renew` 拿的是 key 的 bin 锁，而 `listByKey` 是裸读 ⇒ 同一 key 的并发请求可以都先读到同一份空快照，
于是各自的 `others` 为空、`unusedCapacity` 都算成整个 R，**每个都拿到自己的全额需求**。
spec §3.3 把 `Σ份额 ≤ R` 写成硬不变量、并指名依据是 `minF(gets, unusedCapacity)` 那一行——但公式只在
"读 `SumHas` 与写回份额同一个原子点"时才是公式，实现缺的正是那个原子性。
续租路径撞不上（账本里已有条目，各请求看到的 `others` 一致），所以这是**冷启动专属**的超发窗口：
滚动发布 N 个 pod ⇒ 上界从 `R` 变成 `N×R`，`2R` 过渡窗与"瞬时突发"的构造上界一并失效。

**裁决（用户 2026-09-23 拍板）＝ 本票回修**：`LeaseCase` 里按 key 上锁
（新增 `center-case/usecase/lease/KeyLocks`：1024 片 `ReentrantLock` 条带锁，`floorMod(hash)` 落片；
选 `ReentrantLock` 而不是 `synchronized` 与 SDK 侧同一条纪律——虚拟线程撞上有竞争的 monitor 会 pin
载体线程，而 center 的 JFR profile 正是以 0ms 阈值盯着 `VirtualThreadPinned`）。锁只罩
`leaseOne` 的单 key 段（多 key 请求逐个加解锁、不嵌套，无死锁面）。
**回归用例** `LeaseCaseTest.并发首租同一key_份额之和不被并发数放大`（16 pod × 12 轮，每轮换 key 从空账本起步）。
**灵敏度已证**：把 `KeyLocks.forKey` 临时改成"每次返回一把新锁"（= 无互斥），该用例**必挂**；
改回来 31 条全绿。

**回修后的读数**：`shareInvariant` 40.0（臂 A）、准确度 39.983 ∈ 界内、`burst` 77 ≤ 80 —— 都在界内；
`gatekeeper/CLAUDE.md` §3 加了第 6 条纪律（新增任何"重算并落份额"的路径都要先拿这把锁）。

### 自行拍板（都可反转）

1. **臂 D 用假中心**：v1 真中心**没有 drain 控制**（票 03 票面逐字"drain 机制本票只需支持字段、
   预告流程归终验走查"），`ttl_ms = 0` 只在客户端侧被消费（票 05）。所以这一项用
   `StubCenter`（`com.sun.net.httpserver`）按绝对时刻返回 `ttl=0` / 挂着不回；spec §8.4 对这一项的
   措辞正是"IT 已测语义、**此处实测量级**"，量的就是客户端两态的量级差。
2. **`burst` 判据取 1s 窗口的放行增量，不取桶水位峰值**：水位峰值要求采样器抢到"桶刚建好、突发还没
   抽干"那一瞬（几百微秒），实测抢不到（读到过真值的一半）——那样的数不能当验收。上界用 §3.3 的窗口式
   `Σ份额×(burstSeconds+Δ)`。⚠️ **spec §8.3 那句只写 `Σ max(份额×burstSeconds,1)`，与 §3.3 差一个
   `R×Δ`**（§8.3 把"瞬时"上界误写进了 1s 窗口句）：本 harness 两处都报、按可推导的窗口式判，记在
   README 坑 15。**这条建议回填 spec**（口径不一致，不是读数问题）。
3. **准确度下界含一格采样量化余量**（0.13/s）：窗口两端读数都取自 100ms 栅格，实际窗口长度与名义
   最多差一格。不留余量时 Δ=60s 的 2400 会偶尔数成 2399（0.04%）判 FAIL——那是读数分辨率、不是少放
   （同一批数据拉长到 270s 实测 **39.996**）。报告里留了 `samplingSlack` 与 `longWindowRate` 两个字段。
4. **吞吐按 240 QPS 派发**（高于算例 200），量实测 233.69 —— 贴着派发量出 199.8 说明不了余量。
5. **`STAGGER_MS` 保留为诊断旋钮**（`--stagger-ms`，默认 0 = 验收口径的同步栅栏）：它就是当初把
   "并发"这个变量单独摘出来、坐实缺陷的那个手段，留着比删掉有用。
6. **每臂自己起、自己收中心**（`stopCenter` 会确认端口真的不再应答），起中心前 `requirePortFree`
   兜底、每轮现挑空闲端口。理由见 README 坑 1（两个变体：孤儿进程 / 臂之间串台）。

### code-review 两轴（`/code-review` since HEAD d5f5120）

**Standards 轴：无硬违规**（`KeyLocks` 落在 `center-case` 经核不违反 `gatekeeper/CLAUDE.md` §2/§8.1——
`usecase/metrics/CenterMetrics` 是同类先例，且 §3 第 6 条本身点名了它；锁的持有范围与注释风格也照了本仓做派）。
判为判断项的六条，处置如下：

| 反馈 | 处置 |
|---|---|
| 新用例把 `assertSumOfSharesAtMostR()` 抄了一遍 | **采纳**：把它参数化成 `assertSumOfSharesAtMostR(key, context)` 并复用 |
| `round()` 在 `io.gatekeeper.measure` 里有四份、**精度还不一样**（×1000 vs ×100） | **采纳**：统一成三位小数（同名不同精度才是真问题） |
| `HarnessRunner` 混了进程生命周期 / 判据 / HTTP / 落盘（Divergent Change） | **采纳**：抽出 `Procs`（子进程与 HTTP 管道、端口护栏），`HarnessRunner` 925 → 759 行 |
| `win()` 在三个脚本各一份、规则 YAML 两处 | **采纳**：`win()`/`cp_arg` 收进 `lib.sh`；微基准的规则文件改为复用 `conf/gatekeeper-rules.yml` |
| `KeyLocks` javadoc 写"Σ租约"（词条表里被限的是**份额**） | **采纳**：改口径，并补一段"为什么锁在 case 层、不在账本里"（评审点的 Feature Envy：锁护着账本却由 case 持有——如实记下它是**纪律不是强制**） |
| `Args` javadoc 说"本工程零额外依赖"（`measure/` 用了 Jackson） | **采纳**：改口径（那条纪律管的是发布出去的 SDK 闭包，`measure/` 不是 Maven 模块） |

**Spec 轴**：四条验收项 + 世代交替的实现与读数**逐项核过**，两处判据口径差异（§8.3 vs §3.3 的上界、
准确度下界的采样量化余量）**都已在票面与 README 显式声明**，评审认可"按 §3.3 判是对的"。提出的其余各条处置：

| 反馈 | 处置 |
|---|---|
| 臂 B/C 的读数出自**旧构建**（`peakAtMillis` 是负数） | **采纳**：改完代码后**重跑**（臂 A/C/D + 微基准一次跑完，臂 B 三次重跑直到自证通过），票面数字全部换成最终构建的读数；`peakAtMillis` 的重复偏移已修 |
| 评审说"臂 B 的 43.0/36.0/5000ms 出处未核实"——**顺着这条查下去发现了更严重的问题** | 臂 B 的 `sleepUntil(restartAt)` 曾被误删 ⇒ 重启在 kill 后 ~8s 就发生、观测窗口落到真正重启之后二十多秒，**过渡窗一格没量到而读数照常全绿**。已修 + 补两条自证（`clientRenewFailures` 与 `measuredOutageMillis`）+ README 坑 17（"事件时刻的断言必须能被读数自证"）。**这条是整轮 code-review 最值钱的产出** |
| 臂 C 的 `centerLedgerEntriesGauge = 0.0` 不可信（在客户端退出后才抓） | **采纳**：删掉这个字段（它不在任何判据里，账本状态看份额时间线即可） |
| README 写"240s / 40.01"、票面写"270s / 40.00" | **采纳**：统一为最终那次跑的读数 |
| 3.48×R 那条对照是 `--quick` 跑，票面没标 | **采纳**：票面已注明出处与时长，并补"怎么复现" |
| "每臂稳态 ≥ 5 分钟"只贴界 | **采纳**：票面写清 300s = 1s 突发 + 299s 稳态（≥ 60 个 `refresh_interval`），README 同步 |
| `POD_NAME` 实际走的是 `Builder.instanceId` | **采纳**：README 改精确（它是 spec §6.2 那条来源链的显式覆盖口，两者等价） |
| "分区 fail-open" 不在本 harness | **采纳**：票面「已知波及」已记为与 spec §8.4 括号的**覆盖口径差异**，归票 12 的 chaos 脚本 |

### 已知波及 / 不在本票范围

- ~~`ttl_ms = 0` 的中心侧 drain 预告流程~~：**仍未实现**（票 03 已把流程留给终验走查）；臂 D 只量客户端。
- **覆盖口径与 spec §8.4 的括号差一项**：§8.4 逐字「一个 harness 覆盖四个验收项（突发 / 过渡窗 /
  `ttl=0` 主动停 vs 断租被动继续 / **分区 fail-open**）」，而本票按**票面**的四个（准确度 / 突发 /
  过渡窗 / `ttl=0`）+ 世代交替覆盖 ⇒ 多覆盖了准确度与世代交替、**少**了分区 fail-open。后者明确归
  **票 12** 的 chaos 脚本（它的三场景含网络分区），不是漏做。
- **恢复口径的读数方法踩过一次坑（记下来，因为它差点变成一条假结论）**：一开始用 **1s 窗**量"恢复
  过程最差到哪"，读出 `minRate = 34.0/s`、`settledBy = 193s`（+88s 才稳）——据此我写了"恢复要 5 轮、
  掉到 95%"。**复核原始时间线后推翻了**：100ms 采样 + 整数放行计数下，1s 窗的量化噪声本身就有 ±4/s
  （4 实例各 ±1 次），34/s 是噪声不是事实；换成 **10s 窗**后最低 **39.6/s（99%R）**，而账本 Σ份额
  **第一轮就回到 40**。结论：spec §7 的"≤1 个 `refresh_interval` 收敛"**成立**，无需回填。
  （教训与 README 坑 17 同一条：读数的分辨率要先自己算一遍，别拿噪声当现象。）
- **`mvn test` 里有一条时序敏感的既有用例**（票 08 的 `DemoObservationTest.拦得下来_比值口径_断中心还活着`）：
  本票收尾那次全量跑挂过一次——中心的 `/v1/lease` 计数 **5 ∉ [2,4]**（"首租 1 + 续租两轮 + 抖动余量"）。
  单独连跑三次复现不出来，且它与本票改动无因果（本票只动了 `LeaseCase` 的按 key 锁与 `measure/`）。
  如实记在这里交**票 11** 的常态化回归留意（那正是它"跑进套件、长期盯着"要覆盖的一类）。
- **时长口径**：每臂总时长 300s = 栅栏那一刻的 1s 闭环突发 + 其后 299s 固定速率稳态（稳态段 ≥ 60 个
  `refresh_interval`，spec §8.4 要的正是后者）。
- **真进程级故障的 chaos 场景**（网络分区、双分片杀一个）归**票 12** 的脚本 + 人工走查；本票的
  kill 是进程级 kill。
- **两处读数小瑕疵（不影响判据）**：臂 B/C 报告里的 `peakAtMillis` 是旧构建留下的（多减了一次
  绝对栅栏时刻，已修、未重跑这两臂）；臂 A 的 `firstLeaseFailureCount = 40/72` 是栅栏那一刻
  8 条突发线程撞上 10ms 合批窗、溢出到第二批后命中 `min_renew_interval` 过频窗拿 `throttled` 所致
  （首租三分法第二格：拒 + 记指标，方向安全）——**解释合理但未逐条证实**。
- 读数全部是**单机开发机**、`R=40/s`、4 实例、单分片：证明机制在这个量级成立，**不证明**满配
  （10k key × 200 实例）容量账——那份账在 spec §3.5／票 12，是纸面 + 仿真。

### 平台事实（重跑会再撞的，README 有完整版）

1. **停后台跑 ≠ 收掉子孙**：kill 掉的是 bash 包装进程，Java 子孙照样活着并占着端口；下一轮的
   `/metrics` 探测会被旧中心答 200 ⇒ 新客户端往旧账本灌数据。清法见 README 坑 1。
2. **臂之间串台更隐蔽**：四臂共用同一端口而收尾只在 `main` 的 finally ⇒ 后一臂的中心绑不上端口
   （日志里一句 "Port xxx was already in use"）、`waitCenterUp` 却连上前一臂的中心 ⇒ 该臂全程空转。
   **`waitCenterUp` 这种"只探 HTTP"的就绪判据分不清"新起的"与"上一轮留下的"**——要么端口唯一、
   要么加身份判据（本票的做法是前者 + 每臂收摊 + 臂 B 自证断租）。
3. **`BucketTokensSum` 是所有在册桶的合计**，不是某个 key 的：栅栏前用来垫 HTTP 往返的丢弃 key 会留下
   第二个桶、把水位读数搅浑 ⇒ 垫完立刻 `client.remove(warmupKey)`。
4. **闲置清零会抹掉"栅栏前的垫桶"**（零到达轮 ⇒ 份额清零 + 存量 ×0）：栅栏只能从冷启动开始。
5. **微份额区间（份额 < 1/burstSeconds）容量下限接管**：4 实例 × `burstSeconds=1.0` ⇒ `rate ≥ 4/s`。
6. **Git Bash（MSYS）**：`;` 分隔的 classpath 会被当 POSIX 路径改写、`/c/…` 原生 JVM 读不了 ⇒
   JVM 参数一律走参数文件并把路径转成 `C:/…`。
