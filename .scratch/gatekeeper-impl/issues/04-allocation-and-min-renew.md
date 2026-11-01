# 04: 分配算法与过频判（PROPORTIONAL_SHARE + min_renew_interval + 幂等去重）

**What to build:** spec §3.4 / §6.3 的中心分配算法落地，替换票 03 的均分发放。端到端行为：**多实例上报真实需求后，中心按比例分配份额、Σ 生效份额 ≤ R 全程成立；重复/过频续租不会打爆分配或重复记账**。这是全设计正确性的心脏，两处纪律必须逐字写对。

机制逐条照 spec §3.4 / §6.3：

- **PROPORTIONAL_SHARE（O(n)）**：先保底均分，未用满的余额按需求比例分给超额者（`gets = equalSharePerClient + (r.Wants − equalSharePerClient) × (extraCapacity / extraNeed)`），配两条纪律：
  - `minF(gets, unusedCapacity)`，且 **`unusedCapacity = capacity − SumHas() + old.Has`——逐字，`+ old.Has` 是 Σ租约 ≤ R 硬不变量成立的全部，unused 必须排除请求者自身**（Doorman `algorithm.go:239`），漏写则续租会把已有份额清零；
  - **服务端以自己为准**：客户端自报 `has` 参与计算但只记日志（Doorman `client %v is confused` 一条的形状）。
- **需求换算**：上报是计数（`time_elapsed_ms` / `allowed` / `denied`），中心除以经过时间换算成速率再进公式；`wants` 字段 = 配置基线（应用自声明），分配取「实测需求」与「配置基线」的**较大值**。
- **`min_renew_interval`（默认 5s）**：按 `(key, instance_id, generation)` 逐 key 判。**续租命中 ⇒ 不重算份额 + 用量照记 + 返回现有租约状态**（不返空响应——会被客户端误读成超时）；**首租命中 ⇒ `throttled`**（三值状态第三态在此落地，绝不能被读成 share=0 或 unmatched）。
- **中心侧幂等去重**：条目 = `idempotency_key → 时间戳`，保留期 = 一个 `refresh_interval` + 硬上限 10 万条逐出最旧；**不参与分配决策**（请求级缓存）；重复到达 ⇒ 跳过用量增量记账、其余照常走（重算份额 + 返回当前状态）；收到请求即插表、不区分处理成败（漏记方向 = 少发，安全侧）。
- 中心 `/metrics` 补：`421` 重定向计数占位、幂等去重命中计数（完整清单归票 09）。

**Blocked by:** 03（端点与账本骨架）

**Status:** resolved

- [x] 算法单测：Σ 生效份额 ≤ R 在续租/新增/离开场景全程成立（含 `+ old.Has` 缺失即失败的对照用例）；份额翻转后 1 轮收敛到需求比例、无震荡
- [x] 配置基线单测：`wants` 声明后闲置实例不被清零（分配取实测与基线较大值）
- [x] `min_renew_interval` 单测：续租命中回现有状态 + 用量照记；首租命中回 `throttled`；两态与 `unmatched`/share=0 可区分
- [x] 幂等去重单测：同键重放跳过用量增量、保留期与 10 万上限逐出、表不参与分配决策
- [x] `mvn test` 全绿；票 03 的均分路径被完全替换（不留第二套分配）

## Answer

**落地要点**（2026-09-22；`mvn test` **151 个测试全绿**：protocol 25 + config 24 + client 39 + center-domain 33 + center-usecase 25 + center-app 5；12 模块 reactor 全 SUCCESS）

- **算法（`domain.allocation`）**：`AllocationServiceImpl` 换成 Doorman `algorithm.go` 逐字语义——`equalShare = R/count`、比例支 `equalShare + (wants−equalShare)×(extraCapacity/extraNeed)`、两条纪律 `minF(gets, unusedCapacity)` 与 **`unusedCapacity = capacity − SumHas() + old.Has`**（注释里逐字标了出处）。输入是纯数字（`capacity` + `requester`/`currentWants` + `others`），**不碰账本**——"Σ 份额 ≤ R"成了这个纯函数的输出性质，可以纸面单验：`gets_i ≤ unused_i = R − Σ_{j≠i} has_j` ⇒ **每写一次就复位一次**（单测连"从 1.75R 的超发状态起步"也钉住）。新增两个值对象：`ClientStateVO`（别人的 `has`/`wants` 可见面）、`DemandVO`（计数 ÷ 经过时间 → 速率；与配置基线取大；`time_elapsed_ms = 0` 不出 NaN）。
- **账本（`domain.lease`）**：`LeaseEntity` 改存 `{share, wants, lastRenewMillis, expiresAtMillis}`——原始 RLQS 计数器**不落账本**（进算法前就换算成需求速率，三个 `long` 换一个 `double`，照 [research/12](../gatekeeper/research/12-rate-lease-bounds.md) §5.1 的内存账）。⚠️ **spec §3.5 的那句"最近上报用量"已回填**为"最近上报换算出的需求"（两处小改，见下）。
- **过频判（新 `domain.lease.service.throttle`）**：锚点 = `(key, instance_id, generation) → 上次**被处理**的请求时刻`。**只记被处理的、不记被忽略的**——否则一个 `refresh_interval` 恰好贴着窗口的客户端会每轮都撞窗、租约永远续不上（单测钉住）。命中两支：有租约 ⇒ 不重算份额 + 需求照记 + 回**剩余** ttl（传剩余 ttl 续租 = 到期时刻原地不动）；没租约 ⇒ `throttled`。
- **幂等去重（新 `domain.lease.service.idempotency`）**：`键 → 时间戳` + 插入序队列（逐出最旧 O(1)），保留期 = 一个 `refresh_interval`、硬上限默认 10 万。重复到达 ⇒ 本轮上报不计账（沿用账本里那份需求），**份额照样按当前账本重算**（"重放回放旧响应"的反例单测：pod-2 走了之后重放一个旧键，份额从 0 涨到 R）。⚠️ 队列里放的是**键 + 当时那个时间戳这一对**、逐出按对做条件删除——同一个键过保留期再来会被重新插表，光按键删会把刷新的新戳一起删掉。
- **case 编排**：`幂等去重 → 逐 key（规则匹配 → 过频判 → 需求换算 → 真分配 → 落地）`。顺序有两处是有讲究的：① **`unmatched` 必须排在过频判之前**（否则规则刚删掉时该 key 的首租会命中过频窗拿到 `throttled`，客户端按"首租失败"拒，spec §5「规则删除 = 无拒绝窗口」就破了）；② 过频命中那一支**不盖戳**（见上）。
- **票 03 的均分路径整体删掉**：`IAllocationService.evenShare` 不复存在，票 03 留下的对照用例（"占位实现不保证 Σ ≤ R"）**翻面**成了本票的通过断言（新实例这一轮拿 0、一个续租周期后收敛到需求比例）。
- **指标**：`gatekeeper.lease.idempotent.hit`（幂等命中）+ `gatekeeper.topology.redirect`（`421` 占位，构造器里**预注册**，所以现在抓 `/metrics` 读到的是 0 而不是缺席——票 07 的真计数接上去即可）。过频命中那一支**不记** `share.granted`（它没有重新发份额，只是回现有状态）。
- **`Sweep` 顺手清另外两张表**：`LeaseSweepJob` 一趟同时清冷租约、窗口外的过频锚点、保留期外的幂等键（各有各的保留期，不新开线程/旋钮）。

**实测读数**（`mvn package` 出 jar、真起单分片 center、curl；规则 `api:/orders/` `rate=100.0`，脚本与读数留在 `.scratch/gatekeeper-impl/verify-04/`）：

| 步 | 请求 | 响应 / 读数 |
|---|---|---|
| ① 首租 A | `usage = {5000ms, 400 allowed}` ⇒ 实测 80/s | `granted share=80.0 ttl_ms=5000`（冷 key 短 ttl） |
| ② 首租 B | 同 key、第二个实例、同样报 80/s | `granted share=20.0 ttl_ms=10000` ← **只拿未用量 20，Σ = 100 = R** |
| ③ A 立刻续租 | 同三元组、新幂等键 | `granted share=80.0 ttl_ms=4794` ← 过频命中：**份额不重算、回剩余 ttl**（不是空响应、也没清成 0） |
| ④ B 归还 | `POST /v1/lease/return` | `HTTP 200` |
| ⑤ B 再用同三元组租 | — | `{"status":"throttled"}`（无 `share`/`ttl_ms` 字段）← **首租命中过频窗** |
| ⑥ 配置基线 | 零到达窗口：一个 key 声明 `wants=5.0`、另一个不声明 | `share=5.0` / `share=0.0` ← **取实测与基线较大值**（后者就是"闲置清零"） |
| ⑦ 未匹配规则 | `web:/home` | `{"status":"unmatched"}`（无 `share` 字段） |
| ⑧ `/metrics` | 抓文本 | `gatekeeper_lease_share_granted_total{rule="orders"} 125.0`（80+20+20+5，③ 那次没有重发份额）、`gatekeeper_lease_first_result_total{result="granted"} 5.0` / `{result="throttled"} 1.0`、`gatekeeper_lease_idempotent_hit_total 1.0`（⑤ 那次用的是与 ② 相同的幂等键） |

**自行拍板的空白（三处，都可反转）**：

1. **配置基线 `wants` 落成"逐 key 可选协议字段"**（`LeaseKeyUsage.wants`，可空 `Double`，不带就不上线该字段）：spec §3.4 逐字有"`wants` 字段留给配置基线（应用自声明）"、CONTEXT.md 有词条，但 §6.2 的字段表没列它。按 §6.4「只增可选字段」上线（README 里那句"不新增机制、只是规则作者的使用建议"指的是**不为它加机制**——取较大值就一行的算子，不是新机制）。⚠️ **已回填 spec §6.2 的字段表**（补一行），免得后来的消费方照着 §6.2 实现时漏掉这个字段。另一条路是把它做成规则字段，但那要改 spec §5 已经定死的规则字段集，更贵也更不像"应用自声明"。
2. **过频锚点独立于租约记录**（单独一张按三元组的表，而不是复用 `LeaseEntity.lastRenewMillis`）：存在记录里的锚点会随 `return` / ttl 到期一起消失，而"过频"要禁的恰恰是"记录刚消失就立刻再来一次首租"——那样 **`throttled` 那一支永远不可达**（spec §6.2 逐字要求它存在且可读）。代价：多一张内存表（见下）。语义后果如实记：**归还之后立刻用同一个三元组再首租会吃 `throttled`**（真实客户端归还即下线、再回来是新世代，不受影响）。
3. **客户端自报 `has` 那条不上线**（只落了 `+ old.Has` 半条纪律）：spec §3.4 说"客户端自报 `has` 参与计算但只记日志（Doorman 一条日志的形状）"——本设计的协议（§6.2 字段表）里**没有**客户端自报 `has` 这个字段，中心账本本身就是份额的唯一真相源，算法只读账本里的 `has`（`ClientStateVO` 全部来自 `listByKey`）。也就是说纪律的**实质**（服务端以自己为准、不信任客户端的自述）由结构保证，Doorman 那条 `client %v is confused` 日志**没有输入可打**。要做成可观测的话得先在协议里加一个"客户端自报 has"字段——那是票 05（客户端续租通道，唯一会填它的地方）的事，故不在本票。⚠️ 已把这条差异写进 spec §6.2 的字段表注。

**已知代价 / 波及**：

- **内存账多了一张表**：过频锚点按"窗口内活跃的三元组"计（Sweep 自清），量级与账本条数同阶；spec §3.5 那条 91 B/条 + 4 MB（幂等表）的账**不含它**，已在 spec §3.5 加注说明。三条内存表都在同一批预算里（91 B/条本身就是手算 ±20% 未 JOL 的数）。
- **"首租报零用量 ⇒ share = 0 的租约"**：需求完全由上报驱动（spec §3.4 的口径），首租若报一个零到达窗口，拿到的就是 `share = 0`（有租约、无速率），客户端本地桶速率 0 ⇒ 本地拒到下一轮续租。**这是"分配吃实测 wants"的直接后果**，缓解手段就是配置基线（本票已实现）。⚠️ **票 05 要接的三件事**：首租那个同步请求报什么窗口（报"这个 key 从被看见到现在"的极短窗口会把需求估爆，报零就是 0——两者都不理想，要么改用配置基线兜底、要么延后建桶）；`throttled`/`share=0` 的消费（三分法第二格 = 拒）；`unmatched` 的逐出 + 放行。
- 分片 / `421` / 拓扑归票 07（本票只留了计数器占位）；`/admin/keys` 与其余指标归票 09；JOL 复核内存账仍挂着（spec §11 未实测清单第 4 条）。

**code-review 采纳项**（两轴，`/code-review` 对着 `HEAD` 的工作区改动）：

- **Standards 轴**：① `IAllocationService.proportionalShare` 的 `requesterWants` 与 `requester.wants()` 同名两义 ⇒ 参数改名 `currentWants`，并把"判据与均分那一支用**账本里那份**、只有比例支用**本轮**"这条照 Doorman 的分工写进注释。② `IdempotencyServiceImpl` 的 javadoc 声称"队首就是最旧的"，而"过保留期重新插表"那一支没往队列里补新戳 ⇒ **注释与实现不符**：改成队列放（键 + 时间戳）对、逐出按对做条件删除，并补一条回归用例（旧戳被丢掉但不能带走刷新过的新戳）。③ 两处 `sweepExpired` 写法不一（`int[]` 持有者 vs 显式循环）⇒ 统一成显式循环。④ `LeaseMetrics.REDIRECT` 只放了个常量（"占位"）⇒ 构造器里预注册，`/metrics` 上是一条 0。⑤ `LeaseKeyUsage` 的"首租请显式报零用量"是**误导性指令**（报零就是拿 0 份额）⇒ 改成"首租也要报，报价即需求"。⑥ `gatekeeper/CLAUDE.md` §2 的业务域表补上 `domain.lease` 新增的两个请求级子服务（原本只写了"租约记录/到期/世代"）。
- **Spec 轴**：① **`421` 计数只有名字没有序列** ⇒ 见上（预注册）。② **`wants` 字段不在 §6.2 的表里** ⇒ 回填 spec。③ `LeaseEntity` 不再存"最近上报用量" ⇒ 回填 spec §3.5 的账本形态。④ **过频锚点这张表不在 §3.5 的内存账里** ⇒ 回填 spec §3.5。⑤ **spec §3.5 的"第一轮按均分保守发放"是票 03 占位实现的残留**——真算法下冷 key 的份额是 `min(需求, R)`、需求报 0 就是 0，"保守"全部落在短 ttl 上 ⇒ 回填 spec §3.5。⑥ 首租命中 `throttled` 的唯一可达路径是 `return`/`ttl=0` 之后再来 ⇒ 已写进本 Answer 的拍板第 2 条。
- **两轴都点到、但判为 judgement call 不采纳**：`LeaseCase` 构造器 10 个参数（引入配置 record 会同时改动 Spring 装配形状，票 03 起的形状延续，等票 09 归拢观测时一起收）；`IAllocationService` 的 `requester = null` 表首租（与 `ClientStateVO` 一起传会更别扭：首租没有"账本里那份"可传）。
