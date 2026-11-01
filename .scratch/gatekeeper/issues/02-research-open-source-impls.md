# 02 调研：开源实现的可借鉴代码

Type: research
Status: resolved
Blocked by:

## Question

读**真实实现源码**，回答"边界条件与竞态到底是怎么写的"。为 [票 04](04-grill-lease-model.md)（租约口径 / 中心账本）与 [票 05](05-grill-local-bucket-and-admission.md)（本地桶 / 时间源 / 并发）提供落码级参考。

只收做法与代码事实，选型判断留给 04 / 05。每一条都要指到具体类 / 方法 / 行。

1. **Guava `SmoothRateLimiter` / `SmoothBursty`**：惰性补令牌用 double 还是 long、精度与长时间运行后的漂移；`storedPermits` / `nextFreeTicketMicros` 的处理；`tryAcquire` 与 `acquire(timeout)` 的等待语义差异；`SmoothWarmingUp` 的预热（冷却期）怎么算
2. **Bucket4j**：`ConsumptionProbe` 的返回值；`refillGreedy` vs `refillIntervally` 的区别；NanoTime 基准；Redis 后端的 Lua 脚本与原子性（这条补 [research/00](../research/00-lease-reclaim-prior-art.md) 的未核实项）
3. **Redisson `RRateLimiter`**：Lua 脚本逐行解释（`zrangebyscore` 回收过期"券"、`rate` / `interval` 语义、`release` 的实现与上限截断）
4. **Sentinel 集群流控**：`ClusterTokenClient` / `ClusterTokenService` / `DefaultTokenService` / `FlowRequestData` 的字段与流程；客户端与服务端之间每请求的往返内容；超时与失败处理；客户端定时上报的统计是什么、服务端拿它干什么；`LeapArray` 滑动窗口的实现
5. **Doorman（Go 实现）**：lease 的存储结构与过期清理、apportionment 算法、`ReleaseCapacity` 的落码、客户端 `wants` 怎么算出来
6. **对照**：Envoy RLS / envoyproxy-ratelimit 与 Redis 的每次请求往返——它为什么不做本地桶？在什么规模下"每次远程"是可接受的

产出：`research/02-open-source-impls.md`。**逐字引用**关键代码片段并给出文件路径 / 行号 / commit 或 tag。

## Answer

报告：[research/02-open-source-impls.md](../research/02-open-source-impls.md)（逐字引用 + 文件行号）。

**Guava `SmoothRateLimiter`（tag v33.4.0）** → 存量令牌与间隔全是 `double`（3 qps 时 `stableIntervalMicros = 333333.33…`，取整就错），只有 `nextFreeTicketMicros` 是 `long`；补令牌的锚点不是"上次访问时间"而是"下一次允许发放的时刻"（`resync` L386）。→ 本地桶建议用"下一次可发放时刻"而非"欠账计数器"，`tryAcquire` 一次减法就能判。

**Guava 等待语义** → `acquire` 必睡 `microsToWait`；`tryAcquire(permits,timeout)` 先判 `queryEarliestAvailable - timeout <= now`，**不满足立刻 false**，满足则照样睡（L410–428）。→ 我们的 probe 要区分"能不能过"和"过了要不要等"两件事。

**Guava 时间源** → `Stopwatch.createStarted()` → `Ticker.systemTicker()` → `System.nanoTime()`（Ticker.java L53），单调但**跨进程不可比**。→ 本地桶用 nanoTime 无回拨问题；一旦要跟中心对时间就必须换钟，Guava 这里没有可抄的答案。

**Bucket4j 数值手法（`BucketState64BitsInteger.refill` L447–525）** → 令牌是 `long`，除不尽的**余数存回状态**（`roundingError`，纳秒），下次分子里加上：`divided = refillTokens*duration + roundingError; calc = divided/period; roundingError = divided%period;`；时钟不前进直接 `return`；long 溢出才降级 double。→ **整数 + 余数累加器 = 零漂移且无 double**，本地桶直接抄这个，比 Guava 的 double 更适合我们。

**Bucket4j `TimeMeter`** → 显式两套钟：`SYSTEM_NANOTIME`（`isWallClockBased()==false`）与 `SYSTEM_MILLISECONDS`（`true`），javadoc 写明"能否用于分布式"。→ 我们 SDK 的钟接口应当把这个断言变成显式契约，别让调用方用错。

**Bucket4j 分布式优化（`DelayParameters` / `PredictiveCommandExecutor`）** → 本地先花、攒 `postponedToConsumeTokens`，超 `maxUnsynchronizedTokens` 或超 `maxUnsynchronizedTimeoutNanos` 才同步；同步时 `[ConsumeIgnoringRateLimits(欠账), 原命令, CreateSnapshot()]` 一次往返，回来 `postponedToConsumeTokens = 0` 并整份替换本地状态；javadoc 明说会 **temporal over-consumption** 也会 under-consumption。→ 这就是 gatekeeper 的形态，且它把"欠账上限 + 最长不同步时长"两个旋钮摆到 API 上。

**Bucket4j Redis 后端** → `LuaScripts.java` 里只有 4 个 CAS 脚本（`SCRIPT_COMPARE_AND_SWAP_PX` 等），**令牌数学完全不在 Lua 里**：状态是整份二进制 blob，本地算完 CAS 写回，冲突走 `maxRetries` 重试。→ 与 Redisson 相反；我们领配额走"服务端算 + 客户端不重算"更接近 Redisson 而非 bucket4j。

**Redisson `RRateLimiter` 的 Lua（L200–270）** → `ARGV[2]` 是**客户端传的 `System.currentTimeMillis()`**（墙钟，回拨无防护）；`zrangebyscore permitsName 0 now-interval` 回收过期券；`value` 只是缓存，`currentValue+released > rate` 时**改用 ZSET 全表重算** `currentValue = rate - used`；拒绝时返回 `3 + interval - (now - firstValue[2])`。→ 中心账本若同时维护"计数器"和"租约集合"，就要准备好一致性修复分支，否则只留一个真相源。

**Redisson `release`（L282–338）** → 按 score 升序逐张退券，整张够扣就 `zrem`，不够就**以原 score 重新 zadd 回剩余部分**；`value` 回填时 `if newValue > rate then newValue = rate end` 截断；注意退券**不先回收过期券**，与 `tryAcquire` 不对称。→ 归还路径要定义清楚"退的是现存券还是未过期券"。

**Redisson 等待语义（L145–193）** → `acquire()` 无限重试：**定时器等服务端给的 `delay` 到点再发一次 Lua**，不是本地等；`tryAcquire(timeout)` 用本地 `startTime` 算剩余，**`remains < delay` 时不再往返、定时 `remains` 后直接 false**，且 `startTime` 全程传递使超时是端到端预算。→ 零成本可抄：注定失败的同步不要发。

**Sentinel 集群流控协议** → 每请求只交换 `{flowId, count, priority}` → `{status, remainingCount(int), waitInMs(int)}`（`FlowRequestData` / `FlowTokenResponseData`）；**没有客户端 ID、没有租约、没有批次**；客户端**没有**周期性统计上报，服务端只从请求本身累加 `ClusterMetric`，客户端在册与否靠 Netty 连接（`ConnectionManager`）。→ "连接在册"可以替代心跳上报，但代价是中心不知道客户端本地余量。

**Sentinel 判定与阈值** → `nextRemaining = count*connectedCount*exceedCount - latestQps - acquireCount`（`ClusterFlowChecker` L55–82），`getAvg = getSum/intervalInSecond` 是**滑动窗口均值**；`AVG_LOCAL` 口径需要中心知道客户端连接数；拒绝且 `prioritized` 时走 `tryOccupyNext` 借"即将进入"的桶（`- headPass`），返回 `SHOULD_WAIT + waitInMs = 1000/sampleCount`。→ 中心的"配额从哪来"可以只靠请求流推导，不必依赖客户端上报。

**Sentinel 时间源与回拨** → `TimeUtil` 起后台线程：高负载(>800 reads/s)时每 1ms 刷一个 volatile 钟，空闲时直接 `System.currentTimeMillis()`（省 syscall）；`LeapArray.currentWindow` 遇到 `windowStart < old.windowStart()` **不抛错**，返回一个**游离的空桶**（数据丢弃）。→ 回拨的软着陆可以这样写，但"丢弃"要显式设计，不能靠运气。

**Doorman（Go）** → 租约是 `Lease{Expiry time.Time, RefreshInterval, Has float64, Wants float64, Subclients}`——**速率租约不是令牌租约**；`Assign` 里 `lease.Expiry = time.Now().Add(leaseLength)` 每次续租整份重置；`Clean()` 是**惰性全表扫描**，由 `Resource.Decide` 在持锁内每次请求前调用（`resource.go` L106）；`ReleaseCapacity` 只做整资源放弃。客户端 `wants` 由应用 `Ask()` 声明而非观测；RPC 失败时才 `res.lease = nil` 并向 channel 推 `0.0`（唯一一处"到期归零"落码）。→ 我们的 `Clean` 要现在决定"定时器 vs 搭车全扫"；客户端自报 `has` 服务端只记日志不采信，这条值得抄。默认参数 `lease_length: 15` / `refresh_interval: 5`（秒）。

**Envoy RLS（计数器式）** → 官方文档明文给"本地桶 + 中心"定了性：*"a local token bucket rate limit can absorb very large bursts... the rate limit is applied in two stages. The initial coarse grained limiting is performed by the token bucket limit before a fine grained global limit finishes the job."*；`envoyproxy/ratelimit` 每请求 = 一次 Redis pipeline（`INCRBY` + `EXPIRE`），key 里嵌窗口起点 `(now/divider)*divider`（固定窗口，墙钟秒）；它的"本地"只是 **freecache 超限负缓存**（超了就整窗口不再问 Redis，保守但会欠发），退款脚本 `math.floor(math.max(0, cur-n))` + "key 不存在不创建"两条纪律值得抄。

**Envoy RLQS（配额式）** → 协议里就是我们的形态：流式 watch，客户端周期上报 `BucketQuotaUsage{bucket_id, time_elapsed, num_requests_allowed, num_requests_denied}`，服务端下发 `QuotaAssignmentAction{assignment_time_to_live(Duration), rate_limit_strategy}`；**策略没变只延 `ttl`、视为 assignment 未变；策略变了才"立即结束 + 上报用量 + 换新"**；`ttl=0` 可让客户端提前降级（server restart 场景）；`AbandonAction` 由"太久没收到上报"触发，让客户端**弃桶**（不是退零头）。→ 租约续期"延 TTL 不重置配额"比 Doorman 的无脑重置更省。**但注意：这纠正了 [票 00](../research/00-lease-reclaim-prior-art.md) 的"服务端主动召回在一手源码里找不到任何实现"——协议里有 `AbandonAction`，只是开源没有服务端实现；语义仍是"弃桶"故与票 00 结论不矛盾，但措辞需放宽。**

**未核实项**：报告末尾列了 12 条。最要紧的三条：① 除 bucket4j 外**各仓 HEAD commit SHA 未取到**（GitHub API 限流），行号对应 2026-09-22 的 master/main 快照，会漂；② Sentinel 的 20ms 请求超时是**从 Netty `await(long)` 语义推断**的，未读 Netty 源码；③ **全部为静态源码阅读，未跑任何一个实现**，所有"每请求往返一次"之类的判断都不是实测。

> ⚠️ **2026-09-22 由 [票 04](04-grill-lease-model.md) 收口反转**：票 04 选了**速率租约**，所以本票带回的 Bucket4j 那两个旋钮（`maxUnsynchronizedTokens` / `maxUnsynchronizedTimeoutNanos`）**不是本设计的机制**——速率口径下本地桶按份额自补、悬浮量由 `Σ份额 ≤ R` 构造封顶，不设"本地最多先花多少"的上限。票 05 的 Q7 不再在这两个旋钮上裁。本票其余落码级参考（Guava 的 `nextFreeTicketMicros` / 等待语义 / `doSetRate` 缩放、Doorman 的 `Clean` 搭车与 `has` 只对账、Redisson 的单一真相源纪律、RLQS 的续租与上报字段）**全部仍被票 04 采用**。
