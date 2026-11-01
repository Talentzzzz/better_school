# 03: 中心骨架与租约端点（均分发放起步）

**What to build:** spec §2/§3.5/§6 的 center 单分片骨架，先让「客户端 ↔ 中心」这条链路通起来。新建 `gatekeeper-center` 模块（Spring Boot 3.3.5，Java 21）。端到端行为：**center 起来后，`POST /v1/lease` 能为首租与续租返回 per-key 份额（本票先均分发放，票 04 换真算法）、`POST /v1/lease/return` 能即删条目；不匹配任何规则的 key 拿到 `unmatched`**。

机制逐条照 spec §6 / §3.5：

- **两端点**：`/v1/lease` 兼做首租与续租（首租 = 账本里没有 `(key, instance_id, generation)` 记录的续租）；`/v1/lease/return` 批量、天然幂等、不加幂等键、收到即删条目并立即重算份额。
- **账本**：每 `(key, instance_id, generation)` 一条租约记录（分配速率、到期时刻、最近上报用量）；**单一真相源 = 租约表本身，不养派生计数器**；不落盘，重启清空后第一轮按均分保守发放 + 短 `ttl_ms`。
- **ttl**：默认 = 2 × `refresh_interval`、每次成功续租重置；冷 key（ttl 内无续租）整条逐出；支持下发 `ttl_ms = 0`（主动降级信号，重启预告用，drain 机制本票只需支持字段、预告流程归终验走查）。
- **三值状态**：本票落 `granted`（`share = 0` 合法、无 retry-after）与 `unmatched`（客户端据此逐出 + 放行）；`throttled` 归票 04。
- **中心侧规则加载**：复用票 02 的规则模型 + 文件配置源（客户端与中心直连同一配置源、不从中心拉）；规则版本 = 内容哈希只做相等比较，请求/响应带 `rule_version`。
- **协议形状**：snake_case、`share` double、`refresh_interval_ms` 仅观测、消费方义务忽略未知字段、路径大版本 `/v1/`。
- **请求级错误**：超每请求 key 数上限 500（可配）⇒ `400`；`503`/5xx 语义留给客户端 fail-open（票 05 消费）。
- 中心侧 `/metrics` **基础计数随手带**（份额发放合计、首租结果计数——完整清单归票 09）。

**Blocked by:** 02（规则模型与配置源，center 判 `unmatched` 和按规则 rate 均分都靠它）

## 结构硬输入（2026-09-22 起，[ADR-0007](../../docs/adr/0007-gatekeeper-layering-and-protocol-module.md)）

- **模块骨架已建，本票是「填代码」不是「建模块」**：`gatekeeper-center` 已是**内层聚合 pom**，7 个子模块 `gatekeeper-center-{types,api,domain,case,infrastructure,trigger,app}` 全部就位（依赖方向 + enforcer 规则已挂）。**不要新建模块、不要改依赖方向**。
- **代码落位照 [`gatekeeper/CLAUDE.md`](../../gatekeeper/CLAUDE.md) §2**：两端点在 `center-trigger` 的 `http/LeaseController`（实现 `center-api` 的 `ILeaseService`）；账本在 `center-domain` 的 `domain.lease`；本票的「均分发放」是 `domain.allocation` 的**占位实现**（票 04 换真算法）；`Application` 放 `center-app` 根包 `io.gatekeeper.center`。
  ⚠️ `center-app` 本票要挂 `spring-boot-maven-plugin`（repackage）——骨架期**故意没挂**（还没有 main 类，挂了 `package` 会失败）。
- **⚠️ `/v1/lease` 的请求/响应对象归 `gatekeeper-protocol`，不归 `gatekeeper-center-api`**：`LeaseRequest` / `LeaseResponse` / `LeaseResult` / `Usage` / `LeaseStatus` / `ReturnRequest` 客户端要构造与解析，落在 center 侧会让**接入方依赖闭包含 center**（spec §2 写死的规则）。`center-api` 只装客户端不需要的那一半：`/admin/*`、中心非 2xx body（`400` key 超限等）。
- **中心无持久化** ⇒ 不出现 `Repository` / `Dao` / `PO` / `Aggregate`（`gatekeeper/CLAUDE.md` 的不适用清单）。
- **配置源实现取自 `gatekeeper-config`**（2026-09-22 新增的共享件）：本票「复用票 02 的规则模型 + 文件配置源」里的**文件配置源实现就在那儿**，由 `center-infrastructure` 依赖它（不是从 client 拿——center 依赖 client 被 enforcer 禁止）。`center-infrastructure` 的 pom 已加该依赖。
- 「接入方闭包不含 center」的守卫**已落地并验证过**（`client`/`protocol` 的 `bannedDependencies` 禁 `io.gatekeeper:gatekeeper-center-*`，通配符匹配已实测生效）——本票只需在验收里核验它仍然通过。

**Status:** resolved

- [x] 单分片 center 可启动；curl 走通首租 → 续租（带 usage 三字段）→ return 三步，账本条目随之建/延/删
- [x] `unmatched` 路径：key 无规则时响应三值状态正确；`ttl` 到期冷驱逐有单测
- [x] 中心重启后空账本按均分保守发放 + 短 `ttl_ms`（单测）
- [x] 协议契约测试：snake_case、未知字段忽略、`400` key 超限
- [x] `mvn test` 全绿；center 引 `micrometer-registry-prometheus`、接入方依赖闭包不含 center（模块依赖方向有守卫）

## Answer

**落地要点**（2026-09-22；`mvn test` **120 个测试全绿**：protocol 24 + config 24 + client 39 + center-domain 14 + center-usecase 15 + center-app 4；12 模块 reactor 全 SUCCESS）

- **`protocol` 落 7 个线上类型**：`LeaseRequest` / `LeaseKeyUsage` / `Usage` / `LeaseResponse` / `LeaseResult` / `LeaseStatus` / `ReturnRequest`。字段名由 `@JsonProperty` **逐字钉成 snake_case**、`@JsonIgnoreProperties(ignoreUnknown = true)` 落在类型上——**不配全局 ObjectMapper**（全局命名策略会把 center-api 的非协议 body 也改掉，且"两端各配一遍"正是 ADR-0007 警告的漂移点）。pom 只加 **jackson-annotations**（零传递依赖），jackson-databind 只进测试：契约单测用**裸 `ObjectMapper`** 断言线上形状（注解自己扛住默认配置），含往返、未知字段、三态字段缺省。`LeaseStatus` 自带 wire 值（`@JsonValue`/`@JsonCreator`），指标 tag / 日志 / 序列化共用一处。`LeaseResult` 把"三态与字段"绑成**构造期不变量**：`granted` 必须带 `share` + `ttl_ms`（`share = 0` 合法）、另两态必须都不带——`unmatched` 带 `share` 会被读成"有额度"，在协议层就不成立。
- **⚠️ `case` 是 Java 关键字、包名用不了**：模块仍叫 `gatekeeper-center-case`，**包名落 `io.gatekeeper.center.usecase`**（与 backend 的 `bs-case` → `com.betterschool.usecase.*` 同一处理）。已写进 `gatekeeper/CLAUDE.md` §2「根包与组件扫描」与 case pom。
- **domain**：账本 = **两层 `ConcurrentHashMap`**（外 key / 内三元组）——分配算法按 key 跑 O(n_k)，摊成一层就得每次扫全表（满配 200 万条）。续租走 `outer.compute(key, …)`，让"写条目"与"空桶清理"互斥（否则条目可能写进一个刚被 sweep 摘走的桶里、从此不可见）；访问该 key 时就地逐出到期条目 + `LeaseSweepJob` 每 `refresh_interval` 全量扫一遍（沉默下去的 key 也得把内存还回来）；**空桶不留**（key 面可以比规则集大）。`LeaseEntity` 的可变字段 `volatile`（票 04 的分配要读**别人**的份额与用量）。均分占位分配 + `ShareVO` + 中心侧墙钟 `Clock`（测试可拨）。
- **usecase**：key 上限校验 → 读规则快照（**每次请求取一次原子快照**，热更新立即生效且响应里的版本与本次判定同源）→ 逐 key 匹配（`unmatched` **不建条目**）→ 均分 → 落地租约 → 组装响应。**冷 key（该 key 账本为空）发 `1×refresh_interval` 的短 ttl、热 key 发 `2×`**——重启/刚被逐干净的那一轮均分出的份额可能偏大，用短有效期封住。
- **trigger**：`LeaseController` 实现 `ILeaseService`（**零业务分支**，`400` 走 `@ExceptionHandler`）+ `LeaseSweepJob`；`spring-boot-starter-web` / `actuator` / `micrometer-registry-prometheus` 三个依赖挂 trigger——`/metrics` 是对外端点、与两个租约端点同层；`management` 用 `base-path: /` + `path-mapping.prometheus: metrics` 落在 spec §8.1 写的那条路径上。**app**：`Application`（`@EnableScheduling`）+ `config/`（配置源 bean `destroyMethod="close"`、`Clock`）；三个旋钮在 `application.yml` 里只写成注释（**默认值唯一出处是 `CenterConstants`**）。**infrastructure**：`RuleConfigSources.file(path)` = 复用票 02 的 `FileConfigSource` + **中心 fail fast**——"配置源只报告事实、行为归消费方"这条 spec §5 分裂的落点。
- **实测读数**（`mvn package` 出 jar、真起单分片 center、curl；`gatekeeper-rules.yml` 里 orders 规则 `rate=20.0`）：

  | 步 | 请求 | 响应 / 读数 |
  |---|---|---|
  | ① 首租 | `api:/orders/create` + `web:/home`（各带 usage） | `granted share=20.0 ttl_ms=5000`（冷 key 短 ttl）+ `unmatched`（无 share/ttl） |
  | ② 续租 | 同 key，带 usage 三字段 | `granted share=20.0 ttl_ms=10000`（2×，条目被就地延长） |
  | ③ 归还 | `POST /v1/lease/return` | `HTTP 200` |
  | ④ 归还后再租 | 同 key | `granted ttl_ms=5000` ← **条目确实被删了**（又回到冷 key 口径） |
  | ⑤ `/metrics` | 抓文本 | `gatekeeper_lease_share_granted_total{rule="orders"} 60.0`、`gatekeeper_lease_first_result_total{result="granted"} 2.0`（首租两轮、中间的续租不算首租）、`gatekeeper_config_rule_version_mismatch_total 4.0`（curl 里写的是 `v1`、与中心的内容哈希不同） |

  另核：规则文件缺失 / `rate ≤ 0` 时**中心拒绝启动**（`APPLICATION_FAILED`，栈底是 `中心启动失败：规则配置不可用（spec §5：中心 fail fast…）`）；SDK 闭包 `mvn dependency:tree -pl gatekeeper-client` = protocol (+jackson-annotations) + config (+snakeyaml)，**不含 center**（enforcer 也独立拦着）。
- **指标**（票 09 清单里本票随手带的三项）：`gatekeeper.lease.share.granted{rule}`、`gatekeeper.lease.first.result{result}`、`gatekeeper.config.rule.version.mismatch`；其余 6 项（在册条目数 gauge、冷驱逐/ttl/return 回收、`421`、幂等去重命中、`400` 计数、续租时延 timer）归票 09。

**code-review 采纳项**（两轴，`/code-review` since HEAD）：

- **Spec 轴（真货）**：① **均分占位实现并不保证 spec §3.3 的「Σ 生效份额 ≤ R」**——我原先的 javadoc 和一条单测都声称它成立，**是错的**：新实例加入时旧实例停在旧份额上、新实例按更大的分母又领一份，**同时存在的份额之和可以到 ≈ `R·H_N`**，要各自续租一轮才收敛（spec §3.4「收敛 = 1 个续租周期」讲的正是这个窗口；spec §3.3 那条硬不变量的全部是 `minF` 的 `capacity − SumHas() + old.Has`，属票 04）。⇒ 删掉假断言、把限定写进 `IAllocationService`/`AllocationServiceImpl` javadoc，并**留一条对照用例**（`占位实现不保证同时在册的份额之和不超过R`）给票 04 翻过来。② **`/v1/lease/return` 原来完全没有上限**——归还带的是实例全部在册 key，正是 spec §4 点名的"一批塞 10 万个 key 能打爆中心"那个形状 ⇒ 用**每实例订阅上限**（10k，spec §4③ / §8.4 的同一个数）卡住归还请求：既不留任意大的请求体，也不逼客户端把一次关停拆成多批（票 05 的关停是"发一次"）。③ **规则版本错位原来只回显、中心侧没有任何观测**（spec §5 要"只做相等比较"、§8.2 要计数）⇒ 请求 `rule_version` ≠ 中心版本时**计数 + DEBUG 日志、仍照常发放**。④ 票面"curl 冒烟"原来只在会话里 ⇒ **读数抄进本 Answer**（上表）。
- **Standards 轴**：① 测试里的 `501` / `MAX_KEYS_PER_REQUEST = 500` 与 `CenterConstants`"默认值只有一处"自相矛盾 ⇒ 改引用常数。② `LeaseMetrics.recordFirstLease` 的 javadoc 把 tag 写成 `granted|throttled|fail`，而参数是 `LeaseStatus`（给不出 `fail`）⇒ 改成与实现相符（中心侧只会有 `granted`/`throttled`，`fail` 是错误路径、归票 05 的客户端侧首租失败率）。其余几条判为 judgement call，见下。

**人工拍板 / 顺带写进规范的两处**：

1. **不带 `topo_version`**（请求与响应都不带）：单分片中心还没有拓扑源（票 07 的 `gatekeeper-topology.yml`），填 `0` 或回显请求值都是假数据；spec §6.4「只增可选字段」允许票 07 再补。⚠️ 票 07 落地时按**可选字段**加，不要改成必填。
2. **`case` 包名 = `usecase`**（Java 关键字），已写进 `gatekeeper/CLAUDE.md` §2。

**反驳 / 留档的 judgement call**（Standards 轴提出、本人不采纳或只说清理由）：

- `infrastructure/adapter/config/` 而非 `adapter/port/`：`gatekeeper/CLAUDE.md` §2 说配置源适配器照 `§3` 的 `adapter/port` 形状，但 backend 的 `port` 特指"实现 domain 的 `IXxxPort`"；这里实现的是 protocol 的 `ConfigSource`，再包一层纯转发的端口就是票 02 code-review 刚以 Middle Man 砍掉的那种东西。
- `ErrorBody` 是 `{code, info}` 而不是 backend §5 的 `Response<T>{code, info, data}`：这是给日志和人看的**传输层**错误体，客户端根本不解它（spec §6.3：4xx/5xx 一律 fail-open），套业务 Response 信封是形状模仿。
- **protocol 里不抽公共校验 helper**：`LeaseRequest` / `ReturnRequest` 的 `requireText` 确有重复，但 `gatekeeper/CLAUDE.md` §3.2 的准入标准是"protocol 里每个类型都**跨边界**"（可判定），package-private 工具类不跨边界 ⇒ 宁可留重复。
- `(instanceId, generation)` 这对参数在 `ReturnRequest` / `removeAll` 之间重复出现：`ReturnRequest` 的形状由协议定死，而 `removeAll(instanceId, generation, keys)` 让"三元组怎么拼"只留在账本里一处；摊成 `LeaseIdVO` 集合反而把拼装责任推给调用方，故不改。

**已知波及 / 不在本票范围**：

- **首租三分法的第三格**（曾有租约后断租 ⇒ fail-open/fail-closed）与真首租通道（合批 10ms、50ms 预算、`throttled`/`unmatched` 的消费）归**票 05**；真算法（PROPORTIONAL_SHARE、`minF` 含 `+ old.Has`）与 `min_renew_interval`、幂等去重归**票 04**。
- **`idempotency_key` 本票只解析、不使用**：本票没有"重复到达会重复记账"的路径——用量存的是**最近一次上报值**而不是累加，所以重试无害；去重表（保留期 + 10 万条上限）归票 04。
- 分片 / `421` / 拓扑归**票 07**；`/admin/keys` 与其余 6 项指标归**票 09**。
- **重启预告 `ttl_ms = 0` 只落字段**（票面明说本票只需支持字段），预告流程归终验走查。
- **"重启后均分保守发放"里的"保守"落在短 ttl 上**，不是一个更保守的实例数估计——中心此刻只知道"已经上报过的"实例数，均分出来的份额可能偏大，用 ≤ 1 个续租周期把它封住（单测 `中心重启后空账本保守发放_靠短有效期收敛` 钉着）。
- 未跑 backend 全量测试：只动了 `gatekeeper/`。
