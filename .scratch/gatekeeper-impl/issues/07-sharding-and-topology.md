# 07: 分片与拓扑感知（421 redirect + 客户端真拆批）

**What to build:** spec §4 的固定槽位分片落地，多 center 实例跑通。端到端行为：**两个 center 分片各管一段槽位，客户端把散在 S 个分片的 key 拆成 ≤ S 个子批并发续租；发错分片被 `421` 重定向一跳到权威；杀掉一个分片，该部分 key 的存量 pod 按 fail-open 跑、新起 pod 首租失败 ⇒ 拒**。

机制逐条照 spec §4：

- **拓扑源**：`gatekeeper-topology.yml`（槽→实例映射表 + `topo_version` 单调递增数字，redirect 跟随判据要比较序）；映射表 = 自动均分生成 + 手工覆盖；槽位数固定 **M = 1024**、`slot = murmur3(key) % M`，分片数 = 中心实例数 S 动态；**否掉 `slot % 实例数` 机械派生**。客户端与中心直连同一配置源。
- **中心侧权威校验**：收到请求先算 `slot(key)` 是否归自己，不是就**绝不接**，回 `421 + {authority, topo_version}`——不能容忍双发（票 04 撤销在飞上限后「两个分片都以为自己是权威」没有任何机制兜）。
- **客户端跟随判据**：目标 `topo_version` ≥ 自己的才跟、同一 key 一轮最多一跳，第二次直接放弃本轮（fail-open）+ 后台强制刷拓扑——封死 `A→B→A` 环。
- **客户端真拆批**：一个 pod 的 key 散在 S 个分片 ⇒ 一次续租拆 ≤ S 个子请求并发发出、每子批一个幂等键（替换票 05 的单分片形状）；**否掉中心间转发**。
- **单槽无副本、不重路由、不做接管**：分片挂掉时存量 pod 按最后份额 fail-open、新起 pod 首租失败 ⇒ 拒；实例回来按冷启动语义 ≤ 1 个 `refresh_interval` 收敛。
- `421` 重定向计数进指标（票 09 归拢，本票保证计数器在场；持续非零 = 拓扑不同步）。

**Blocked by:** 05（续租通道与拆批骨架就位）

**Status:** resolved

- [x] 槽位/拓扑单测：murmur3 % 1024、自动均分 + 手工覆盖、`topo_version` 单调性
- [x] `421` redirect IT：发错分片 ⇒ 421 带权威信息 ⇒ 客户端一跳命中；`topo_version` 更低不跟、第二轮不跳、后台刷拓扑
- [x] 真拆批 IT：双分片下一次续租拆 ≤ 2 子批并发、各带幂等键，中心各自记账正确
- [x] 故障 IT：双 center 杀一个 ⇒ 存量 fail-open、新 pod 首租拒、实例回来 ≤ 1 个 `refresh_interval` 收敛
- [x] `mvn test` 全绿

## Answer

**落地点**：

| 模块 | 新增/改动 | 装什么 |
|---|---|---|
| `gatekeeper-protocol` | **`SlotHash`** / **`Topology`** / **`LeaseRedirect`**；`LeaseRequest` / `LeaseResponse` 补 `topo_version` | murmur3_32（x86 变体、seed 0）逐字实现 + `slot = hash & 1023`（2 的幂 ⇒ 与 floorMod 等价）；拓扑 = 自动均分（**连续区段** `instances[k·M/S, (k+1)·M/S)`，非 `slot % S`）+ 单槽覆盖；421 body（**归 protocol 不归 center-api**，见拍板③）；`topo_version` 按 §6.4「只增可选字段」上线（缺字段 = 0，旧请求照旧解析，有专测） |
| `gatekeeper-config` | **`TopologyParser`**；`ConfigSource` 接口加 **`refresh()`**（两实现各自落实） | 拓扑 yml 解码（`topo_version` ≥ 1 / instances 非空且绝对 URI / 覆盖槽位与地址校验，all-or-nothing，与 `RuleSetParser` 同一份纪律）；`refresh()` = 「立即重查一次外部」的口子——spec §4「后台强制刷拓扑」的最小必要形状（file = 同步做一轮指纹检查、nacos = 后台虚拟线程重读） |
| `gatekeeper-center-domain` | `domain.topology`：`RedirectVO` + `ITopologyService` / **`TopologyServiceImpl`** | 421 判据 = 纯查表（`slot(key)` 的权威是不是本实例）+ volatile 整体替换的热更新；**self 必须在 `instances` 名册里**（构造期校验，全部槽被覆盖挪走是合法排水态、人要在名册上） |
| `gatekeeper-center-types` / `-case` / `-trigger` | `NotAuthorityException`；`LeaseCase` 权威校验；`421` handler | 校验排在**幂等去重与 400 之前**（被拒请求不进本中心任何账；见拍板⑦）；`lease` 与 `release` 都校验；响应带 `topo_version`；`LeaseMetrics.REDIRECT` 从占位变真计数（票 03 预注册的 0 从此有人推） |
| `center-infrastructure` / `-app` | `TopologyConfigSources`（file/nacos + fail fast）、`TopologyProperties`（`gatekeeper.topology.*`，`self` 必填）、`GatekeeperConfig` 装配（首份拓扑喂判据 + 监听器接热更新） | 拓扑源与规则源同一套抽象、**各自独立**的配置对象（spec §5）；没有拓扑 421 无从判起 ⇒ 中心同样 fail fast |
| `gatekeeper-client` | **`ClientTopology`**（路由）/ **`RedirectFollower`**（跟随判据唯一实现）/ `LeaseRedirectedException`；`Renewer` / `FirstLeaseBatcher` 真拆批并发；`LeaseChannel` 归还按分片；`Builder.topologySource`；`ClientStats` 加 `redirect` / `redirectGiveup` 两个计数 | 没配拓扑源 = **单分片合成拓扑**（版本 0、全槽指向 `center`，票 05 形状不破坏）；route 把「地址串 → URI」归一化收在一处；子批 = 按权威分组再按 500 细分，多片时虚拟线程并发发出；421 计数器在场（票 09 归拢） |

**验收**：`mvn test` **232 测试全绿**（票 06 收口时 196；本票新增 36 条：protocol 9 / config 6 / client 6 / center-domain 5 / case 5 / infra 3 / app 3，另有既有用例的 constructor 与断言口径修订）。测试布局：槽位/拓扑单测（SlotHash 钉 mmh3 参考实现向量 + 分布、Topology 钉「连续区段不是 slot 取模」）；`421` redirect IT 在 `ShardTopologyTest`（真 HTTP 双 StubCenter + 真文件拓扑源：一跳命中且同一幂等键、v3 < v5 不跟、A→B→A 第二次放弃、**等号也跟**钉 `≥` 边界、刷拓扑后直达）；真拆批 IT（挂住 A 的应答看 B 的子批到达 ⇒ 并发而非串行）；故障 IT（杀 B ⇒ 存量 fail-open、新 pod 首租拒、同端口重启 ≤ 一轮收敛）；端点级 `TopologyRedirectEndpointTest` 用**真 Spring + 双分片测试拓扑**补上中心侧 421 的真装配面（lease/return 都测）。

**实测平台事实（本票的几条）**：

1. **子批并发发出后，「计数先到、落账后到」**：`StubCenter` 在 dispatch 开头就计数、读完 body 才记请求——旧串行代码「`leaseCallCount() ≥ 2` 蕴含两条都已落账」的隐含先后在并发路径上不再成立，两条按计数取 `leaseRequests().get(N)` 的既有断言偶发 `IndexOutOfBounds`。断言一律改锚 `leaseRequests().size()`。
2. **同尺寸极快改写 + `refresh()` 也会撞上 mtime 粒度**：file 源的指纹口径（mtime+size）下 refresh 只是「立即做一轮检查」，指纹没动就什么也不发生——测试里显式 `touch` 顶开 mtime。这与该实现 javadoc 已记的已知限制同一条，不是 refresh 的缺陷。

**自行拍板的空白（都可反转）**：

1. **「一轮最多一跳」的口径 = 一次发送尝试**：首跳跟随的第二次 421 ⇒ 放弃该子批；spec §6.3 允许的那次重试算**新的一次尝试**、判据照常（可再跟一跳）。环封死的保证来自**版本比序**（`A→B→A` 第二个 421 因目标版本不新于自己而跟不动）＋中心「绝不接」，不靠跨重试的跳数记账。code-review Spec 轴指出这与票面「第二轮不跳」的字面有差，此处留档拍板。
2. **中心对拓扑同样 fail fast + `self` 必填**：没有拓扑权威校验无从判起，半个分片服务上线比不上线更糟；`gatekeeper.topology.self` 启动期校验「在 `instances` 名册里」。
3. **`LeaseRedirect`（421 body）归 `protocol` 而非 `center-api`**：ADR-0007「非 2xx body 归 center-api」的默认只对**客户端不解析**的错误体成立（400/5xx 一律 fail-open 不看体）；421 是客户端要跟随的那一条，按「protocol 里每个类型都跨边界」的准入标准必须落在契约里。
4. **`ConfigSource.refresh()` 进共享接口**（动了票 02/06 的契约面）：「后台强制刷拓扑」需要一个「现在就去查」的口子，等轮询太慢、推送没来就没辙；两实现各自最小落实，map 已记。
5. **客户端没配拓扑源 = 单分片合成拓扑**（版本 0、全槽指向 `Builder#center`）：421 照常一跳跟随（版本 0 < 任何真实版本），持续重定向由中心/客户端两侧计数暴露为「拓扑不同步」（spec §8.6 的读法）——单分片部署与 demo（票 08）不必被逼着配拓扑。
6. **归还路径的 421 无条件跟一跳**：spec §6.1 逐字「跟随一跳即可，跟不动就弃——条目由 ttl 到期兜底」；归还没有「下一轮」语义，版本比序在这里买不到什么，最坏 = 条目晚 ttl 消失（方向安全）。
7. **权威校验排在幂等去重与 400 之前**：421 的含义是「这请求不该到我这里」（不进任何账），400 是「违约但确实到我这里了」；路由是第一道防线，被拒请求连幂等表都不进（有专测钉住）。

**code-review 采纳项（两轴）**：

- **Standards 轴**：① 421 跟随逻辑在 `Renewer.attempt` / `FirstLeaseBatcher.attempt` 逐行同构（约 20 行 × 2）⇒ 抽 **`RedirectFollower`**（判据唯一实现，与 `LeaseApplier` 同一条「写两遍必然分叉」的教训；transport 里归还那一跳是有意的第三种形状——无条件跟、无判据，不算重复）；② 权威地址的「trim + `URI.create`」散在 5 处（Primitive Obsession）⇒ 收进 `ClientTopology.route`（直接返回 `Map<URI, List<String>>`）；③ `FirstLeaseBatcher` 绕开 `route` 直摸 `current().authorityOf`（Message Chains + 与续租走法不一）⇒ 统一走共享 `route`；④ `Renewer` 内联 `java.util.HashMap` 全限定名 ⇒ 改 import。
- **Spec 轴**：① 验收 1 的「`topo_version` 单调性」单测缺席 ⇒ 补「目标版本**等于**自己也跟」用例钉住跟随判据是 `≥` 的比序；**单调性本身是部署纪律**（文件里显式声明的数字、无机制可验），机制上的防线 = 比序判据 + 中心权威校验，两道都有测；② 验收 4 的「双 center」在 IT 里是**真 HTTP 假中心**（`StubCenter` × 2）⇒ 结构性原因留档：client 模块 enforcer 禁依赖 center，两半不可能同进程；真 center 侧的 421 面由 `TopologyRedirectEndpointTest`（真 Spring + 双分片拓扑）补上，「杀分片三态」本就是客户端侧行为（票 05 的 IT 同一分工）；③「≤ 1 个 `refresh_interval` 收敛」的精确标定归票 10 harness（与首租 50ms 同一条口径，IT 里 5s 窗口只证收敛不证上界）。
- **判为 judgement call 不采纳**：`TopologyConfigSources` / `TopologyProperties` 与规则侧的平行同构不抽成泛型工厂——两个配置对象各有自己的部署面与 dataId 缺省，参数化省不了几行、还把「两个独立配置对象」这条 spec §5 的形状糊掉了。

**已知波及 / 不在本票范围**：

- 真拆批的并发断言在 StubCenter 上做（挂住一个分片看另一个到达）；**两个真进程的两端同跑归票 10** 的 harness（无模块能同时依赖 center 与 client，票 06 已立的边界）。
- 拓扑源的 **Nacos 分支没有真 Nacos 用例**（票 06 的三支只盖规则源）；机制与规则源同一套 `SnapshotStore` / `ConfigSubscription`，风险低，票 11 常态化回归可补。
- `rule_version` 那条跨票缺陷（票 06 Answer 末节）与本票无关、落点票 09，不受本票影响；本票给 `topo_version` 用的就是「显式声明数字」而非自产序号——两条版本语义在 spec §5 就是分开的。
- `ConfigSource.refresh()` 对 file 源「同内容 = 空操作」：后台强制刷在「文件没变但中心版本更新」的场景（如发布方漏了 bump）不会凭空造出新拓扑——这是指纹口径的诚实行为，误配由持续 421 计数暴露。
