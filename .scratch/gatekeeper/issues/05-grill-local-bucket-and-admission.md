# 05 grilling：本地桶行为与放行语义

Type: grilling
Status: resolved
Blocked by: 03, 04

## Question

额度到了客户端之后怎么花，以及"没令牌时"的语义。这是 SDK 的行为契约，[票 08](08-grill-protocol-contract.md)（协议）、[票 09](09-grill-observability-and-acceptance.md)（验收）、[票 10](10-grill-reference-integration.md)（接入器形状）都依赖它。

要裁决：

1. **本地是"纯搬运桶"还是"整形器"**：
   - 纯搬运桶——领来 N 个，来一个请求花一个，20 个可以在一瞬间花光 → 这个实例是**脉冲式**放行
   - 本地整形器——把这 N 个按 rate 摊平着花（等价于本地再套一层漏桶）→ 每个实例自己匀速，全网更平滑
   两者对"端到端观感"和"超发上界"的影响不同，且**与票 04 的租约口径耦合**
2. **放行 API 的语义**：
   - 快速失败（`tryAcquire()` → 布尔 / 异常）
   - 阻塞等待（`acquire(timeout)` → 等到有令牌或超时）
   - 两者都要？
   分布式语境下"等待"意味着什么（在本地等？等到本地桶被中心补齐？还是等一个明确的重试时刻）
3. **时间源与时间差**：本地桶按什么时间算剩余额度（单调时钟 vs 墙钟）；客户端与服务端时间不同源时租约怎么算才不出错；时钟回拨的防护（票 03 给事实）
4. **同一 key 的并发访问**：多线程同时 `acquire` 同一个 key 时的同步方式（这决定 SDK 的锁粒度，也是 [map](../map.md) "Not yet specified" 里"SDK 并发模型"那张 fog 的入口）
5. **规则里的 `capacity` 与本地桶容量的关系**：是同一个数，还是"全局突发"与"单实例突发"两个不同的数？如果本地桶容量小于 `capacity`，客户端会不会永远打不满规则允许的突发

### 票 02（[开源实现落码](02-research-open-source-impls.md)）带回来的新增待裁问题

6. **补令牌的数值手法**（票 02 给了两个现成答案，二选一即可，别自己发明）：
   - **Bucket4j 式**：令牌用 `long`，除不尽的余数存回状态（`roundingError`，纳秒），下次并进分子 → **整数域零漂移、无 double**；时钟不前进直接 `return`
   - **Guava 式**：存量令牌用 `double`，锚点是"**下一次可发放的时刻**"（`nextFreeTicketMicros`）而不是欠账计数器 → `tryAcquire` 一次减法就能判
   票 02 倾向前者（更适合我们），但这条要你拍
7. **要不要外露 Bucket4j 那两个旋钮**：`maxUnsynchronizedTokens`（本地最多先花多少）+ `maxUnsynchronizedTimeoutNanos`（最长多久不同步）。Bucket4j 的分布式优化就是我们的形态、且把这两个旋钮**摆在 API 上**；它们直接决定超发上界与"多久才发现中心已经没额度了"。外露（可配）还是内部定死
8. **probe 的语义**：把"**能不能过**"与"**过了要不要等**"拆成两件事（Guava `tryAcquire` 的先判后睡）；以及"注定失败的同步不要发"（Redisson `tryAcquire(timeout)`：本地算剩余、`remains < delay` 时不再往返、到点直接 false，超时是端到端预算）

> ⚠️ **2026-09-22 [票 04](04-grill-lease-model.md) 已裁，直接改变本票 Q1 / Q5 / Q7**：口径是**速率租约**——①**Q7 那两个旋钮（Bucket4j `maxUnsynchronizedTokens` / `maxUnsynchronizedTimeoutNanos`）不在本设计的机制里**，不用裁；②**Q1**（纯搬运桶 vs 整形器）与 **Q5**（`capacity` 与本地桶容量的关系）已有口径：本地桶是**按份额自补的整形桶**，**容量 = 份额 × burstSeconds**，全局突发 = `R × burstSeconds`（与实例数无关）；③本地桶仍要"改份额时按容量比例缩放存量令牌"（Guava `doSetRate` 形状，见票 04 Q9）；④新增一条本票要面对的：SDK 需要**周期续租**（票 04 已定"续租按实例聚合、频率有硬下限"），它的载体（定时器 / 虚拟线程 / 懒续租）正是本票要定的。

依赖票 03（时间源 / 精度）与票 04（租约口径）。结论落 spec 的"租约与配额模型"节（本地侧行为）+ "参考接入器"节（API 形状）。

## Answer

**裁决：本地桶 = 按份额自补的整形桶（微令牌整数域）+ 快慢双 API + 每 key 一把 `ReentrantLock` + 每实例一条后台续租线程。** 串起来说：调用方 `tryAcquire(key)` 只做本地令牌数学、拿不到就拒；中心侧只有一条**每实例一个周期**的批量续租通道在跑；**唯一的例外**是冷启动第一次见到某 key 时由调用方线程**同步首租**一次（Q10）——除此之外放行路径**一次网络往返都不发**。

> 编号约定（供 [票 11](11-task-write-spec.md) 逐条汇总）：**Q1–Q8 照票面 `## Question` 的编号**；**Q9 = 票 04 的 ⚠️ ④ 新增项**（周期续租的载体）；**Q10（冷启动 / 未知 key）与 Q11（本地桶驱逐）是票面没列、本轮 grilling 新暴露的两条**——票面 `## Question` 里没有对应条目，汇总时别按编号去找。

### 票 04 已裁、本票继承（票面 Q1 / Q5 / Q7）

- **Q1 纯搬运桶 vs 整形器** → 继承票 04：**整形器**——本地桶按份额自补（等价于本地再套一层漏桶），每个实例自己匀速。本票补上它的具体形状：状态 = `(tokens 微令牌, lastRefillNanos, roundingError)` + 一把锁（见 Q4/Q6）。
- **Q5 `capacity` 与本地桶容量的关系** → 继承票 04：**不是同一个数**。规则给的是全局 `R` 与 `burstSeconds`；本地桶容量 = **份额 × burstSeconds**；规则里不写裸容量数字（票 07 落字段）。⇒ 客户端**不会"永远打不满规则允许的突发"**：全局突发 `R × burstSeconds` 由各实例的 `份额 × burstSeconds` 相加而成（Σ份额 ≤ R），本地容量小是设计而非缺陷。
- **Q7 Bucket4j 那两个旋钮** → 随票 04 一并**不在本设计的机制里**（`maxUnsynchronizedTokens` / `maxUnsynchronizedTimeoutNanos` 服务的是"本地先花 + 攒欠账 + 超阈值同步"，我们没有"欠账"这个量）。**不外露**——机制不存在，没有旋钮可露；若将来要做，先过票 04「不值得设」那一关。

### Q2 放行 API 的语义 → 快慢双 API，返回 `Probe` 值对象

- `tryAcquire(key)` → **`Probe`**：`{ allowed, remainingTokens, nanosToWaitForNextToken }`，布尔只作便捷方法 `allowed()`。形状照 Bucket4j `ConsumptionProbe`（票 02 §2.4）；**不抄** `nanosToWaitForReset`（"补满"不是调用方关心的量）。`nanosToWaitForNextToken` 定义为"**补足 1 个完整令牌还需多久**"（0 = 现在就能过）。
- `acquire(key, timeout)` → 布尔。语义**严格限定为"等本地桶"**：park 到 `min(nanosToWaitForNextToken, 剩余预算)` 后重试，循环到成功或超时；**绝不发中心请求**；**睡在锁外**（Guava 把 `sleepMicrosUninterruptibly` 放在 `synchronized` 之外，票 02 §1.6）。超时是**端到端预算**（Redisson 把 `startTime` 传遍整条重试链，票 02 §3.3），不是每次重试的预算。
- 不做第三个方法，**不做"欠账 / 预支"**（这一点决定了 Q6 不抄 Guava 的锚点）。
- 票面 Q8 后半的 Redisson 那条（"注定失败的同步不要发"）在速率口径下**退化到续租侧**：放行路径根本不碰网络，它落成 Q9 的一条（份额为 0 且无积累需求时，不必为它占用批量请求）。

### Q3 时间源与时间差 → 单调钟差值 + 租约只传时长 + 一行防回拨；**到期后 fail-open**

- **时间口径照票 03 §2.5（事实驱动、无分叉）**：本地时间轴用 **`System.nanoTime()` 差值**（不用墙钟——`currentTimeMillis` 受 NTP `makestep` 与闰秒影响，票 03 §2.3）；**租约在协议里只传"时长"**（RLQS 的 `assignment_time_to_live` 逐字就是 duration，票 02 §6.7），两端各用各自的单调钟记"收到时刻 + ttl"，中心用**自己的**钟结算到期——**两侧各自闭环、不对齐时钟**。反例：Doorman 用墙钟绝对 `expiry_time`，δ = 1s 钟差即可凭空多出 `R × δ` 个令牌的在飞量（票 03 §2.4 手算），它能这么做只因"内网 NTP + 5 分钟租约"这个运维前提。
- **防回拨**：桶里记 `lastRefillNanos`，`now <= lastRefillNanos` 时按"时间没走"处理（不补令牌、不推进 `lastRefillNanos`），一行 `max(0, now - last)` 即可——Bucket4j `refill` 的第一句就是这个（票 02 §2.2）。依据：`nanoTime` 在 Linux 上 JDK 自己声明 `may_skip_backward = false`，但那是**实现性质而非规范保证**、且不覆盖 macOS/AIX（票 03 §2.2）。
- **到期行为（本票真正的裁决）= fail-open**：续租没回来时**继续按最后已知份额自补、继续放行**，直到收到**主动的 `ttl = 0`** 或规则变更。理由：① 与 map 已定的"降级默认 fail-open、本地桶按已知规则自跑"一致；② "没收到回复"分不清"中心挂了"与"网络抖了"，此时惩罚客户端是纯粹的可用性损失；③ **票 03 §5.4 那条"fail-open 的本地速率必须是 `R/N` 而不是 `R`"在速率口径下自动满足**——本地跑的速率就是自己的份额，而 Σ份额 ≤ R。**这条红利之前没被点名，是选速率租约的额外收益。**
- ⚠️ **必须与票 04 Q5 ③ 的 `ttl = 0` 预告降级区分开**（否则 `ttl = 0` 在 fail-open 口径下会退化成**空操作**）：

  | 情形 | 本地动作 |
  |---|---|
  | **被动到期**（没收到新租约，中心可能不可达） | 继续按最后份额跑（fail-open） |
  | **主动 `ttl = 0`**（中心活着且明确说"该租约立即作废"） | **停止自补（份额视作 0）、存量允许花完**，直到下次续租 / 推送带回新份额 |

  依据：`ttl = 0` 是中心**还活着**时发出的明确信号，遵守它不损失可用性；而它的用途（票 04 Q5 ③：重启前预告）恰恰是"别让客户端拿着旧份额跨过重启"——本地若把它也当"继续跑"，该机制即失效。
- ⚠️ **这一格是我们的取舍，不是 RLQS 的原文语义**：RLQS 里 `ttl = 0` 表示 assignment 立刻 `expired`，其后行为由过滤器的 `expired_assignment_behavior` 决定（该字段的取值 [research/02](../research/02-open-source-impls.md) **未读、属未核实项 11**）。我们把"主动 `ttl = 0`"**规定**为"停止自补、存量花完"、把"没有响应"**规定**为 fail-open——两条都写成 spec 里的契约，**不假装有先例背书**。
- 残余风险如实记：若某实例分区前的份额远大于 `R/N`（它是唯一活跃者），fail-open 期间它会按那个大份额跑。想做保底就抄 Doorman `safe_capacity`（中心算 `R / 在册数` 回传，票 02 §5.7）——**本票不做**，字段形状留票 07/08。

### Q4 同一 key 的并发访问 → 每 key 一把 `ReentrantLock`

- 桶表 `ConcurrentHashMap<Key, BucketEntry>`，`BucketEntry = { long tokens; long lastRefillNanos; long roundingError; ReentrantLock lock; }`；懒创建用 `computeIfAbsent`（里面不做重活；冷启动首租见 Q10）。
- **每 key 一把锁**，不是全局一把：50 个 key = 50 把锁，跨 key 不互相阻塞。
- **不用 `synchronized`**：Java 21 上它会 **pin 载体线程**，而 SDK 恰恰是被大量虚拟线程调用的东西——本仓 virtual-thread effort 票 13 刚把 `ensureBucket` 从 `synchronized` 换成 `ReentrantLock` + 双检就是为这个，SDK 不该再引入 pin 源。
- **锁内只做令牌数学**：不发续租、不打日志、不做驱逐；`acquire(timeout)` 睡在锁外。
- **无锁 CAS 留档不做**：把 `(tokens, 时刻, 余数)` 打包进一个 `long` 做 `AtomicLong` CAS 循环更快，但可读性与调试成本高，且 Bucket4j 自己也是"state 对象 + CAS 整份替换"而非位域 CAS（票 02 §2.5）；50 key × 200 线程远未到需要它的争用水平。
- **驱逐与在飞 `acquire` 的竞争**：驱逐用 `map.remove(key, entry)` 且要求 `entry.lock.tryLock()` 成功，拿不到就跳过、下一拍再试。

### Q6 补令牌的数值手法 → Bucket4j 式 + 微令牌（10⁶ 缩放）

- **选 Bucket4j 式**（票 02 §2.2）：令牌用整数，**除不尽的余数存回状态、下次并进分子**——`divided = refillTokens × duration + roundingError; refill = divided / period; roundingError = divided % period` ⇒ 整数域零漂移、余数守恒。`refill == 0` 时**显式把整份 `divided` 存进余数**（否则低速率下会被反复截断成 0，票 02 源码里就是这个分支）。
- **令牌单位 = 微令牌（1 req = 10⁶ 微令牌），`refillPeriodNanos` 固定 1 秒**：份额 3.3 req/s → 每秒补 3,300,000 微令牌；容量 = 份额 × burstSeconds → 3,300,000 微令牌。这样"份额是实数"不再是障碍——**不缩放就套不进整数手法**：容量 3.3 个令牌不是整数，只能把份额砍成 3/s，等于系统性少放 **9%**。量化精度是 1e-6 req/s ⇒ 份额 3.3 的**相对**误差 ≤ 3×10⁻⁷；绝对量上它确实会累积（量化只在收到份额时做一次、之后不再重取整），一年按 3.3/s 跑满 ≈ **31 个令牌**，相对该实例一年 ≈ 1.04×10⁸ 的总放行量仍是 3×10⁻⁷ 量级，可忽略。
- ⚠️ **2026-09-22 [票 12](12-research-rate-lease-bounds.md) 收口，容量公式加下限（用户裁决"乙案"）**：`容量微令牌 = max(份额 × burstSeconds, 1.0) × 10⁶`。起因：份额 × burstSeconds < 1 个请求时（条件 = 该 key 实例数 > R × burstSeconds），桶永远装不满 1 个请求 → `tryAcquire` 恒拒（s5 实测 R=1000/N=5000 时 99.98% 实例恒拒、翻转前全网放行 = 0）。下限只作用于**容量**、不作用于**补令牌速率**（速率仍 = 份额，微份额区间行为 = 每 `1/份额` 秒放 1 个、无囤积）；rescale 的缩放比例用**新旧容量**（含下限）算，份额穿越 1/burstSeconds 边界时存量缩放才正确。代价：全局突发上界改写为 `Σ max(份额×burst, 1)`（正常区间与原式逐字相同），见 [research/12](../research/12-rate-lease-bounds.md) §7。
- **不采用 Guava 的 `nextFreeTicketMicros` 锚点**（票面 Q6 给的另一个现成答案）：它是为"欠账可以推到未来、`acquire` 必睡"设计的；我们的桶**不预支**（拒了就是拒了、`acquire` 只等本地补足），所以"上次补充时刻 + 余数"就够。**这条要点名，否则将来会被当成漏抄了 Guava 的一半。**
- **改份额时照票 04 Q9**（Guava `doSetRate` 形状）：`stored = stored × newMax / oldMax`（**先乘后除**，余数进 `roundingError`），满度不跳变、缩容不会凭空多放（该式天然保证 `stored ≤ newMax`）；`lastRefillNanos` 不动，其后按新速率补。
- **溢出**照 Bucket4j 的两条路：乘法用带守卫的 `multiplyExact` 风格（超界即当溢出、直接灌满容量），`newSize < currentSize` 也当溢出处理（票 02 §2.2）。
- ⚠️ **给票 08 的输入**：协议里份额的类型建议 **double**（JSON 友好、人可读），SDK 内部定点化，且**量化一律向下取整**——舍入方向永远保守（少发不超发）。

### Q8 probe 的语义 → 把"能不能过"与"过了要不要等"拆成两件事；中心的往返不在放行路径上

- `Probe` 同时回答两件事：`allowed()`（能不能过）与 `nanosToWaitForNextToken`（过了要不要等、等多久）——这正是 Guava"先判后睡"的形状（票 02 §1.6：`tryAcquire(permits, timeout)` 先 `canAcquire` 判，判真之后**照样睡**）。
- `tryAcquire` 等价 timeout = 0 的特例（`nanosToWaitForNextToken > 0` ⇒ false）；`acquire(timeout)` 是"允许等"的形态。
- ⚠️ **与票面原问法的差别**：Redisson 那条"本地算剩余、`remains < delay` 时不再往返"**在速率口径下没有对象**——放行路径一次网络往返都不发。它的对应物落在 Q9 的续租侧。

### Q9 SDK 周期续租的载体（票 04 ⚠️ ④ 新增）→ 每实例一条守护虚拟线程 + 批量 + 抖动

- **载体**：**一条守护虚拟线程**（`Thread.ofVirtual().name("gatekeeper-renewer").daemon()` 里跑 sleep 循环），每 `refresh_interval` 醒来一次，把该实例**所有在册 key** 的（份额、用量计数）打包成**一个**批量请求发出去。不用 `ScheduledExecutorService`（没有虚拟线程工厂、会白占一个平台线程，其调度精度对我们没有价值）。
- 这是票 04 Q8"续租按实例聚合"的物理载体：全中心 QPS ≈ **实例数 / refresh_interval**（5000 实例 / 5s = 1000 QPS 全中心，**与 key 数无关**；每 key 一个定时器是 50×）。
- **懒续租只作补充、不作唯一载体**：它的上报粒度 = 访问频率，而票 04 Q4 的分配算法**吃的是上报换算出的实测需求**——一个 5s 只被访问 1 次的 key，懒上报会让中心看到的用量严重滞后。**"上报是算法输入，不是优化"**，这是"必须有后台线程"的真正理由。懒路径只保留给两件事：① 首次在册（Q10 的同步首租）；② 中心主动推"份额变了"时的快速收敛（票 08 的下行消息）。
- **抖动的落点**：加在**客户端侧的下一次续租时刻**（`refresh_interval × (1 ± jitter)`），不改协议。用途是票 03 §6.3 的惊群缓解——中心重启后会统一发放，不加抖动则所有租约同时到期。
- **续租频率硬下限** + "太频繁就静默忽略"（Doorman 形状）。⚠️ 票 04 Q8 ③ 已点名：该条**只见于 Doorman `doc/design.md`、未在 `server.go` 找到实现**，属"文档声明"而非"落码事实"，引用时要带上这条。⚠️ **2026-09-22 [票 08](08-grill-protocol-contract.md) 收口，本条已具体化（非反转）**：下限 = 中心侧配置 `min_renew_interval`（默认 5s）、按 `(key, instance_id, generation)` 逐 key 判；"静默忽略"落成——续租命中 ⇒ 不重算份额 + 用量照记 + 返回现有租约状态，首租命中 ⇒ 显式 `throttled` 状态（真返空响应会被客户端误读成超时）。
- **"份额没变只延有效期"**（票 04 Q9 / RLQS）在客户端侧的落点：本地桶**什么都不动**（不缩放、不重置），只把 `leaseExpiresAtNanos` 往后推；**只有份额变了**才走 Q6 的按比例缩放。
- **份额为 0 且上一轮用量/需求都为 0 的 key，不为它占用批量请求的字节**——这是 Q8 那条 Redisson 纪律在续租侧的形态。注意这**不是**"不发整个批量请求"：批量请求按实例发，与单个 key 无关。

### Q10 冷启动 / 未知 key（票面未列，票 04 之后新暴露；票 10 的演示直接依赖）→ 同步首租 + 三分法兜底

- **首次见到某 key**（本地无份额、也不在在册表里）→ **调用方线程同步首租一次**（带短超时，默认 50ms），拿到份额就按它跑，之后交给 Q9 的后台续租。理由：① 每个新 key 只多一次**有界**往返；② 它让"本地优先"在**第一个请求上就成立**，否则第一个 `refresh_interval` 是盲的；③ 票 10 的"能真的拦下来"演示与票 09 的瞬发 harness 都要靠它。
- ⚠️ **同步首租必须合批，否则与票 04"批量是硬要求"打架**：实例冷启动时 50 个 key 被并发首访会打出 50 个请求——正是 Doorman `doc/design.md` 描述的场景（原文逐字："…during client initialisation when multiple resources are created, each potentially leading to a GetCapacity request"，见 [research/02](../research/02-open-source-impls.md) §5.8；其未核实项 9 已点名该条**只见于文档、未在 `server.go` 找到实现**）。落法：首租请求进**同一个批**（极短的合批窗口，如 5–10ms，或"能凑就凑"），各调用方的 future 共享同一个响应；中心侧另按 Doorman 形状对同 `clientId` 的高频首租静默忽略。协议形状交票 08。⚠️ **2026-09-22 [票 08](08-grill-protocol-contract.md) 已落**：合批窗口默认 **10ms**（本条建议 5–10ms 取上限）、协议面落成显式 `throttled` 状态（非真静默）。
- **超时兜底要把三种"没有"分开**（本票给的语义骨架，三个格子都可测）：

  | 情形 | 动作 |
  |---|---|
  | **key 不在任何规则里**（SDK 本地规则集查无此 key） | **放行**。不限流是默认语义；否则漏配一条规则就会把接入方的应用打死。⚠️ 前提是"SDK 本地有规则集"，由票 07 定 |
  | **key 有规则、但首次在册失败**（中心不可达 / 首租超时） | **拒绝 + 记指标日志**。无份额可依据，此时"按规则自跑"**没有速率可用**（也没有票面 Q2 那条"按 `R` 放行"——票 03 §5.4 已点名那会 N 倍超发） |
  | **曾经在册过、后续断租** | 继续用最后份额（= Q3 的 fail-open） |

> ⚠️ **2026-09-22 [票 06](06-grill-center-sharding-and-ha.md) 收口带来的两处补充**：① **上表第②格的一个具体触发面 = 挂掉分片上的新 pod**——票 06 裁了"单槽无副本、不重路由"，所以某分片挂掉期间，**新起的 pod 首租必然失败 ⇒ 拒绝**（既有 pod 走第③格 fail-open）。这是已知限制，不是缺陷，spec 要一并写。② **上面那条"首租请求进同一个批"在分片后要读成"按分片拆成 ≤ S 个子批"**（票 06 Q12 对批量续租的同一个结论）——否则一个冷启动实例的首租会被某个分片拒掉一半。

> ⚠️ **2026-09-22 [票 07](07-grill-rule-model-and-hot-reload.md) 已裁，给本票 Q10 两处细化（非反转）**：① **②格「有规则、首租失败 ⇒ 拒」不分 `fallback` 档、一律拒**——fail-open 的规则在「从未拿到份额」时没有速率可跑，fail-open 无处着力；`fallback: fail-open | fail-closed` 字段只作用于**曾有租约后的断租 / 分片挂掉**情形（fail-closed ⇒ 停止自补、存量花完，即 Q3 表第二行的语义复用）。② ①格的前提「SDK 本地有规则集」已由票 07 落地：规则源 = `ConfigSource<T>` 抽象、客户端直连配置源（Nacos / 本地文件），且**首份配置非法或规则源连不上 ⇒ 空规则集 = 走①格放行 + 「配置非法」告警 + 持续重试**——①格多了一个具体触发面。

### Q11 本地桶的驱逐（票 03 §6.2 末尾点名的"会被忽略的突发来源"）

- **容量上限 + 近似 LRU（按最后访问时刻），逐出整个条目**：默认 ~10k key/实例量级、可配。清扫**搭在 Q9 的续租心跳里**（不另起线程）。
- 丢掉的余量上界 = `份额 × burstSeconds`（例：3.3 个令牌），方向**保守（少发）**；下次访问重新走 Q10 的同步首租，行为与冷启动一致。（⚠️ 2026-09-22 [票 12](12-research-rate-lease-bounds.md) 乙案后读作 `max(份额 × burstSeconds, 1 请求)`——微份额区间逐出最多丢 1 个请求，方向不变。）
- **明确不选"保留条目、只把令牌清零 / 灌满"**：票 03 §6.2 点名"重置为满桶 = 冷 key 恢复时白送一个满桶突发"，是个会被忽略的突发来源；"重置为空桶"则给恢复加了一次延迟。

### 给下游票的硬输入

- **票 07（规则模型）**：规则字段是 `R` + `burstSeconds`（**不写裸容量**），本地桶容量由二者派生；`burstSeconds` 默认值待定（建议照 Guava 的 `1.0`）；**"SDK 本地必须有规则集"是 Q10 第①格的前提**（规则下发 / 本地文件兜底）；热更新导致份额变时的本地衔接见 Q6。
- **票 08（协议）**：① 份额的类型（建议 double，SDK 向下取整定点化）；② 租约只传**时长**；③ **`ttl = 0` 与"没有响应"必须是两种可区分的状态**（见 Q3 的表）；④ 首租的合批形状；⑤ 批量续租的字段（每 key 份额 + 用量三字段 `time_elapsed` / `num_requests_allowed` / `num_requests_denied`，照 RLQS）；⑥ 下行推送（`ttl = 0` 与"份额变了"）。
- **票 09（可观测与验收）**：本地要报的量 = 桶水位（`remainingTokens`）、被拒数、`acquire` 等待分布、首租延迟与失败率、续租失败 / 降级次数；验收需含"**同步首租路径**"与"**分区 fail-open（按份额继续跑）**"两个场景；"瞬时突发 ≤ `R × burstSeconds`"的 harness 要能制造同步突发（精确形式引票 12）。
- **票 10（参考接入器）**：拦截器 / 注解只用 `tryAcquire` → `Probe`；拒绝时 HTTP 429 + 可插拔拒绝处理器；日志打 key / 规则 / 桶水位（`Probe` 的三个字段正好够，不必二次查询）。⚠️ **2026-09-22 [票 10](10-grill-reference-integration.md) 已裁**：三条全部采纳——注解/拦截器只走 `tryAcquire`、`RejectionHandler` 默认 = 429 + `Retry-After` + 可插拔换业务错误码、日志三样取自 `Probe`；另把「证明本地优先」扩成**比值 + 断中心**两个观测点。

### 被修正 / 细化的口径（沿全链传播）

- **票 04 Q5 ③ 的 `ttl = 0`**：票 04 只写了"中心重启前下发 `ttl = 0` 预告降级"，**没有说客户端拿它做什么**。本票细化：**主动 `ttl = 0` ⇒ 停止自补（存量花完）；被动到期 ⇒ 继续最后份额**。若不这么分，`ttl = 0` 在 fail-open 口径下会退化成空操作（**这是本轮 grilling 揪出的跨票不自洽，不是反转**）。
- **票 03 §5.4 的"fail-open 必须 `R/N`"**：在速率口径下**自动满足**（本地速率 = 份额），该警告从"必须额外实现"降为"构造上已保证"。[research/03](../research/03-algorithm-pitfalls.md) §5.4 处加注。
- **票 01 / 02 的 Bucket4j 两旋钮**：随票 04 一并作废，本票确认**不外露**（票面 Q7）。

### 传播站点（本次已逐处加注记）

- [map](../map.md)：Decisions-so-far 新增本票一行；"Not yet specified" 里"**客户端 SDK 的并发模型与锁粒度**"整条撤下（本票已答），换成"各处默认值的标定"
- [票 07](07-grill-rule-model-and-hot-reload.md) / [票 08](08-grill-protocol-contract.md) / [票 09](09-grill-observability-and-acceptance.md) / [票 10](10-grill-reference-integration.md)：各加一条 ⚠️ 注记，内容见上面"给下游票的硬输入"
- [票 06](06-grill-center-sharding-and-ha.md) Q9：加一条 ⚠️——"本地速率取什么"的**本地侧**已由本票 Q3 定（继续用最后份额），Q9 只剩**中心侧**档位
- [research/01](../research/01-prior-art.md)：§「Bucket4j 两旋钮」那句"正好对位本设计要讨论的 `batch 大小`"加注（票 04 选速率后就无 batch 这个量）
- [research/02](../research/02-open-source-impls.md)：§1.8 / §2.1 / §2.6 三处"对我们的启示"加注——"进分布式就必须换墙钟"、"跨进程就得放弃 `nanoTime`"、"两旋钮必须暴露"三条都被本票推翻
- [research/03](../research/03-algorithm-pitfalls.md)：头部追加更新说明 + §2.5（时间源已裁）+ §3.2 / §3.3（"搬运工 / 批量 `B`"失效）+ §5.4（`R/N` 自动满足、"残留批量令牌作废"失效）+ §6.2（冷 key 驱逐的"要进票 04/06"→ 本票 Q11 已裁）
- ⚠️ **与票 04 的交接**：`ttl = 0` 那一格是**细化不是反转**（票 04 只写了中心什么时候发，没写客户端拿它做什么），票 04 的 `## Answer` 不需要加反转注记

### 已知代价 / 待办

- 本票结论**全部未落码**；spec 由票 11 产出后才谈实现。
- 术语回填（票 11）：`微令牌`、`Probe（放行判定结果）`、`首租 / 在册`、`续租心跳（renewer）`、`份额（assigned rate）` 在本地侧的用法。⚠️ [票 11](11-task-write-spec.md) 现有的词条建议清单里只有令牌桶 / 本地桶 / 份额 / 租约 / 悬浮量 / 分片 / 规则源，**要补上本票这几个**。
- 留给下游的数值：**`burstSeconds` 的默认值 → 票 07**（规则字段）；**`refresh_interval` 的默认值 → 票 08**——票 04 Q2 已定"续租周期是 SDK / 协议的参数，不是限流语义"，所以**不归规则模型**；首租合批窗口的具体值 → 票 08；本地桶表上限的默认值 → 票 09 的容量账。⚠️ **2026-09-22 [票 08](08-grill-protocol-contract.md) 已落定前两项**：`refresh_interval` 默认 5s、首租合批窗口默认 10ms（均为拍定值，标定复核归票 09）；另经由 08 落定 `ttl`（lease_length）默认 = 2 × `refresh_interval`。
