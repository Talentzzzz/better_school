# 04 grilling：租约口径（令牌租约 vs 速率租约）

Type: grilling
Status: resolved
Blocked by: 01, 02, 03

## Question

定**令牌从中心到客户端的发放口径**——这是全设计的拱心石，[票 05](05-grill-local-bucket-and-admission.md)（本地桶行为）、[票 07](07-grill-rule-model-and-hot-reload.md)（规则字段集）、[票 08](08-grill-protocol-contract.md)（协议字段）都挂在它下面。

两条真实路子（[research/00](../research/00-lease-reclaim-prior-art.md) 已经查到，细节由票 01 / 02 补）：

- **(i) 令牌租约**：客户端说"给我 20 个"（want），中心扣 20、返回 `(granted, leaseId, 有效期)`，客户端花完再来领。**突发由 batch 大小决定**；需要**在飞量上限**兜住"全局悬浮量"
- **(ii) 速率租约**（Doorman）：客户端报 `wants`（我大概需要 X/s），中心回 `gets`（给你 Y/s），每 `refresh_interval`（5s）续租、租约 `lease_length`（5min）到期；服务端**每次续租重新分配**。**突发由"续租间隔内累积的余量"决定**；"在飞量"这个问题**自然消解**（因为中心发的是速率，不是令牌）

要裁决：

1. **选哪种**？是否值得混合（速率租约定基调 + 令牌批量落地）？各自对"客户端实例数多、流量抖动大"的适应度
2. **突发由谁决定**：令牌租约 = batch 大小（一次领多少）；速率租约 = 续租间隔 × 速率。哪种更符合"限流器该有的突发语义"（对照 Guava 的 `capacity`）
3. **在飞量上限怎么落成算法**（图绘期已定"设，默认 2×，可配"，见 [map](../map.md) 的 Notes）：中心侧判断条件是什么（`在飞总额 > 配额 × 2` 时拒发？还是把 batch 大小压到剩余额度？）；⚠️ 若选速率租约，写清这个问题由什么替代
4. **客户端怎么表达"我需要多少"**：固定配置 / 按本地实测速率自适应 / 按上次被拒的比例放大——这决定 SDK 有没有"自学习"成分，也决定被限流时的收敛速度
5. **中心侧账本的数据结构**：令牌桶（对应令牌租约）/ 滑动窗口计数器（对应速率租约，Sentinel 那路）/ 其他；以及**它要不要持久化**（中心重启丢不丢、丢了是"重置为满桶"还是"从快照恢复"——与票 06 的结论要对齐）

### 票 03（[算法与陷阱清单](03-research-algorithm-pitfalls.md)）带回来的新增待裁问题

票 03 的 §4 是纸面推导（未实测），但推导出的三条约束**必须在选口径时正面回应**，不能绕开：

6. **"过期作废的发放量"退不退回中心账**（票 03 点名必须在本票裁）：令牌租约下客户端领了没用完、T 秒后作废——这笔在中心账上算"已消耗"还是"已归还"？两种口径在大 N 下放大成**10 倍量级**的服务偏差（算例里每客户端用 2 个却领了 B=20，90% 批量过期）
7. **在飞上限的锚点与可行域**：约束必须锚在**规则桶容量 K** 上（C=1 ⇒ "突发 ≤ 2K"才有效）；**锚在 R·T 上是失效的**——默认 C=2 等于"放行 20 秒流量"。且要接受可行条件 `B ≤ (λ−1)·K/(f·N)`：算例下 N=200 得 B≤5、N=5000 得 B<1 ⇒ **"默认 2× + 一次领 20 个"在大 N 下不能共存**。选令牌租约就得定：B 由中心按当前活跃实例数动态压，还是放弃 2× 这个承诺
8. **中心的租约 QPS 账**：下界 `N/(C·W)` **与 R 无关**——N=5000、C=1 时每个 key 5000 QPS，哪怕那个 key 只有 1 QPS 流量。这笔成本可不可接受？不可接受的话替代方案是什么（拉长租约 / 改速率租约 / 让在飞上限只对高 QPS key 生效）

⚠️ 若最终选**速率租约**，票 03 的 §4 整节作废、需要重新推导一遍"速率租约下的突发上界"——这条要写进 Answer，作为口径切换的已知代价。

### 票 02（[开源实现落码](02-research-open-source-impls.md)）带回来的新增待裁问题

9. **续租的语义**：Envoy RLQS 的做法——"**策略没变只延 TTL、视为 assignment 未变；策略变了才立即结束 + 上报用量 + 换新**"——比 Doorman 每次续租整份重置更省。两种口径都要回应：续租是"重置一批新额度"还是"延有效期"；以及"策略变了"（规则热更新）时在飞额度怎么办（与票 07 的 Q4 是同一件事的两面，两边答案要对齐）
10. **中心账本只留一个真相源**：Redisson 同时维护 `value` 计数器 + ZSET 租约集合，对不上时走"全表重算"的修复分支——这是双真相源的税。我们的中心账本定成**单一真相源**（只有令牌桶 / 只有滑动窗口），还是接受一条修复分支？与 Q5（账本结构 + 持久化）一起定
11. **客户端"在册"怎么维持**：Sentinel 靠 Netty 连接在册、**不需要心跳**（代价：中心不知道客户端本地余量）；Doorman 靠续租请求自身；Envoy RLQS 靠周期上报用量。我们的续租请求是不是天然的就够了？还是需要独立心跳——⚠️ 票 03 警告过"按 (key, 实例) 打点 = 20 万 QPS"，必须按客户端聚合

### 票 01（[业界对照](01-research-prior-art.md)）带回来的新增待裁问题

12. **图绘期"设上限"是逆主流的决定，本票必须正面论证**（[map](../map.md) 的"三条定位事实"）：给"已发出未消耗"设上限的**只有 Bucket4j 一家**；Doorman 承认 `shortfall` 存在（仿真峰值 **106%**）却**明确放弃治理**（理由是治理会拖慢收敛）、Uber 干脆**废弃了本地令牌桶**改概率丢弃。要回答：设上限的代价（票 03 算出的中心 QPS 下界 `N/(C·W)`、以及 `B ≤ (λ−1)·K/(f·N)` 压死大 N）换来的"突发有上界"，值不值？**若不值，plan B 是 Doorman 路线**——承认超发 + 靠**短租约**间接控制（Doorman 的做法）。这条要给出明确取舍与理由，不能含糊过去
13. **中心的配额分配算法选哪类**（票 01 给了三个现成口径）：Doorman 每请求重跑 `wants`/`gets` 的公平分配（`FAIR_SHARE` O(n²) / `PROPORTIONAL_SHARE` O(n)）；Sentinel「单机阈值 × 命名空间在线连接数」（总量随连接数线性伸缩）；DFMF 期刊的滑动窗口预测 + 按比例重分。我们"批量领 + 中心账本"的形态适配哪一个；且要注意**自适应一律在客户端**（Doorman `AdaptiveQPS` 加权移动平均）——即"客户端怎么表达 needs"（见本票 Q4）业界是放在客户端侧的

依赖票 01（业界对照）、02（落码参考）、03（在飞量上界推导）。结论落 spec 的"租约与配额模型"节。

## Answer

**裁决：选 (ii) 速率租约**（Doorman / Envoy RLQS 同族）——中心下发的是**份额速率 + 有效期**，客户端本地桶按份额自补令牌、桶容量 = 份额 × burstSeconds。**不做令牌租约 (i)，也不做"速率定基调 + 令牌批量落地"的混合**：若"令牌批量落地"指"本地把份额物化成令牌桶"，那就是本口径本身（且是它唯一的自然落地方式）；若指"份额之外再补领一批带 TTL 的令牌"，等于把在飞量问题请回来、同时养两套机制，不做。

### 决策依据

**1. 同形最近邻下发的正是速率，不是令牌数。** Envoy RLQS 的 `BucketAction.QuotaAssignmentAction` 里只有 `rate_limit_strategy`（逐字，[research/02](../research/02-open-source-impls.md) §6.7）：

> `// Configures the local rate limiter for the request matched to the bucket.`
> `// If not set, allow all requests.`
> `type.v3.RateLimitStrategy rate_limit_strategy = 3;`

协议里**没有"令牌数"这个字段**；客户端上报的也是三个量（逐字，同节）：`// Time elapsed since the last report.` / `// Requests the data plane has allowed through.` `uint64 num_requests_allowed = 3;` / `// Requests throttled.` `uint64 num_requests_denied = 4;`。Doorman 同样：`Lease` 的字段是 `float64` 的 `Has` / `Wants`（req/s），**没有令牌、没有桶**（[research/02](../research/02-open-source-impls.md) §5.1）。

**2. 悬浮量由"份额之和 = R"构造保证，与实例数无关。** 速率口径下每个实例按自己的份额自补、桶容量 = 份额 × burstSeconds，而 Σ份额 ≤ R（由分配算法的"绝不发出超过当前未用量"保证）⇒ **在飞量 ≤ R × burstSeconds**。令牌口径下在飞量是 `f·N·B`——批量 B 是常数，**实例越多悬浮越多**。

**3. 图绘期的"在飞量上限"在算术上做不到，而它唯一的用途就是压住令牌口径的悬浮量。** 票 03 的两条：可行条件 `B ≤ (λ−1)·K/(f·N)`（N=200 → B≤5；N=5000 → **B<1，机制直接失效**）、中心租约 QPS 下界 `N/(C·W)`（**与 R 无关**——5000 实例、C=1 时每 key 5000 QPS，哪怕该 key 只有 1 QPS 流量）。业界对照：给"已发出未消耗"设上限的**只有 Bucket4j 一家**；Doorman 承认 `shortfall` 存在（仿真读数逐字：`The maximum capacity handed out was 530.24 (106.05%)`）并**明确放弃治理**——`Combatting shortfall would mean changing the system in a way which also slows down convergence.`（逐字）。⇒ 选速率口径后**不需要这个机制**。票 03 §6.1 那句结论**精神照旧成立**——逐字："**"不设上限"不是"放弃保护"，而是"接受 `R·T + K` 这个免费上界"**"——但**它的具体形式换了**：式中的 `R·T + K` 是 §4 在"中心扣账"前提下推出来的，随 §4 一并作废；速率口径下对应的免费上界是 **`R × burstSeconds`**（见上一条，形式化与收敛项待票 12）。

**4. 代价先讲清**：①中心账本从 O(key) 变 **O(key × 该 key 的在册实例数)**（Doorman 形状，只有真正用到该 key 的实例占一条）——**这正是 [research/03](../research/03-algorithm-pitfalls.md) §6.2 判为"不可行"的"设计①（按租约条目记）"形状**；选速率租约就是认下这笔内存账，靠分片与冷实例驱逐兜（票 06；量级待票 12 重算）；②协议必须带客户端身份 + 用量上报 + **按实例聚合的批量**；③中心每次续租要跑一遍分配算法；④**图绘期"在飞量上限默认 2×"反转**（见下"被反转的口径"）；⑤客户端 SDK 需要后台周期续租。

---

### 逐条结论

**Q1 口径** → 速率租约（如上）。

**Q2 突发由谁决定** → **由规则里的 `burstSeconds` 决定**（"允许几秒的量"，照 Guava 的 `maxBurstSeconds` 界面：逐字 `if a RateLimiter is 2qps, and this time is specified as 10 seconds, we can save up to 2 * 10 = 20 permits.`）。全局突发 = `R × burstSeconds`，单实例突发 = `份额 × burstSeconds`，**N 不参与**。规则里不写裸的桶容量数字，K 由 `R × burstSeconds` 派生（真要解耦再单列）——交票 07 落成字段。

> ⚠️ **2026-09-22 [票 12](12-research-rate-lease-bounds.md) 收口，细化上界公式（含一次用户裁决）**：① 正常路径 `瞬时突发 ≤ R × burstSeconds` 被证明是**硬上界**（中心账本 Σ租约 ≤ R 不变量 + 本地桶容量覆盖式更新，[research/12](../research/12-rate-lease-bounds.md) §1.1/§1.2，仿真全程贴 1.0×R）；降级期（fail-open 陈旧份额 + 再分配并存）≤ `2R × burstSeconds`。② **用户裁决（乙案）：本地桶容量下限 1 个请求**（`容量 = max(份额 × burstSeconds, 1 请求)`，化解"份额 × burstSeconds < 1 时 tryAcquire 恒拒"的微份额区间——s5 实测 R=1000/N=5000 时 99.98% 实例恒拒、翻转前全网放行 = 0）⇒ **上界改写为 `Σ max(份额 × burstSeconds, 1) ≤ R × burstSeconds + 微份额实例数`**（⚠️ **不是** `max(R × burstSeconds, 实例数)`——混合区间两项都存在，[research/12](../research/12-rate-lease-bounds.md) §1.2 有反例）；正常区间（份额 ≥ 1/burstSeconds）与原式逐字相同，对照臂实测最坏 3.6×（[research/12](../research/12-rate-lease-bounds.md) §7）。落实点：票 05 Q6 容量公式、票 09 Q3 验收口径。

> ⚠️ 票面 Q2 把速率租约的突发描述成"续租间隔 × 速率"——那是同一件事的一个取法（`burstSeconds` 通常取成等于或小于续租周期）。我们把它**显式化成规则字段**、沿用 Guava 的"秒数"界面，因为规则作者要读得懂"允许多少秒的量"；而**续租周期是 SDK / 协议的参数，不是限流语义**。

**Q3 在飞量上限怎么落成算法** → **撤销该机制**（图绘期决定反转）。速率口径下在飞量由构造上界 `R × burstSeconds` 保证，不需要第二个旋钮。若将来仍想要兜底，旋钮就是 `burstSeconds` 本身，不引入第二套机制。

**Q4 客户端怎么表达"我需要多少"** → **只报实测**：`放行量 / 被拒量 / 经过时间`（逐字字段见上，照 RLQS）。中心把这三个计数**除以经过时间换算成速率（req/s）**，再按"实测消耗 + 被拒量（= 未满足的需求）"的比例重分——**上报用计数（RLQS 形状）、进公式前换算成速率**（Q13 采纳的 Doorman `gets` 公式吃的是 `Wants`，单位 req/s）。`wants` 字段留给"配置基线"（应用自己声明），分配时取"上报换算出的实测需求"与"配置基线"的较大值；**v1 不实现客户端自适应估量**（不抄 Doorman 的 `AdaptiveQPS` 加权移动平均）——少一块会漂的估量状态。
> 收敛为什么不会无限放大（grilling 现场追问）：**份额是每轮重算的，不是在上一轮基础上累加**；被拒量只决定"总量恒为 R 的这块蛋糕怎么切"，多给某实例的部分来自别人没用完的余额；全网都跑满时没有余额可挪，谁都涨不了。真正会无限涨的写法是"新份额 = 旧份额 + 被拒量"（欠账累加），不做。Doorman 对应的硬纪律是逐字 `minF(gets, unusedCapacity)`。

**Q5 中心侧账本的数据结构 + 持久化** → ①**账本形态 = 每 `(key, 实例)` 一条租约记录**（分配速率、到期时刻、最近上报用量）；"要不要同时养派生计数器"见 Q10。②**v1 不落盘**：重启后账本清空，**第一轮按均分保守发放 + 给短有效期**，等客户端上报回来再收敛；**不抄 Doorman 的 learning mode**（它"不知道 `has` 就发 0"的语义与我们的 fail-open 默认直接冲突）。③中心重启前给在册客户端下发 `ttl = 0` 预告降级（RLQS 的 `assignment_time_to_live` 显式支持这个用途）——这条进票 08 协议。④冷实例的驱逐语义要定：速率口径下"驱逐 = 忘掉这条租约"，该实例下轮续租会被当新实例（按 `clientId` 重新在册）——**这条要写进票 06**。⚠️ 与票 06（分片 / HA）有交接，票 06 定稿时复核。

**Q6"过期作废的发放量"退不退回中心账** → **不适用**（票 03 点名要在本票裁，此处给出结论与其失效理由）：中心发的是**速率份额**不是一次性令牌，"没用完的份额"不是账上一笔待回收的资产（份额随时间自然流逝，与漏桶同理），因此**既不存在"退回"这个动作，也不存在票 03 算出的 10 倍量级服务偏差**。置换来的代价是"客户端不再囤令牌"（冷启动突发变小；票 03 §3.2 指出这比 Guava 更保守，是好事）。

**Q7 在飞上限的锚点与可行域** → 随 Q3 一并作废：`B ≤ (λ−1)·K/(f·N)` 在"中心发令牌"的前提下才成立。速率口径下的突发上界需重推（见 Q2 / Q3 与「已知代价」）。

**Q8 中心的租约 QPS 账** → ①**续租必须按实例聚合**——一次请求带上该实例订阅的**所有 key** 的（份额、用量），于是全中心 QPS ≈ **实例总数 / refresh_interval**（N=5000、5s → 1000 QPS 全中心），**与 key 数无关**；按 `(key, 实例)` 打点才是票 03 警告的 20 万 QPS——**这就是"批量接口是硬要求、不是优化"的落点**。②**每 key 的处理成本**是 `Σ_k(该 key 的在册实例数) / refresh_interval`（每次续租要对每个 key 跑一遍分配），这是 CPU 而非 QPS，靠分片摊（票 06）。③续租频率设硬下限（Doorman **文档声明**，逐字：`a client may not send more GetCapacity requests for a given resource than once every five seconds. If it does send a request for capacity for a resource inside of five seconds since the last request it is silently ignored.`；⚠️ [research/02](../research/02-open-source-impls.md) 未核实项 9 点名：该条只见于 `doc/design.md`，**未在 `server.go` 找到对应实现**，属"文档声明"而非"落码事实"）。⚠️ ①②③ 是票 08 的协议输入。
> ⚠️ **2026-09-22 [票 08](08-grill-protocol-contract.md) 收口，③ 已具体化（非反转）**：硬下限落成中心侧配置 `min_renew_interval`（默认 5s）、**按 `(key, instance_id, generation)` 逐 key 判**；"静默忽略"落成——**续租命中 ⇒ 不重算份额 + 用量照记 + 返回现有租约状态**、**首租命中 ⇒ 显式 `throttled` 状态**（不真返空响应：返空会被客户端误读成超时、触发 fail-open 判定）。

> ⚠️ **2026-09-22 [票 06](06-grill-center-sharding-and-ha.md) 收口，细化 ① 的数字（非反转）**：本条的 `实例数 / refresh_interval` 是在"一个实例一个批量请求"的前提下给的；按 key 分片后，**一个 pod 的 key 散在多个分片上**，客户端要**按分片拆成 ≤ S 个子请求并发发出**（票 06 Q12）⇒ 全中心 QPS 细化为 **≤ `实例数 × min(该实例订阅 key 数, 分片数) / T`**（5000 实例 × 8 分片 / 5s = **8000 QPS**）。**"与 key 数无关"仍然成立**（封顶于分片数），**"批量接口是硬要求不是优化"这个结论不变**。②（每 key 分配成本是 CPU、靠分片摊）**票 06 给出了摊法**：固定槽位分片（M=1024）。

**Q9 续租的语义** → 照 RLQS：**份额未变 → 只延有效期，视为租约未变**（不重算、不换租约；逐字：`If the rate limiting strategy is the same as the bucket's ``active`` (not ``expired``) assignment, the data plane should extend the duration of the ``active`` assignment … The ``active`` assignment is considered unchanged.`）；**份额变了（含规则热更新导致份额变）→ 换租约、立即生效**。本地桶的衔接照 Guava `doSetRate`：**改速率时存量令牌按容量比例缩放**（逐字：`storedPermits = (oldMaxPermits == 0.0) ? 0.0 : storedPermits * maxPermits / oldMaxPermits;`），这样桶的"满度"不跳变、缩容时不会凭空多放一批。票 07 Q4（热更新时在飞额度怎么处置）按同一条口径写。

**Q10 中心账本只留一个真相源** → **单一真相源 = Q5 的租约表**，**不养派生计数器**（不抄 Redisson 的 `value` 计数器 + ZSET 双线，那要付"全表重算"的修复分支）；"在册实例数"若要缓存，必须标"派生、可重建、**不参与分配决策**"（分配一律遍历租约表本身）。→ 因此**不需要一致性修复分支**。

**Q11 客户端"在册"怎么维持** → ①**在册 = 续租请求自身**（Doorman 形状：租约到期即被 forget），**不加独立心跳**；代价是中心只知道"这个实例还在续租"、不知道它本地还剩多少——**这正是用量上报要带放行量的原因**。②`clientId = 实例身份 + 世代号`（票 03 §5.3 的 Kafka `ProducerEpoch` 形状，**采纳**）：出现更大 epoch 即作废该 clientId 名下的全部旧租约，否则崩溃重启的实例会让在册量翻倍。⚠️ **2026-09-22 [票 08](08-grill-protocol-contract.md) 反转了本条的"世代作废"机制（generation 字段本身保留）**：①"在飞量翻倍"是**令牌口径**的失效模式，速率口径下幽灵条目只**稀释份额**（Σ份额 ≤ R 构造上界仍成立、安全侧少发）；②优雅关停由 return 即删 + `ttl = 0` 预告兜住，崩溃的幽灵条目 ≤ ttl（默认 2 × `refresh_interval`）内到期，与既有窗口纪律同量级；③主动作废是第二套机制买同一样东西。反转的完整理由见该票 Answer「被反转的口径」。③**回收**：租约到期自动收回 + 正常关停主动归还（等价 Doorman `ReleaseCapacity` 的整资源放弃）；**不做运行期退零头**（与 map Notes「只做下线归还」一致）。⚠️ ②③ 是票 08 的协议输入。

**Q12 图绘期"设上限"逆主流这条怎么正面论证** → 见"决策依据"第 3 条：论证的结论是**该机制不值得设**，plan B（选 Doorman 路线）就是本票选的方案。

**Q13 中心的配额分配算法选哪类** → **PROPORTIONAL_SHARE（O(n)）**：先保底均分，未用满的余额按需求比例分给超额者（Doorman 逐字：`gets = equalSharePerClient + (r.Wants-equalSharePerClient)*(extraCapacity/extraNeed)`）。配两条纪律：`minF(gets, unusedCapacity)`（**绝不发出超过当前未用量**；Doorman 注释逐字：`We cannot give out more than the currently unused capacity. If that is less than what the algorithm calculated we will adjust this in the next capacity refreshes.`）、**客户端自报的 `has` 参与计算但服务端以自己为准**（Doorman 只记日志：`client %v is confused: says it has %v, was assigned %v.`）。**不选 Sentinel 的 AVG_LOCAL**：其份额是静态 R/N，会饿死吃掉 90% 流量的实例——这条定性论证出自 [research/03](../research/03-algorithm-pitfalls.md) §4.10，**该节随 §4 一起作废**，但这条论证不依赖"中心扣账"前提、仍然成立（待票 12 复核）。O(n²) 的 FAIR_SHARE 不取。自适应在客户端（票 01 结论）→ 落在 Q4 的上报里。

---

### 被反转的口径（沿全链传播）

**图绘期 Notes「在飞量上限（overshoot）：设，默认 2×（可配）」反转 → 不设该机制。** 反转理由：它只在"中心扣账发令牌"的前提下才有意义，而该前提下它在算术上做不到（`B ≤ (λ−1)·K/(f·N)`）；速率口径下悬浮量由 `Σ份额 ≤ R` 构造保证。

传播站点（本次已逐处加注记 / 改写）：

- [map](../map.md) 的 Destination 行（"本地优先取令牌，不够时向中心批量领"失效）、Notes「图绘期已裁的两个机制点」、Notes「三条定位事实」第 2 条、Decisions-so-far 的票 03 行（式 (5) 的 `min{…}` 封顶要一并标注）
- [票 01](01-research-prior-art.md) `## Answer`（"逆主流"那条的最终裁决）与 [票 02](02-research-open-source-impls.md) `## Answer`（Bucket4j 两个旋钮不再是本设计的机制）
- [票 03](03-research-algorithm-pitfalls.md) 的 `## Answer`、[research/03](../research/03-algorithm-pitfalls.md) 的头部说明 / 摘要第 4 与第 6 条 / §4 节首 / §6.2 节首（**§4 整节作废**，其前提是"中心真的在扣账"；§6.2 判为"不可行"的"设计①"正是本票采纳的账本形状）
- [票 05](05-grill-local-bucket-and-admission.md) 的 Q1 / Q5 / Q7（本地桶形状、`capacity` 与桶容量的关系、Bucket4j 两个旋钮）
- [票 06](06-grill-center-sharding-and-ha.md) 的 Q2 与 Q5 / Q7（"靠在飞量上限兜"这条路没了；账本形态与重启语义）
- [票 07](07-grill-rule-model-and-hot-reload.md) 的 Q1 / Q4（字段集与热更新语义）
- [票 08](08-grill-protocol-contract.md) 的 Q2 / Q3 / Q4 / Q6（字段面与消息面）
- [票 09](09-grill-observability-and-acceptance.md) 的 Q3（"瞬时突发 ≤ 在飞量上限"改为"≤ `R × burstSeconds`"）
- [票 11](11-task-write-spec.md) 的 `Blocked by`（加票 12）与 CONTEXT.md 词条建议（"权威桶"改为"份额"）

### 已知代价 / 待办

- **票 03 `research/03-algorithm-pitfalls.md` §4 整节作废**（含式 (3)(5)(8)(9') 与三个算例），需按速率口径重推突发上界与中心容量账 → 已另立 [票 12](12-research-rate-lease-bounds.md)，[票 11](11-task-write-spec.md) 增加依赖。
- **本票给出的速率口径上界是纸面推导**（瞬时突发 ≤ `R × burstSeconds`；窗口 Δ 内放行量 ≤ `R·Δ + R·burstSeconds` + 收敛超调），**未实测**；Doorman 自己仿真的超调读数是 `106.05%`（≈ 6%），不是我们的读数。⚠️ **2026-09-22 [票 12](12-research-rate-lease-bounds.md) 已复核**：带收敛项的精确形式 = 正常路径系数 1（Σ份额 ≤ R 硬不变量）、降级期系数 ≤ 2；收敛 ≤ 2 个续租周期、无震荡；Doorman 的 106% 成因是其层级部署、本设计无此通道（[research/12](../research/12-rate-lease-bounds.md) §1/§4）。
- **中心账本的内存账是一笔明账，不是"沿用旧估算"**：形态 = 每 `(key, 实例)` 一条租约记录，即 [research/03](../research/03-algorithm-pitfalls.md) §6.2 的"设计①"。该节当时在"每 key 一个桶就够"的框架下把它判为**不可行**（99 B/条 × N 条/key，10k key × 200 实例就 ~200 MB），**选速率租约即认下这笔账**；Doorman 的 `map[string]Lease`（两个 float64 + 一个 `time.Time`）比那条估算更紧凑，量级与分片后的分布~~待票 12 重算~~ **⚠️ 2026-09-22 [票 12](12-research-rate-lease-bounds.md) 已算实**：≈ 91 B/条（Java、含 wants/expiry，手算 ±20% 未 JOL）、满配 10k key × 200 实例 ≈ 200 MB → 8 分片 25 MB/实例，两个 10k 默认值复核通过（[research/12](../research/12-rate-lease-bounds.md) §5）。
- **spec 尚未存在**（`.scratch/gatekeeper/spec.md` 由票 11 产出），本票结论落在 spec 的"租约与配额模型"节，由票 11 执行。
- 术语回填（票 11）：本票把"权威桶"细化为"**份额（assigned rate）**"、"在飞量 / 悬浮量"在速率口径下重新定义为"**各实例本地桶里尚未消耗的令牌之和，上界 `R × burstSeconds`**"。
