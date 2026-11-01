# 04 task：实测单体翻框架开关后的 pinning 与吞吐

Type: task
Status: resolved
Blocked by:

## Question

用实测把「翻 `spring.threads.virtual.enabled` 到底值不值、会不会咬人」从纸面变成数据。这是 spec 唯一的事实底座，也是唯一能证明收益的手段。

**做什么**：

1. 起全套环境（`backend/scripts/test-env.sh up`，注意容器不自启、Nacos 先就绪），单体 `better-school` 构建可运行 jar
2. **基线**：开关关闭，跑一条覆盖主要阻塞路径的压测——帖子详情/列表（Redis + MySQL）、发帖/评论（MySQL + outbox）、图片上传（MinIO）、二手/跑腿下单（MySQL + 钱包 + outbox）。记录吞吐 / P50–P99 延迟 / 错误率
3. **实验组**：只改 `spring.threads.virtual.enabled=true`（**不改别的**，尤其不动 Hikari），同一压测重跑
4. **收 pinning**：实验组开 JFR（`jdk.VirtualThreadPinned` + 栈轨迹），导出钉住的调用栈清单与次数 / 耗时分布
5. **对照连接池口径**：同一实验下记录连接池等待（单体 Hikari 默认 max=10），判断收益是否被连接池抹平

**产出**：压测脚本 + 两份原始结果 + JFR 导出，落 `.scratch/virtual-thread/measure/`；票面 `## Answer` 写核心结论并转[票 05](05-grill-framework-flip-and-backpressure.md)。

**约束**：

- 实验性配置改动**不得留在 master**——用 profile / 一次性分支，跑完还原
- 环境起不来的话降级为「给用户一份可照做的清单」（HITL），但**先尝试自己驱动**
- 压测工具任选（机器上有 JMeter 5.6.3 `/c/Code/apache-jmeter-5.6.3`，但脚本 + 并发客户端更省事，不作硬性要求）

## Answer

**一句话结论：在本仓库真实的混合负载下，翻 `spring.threads.virtual.enabled` 换不来吞吐——收益的上限早就被连接池与 DB 延迟兜死了；把池放开、并发打满之后，实验组也仍然没有优势（那组单次跑的尾巴还更长，只能当信号）。**

实测（单体 `better-school`，同机，`c` = 并发客户端数，每档 30s；脚本与原始结果见 [`measure/`](../measure/README.md)，`measure/results-table.txt` 是汇总表）：

| 配置臂 | c=32 | c=128 | c=256 |
| --- | --- | --- | --- |
| A VT off，Hikari 默认 10 | 179 rps / p50 138ms | 256 / 419ms | 247 / 830ms |
| B VT on，Hikari 默认 10 | 93 / 262ms | 143 / 727ms | 261 / 777ms |
| C VT off，Hikari 64 | 452 / 47ms | 489 / 208ms | 516 / 421ms |
| D VT on，Hikari 64 | 413 / 51ms | 481 / 213ms | 503 / 406ms |

**读法**：把池从 10 抬到 64，同一份负载吞吐翻倍（c=32: 179→452 rps）；**决定上限的是池，不是线程模型**——Tomcat 能不能再开 200 个线程根本不影响结果，因为 10 个连接后面排着两百个等的人。

⚠️ **但这张表里 VT on/off 的对照不能直接读**（表的 A→D 是按时间先后跑的）：
- 池 64 那对（C/D，相邻）差 ≤9%，落在噪声里；
- 池 10 那对（A/B）在 c=32 差到了 2×（179 vs 93），**这不是「VT 更差」的证据**——B 跑在 A 之后，而坑 #1 的数据量增长只会让靠后的臂更慢。真正干净的对照要看相邻时刻的 **ABAB** 与**池 256** 那两对（见下）。
- 综合三组对照，可支持的结论只有「**没有可辨收益**」，不支持任何方向性断言。

错误率（各档，`r-*.json` 的 `error_rate`）：池 10 约 0.7%–1.4%；池 64 约 4.8%–6.2%。差异来自坑 #2 的 `virtual_wallet` 死锁——池越大 → 落到 DB 的并发越高 → 死锁越多，**与 VT 开关无关**（同池的两个臂错误率几乎相同）。
数据量增长这个混淆只会让**靠后**的臂更慢，所以「池 10→64 吞吐翻倍」是**保守**读数（真实增益 ≥ 2.5×），同理池 256 那组的 576 rps 也是下界。

### ① 连接池口径（票面 ⑤）：池是第一道的、也是本票唯一能量化的瓶颈

JMX `HikariPoolMXBean` 逐 200ms 采样（`probe-*.csv`）：

| 臂（c=256） | 池占用 | `ThreadsAwaitingConnection` | 进程 CPU |
| --- | --- | --- | --- |
| A VT off，池 10 | 均值 10.0 / 10，97% 采样点占满 | 均值 195、峰值 **280** | ≤10.5% |
| B VT on，池 10 | 均值 9.9 / 10，97% 占满 | 均值 232、峰值 **336** | ≤13.8% |
| C VT off，池 64 | 均值 59.5 / 64，76% 占满 | 均值 149、峰值 231 | ≤15.7% |
| D VT on，池 64 | 均值 59.4 / 64，69% 占满 | 均值 183、峰值 281 | ≤15.4% |
| VT on，池 256 | 峰值 106、均值 52 | **恒 0**（一次都没等过） | ≤27.3% |

应用**全程不是 CPU 受限**（全部臂峰值仅 30.7%），纯粹在等 DB。这正是「能把并发抬高」与「抬高了有用」是两件事的证据。

### ② pinning（票面 ④）：全仓只有一条路径在 pin，尾巴极短

实验组（VT on + 混合负载 c=256）40s 记录，`jdk.VirtualThreadPinned` 阈值设 **0ms** 全收：

- **59,761 个事件，全部来自同一个调用点**：`com.mysql.cj.protocol.ReadAheadInputStream.fill()`——JDBC 读 MySQL 响应包的阻塞 socket 读（`NioSocketImpl.implRead → Poller.poll → parkOnCarrierThread`）把载体钉住
- 时长 p50 **1.28ms**、p90 6.44ms、p99 28.6ms、max 176ms；**超过 `default.jfc` 默认 20ms 阈值的只占 1.8%**（1078 个 ≈ 27 个/秒）
- 线程 58,055 条 `tomcat-handler-*` + 1,706 条 `timeout-register-immediate`
- **票 03 点名的 `FileStoragePort.ensureBucket()` 一次都没进榜**（`bucketReady` 快路径挡住了后续调用）

⚠️ **一处对票 03 结论的实测修正**：票 03 由「MySQL 驱动协议层零 monitor」推出该路径「不 pin」，但 0ms 阈值下 JDBC socket 读确实在刷 pin 事件。二者不矛盾——它属票 03 归的**补偿型**（载体池可扩到 256、非死锁型），只是「零事件」在 0ms 阈值下不成立。**落到票 05 的口径**：pinning 不构成本仓的改造项（清单仍只剩票 03 那一项），但也别把默认阈值下的「零事件」当成「零 pin」的证据。

### ③ 池放开后实验组仍无优势，且尾巴更长（单次跑，只作信号）

池开到 256、c=256，这一组 VT 组的 `awaiting` **恒为 0**（一次都没等过连接），却：

| | rps | p50 | p99 |
| --- | --- | --- | --- |
| VT off | 575.7 | 368ms | 1357ms |
| VT on | 434.5 | 349ms | **6461ms** |

吞吐低 25%、p99 长 4.8 倍。合理怀疑是「无上限放进来 + 载体被 JDBC 读钉住」造成的排队/护航效应，**但每档只跑一次，这条只能当信号、不能当定论**。

### ④ 纯阻塞 I/O 路径（不占 DB 池）也没差

`--only-op image_upload`（MinIO，c=256，各臂一次，**四次全部 0 错误**）：VT off **117.7 / 95.1** rps，VT on **91.6 / 94.5** rps，p50 都在 2.2–2.8s。**连不碰连接池的路径，开关也换不来上限**——天花板在 MinIO 侧（单容器写盘）；组内离散本身就有 ~20%，两组差异不超出该离散。

### 环境实测踩到的坑（供后续票参考）

1. **数据量是最大混淆项**：同一条 `post_list` 单 op、同一个 c=32，帖子表 ~500 行时 **1756 rps / p50 17.7ms**（早期单 op 诊断跑，原始文件未留存，只在 `jfr_run.sh` 注释里可考），涨到 1.7 万行后只剩 **190 rps / 147ms**（`r-ctrl-postlist-c32.json`）。跨时间比大小会得出完全错误的结论；本票的 A/B 一律读**相邻时刻**的臂。
   ⚠️ **ABAB 那一组要连带读**：实际跑了 4 个臂，**第一个（VT off）被丢弃**——`r-AB-a-mixed.json` 错误率 34.8% 且全是压测客户端侧 `ClientConnectorError`，同时刻的 `probe-AB-a.csv` 只有 95 行（预期 ~390 行），说明那一次被测应用没能提供稳定服务。丢弃后 VT off 只剩 1 个样本（291.3 rps），VT on 有 2 个（252.6 / 309.8）。**若把丢弃那个臂（130.5 rps）计入，VT off 均值 210.9 反而低于 VT on 的 281.2——符号会翻转。** 所以这一组只支持「没有可辨差异」，不支持任何方向性结论。
2. **存量 bug，与 VT 无关**：同一用户并发下跑腿单 → `UPDATE virtual_wallet` 死锁（`DeadlockLoserDataAccessException`），错误率随 DB 并发上升（池 10 ≈ 1.4%，池 256 ≈ 7%），**每个臂都有**。
3. **超时中心没起**：注册边每次都 `RpcException: No provider available ... ITimeoutTaskRegister`（快速失败，留在 outbox PENDING），对称出现在所有臂。
4. 压测期间**临时关掉了单体 xxl-job 的 8 个启用任务**（`job_group=3`，它们跑同一个 Hikari 池会污染对照），跑完已还原原状态。

### 给 票 05 的输入

- 「翻开关」在本仓**没有可观察收益**，落地面裁决不必拿吞吐当论据，按「选点规则 + 线程账」判即可
- **承压上限的现实答案是连接池与 DB 延迟，不是线程数**：池 10 → ~250 rps 天花板，池 64 → ~500 rps，池 256 时 VT off 摸到 576（且池仍未打满，真实上限还没探到）
- pinning 改造清单**没有新增项**，仍只剩票 03 说的 `FileStoragePort.ensureBucket()`
- ⚠️ **票面「翻开关省事」的隐含前提要打折**：它省的是 Tomcat 线程数，而线程数在本仓根本不是瓶颈

**约束遵守**：实验性配置**全部走运行时参数**（`--spring.threads.virtual.enabled=true`、`--spring.datasource.hikari.maximum-pool-size=N`、`--spring.datasource.hikari.register-mbeans=true`），**master 零改动**（收口时 `git diff` 为空）。

**已知局限**（按对本结论的威胁从大到小）：
1. **无重复**：每档只跑一次；差 10% 以内的差异不可判（本票所有 on/off 差异都落在这个带里）。ABAB 那组有一个臂因压测客户端侧连接错误被丢弃（坑 #1），VT off 在该组只剩 1 个样本。
2. **数据量随时间增长**：A→D 按时间先后跑，跨臂比较被混淆，只有**同池 on/off** 与**相邻时刻**的对照可用；对「池是大头」这个结论而言混淆方向是保守的（见上）。
3. **负载是自造的混合场景**，不代表线上配比；10 个 op 的权重（`loadtest.py` 的 `MIX`）是拍的。
4. **压测客户端与被测应用同机**，c=256 时客户端自身也抢 CPU；单进程 aiohttp 在高并发档可能自己成为一部分瓶颈（未量化）。
5. 联调库被本票灌脏（帖子 ~1.7 万 / 评论 ~2.1 万 / outbox ~8.6 万 / 跑腿单 ~1.7 万），绝对数字整体偏悲观——但**同向作用于两个臂**。

**转 [票 05](05-grill-framework-flip-and-backpressure.md)**（其依赖 03、04 至此齐备）。
