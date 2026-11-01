# 09: 观测归拢（SDK 三件套 + center 指标清单全量）

**What to build:** spec §8.1/§8.2 的观测设施归拢补齐——基础计数器已随票 01–07 随手带，本票把两边清单**全量**落齐并接上「开箱能看」与「能接走」两条路。端到端行为：**不接任何外部系统，center `/metrics` 与 SDK MXBean 就能回答 spec §8.6「坏的时候怎么看」手册里的每个问题；接上 `GatekeeperObserver` SPI 能把事件整流导走**。

机制逐条照 spec §8.1 / §8.2：

- **SDK 零观测依赖三件套**：内置原子计数器（已有）+ 默认 **MXBean**（放行/被拒计数、桶水位 sum+max——sum 即悬浮量、`acquire` 等待分布、首租延迟+失败率、续租失败/降级计数、桶表条目数+逐出计数）+ **`GatekeeperObserver` SPI**（放行/拒绝/降级/续租结果/逐出事件，能接走）。
- **center 指标清单 9 项全量**（`gatekeeper.<域>.<名>`）：份额发放合计（tag=rule，与客户端放行对照）、首租结果计数、在册条目数/在册实例数 gauge（分片维度）、冷驱逐/ttl 过期/return 回收计数（按 cause 分）、`421` 重定向计数、幂等去重命中计数、`400` key 超限计数、规则版本错位 + 配置整份拒收计数、续租请求处理时延 timer。
- **纪律**：key 维度不进指标（tag 基数爆炸），tag 只允许 `rule`；key 级排查 = 日志采样（续租/拒绝事件带 key）+ **`GET /admin/keys?top=N`** 快照端点（按水位/份额排序，从账本直读）。
- **JFR**：center 挂持续 JFR（照本仓 `bs-vt-recording.jfc` 手法新建 gatekeeper 版 profile）。
- 人工手册「坏的时候怎么看」的条目在本票逐条核过一遍可观测支撑（手册正文归终验票收口）。

**Blocked by:** 07（分片后指标才带上分片维度，`421`/回收 cause 才有真数据）

## 结构硬输入（2026-09-23，票 06 的 code-review 带出来）

**要修的缺陷**：`rule_version` 把 `#<本进程加载次数>` 带上了线、而且是**双向**的——客户端发 `rules.version()`、**中心响应也回 `rules.version()`**（`LeaseCase:108`），字段串是 `md5#N`；`LeaseCase.observeRuleVersionLag` 拿客户端那个串跟**中心自己**的比，而两端的 N 各自自产（谁多加载过一次就不一样）⇒ **同一份规则也常态判「错位」**，`RULE_VERSION_MISMATCH` 恒增，spec §8.6 指望的「持续非零 = 配置传播滞后」这个信号失效，且与 spec §5「规则版本（协议里带）= **规则内容哈希、只做相等比较**」、§6.2 字段表相悖。不是票 06 引入（版本格式来自票 02、比较来自票 04、上线来自票 05），本票是落点。

**裁决（用户 2026-09-23 拍板）= 让 `ConfigSnapshot.version` 直接就是内容哈希（去掉 `#seq`）**，而**不是**"比较时取 `#` 前一段"：

- 只改比较函数是**半修**——发出的字段（请求与响应两边）仍不合 §6.2，照 §6 自实现的其他语言 SDK 会拿到这个私有后缀。
- `#seq` 目前**零消费者**：变更判定在 `SnapshotStore` 里比的是 md5 本身（A→B→A 照样回调）；没人拿版本比序（`topo_version` 的比序来自配置里**显式声明的数字**）；`isBlank()` 只当"从没加载过"的哨兵。票 02 给的理由（"回退到旧内容时版本仍然往前"）没有任何调用方需要。
- 顺带简化拓扑：§6.2 逐字「`topo_version` = 拓扑配置里显式声明的单调递增数字」⇒ 它在**载荷里**，本字段只表"这份内容是什么"，票 07 不必再拧一次语义。

**本票要动的地方**（按反转处理，票面 + spec 都要留档）：

- `SnapshotStore`：版本自产从 `digest + "#" + seq` 收成 `digest`，`sequence` 字段随之删。
- 票 02 的文档化拍板按**反转**记：`FileConfigSource` javadoc 的「版本格式」段、票 02 的 `## Answer`、spec §5 那句「Nacos 用内容 md5 + 单调序号、文件用 size+mtime 指纹」的括号。
- 断言：`FileConfigSourceTest` 与 `NacosConfigSourceTest` 里的 `matches("[0-9a-f]{32}#1")`、`endsWith("#2")`。
- `ConfigSnapshot` 的 javadoc：拓扑版本那段改成"声明的数字在载荷里，本字段只表内容身份"（若票 07 已按别的方式落，以 07 为准）。
- **判据用例**：两端加载同一份内容时 `observeRuleVersionLag` **不**计数（现在必然计数）——这是"修好了"的机械证据。

**Status:** resolved

- [x] MXBean 全量属性在场且读数正确（含悬浮量 sum 上界 Σ max(份额×burstSeconds, 1) 的对照）
- [x] `GatekeeperObserver` SPI IT：五类事件都能回调、不接 SPI 时零开销路径成立
- [x] center 9 项指标逐项在场、tag 只有 `rule`、`/metrics` 文本能算出差值（验收口径就靠它）
- [x] `/admin/keys?top=N` 端点 IT：按水位/份额排序正确
- [x] **`rule_version` 只带内容哈希**（去掉 `#seq`）：同一份规则在两端得到的版本**相等**、`RULE_VERSION_MISMATCH` 不再恒增（见上节）
- [x] SDK 仍零观测依赖（模块依赖守卫）；`mvn test` 全绿

## Answer

**落地点（2026-09-23）**：

| 模块 | 新增/改动 | 装什么 |
|---|---|---|
| `gatekeeper-client`（观测三件套） | **`ClientObservation`**（包内，替代原 `ClientStats`——三者合成一个对象，因为记的是同一批事实）/ **`ClientObservationMXBean`**（public，20 个属性）/ **`GatekeeperObserver`**（public，五类事件 + `DegradeReason`/`EvictCause` 两枚举）/ `BucketTable` 加逐出回调与水位读数 / `LocalBucket.storedMicroTokens()` / `GatekeeperClient` 加 MXBean 注册注销 + `Builder.observer` | 计数器（放行/被拒/首租次数+失败+时延/续租失败/降级/重定向+放弃/**桶表逐出**）+ `acquire` 等待分布（5 格 + 最大值，只在**真等过**的那回记）+ 桶水位 sum/max + 桶表条目数；MXBean 用 `StandardMBean(impl, iface, true)` 注册（**实现类保持包内可见**，不为 JMX 把内部类提上公开面），注册失败只记 WARN |
| `gatekeeper-config` + 两端消费方 | `SnapshotStore` 版本自产 `digest`（`sequence` 与随之冗余的 `loadedDigest` 一起下线）/ `FileConfigSource`、`NacosConfigSource`、`ConfigSnapshot` javadoc / 三条断言改"等于内容 md5" / 新增两条判据用例（两实现各一条：**同一份内容回头再来版本回到同一个值**） | `rule_version` 反转落地（详见票面「结构硬输入」节） |
| `gatekeeper-center-case` | `LeaseMetrics` → **`usecase/metrics/CenterMetrics`**（改名 + 挪包：它管的是 `gatekeeper.lease.*` / `topology.*` / `config.*` 三族，叫 LeaseMetrics 名实不符）；加 `lease()` 整条计时 + 400 计数 + 归还回收计数 | 九项清单：既有 5 项原样 + 新增在册条目/实例 gauge、回收（cause=sweep/expired/return）、`400` key 超限、配置整份拒收（tag=source）、续租时延 timer |
| `gatekeeper-center-domain` | `ILeaseLedgerService` 新增两条读数 `instanceCount()` / `expiredEvictions()` + `snapshot()`；实现里加两个**观测用派生量**（按 `instance_id` 的引用计数表、读路径逐出累计数） | instance 计数按 `instance_id` 去重（幽灵世代算条目、不算实例）；`snapshot()` 只含**未过期**条目，供 `/admin/keys` 直读 |
| `gatekeeper-center-api` + `trigger` | `IAdminService` + `AdminKeyVO`（api）；`AdminController`（trigger）；`LeaseSweepJob` 记 `cause=sweep` | `GET /admin/keys?top=N`：按 key 聚合**在册份额合计**、降序、并列按 key 升序；`top` 缺省 20、上限 1000 |
| `gatekeeper-center-app` + `infrastructure` | `RuleConfigSources`/`TopologyConfigSources` 的 `file`/`nacos` 收一个 `Function<String,T> decoder` 参数（由装配层传）；`GatekeeperConfig` 用 `countingRejections` 壳包住解码器 | 整份拒收的计数点只能落在装配层（它是唯一同时看得见"哪一份配置"与指标注册表的地方）；app 因此**显式声明** `gatekeeper-center-case` 依赖（此前靠 trigger 传递、没声明就用了） |
| `gatekeeper/measure/` | **`gatekeeper-recording.jfc`**（照 `bs-vt-recording.jfc` 手法） | pin@0ms + 线程统计/生命周期 + **慢 socket（threshold 10ms）** + CPU 采样 |
| 文档 | `gatekeeper/CONTEXT.md` 加 **「回收（reclaim）」** 词条（三格成因，`_Avoid_: 删租约/过期`）；`gatekeeper/CLAUDE.md` §2 依赖图加 `app → case`；spec §5 按反转回填、§8.2 补 `{result=fail}` 的说明 | — |

**验收**：`mvn test` **264 测试全绿**（票 08 收口时 252；本票新增 12 条：config 2 + client 4 + domain 3 + app 3）。两条判据用例按票面要求钉住 `rule_version` 修好了：`FileConfigSourceTest`/`NacosConfigSourceTest` 的「版本只由内容决定」（旧写法 A→B→A 会给出第三个版本）、`ObservabilityEndpointTest.两端同一份规则时版本不错位_只有真的落后才计数`（照中心回的版本回发**零增量**、发假版本恰 +1）。

**§8.6「坏的时候怎么看」逐条核对**（手册正文归终验票）：

| 手册条目 | 可观测支撑 |
|---|---|
| 接入方：放行/被拒计数对照 ⇒ 是否在真拦 | `getAllowedCount` / `getDeniedCount`（MXBean）+ `onAllowed`/`onRejected`（SPI） |
| 接入方：续租失败/降级非零 ⇒ 中心或网络问题 | `getRenewFailureCount` / `getDegradeCount` + `onRenewResult`/`onDegraded` |
| 接入方：首租失败率非零且持续 ⇒ 分片挂掉/中心不可达 | `getFirstLeaseFailureCount` ÷ `getFirstLeaseCount` |
| 接入方：`acquire` 等待分布 ⇒ 放行延迟体验 | `getAcquireWaits*` 五格 + `getAcquireWaitMaxMillis` |
| 接入方：桶表逐出非零 ⇒ key 面超预期 | `getBucketEvictions` + `getBucketCount`（对着 `maxTrackedKeys` 看） |
| 中心：份额发放 vs 客户端放行偏差 ⇒ 幽灵条目/逐出异常 | `gatekeeper_lease_share_granted_total` vs 客户端 `getAllowedCount` |
| 中心：在册条目数不收敛 ⇒ ttl/驱逐异常 | `gatekeeper_lease_ledger_entries` / `_instances`（gauge）+ JFR |
| 中心：421 持续非零 ⇒ 拓扑不同步 | `gatekeeper_topology_redirect_total`（+ 客户端 `getRedirectCount`） |
| 中心：幂等去重命中 ⇒ 重试/重复量 | `gatekeeper_lease_idempotent_hit_total` |
| 中心：规则版本错位 ⇒ 配置传播滞后 | `gatekeeper_config_rule_version_mismatch_total`（**本条修复前恒增、信号失效**） |
| 中心：回收里 ttl 过期偏高 ⇒ 客户端异常退出量 | `gatekeeper_lease_reclaim_total{cause=sweep\|expired}`（`cause=return` 恰是"不是异常"的那一格） |
| 单 key 排查 | 日志采样 + `GET /admin/keys?top=N` |

**实测平台事实 / 踩坑（重跑会再撞的）**：

1. **`@SpringBootTest` 默认关掉指标导出**：`ObservabilityContextCustomizerFactory$DisableObservabilityContextCustomizer` 会把 `management.defaults.metrics.export.enabled` 置 false ⇒ micrometer 退化成 `SimpleMeterRegistry`、**prometheus 端点压根不存在**、`/metrics` 404（生产里是有的，这是测试环境的开关）。要抓真 `/metrics` 文本必须加 `@AutoConfigureObservability`（诊断路径：`context.getBean(MeterRegistry.class)` 打出的是 `SimpleMeterRegistry` 就不是它）。
2. **`LeaseRequest.rule_version` 是必填字段**：漏带会在 **Jackson 反序列化**时就被 400 打回、根本到不了 `LeaseCase`——写"400 key 超限"这类用例时容易把两种 400 混成一种（实测踩到：计数器 delta 恒 0）。
3. **堆不出 2R 份额**：`/admin/keys` 的排序数据不能用"两个实例各报满 R"来造——`minF(gets, capacity − SumHas + old.Has)` 会把第二个实例压成 0，合计仍是 R。要两实例都拿到份额得各报 R/2。
4. **JFR 的 `settings=` 是替换不是叠加**（给了自定义 profile，`default.jfc` 的事件就都不录了），所以 profile 必须自包含。**每条事件都实测过**：让对端故意晚 120ms 写，`jdk.SocketRead` 记下那一次、快的那次不记（threshold 10ms 生效）；`synchronized + LockSupport.parkNanos` 的虚拟线程在 **0ms 阈值**下被 `jdk.VirtualThreadPinned` 记下。

**code-review 采纳项（两轴，`/code-review` since HEAD）**：

- **Spec 轴**：① **`RealNacosConfigSourceTest` 还留着 `endsWith("#2")`**（票面只点了另外两个文件，第三条真 Nacos 用例漏了；本机 Nacos 栈没起、`assumeTrue` 跳过 ⇒ 本地绿得看不出来，栈一起来就红）⇒ 改断言为 `isEqualTo(md5("v2"))`；② `{result=fail}` 在中心侧没有序列 ⇒ **不采纳"补一条恒 0 的序列"**（照它建告警的人会以为在盯一个会动的东西），改为把理由回填 spec §8.2（见下「自行拍板」）。
- **Standards 轴**：③ **`app` 引 `case` 的 `CenterMetrics` 却没声明依赖**（靠 trigger 传递才编得过）⇒ 显式加 `gatekeeper-center-case` 依赖 + `gatekeeper/CLAUDE.md` §2 依赖图同步；④ `AdminController` 两张平行 map + `double[1]`/`int[1]` 当累加器 ⇒ 收成一个 `KeyLoad` 私有累加器（一次遍历、不二次查表）；⑤ `BucketTable.watermarkSum` 那条 `if (stored > 0)` 守卫的注释讲的是**不存在的哨兵**（`Long.MAX_VALUE` 在 `Probe` 里，桶内存量不可能取到）且与 `watermarkMax()` 不对称 ⇒ 删守卫；⑥ `SnapshotStore.loadedDigest` 在去掉 `#seq` 之后**成了快照版本的复制品**（是本票改动新引入的冗余）⇒ 删字段，比 `snapshot.version()`；⑦ `BucketTable` 里 `java.util.Objects` 全限定名 ⇒ 加 import；⑧ `ClientObservation` javadoc 里的类名笔误 `BucketsTable` ⇒ 改。
- **核过但不成立的**：Spec 轴报「421 give-up 不发 `onRenewResult`」——`LeaseRedirectedException extends LeaseTransportException`（见其 javadoc），`Renewer.sendWithRetry` 会接住、重试、仍败则返回 `null` ⇒ `renewSubBatch` 走 `recordRenewFailure`（**它内部就调 `recordRenewResult(false, …)`**），事件照发。

**自行拍板（都可反转）**：

1. **`tag 只允许 rule` 读作"不许 key / instance_id 这类无界 tag"**：spec §8.2 自己要求 `{result=granted|throttled|fail}` 与"按 cause 分"，`result` 这一维票 04 已在场——按字面只留 `rule`，这两项要的区分度就没处放。新增用到的 tag 只有 `cause`（3 值）与 `source`（2 值），都是闭枚举。
2. **回收三格 = `sweep` / `expired` / `return`**：前两格都是"实例不再续租了"（只是发现路径不同：定时扫 vs 有人来问），第三格是好好说了再见。`expired` 的计数不摊到业务代码里（那要给 `listByKey` 加返回值），而是账本内维护一个单调总量、由 `FunctionCounter` 读走。
3. **`/admin/keys` 的聚合落在 trigger**：它不跨域编排（只读一个域的一张表），而 `case` **看不见 `center-api` 的 `AdminKeyVO`**（依赖图 `case → domain, types`）——做成用例类只多一层空壳。输出形状 `{key, share(Σ在册份额), instances}`；排序 share 降序、并列按 key 升序（并列次序也必须确定）；`top` 缺省 20、上限 1000（人工排查接口，不该被当全量导出用）。⚠️ 端点**无鉴权**（spec 的 Out of scope：v1 部署在内网）。
4. **`{result=fail}` 中心侧不注册**（理由见上）：中心侧的首租只有 `granted`/`throttled` 两支，`fail` 的读数是客户端的首租失败率。spec §8.2 已回填这句话。
5. **MXBean 用 `StandardMBean` 显式声明接口**：走"类名 + MXBean"命名约定就得把实现类设为 public，而 `gatekeeper/CLAUDE.md` §1 要求 client 侧"包内平铺 + package-private 作默认纪律"——宁可多一行注册代码，也不为 JMX 把内部类提上公开 API。
6. **"逐出计数"只数 LRU**：`unmatched` 与手工 `remove` 只发 SPI 事件、不进计数——混进去会让 spec §8.6 的"逐出计数非零 ⇒ 该调大桶表上限"失效（规则删一次就能顶起来）。
7. **首租延迟只在真的走通道时计**：纯本地模式（没配中心）命中规则 ⇒ 拒是**配置形态**，不是"中心不可达"，不该进首租失败率。
8. **旧的 5 项指标保持懒注册**（不预注册）：预注册会让"没发生过就是 0"变成"序列一直在"，且要改动票 04 已有的断言；本票**新增**的几项（gauge / 回收三 cause / 400 / 配置拒收）按既有「占位指标先注册出来」的先例预注册为 0。

**已知波及 / 不在本票范围**：

- **JFR 的"挂"是部署参数**：本票交付的是 profile（票面括号里那句就是它）+ jfc 头部可直接复制的启动行；本仓 `gatekeeper/` 还没有任何 run 脚本，`measure/` 的脚本与 README（spec §8.6 的资产条）归**票 10**（它的验收项里就有那条 README）。
- **`{result=fail}`** 见上（spec 已回填）；**回收 `cause=sweep`** 只在真有回收时非零（没回收不记空账）。
- **`LeaseCase.release` 复用 `recordTooManyKeys`**：归还超订阅上限也回 400，与租赁路径同一个计数（都是"契约违约"那一格），没按路径 TAG 分。
- `gatekeeper-center-case` 的 `CenterMetrics` 在**测试上下文里拿的是 SimpleMeterRegistry**（见踩坑 1），所以 case 层单测断言的是 `registry.find(...)` 而不是 `/metrics` 文本；文本口径由 app 层的端点用例钉。
