# 16: 可观测常态化 + 终验（effort 收口）

**What to build:** spec §5 收口两件事。

**JFR 常态化**：JVM 启动参数挂持续 recording 落 4 工程，含 `jdk.VirtualThreadPinned` **threshold=0ms**（默认 20ms 会漏短 pin）；recording 配置以拷贝 `measure/vt-pin-only.jfc` 为底**加线程统计事件**（原文件只含这一个事件，不能直接复用）。落盘位置与轮转本票定。不引入 actuator / micrometer / prometheus（spec 明文不采用）。

**命名审计收尾**：扇出（票 15）与消费 executor（票 14）落地后，全仓扫一遍「自己 new 的 VT 一律命名」，框架内部池归框架不管；既有 2 处单发已合规。

**终验**（spec §5.2）：① 平台线程数按 §5.1 分路径口径对照票 12 留下的基线各记一数——Web 层「下降」、消费层「清零」（`ConsumeMessageThread_*` 不再出现）、Dubbo provider 池与调度扇出「看峰值不看常驻」；② 替代信号手册可用性走查一遍——Hikari `active`/`awaiting` JMX 读数（需 `--spring.datasource.hikari.register-mbeans=true`）与 `connection-timeout` 异常日志口径能实际取到；③ 确认常态化 JFR 里 `ensureBucket` 路径零 pinning 事件（票 13 已修，常态化 recording 复核一遍）。

吞吐对照**不强制重跑**（spec：结论已知「实测无收益」，无未知假设可验证；`measure/` 脚本保留可重跑，复现先读其 README 的 8 条坑）。

**Blocked by:** 12（基线数字在票 12 Answer）、13、14、15

**Status:** resolved

- [x] 4 工程 JVM 启动参数挂持续 recording，threshold=0ms，jfc 为拷贝加线程统计事件的版本
- [x] 全仓命名审计过：自己 new 的 VT 全部有名字，无裸 `newVirtualThreadPerTaskExecutor()` 扇出
- [x] 终验数字留票面：分路径平台线程数 vs 票 12 基线（Web 下降 / 消费清零 / Dubbo 与调度看峰值）
- [x] 替代信号走查通过：Hikari JMX 读数与 `connection-timeout` 异常日志口径实际可取
- [x] 常态化 JFR 确认 `ensureBucket` 路径零 pinning 事件

## Answer

### 落地内容

**1. `backend/jvm/bs-vt-recording.jfc`（新建，入库）** —— 以 `measure/vt-pin-only.jfc` 为底拷贝，`jdk.VirtualThreadPinned` 保持 **threshold=0ms** + 栈；**加线程统计事件**：`jdk.JavaThreadStatistics`（1000ms 周期）、`jdk.ThreadStart`（带栈）、`jdk.ThreadEnd`、`jdk.VirtualThreadSubmitFailed`（带栈）。**刻意不开 `jdk.VirtualThreadStart/End`**：每创建一条 VT 就记一条，常驻 recording 下体量爆炸；要看「有哪些虚拟线程在跑」用 `jcmd Thread.dump_to_file`（票 14 口径），那才是读物。事件名与设置逐个照本机 JDK 21 的 `lib/jfr/default.jfc` 核实，起 JVM 验证无 unknown-event 告警。

**2. `backend/scripts/run-app.sh`（新建，入库）** —— 4 工程共享启动入口（`bs|activity|asset|center` → 8080/8082/8081/8083）。仓里原本**没有任何应用启动入口**（只有 infra 的 `test-env.sh`，应用都是手动 `java -jar`），故本票新立一个。三个设定（spec §5.1 下放给本票的三件）：

| 设定 | 值 | 说明 |
| --- | --- | --- |
| settings | `backend/jvm/bs-vt-recording.jfc` | 唯一来源，改这里 |
| 落盘位置 | `<工程目录>/logs/jfr/app-%t.jfr` | 随 `.gitignore` 的 `backend/*/logs/` 一起不入库 |
| 轮转 | `maxsize=200m` + `maxage=2d` + `dumponexit=true` | 超限丢最老的 chunk |

`--no-jfr` 可关；其余参数透传（例：`--spring.datasource.hikari.register-mbeans=true`，替代信号手册要它）。JFR 参数里的路径经 `cygpath -w` 转 Windows 形式 —— JVM 是原生进程、认不了 MSYS 路径（measure/README 坑 1）。

⚠️ **一条运维口径**（实机踩到，写进脚本头注释）：`taskkill /F`（硬杀）绕过 shutdown hook，`app-%t.jfr` 会停在 **0 字节**；取数要在杀进程**之前** `jcmd <pid> JFR.dump name=bs-vt filename=...`（或控制台 Ctrl+C 让 hook 跑）。正常退出时 dumponexit 确实落全量文件（实测 113KB）——两条路都验过。

**3. 命名审计（spec §5.1）** —— 全仓 `src/main` 扫出**恰好 3 处裸 `newVirtualThreadPerTaskExecutor()` 扇出**（即 map 记的「既有 3 处无名扇出落地票各改一行」），逐个补命名：`TimeoutTaskScanCase`（`timeout-scan-`）、`TimeoutTaskRegisterCase`（`timeout-register-`）、单体 `TimeoutRegisterOutboxSender.sendAndSettle`（`timeout-register-send-`——起初取 `timeout-outbox-`，code-review Standards 轴指出同文件既有两个单发叫 `timeout-register-immediate` / `timeout-register-cancel`，扇出另起一族的做法会让「按前缀 grep 日志」把同一组件拆成两义，已改成同族前缀）。另把 `TimeoutTaskScanCase` Javadoc 里那句已过时的 `newVirtualThreadPerTaskExecutor` 引用改掉。审计后：全仓 `Thread.ofVirtual()` 零例外全是命名 factory / 命名单发；`newVirtualThreadPerTaskExecutor` **零命中**（`src` 与 `src/test` 都扫过）。框架内部池（Tomcat / RocketMQ client / Redisson / xxl）归框架，不在审计面。

**4. 启动口径入文档（code-review 采纳）** —— `backend/docs/README.md` 加一节：起应用一律走 `scripts/run-app.sh`，含 JFR 三设定与取数命令。理由：code-review Spec 轴指出「常态化靠纪律」——原先只有脚本存在、没有任何文档指向它，沿用旧的 `java -jar` 习惯就静静没有 recording。现在它是文档化的标准入口。

### 终验证据

**① 分路径平台线程数（vs 票 12 基线）**

| 口径 | 票 12 基线 | 票 16 | 同窗口的反证（证明有负载、不是「没跑所以是 0」） |
| --- | --- | --- | --- |
| Web `http-nio-8080-exec`（启动态） | 10 | **0** | — |
| Web（c=32/30s 压测中） | 32 | **0** | 同期 `tomcat-handler-*` **VT 35 条** |
| 消费 `ConsumeMessageThread_*`（压测峰值） | 100（票12）/ 80（票14 基线） | **0** | 同期 `mq-consume-*` **VT 21 条** |
| Dubbo provider 池 `DubboServerHandler-*` | 空载 0 | **0** | 看峰值不看常驻；本票**未在带载下观测**（单体独立跑、无消费方调它的 provider）——但 0 是**结构性**的：`threadpool: virtual` ⇒ 这类 handler 线程根本不会被创建，启动日志里 `threadpool=virtual` 出现 23 处为证 |
| 调度 `xxl-job, JobThread-N` | 空载 0 | 峰值 **7** = 当轮 7 个任务各 1 条 | 扇出项本身是 VT：`trade-remind-product-*` 峰值 **98**、`errand-remind-*` 峰值 **93** |
| 平台线程**总量** | 878 / 1490 | 970 / 1249 | 被 RocketMQ client 内部线程主导、波动大 ⇒ **不拿总量当 KPI**（票 12 同款口径） |

**比快照更强的会话级证据**：常态化 recording 的 `jdk.ThreadStart`（**2108 条**，覆盖整个会话含两轮压测）里，`ConsumeMessageThread_*` 与 `http-nio-8080-exec-*` **零次创建**——消费层与 Web 层在整个观测期一条平台线程都没起过，不是某一时刻的巧合。（顺带证实 `jdk.ThreadStart` 只覆盖平台线程、不覆盖 VT，故它正是「平台线程创建账」。）

压测读数：c=32/30s → rps **154.7** / 错误率 0.53%，与票 12（157.2）、票 14（144.5）同落噪声内 —— **原样印证「实测无收益」**。

**② 替代信号走查（spec §5.1，两条都实际取到）**

- **Hikari JMX**：`--spring.datasource.hikari.register-mbeans=true` + 票 04 `HikariProbe` 同款探针，c=32 臂读出等连接线程峰值 **64**、`active` 峰值 **10**（= 池大小）；c=256 臂 `awaiting` 峰值 **246**。
- **`connection-timeout` 异常日志**：c=512/30s 单 op 压测（p99 3656ms > 3000）打出 **86 条** `java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 3000ms (total=10, active=10, idle=0, waiting=500)`。这同时是 §2.3「连接池是唯一真上限 + 3000ms 快失败」的实证：Tomcat max/busy 读 `-1`、Dubbo 丢掉拒绝信号之后，**背压确实在池上现形**。

**③ `ensureBucket` 常态化 JFR 零 pinning（含灵敏度对照）**

- 常态化 recording 全量 **144318** 个 `jdk.VirtualThreadPinned` 里，`ensureBucket` / `FileStoragePort` **0 命中**。站点分布三分（合计 = 全量）：`com.mysql.cj.protocol.ReadAheadInputStream.fill` **144274**（99.97%，票 04 已证无害的 JDBC socket 读 pin，p50 1.5ms / max 389ms）、`NioSocketImpl.timedRead` **43**（max 2.63ms）、Spring 内部 **1**（下条）。线程名分布：`tomcat-handler` 68648 / `mq-consume-*` 74648 / `timeout-register-immediate` 1022。**逐条核过栈**：全量事件里 `com.betterschool` 包名 **0 次**出现——即我们自己的代码在整轮观测里**一处都没 pin**。
- 那唯一 1 条非 JDBC 事件是 **Spring 自己的**：`tomcat-handler-32` 一条 VT 等了 **0.825ms**，栈是 `AbstractFallbackTransactionAttributeSource.getTransactionAttribute → AopUtils.canApply → ConcurrentReferenceHashMap.put → ReentrantLock.lock`——即首次请求触发某个被代理 bean 的懒建时，Spring 解析事务属性用的内部 `ConcurrentReferenceHashMap`（`synchronized` 段）把 VT 钉了一下。**框架内部、非本仓代码**，0.0007% 且亚毫秒；与 spec §2.4「框架内部池归框架」同精神，**不新增改造项**（记一笔以免后人看到「还有 1 个 pin」以为漏改）。
- **灵敏度对照（本票新做）**：票 13 那支 harness 用**新 jfc** 重跑两臂 —— 旧 `synchronized` 臂 **3 个事件全落 `ensureBucket`**、新 `ReentrantLock` 臂 **0**，两臂各 32/32 成功。即「0」不是探针失灵（`feedback_zero_claim_needs_sensitivity` 的口径：换过 recording 配置就得重证灵敏度，不能拿票 13 那次直接外推）。

### 一个必须点名的观察

压测中打出 **33 条** `Deadlock found when trying to get lock`，SQL **全是** `UPDATE virtual_wallet SET frozen_amount = frozen_amount + ?`（`WalletRepository.freeze`，栈顶 `ErrandOrderCase.payOrder`）—— **不是** spec §4.3 F 的关单路径。

- 该死锁**每个历史臂都有**（VT-off 臂 `app-vt-baseline.log` 68 次、`app-vt-C-vtoff-p64.log` 13132 次）⇒ 票 04 已记的「同一用户并发 `virtual_wallet` 死锁、与 VT 无关」原样复现，不是本 effort 引入。
- **§4.3 F 的触发条件（关单路径出现该日志）本轮未触发、也无法证伪**：dev 库里没有到期商品可关，`tradeExpireScan` 的 `trade-expire-*` 扇出本轮峰值 0（真正跑起来的是它的 `trade-remind-product-*` 待审提醒支）。该条仍是「出现即另立票」的待观察项。

### code-review 采纳项

- **Standards 轴**：① 扇出前缀同族化（`timeout-outbox-` → `timeout-register-send-`，见上）；② `run-app.sh` 的 `usage()` 原用 `sed -n '3,20p'` 抓自己的头注释行号——改头注释会静默改 usage 输出，已换成 heredoc；③ 补 `command -v java` 预检（对齐 `test-env.sh` 的 `command -v docker`）；④ jar glob 由 `ls $JAR_GLOB` 改为 `"$PROJ_DIR/$APP_DIR/target/$APP_DIR-"*.jar`（只让文件名段展开）；⑤ 调用口径对齐 `backend/CLAUDE.md` §10 的仓内惯例（`scripts/run-app.sh <svc>`，不带冗余 `bash ` 前缀——脚本本就是 755）。**未采纳**：三处扇出与 `bs-types` 的 `VirtualThreadFanout` 形状重复——timeout-center 是独立 Maven project、看不到单体 `bs-types`（票 14 同款理由），单体那处并入 helper 也会改掉它在项内 `registerOne` 自吞异常、按 boolean 汇总的语义；属跨工程共享组件的另一次裁决，不在命名审计票内。
- **Spec 轴**：① 「常态化靠纪律」（无文档指向脚本）⇒ 已加 `backend/docs/README.md` 条目（见上）；② Dubbo 行缺带载峰值 ⇒ 已在表内写明「未在带载下观测」+ 结构性理由（不拿「空载 0」冒充峰值读数）。其余（`VirtualThreadSubmitFailed` 属非统计类事件、`TimeoutTaskScanCase` Javadoc 顺带改写）经核属合理范围，不撤。

### 验证

`mvn -o test` 全绿：timeout-center **32** 用例、单体 **364** 用例，0 失败 0 错误（命名改动是 3 行字符串字面量 + 1 行注释，无行为变化；`FileUploadIntegrationTest` 9/9 亦绿）。

⚠️ **一次自己造出来的假红（留档，避免后人误判为回归）**：本票在跑全量时**中途 TaskStop 了一次 `mvn test`，forked surefire JVM 活了下来继续跑**，与随后启动的第二次全量并发 truncate/insert 同一个 `better_school_test` ⇒ 第二次报出 28 失败 + 13 错误（`用户不存在`、`Table definition has changed ... 1412`、`信用分低于 60`、`Incorrect result size: expected 1, actual 0`）——**成组聚在完全没碰过的 chat/community/errand 域，看起来像改动打崩了全仓**。判污染的决定性证据：受影响的 `ChatGroupDomainIntegrationTest` **单跑 7/7 全绿**；且全量跑期间 `jps -l` 能看到 **4 个 JVM**（正常应为 2：Maven + surefire）。处置照仓内既有口径：`taskkill /F` 清残留 → `cd backend && bash scripts/test-env.sh reset-test-db`（只重建 4 个测试库、不碰开发库）→ 重跑 **364/364 全绿**。**这条坑 memory 里早写过**（"TaskStop 杀 mvn 测试：forked surefire JVM 会活下来继续跑"），本票是又一例。

产物落 `.scratch/virtual-thread/measure/verify16/`（未入库，可重生成）：`final.jfr`、`pins-aggregate.txt`、`dump-startup|load-c32.txt`、`vthreads-load-c32.json`、`probe-vton-t16-c32|c256|c512.csv`、`ticks/`（28 个调度采样）、`old16.jfr` / `new16.jfr`、`aggregate_pins.py`。验证用的两个临时桶（`bs-pin-t16-old|new`）已删；xxl 组 3 的任务状态跑完**已还原**（`1,2,3,4,6,14,15,16` 启用）。
