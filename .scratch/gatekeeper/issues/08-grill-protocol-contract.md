# 08 grilling：中心 ↔ 客户端协议契约

Type: grilling
Status: resolved
Blocked by: 04, 05, 06, 07

## Question

把中心与客户端之间的接口定死。图绘期已定：**对外 HTTP/REST（JSON）+ 批量租约接口**，且"**协议即契约**"——其他语言照协议自实现，所以这份契约要写得能照着实现。

要裁决：

1. **端点集**：租约（lease）/ 归还（return，仅下线用）/ 规则查询（客户端要不要从中心拉规则）——各端点的方法、路径、语义
2. **字段逐个定**：请求侧（key、want、客户端标识、规则版本、幂等键）；响应侧（granted、leaseId、有效期、规则版本、错误码、`retry-after`）。⚠️ 形状由票 04（租约口径）与票 05（本地桶行为）决定
3. **超时、重试与幂等**：租约请求超时后客户端重试会不会**重复领**；幂等键放在请求的哪个字段、中心侧怎么去重、去重窗口多长；客户端重试的退避策略
4. **无额度时的响应语义**：直接拒绝（返回 0 + 建议等待时间）还是给一部分（best-effort 返回剩余额度）；客户端拿到"0"之后该等多久（中心给 `retry-after` 还是客户端自算）
5. **协议的版本化与兼容承诺**：因为要给别人照着实现，字段的增删规则、版本号怎么带、不兼容变更怎么发布

6. **客户端"在册"与用量上报**（票 02 带回的对照，直接决定协议里有没有这条消息）：Sentinel 靠连接在册、**没有任何周期上报**；Envoy RLQS 靠客户端周期上报 `BucketQuotaUsage{bucket_id, time_elapsed, num_requests_allowed, num_requests_denied}`。我们要哪条？⚠️ 若上报，票 03 的硬约束是**必须按客户端聚合**（按 (key, 实例) 打点在 10k key × 200 实例下 = **200,000 QPS**，聚合后 20 QPS）⇒ "批量接口是硬要求、不是优化"。另外若要支持"中心主动弃桶/规则切换时的在飞处置"（票 04 的 Q9），协议里就得有服务端 → 客户端的下行消息

> ⚠️ **2026-09-22 [票 04](04-grill-lease-model.md) 已裁，给本票的字段面 / 消息面定了六条硬输入**：①选**速率租约** ⇒ 请求侧要有 **`clientId`（实例身份 + 世代号）**与**批量多 key**（一个实例一次请求带上它订阅的所有 key——这就是"批量接口是硬要求、不是优化"的落点）；响应侧下发的是**份额速率 + 有效期**，**不存在 `granted` 令牌数**。②**必须有周期用量上报**（照 RLQS 三字段：`time_elapsed` / `num_requests_allowed` / `num_requests_denied`）——"Sentinel 式靠连接在册、无上报"那条**不选**（代价：中心不知道客户端本地余量）。③**必须有服务端 → 客户端的下行**：`ttl = 0` 预告降级（中心重启前）、以及"份额变了"的推送（票 04 Q9 的"换租约"）。④**Q3 幂等要重新裁**：口径从"扣减一批令牌"变成"重算份额"，重试的后果不再是"重复领 B 个令牌"。⑤**Q4"无额度"语义**：份额为 0 就是份额为 0，不是"领不到令牌"（没有 `retry-after` 可给，除非另设）。⑥**续租频率要有硬下限并定义"太频繁就静默忽略"的行为**（Doorman 形状）。

> ⚠️ **2026-09-22 [票 05](05-grill-local-bucket-and-admission.md) 已裁（本票的两张前置票都清了），给本票六条硬输入**：① 租约在协议里**只传"时长"、不传绝对到期时刻**（票 05 Q3；RLQS `assignment_time_to_live` 逐字就是 duration）；② **份额的类型**建议 `double`，SDK 内部按 10⁶ 定点化且**量化一律向下取整**（票 05 Q6）——舍入方向永远保守；③ **`ttl = 0` 与"没有响应"必须是两种可区分状态**：**主动 `ttl = 0`** ⇒ 客户端停止自补（存量花完），**被动超时无响应** ⇒ 客户端继续按最后份额跑（fail-open）。若不区分，`ttl = 0` 在 fail-open 口径下会退化成**空操作**（票 05 Q3 的表）；④ **首租（首次在册）必须能合批**：实例冷启动时 N 个 key 并发首访不能打出 N 个请求（票 05 Q10；Doorman `doc/design.md` 描述的正是这一场景，原文逐字："…during client initialisation when multiple resources are created, each potentially leading to a GetCapacity request"，见 [research/02](../research/02-open-source-impls.md) §5.8，其未核实项 9 已点名该条**只见于文档、未在 `server.go` 找到实现**）——协议要给"能凑就凑"的批量形状，中心侧另对同 `clientId` 的高频首租静默忽略；⑤ **批量续租的字段**：每 key 的份额 + 用量三字段（`time_elapsed` / `num_requests_allowed` / `num_requests_denied`，照 RLQS）；⑥ **下行推送要有两种**：`ttl = 0`（重启预告）与"份额变了"（票 05 Q9 的快速收敛路径）。

> ⚠️ **2026-09-22 [票 06](06-grill-center-sharding-and-ha.md) 已裁（本票的第三张前置票也清了），给本票再添五条硬输入**：① **中心侧权威校验 + 重定向**：请求要带 **`topo_version`**（客户端认的那版拓扑）；发到非本分片的请求回 **`redirect`**（建议 `421`），带 **`authority`（权威实例的地址 / 标识）+ `topo_version`**（票 06 Q2；先例 Doorman `doc/design.md:208-216` 逐字 `If a non-master task gets a request if will respond with the address of the master, so that the client knows who to talk to.`，⚠️ **仅文档声明、本 effort 未在 `server.go` 核实**）——客户端拿到 redirect 立即改道，且**只在 `目标版本 ≥ 自己版本` 时跟随、同一 key 一轮最多跟一跳**（票 06 Q10）。② **批量请求要按分片拆**：一个 pod 的 key 散在多个分片 ⇒ 一次续租拆成 ≤ S 个子请求**并发**发出，**每个子批一个幂等键**（票 06 Q12 / Q13）——上面票 04 输入① 的"一个实例一次请求带上它订阅的所有 key"、票 05 输入④ 的"首租合批"**都要按本条修正为"按分片拆批"**。③ **幂等键照 [票 03](03-research-algorithm-pitfalls.md) §5.2 的形状**（客户端生成 UUID、重试复用、中心侧短窗口去重表 + 硬上限），**用量字段保持 RLQS 的增量语义**——票 06 Q13 在此处**否掉了"改报累计值（重试天然幂等）"这个替代方案**，选了照 RLQS 逐字 + 付一张去重表。④ **每请求 key 数上限**（建议 500，可配）与超限时的报错语义（票 06 Q6）。⑤ **续租其实是"每分片一条通道"**：客户端每 `refresh_interval` 对每个分片发一次（票 06 Q12），这决定了 SDK 侧并发发起的形状与超时预算。

> ⚠️ **2026-09-22 [票 07](07-grill-rule-model-and-hot-reload.md) 已裁（本票第四张前置票也清了），给本票三条硬输入**：① **票面 Q1 列的「规则查询端点」不做**——客户端直连配置源（`ConfigSource<T>` 抽象，Nacos 订阅 / 本地文件轮询，与中心同一条路），**不从中心拉规则**，协议里没有这个端点（fail-open 要求中心挂掉时规则仍可得）。② **请求与响应都要带规则版本号**——**观测面、不阻断**：中心发现客户端版本与自己不一致时打日志 + 出指标（票 09）；规则版本错位的后果 = 超发 ≤ `max(R_old, R_new) × 一个 refresh_interval`，与票 06 的 `2R` 过渡窗口同成因、同自愈窗口，不值得为此养协议状态机（与拓扑版本的 `redirect` 硬机制**区分开**——那是「双发」正确性问题）。③ **续租响应要有显式「未匹配」状态**（key 不匹配任何规则时，区别于 fail-closed 的拒）：客户端收到 ⇒ 停止续租 + 逐出在册条目 + 该 key 放行——**规则删除绝不能让该 key 翻成被拒**（票 07 Q8）。

依赖票 04（租约口径）与票 05（本地桶 / 放行语义）。结论落 spec 的"协议契约"节。

## Answer

**裁决总览**：协议 = **两个端点**（`POST /v1/lease` 兼做首租与续租；`POST /v1/lease/return` 只做下线归还）+ **下行搭续租响应的便车**（不建第二条通道）+ **路径大版本 `/v1/`**（v1 内只增可选字段、消费方必须忽略未知字段）+ **snake_case**。请求侧字段：身份双字段（`instance_id` + `generation`）、`topo_version`、`rule_version`、`idempotency_key`、`keys[]`（每 key 带 RLQS 三字段用量）；响应侧 per-key **三值状态枚举**（`granted` / `unmatched` / `throttled`）。本票有三处沿链交代：**反转了 [票 04](04-grill-lease-model.md) Q11② 的"世代作废"机制**（见「被反转的口径」）；**修正了 grilling 现场自己的两处推荐**——去重窗 60s（对齐 [票 06](06-grill-center-sharding-and-ha.md) Q13 已交付口径）与首租超时/合批窗口（对齐 [票 05](05-grill-local-bucket-and-admission.md) Q10 已裁的 50ms 端到端预算），见 Q3 与「现场新增」。

### 逐条结论

**Q1 端点集** → ①**规则查询端点不做**（票 07①，客户端直连 `ConfigSource<T>`）。②**首租与续租是同一个端点 `POST /v1/lease`**：首租在中心眼里就是"账本里没有 `(key, instance_id, generation)` 记录的续租"，分两个端点只会让票 05④ 的首租合批与续租批走两条码路。③**`POST /v1/lease/return`**（下线归还，票 04 Q11③ 的"正常关停主动归还"落点）：批量 keys、**天然幂等**（退不存在的记录 = no-op）**不加幂等键**、fire-and-forget（进程关停钩子 best-effort 发一次、不等响应、失败即弃）。请求体 = lease 请求去掉 usage 与幂等键：`{ instance_id, generation, topo_version, keys: [{key}] }`，响应 200 空体。return 同样受中心侧权威校验（421 redirect，客户端**跟随一跳**即可；跟不动就弃——条目由 ttl 到期兜底回收）。中心收到即删该批 `(key, instance_id, generation)` 条目并立即重算份额。

**Q2 字段逐个定** → 全字段表（类型 / 语义）：

请求（`POST /v1/lease`）：

```json
{
  "instance_id": "pod-7f3c",
  "generation": "9a1b2c3d-…",
  "topo_version": 12,
  "rule_version": "8f3a91c2…",
  "idempotency_key": "d4e5f6a7-…",
  "keys": [
    { "key": "api:/orders/create",
      "usage": { "time_elapsed_ms": 5000, "allowed": 4200, "denied": 37 } }
  ]
}
```

响应（200）：

```json
{
  "topo_version": 12,
  "rule_version": "8f3a91c2…",
  "refresh_interval_ms": 5000,
  "results": [
    { "key": "api:/orders/create", "status": "granted", "share": 12.5, "ttl_ms": 10000 },
    { "key": "api:/orders/cancel", "status": "unmatched" },
    { "key": "api:/orders/pay",    "status": "throttled" }
  ]
}
```

要点：**`instance_id` + `generation` 双字段**（账本键 = `(key, instance_id, generation)`；`instance_id` 来源链写进契约：`POD_NAME` → `HOSTNAME` → IP 回落；`generation` = SDK 实例启动时生成的 UUID。双字段是因为票 09 观测面要按实例打点，免拆串免转义）。**`usage` 就是 RLQS 三字段逐字、不加维度**（票 04②）：`time_elapsed_ms`（自上次上报的时长，增量语义）/ `allowed` / `denied`（本窗口计数，增量语义，票 06 Q13）。**`share` = double**（票 05②，SDK 侧 10⁶ 定点化、量化一律向下取整）。**`ttl_ms` 只传时长不传绝对时刻**（票 05①），**默认 = 2 × `refresh_interval`（10s）**、每次成功续租重置——容忍丢一轮续租（抖动 / GC 不至于把在册条目打掉重登记）；票 04 Q5 的"重启后给短有效期"与票 06 Q4 的"新权威首轮只发短 TTL"落在这个字段。**`ttl_ms = 0` = 主动降级信号**（中心重启预告 ⇒ 客户端停止自补、存量花完；与"被动超时无响应 = 继续按最后份额跑"是两种可区分状态，票 05③）。**`refresh_interval_ms` 仅观测**（Doorman 形状里有、但客户端节奏以自己配置为准——fail-open 体系里客户端不信任中心的临时状态）。**`rule_version` = 规则内容哈希（十六进制串）**、只做相等比较（观测面只需要"不一致就打日志 + 出指标"，票 07②；对 Nacos 与本地文件双实现都成立）；**`topo_version` = 拓扑配置里显式声明的单调递增数字**（票 06 Q10 的 redirect 跟随判据要比较序，哈希给不了）。

**per-key 状态枚举（三值）**：**`granted`**（带 `share` + `ttl_ms`；**`share = 0` 是合法值、不是错误**——票 04⑤"份额为 0 就是份额为 0"，无 `retry-after`）；**`unmatched`**（票 07③：key 不匹配任何规则 ⇒ 客户端停止续租 + 逐出在册条目 + 该 key 放行——规则删除绝不翻成拒绝）；**`throttled`**（新增：首租命中 `min_renew_interval` 过频窗，见 Q3——客户端按票 05②格处置：本次准入拒、下一轮再试首租；**绝不能被读成 `share = 0`**——那会误停自补——也不能读成 `unmatched`——那会误逐出）。

**请求级错误**：**`421` + `{ authority, topo_version }`**（票 06②；跟随便据 = 目标 `topo_version` ≥ 自己的才跟、同一 key 一轮最多一跳，票 06 Q10）；**`400`** = 超每请求 key 数上限（默认 500，票 06⑥——客户端侧同源配置理应不触发，触发即契约违约、走 fail-open）+ `{"error": "…"}` 说明串（客户端不解析）；**`503`**（中心过载快速失败，票 06 Q6①）与**一切 5xx / 超时 ⇒ fail-open**（继续按最后份额跑，票 05）。

**Q3 超时、重试与幂等** → ①**两条路径的超时预算分开**：**续租**在守护虚拟线程上跑、超了顶多晚一个周期 ⇒ 每子批超时 **2s**（< `refresh_interval`），周期内最多重试 1 次（退避 ~1s + 抖动），仍失败走 fail-open、等下一周期——不做周期内的激进重试风暴；**首租**在准入同步路径上跑（票 05：冷启动同步首租）、超时直接加到业务请求延迟 ⇒ **端到端预算 50ms（沿票 05 Q10 已裁的"带短超时，默认 50ms"）**，合批窗口与重试都封在这 50ms 里，重试**复用同一幂等键**（票 03 §5.2），仍失败按票 05②格拒（有规则 → 拒，本次准入不再放大延迟）。⚠️ 现场初版曾推荐首租超时 1s，收口对齐票 05 已裁的 50ms、按上游修正。②**幂等键** = 每个按分片拆出的子批一个 UUID（票 06④），重试复用、新周期新键。③**中心侧去重**：条目 = `idempotency_key → 时间戳`（≈ 80–120 B/条，[票 06](06-grill-center-sharding-and-ha.md) Q13 的账原样适用），**保留期 = 一个 `refresh_interval`（默认 5s）**——重试跨度上界 ≈ 3s（超时 2s + 退避 1s），一个 `refresh_interval` 足盖；**另设硬上限（默认 10 万条、逐出最旧）**，该表不参与分配决策（票 06 Q13 的显式交代）。⚠️ **本票 grilling 现场初版曾推荐 60s 窗，收口时发现与票 06 Q13 已交付的"保留期 = 一个 `refresh_interval`"冲突，按上游修正**——60s 会把"4 万条 ≈ 4 MB"的账放大 12 倍。④**重复到达的行为**：**跳过用量增量记账、其余照常走**（重算份额 + 返回当前租约状态）——份额重算天然幂等，"重放原始响应"的等价物就是"当前状态"，且只存键不存响应（存响应可达 500 key/批，内存爆炸）；**去重表在收到请求时插入、不区分处理成败**（收到即视为可能已生效，保守跳过增量；漏记增量的方向是"中心低估需求 ⇒ 少发"，安全侧）。⑤**续租过频**：中心侧独立配置 **`min_renew_interval`（默认 5s）**，**按 `(key, instance_id, generation)` 逐 key 判**（Doorman 逐资源判的形状；批量内逐 key 区分：窗内的回现有状态、窗外的正常重算）；**续租命中 ⇒ 不重算份额 + 用量照记 + 返回现有份额与剩余 ttl**（"静默"若真返空响应，客户端会误读成超时触发 fail-open 判定）；**首租命中 ⇒ `throttled`**（没有现有租约可回，必须显式状态）。契约写明：**客户端 `refresh_interval` 不应低于 `min_renew_interval`**。

**Q4 无额度时的响应语义** → ①**份额 0 就是份额 0**：`granted` + `share = 0`（票 04⑤），**无 `retry-after`**——速率口径下"建议等待时间"没有锚（份额是持续自补的速率，不是一次性额度），客户端侧的等待语义由本地桶的 `nanosToWaitForNextToken`（票 05 Q2）承担，不进中心协议。②**与"未匹配"严格区分**：`share = 0` = 有规则但没分到；`unmatched` = 没有规则（逐出 + 放行，票 07③）——两个状态、两种客户端处置，绝不混用。③**首租失败**（含 `throttled`、超时、5xx）一律按票 05②格：有规则 → 本次准入拒；**冷启动兜底三分法不变**（无匹配规则 → 放行 / 有规则但首租失败 → 拒 / 曾断租 → 继续最后份额）。

**Q5 版本化与兼容承诺** → **大版本在路径（`/v1/`）**，body 不再带协议版本号（路径就是版本）；**v1 兼容承诺三条写进 spec：只增可选字段 / 不改既有字段语义 / 不删字段**；**消费方义务 = 忽略未知字段**（protobuf 惯例——这是"只增不破坏"能成立的前提）；**不兼容变更 = `/v2` 并存过渡**（不是原地改）。**字段名 snake_case**（契约面向 protobuf / RLQS 惯例对齐；Java 侧 Jackson `@JsonProperty` 即可）。

**Q6 客户端"在册"与用量上报（消息面）** → ①**周期上报已由票 04②/05⑤ 裁定**：RLQS 三字段、按实例聚合、批量是硬要求（票 03 §5.4）——本票落成字段表（Q2）。②**下行走搭便车，不建第二条通道**（SSE / WebSocket / 长轮询都不做）：所有依赖下行的窗口都已接受 refresh_interval 量级——票 06 的 `2R` 过渡窗口、票 07 的规则版本错位窗口、票 04 的重启预告（预告本就只需在重启前到达；落法 = 优雅停机 drain 窗口内所有响应带 `ttl_ms = 0`）。③**"份额变了"不需要独立消息**：每次续租响应本来就带当前份额，客户端比对发现变了就走票 04 Q9 的"换租约 + 本地桶按容量比例缩放"；规则变更的"立即"由票 07 的变更回调触发立即续租解决。⇒ **下行的全部内容 = 响应里的字段值，没有额外消息类型**。

### grilling 现场新增、票面未列的裁决

- **`refresh_interval` 默认 5s**（客户端可配）——它是三个窗口的锚（票 06 的 `2R` 窗口、票 07 的规则错位窗口、本票的下行时延上界），票 06 Q4 的算例（R=1000/s ⇒ ≤5000 请求）与 Doorman 的五秒硬下限都隐含它。
- **`ttl`（lease_length）默认 = 2 × `refresh_interval`**——票 06 交接里"冷 key 逐出触发 = 该 key 在 `lease_length` 内没有任何续租"的 `lease_length` 就是这个字段；幽灵条目（崩溃未 return）残存上界 = ttl（见下）。
- **首租合批窗口默认 10ms（可配）**（票 05 line 157 点名交本票的值；票 05 Q10 自己的建议是"5–10ms 或能凑就凑"，取上限）——窗口必须小于首租的 50ms 端到端预算。**以上默认值的标定复核归 [票 09](09-grill-observability-and-acceptance.md)**。

### 被反转的口径（沿全链传播）

**[票 04](04-grill-lease-model.md) Q11② 的"出现更大 epoch 即作废该 clientId 名下的全部旧租约"反转 → 世代交替复用 ttl 到期 + return，不做"新世代顶旧世代"的主动作废。** 反转理由：①该机制采纳时引用的理由是票 03 的"崩溃重启的实例会让**在飞量翻倍**"——那是**令牌口径的失效模式**；速率口径下幽灵条目只会**稀释份额**（Σ份额 ≤ R 的构造上界仍成立，稀释是**安全侧**少发、不是超发）。②优雅关停有 return 即删 + 重启预告（`ttl_ms = 0`）；崩溃重启的幽灵条目在 ≤ ttl（默认 2 个 `refresh_interval`）内到期，与既有窗口纪律（`2R` 窗口、规则错位窗口）同量级、同自愈路径。③主动作废是**第二套机制买同一样东西**。**`generation` 字段本身保留**（身份区分 + 观测面 + 账本键），只是不再带"作废语义"。

另有一处**对现场自己推荐的修正**（非上游反转）：Q3 ③ 的去重窗长，60s → 一个 `refresh_interval`（对齐票 06 Q13，理由见彼处）。

传播站点（本次已逐处加注记 / 改写）：

- [map](../map.md)：`Decisions so far` 新增本票一行；`Not yet specified` 的默认值条目更新（`refresh_interval` / `min_renew_interval` / 去重窗 / 超时 / `ttl` / 合批窗口已落，标定复核归票 09）
- [票 04](04-grill-lease-model.md) `## Answer` Q11②（世代作废反转注记）与 Q8 ③（续租硬下限的具体化注记）
- [票 05](05-grill-local-bucket-and-admission.md) `## Answer`（续租硬下限具体化注记；"留给下游的数值"条更新——`refresh_interval` 与首租合批窗口已由本票落定）
- [票 06](06-grill-center-sharding-and-ha.md) `## Answer` Q13（去重窗长沿用确认 + 现场 60s 推荐的修正记录 + 重复行为落定）
- [票 09](09-grill-observability-and-acceptance.md)（`Blocked by` 补 08；窗口锚、协议级指标、世代交替验收场景加注）

### 已知代价 / 待办

- **本票全部数值默认值（`refresh_interval` 5s / `min_renew_interval` 5s / 去重窗 5s / 硬上限 10 万条 / 续租超时 2s / 首租端到端预算 50ms / `ttl` 10s / 合批窗口 10ms / key 数上限 500）是拍定值、未标定**——标定复核归 [票 09](09-grill-observability-and-acceptance.md)（map「Not yet specified」原有条目）。⚠️ **2026-09-22 [票 09](09-grill-observability-and-acceptance.md) 已裁复核方法**：性能值**实测三项**（首租端到端 p99 ≤ 50ms（空载+带载）/ 满配 500 key 续租批端到端 ≪ 2s / 中心单分片续租吞吐 ≥ 200 QPS（1000 实例 ÷ 5s 算例））、策略值**场景语义四项**（`refresh_interval` / `min_renew_interval` / `ttl` / 去重窗不另立基准、由故障验收场景复核窗口行为）；数值若与 [票 12](12-research-rate-lease-bounds.md) 仿真冲突，改数值不改结构（票 09 Q2 接口）。
- **`421 redirect` 的先例仍是"仅文档声明"**（Doorman `doc/design.md:208-216`，未在 `server.go` 核实）——沿 [票 06](06-grill-center-sharding-and-ha.md) 的 ⚠️ 原样带过来；`min_renew_interval` 的 Doorman 依据同理（[票 04](04-grill-lease-model.md) Q8③ ⚠️）。我们自己的实现语义（`throttled` + 现有租约回放）是新增裁决，无先例可引、也不需要。
- **协议契约的 spec 落点**：spec 第 6 节"协议契约"由 [票 11](11-task-write-spec.md) 执行；本票 Answer 的字段表即该节的素材。
- spec 定稿时的术语回填（票 11）：本票新增 `throttled 状态`、`min_renew_interval`（实现旋钮，是否入词条由票 11 裁）。
