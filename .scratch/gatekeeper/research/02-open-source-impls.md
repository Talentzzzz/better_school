# 02 开源实现的可借鉴代码（落码级）

_调研日期 2026-09-22。除 Guava 用 tag `v33.4.0` 外，其余全部为当日抓取的默认分支（master / main）源码。来源一律是 GitHub raw 原始文件，不是博客二手解读。_

## 结论速览

| 实现 | 令牌/额度表示 | 时间源 | 惰性补令牌的数值手法 | 等待语义 | 客户端↔中心 |
|---|---|---|---|---|---|
| **Guava** `SmoothRateLimiter` | `double storedPermits` / `double maxPermits` / `double stableIntervalMicros`，只有 `nextFreeTicketMicros` 是 `long` | `System.nanoTime()`（经 `Ticker.systemTicker()`）单调钟 | 保留 double，不设取整补偿 | `acquire` 必睡；`tryAcquire(permits,timeout)` 先判 `queryEarliestAvailable - timeout <= now`，**不满足立即 false** | —（纯本地） |
| **Bucket4j** `BucketState64BitsInteger` | **`long` 令牌 + 独立的 `roundingError`（纳秒）累加器** | `TimeMeter` 二选一：`SYSTEM_NANOTIME`（本地）/ `SYSTEM_MILLISECONDS`（分布式，`isWallClockBased()==true`） | 整数除法余数**存回状态**，跨次累加回收 | 返回 `nanosToWaitForRefill` / `nanosToWaitForReset`，不阻塞 | Redis 后端：**整份 state 二进制 + Lua CAS**（令牌数学不在 Lua 里）；另有 Delay/Predictive/Batching 三类"本地先花"优化 |
| **Redisson** `RRateLimiter` | `rate`/`interval` 整数 + `permits` ZSET（score = 毫秒时间戳） | **墙钟** `System.currentTimeMillis()`（由客户端传入 Lua 当 ARGV） | 无桶曲线，纯"滑动窗口内计数" `zrangebyscore 0 (now-interval)` 回收 | `acquire()` 无限重试（定时器等 `delay` 再发）；`tryAcquire(timeout)` 客户端本地算剩余时间 | 每次 `tryAcquire` 一次 EVAL 往返 |
| **Sentinel 集群流控** | 服务端滑动窗口计数 `long`（`ClusterMetricLeapArray`），非令牌 | 墙钟，但**故意缓存**：`TimeUtil` 后台线程每 1ms 刷新一个 volatile `currentTimeMillis` | 无补令牌；`getAvg = getSum / intervalInSecond` 滑动窗口均值 | 服务端返 `waitInMs` → `SHOULD_WAIT`；客户端自己不阻塞 | 每请求一次 gRPC/Netty 往返，`{flowId, count, priority}` → `{remainingCount, waitInMs}` |
| **Doorman** | **`float64` 速率**（`Has` / `Wants`，单位 req/s），**不是令牌** | 墙钟 `time.Now()` | 无补令牌；租约到期即失效 | 无等待，只有乐观/悲观/安全三档降级 | 每 `refresh_interval` 一次 `GetCapacity`，`{has, wants, subclients}` → `{capacity, expiry_time, refresh_interval}` |
| **Envoy RLS（计数器式）** | Redis `INCRBY` 固定窗口计数 | 墙钟秒 `time.Now().Unix()` | 无；key 里嵌窗口起点 `(now/divider)*divider` | 无等待，`DurationUntilReset` 回给调用方 | **每请求**一次 Redis pipeline RTT |
| **Envoy RLQS（配额式）** | 未落码（服务端无开源实现），协议里是"assignment + TTL" | — | — | — | **流式 watch + 周期用量上报**（`num_requests_allowed` / `num_requests_denied` / `time_elapsed`），服务端可 `AbandonAction` 召回 |

---

## 1. Guava `SmoothRateLimiter` / `SmoothBursty`

**来源**：`https://raw.githubusercontent.com/google/guava/v33.4.0/guava/src/com/google/common/util/concurrent/SmoothRateLimiter.java`（tag `v33.4.0`）
配套：`guava/src/com/google/common/util/concurrent/RateLimiter.java`、`guava/src/com/google/common/base/Ticker.java`、`.../base/Stopwatch.java`

### 1.1 double 还是 long

三个核心字段是 **double**，只有一个时间刻度是 long（`SmoothRateLimiter.java` L314–330）：

```java
  /** The currently stored permits. */
  double storedPermits;

  /** The maximum number of stored permits. */
  double maxPermits;

  /**
   * The interval between two unit requests, at our stable rate. E.g., a stable rate of 5 permits
   * per second has a stable interval of 200ms.
   */
  double stableIntervalMicros;

  /**
   * The time when the next request (no matter its size) will be granted. After granting a request,
   * this is pushed further in the future. Large requests push this further than small requests.
   */
  private long nextFreeTicketMicros = 0L; // could be either in the past or future
```

用 double 是**被迫的**：速率不整除时每张令牌的间隔不是整数微秒。`SmoothRateLimiter.doSetRate`（L336–342）：

```java
  final void doSetRate(double permitsPerSecond, long nowMicros) {
    resync(nowMicros);
    double stableIntervalMicros = SECONDS.toMicros(1L) / permitsPerSecond;
    this.stableIntervalMicros = stableIntervalMicros;
    doSetRate(permitsPerSecond, stableIntervalMicros);
  }
```

3 qps → `stableIntervalMicros == 333333.333...`。这个 double 值同时被拿去积分（预热曲线）和拿去乘令牌数，所以没法取整。

### 1.2 惰性补令牌：`resync` 是唯一的入口

L385–393：

```java
  /** Updates {@code storedPermits} and {@code nextFreeTicketMicros} based on the current time. */
  void resync(long nowMicros) {
    // if nextFreeTicket is in the past, resync to now
    if (nowMicros > nextFreeTicketMicros) {
      double newPermits = (nowMicros - nextFreeTicketMicros) / coolDownIntervalMicros();
      storedPermits = min(maxPermits, storedPermits + newPermits);
      nextFreeTicketMicros = nowMicros;
    }
  }
```

三个可借鉴点：

1. **补令牌的"起点"不是上次访问时间，而是 `nextFreeTicketMicros`**——一个"下一次允许发放的时刻"。欠账被推进到未来，`resync` 时只有"未来时刻已经过去"的那部分空闲才折算成令牌。这就是类注释 L129–141 说的：*"it means that the RateLimiter doesn't remember the time of the **last** request, but it remembers the (expected) time of the **next** request"*。
2. **`min(maxPermits, ...)` 截断**——本地桶上界靠这一行兜住，而不是靠调用方。
3. **`newPermits` 是连续的 double，没有取整补偿**。Guava 的漂移防线是"记账而不是累加"：每次消费时从 `storedPermits` 里**精确扣掉实际花掉的量**，并把 `nextFreeTicketMicros` 前推，所以误差不会被反复累乘。反过来说，Guava 没有解决"每张券被截断成整数微秒"的那点残差——见 1.6。

### 1.3 `coolDownIntervalMicros` 是"补令牌速率"的抽象

`SmoothBursty`（L308–311）与 `SmoothWarmingUp`（L267–270）给出两种：

```java
    @Override
    double coolDownIntervalMicros() {
      return stableIntervalMicros;
    }
```
```java
    @Override
    double coolDownIntervalMicros() {
      return warmupPeriodMicros / maxPermits;
    }
```

即：突发桶按稳定间隔补；预热桶按 `warmupPeriod / maxPermits` 补——**预热期越长，补得越慢**，这就是"冷却"的实现方式。

### 1.4 `SmoothBursty`：存的令牌免费

L279–312。`maxPermits = maxBurstSeconds * permitsPerSecond`（L291），默认 `maxBurstSeconds = 1.0`（见 `RateLimiter.create(double)`，「a burst of 1 second worth of permits」）：

```java
    @Override
    void doSetRate(double permitsPerSecond, double stableIntervalMicros) {
      double oldMaxPermits = this.maxPermits;
      maxPermits = maxBurstSeconds * permitsPerSecond;
      ...
    }

    @Override
    long storedPermitsToWaitTime(double storedPermits, double permitsToTake) {
      return 0L;
    }
```

`storedPermitsToWaitTime` 返回 `0L` = **花存下来的令牌不等**，这正是"突发"的语义。改速率时按比例缩放存量（L296–300）而不是清零：

```java
        storedPermits =
            (oldMaxPermits == 0.0)
                ? 0.0 // initial state
                : storedPermits * maxPermits / oldMaxPermits;
```

### 1.5 记账主循环：`reserveEarliestAvailable`

L356–369：

```java
  final long reserveEarliestAvailable(int requiredPermits, long nowMicros) {
    resync(nowMicros);
    long returnValue = nextFreeTicketMicros;
    double storedPermitsToSpend = min(requiredPermits, this.storedPermits);
    double freshPermits = requiredPermits - storedPermitsToSpend;
    long waitMicros =
        storedPermitsToWaitTime(this.storedPermits, storedPermitsToSpend)
            + (long) (freshPermits * stableIntervalMicros);

    this.nextFreeTicketMicros = LongMath.saturatedAdd(nextFreeTicketMicros, waitMicros);
    this.storedPermits -= storedPermitsToSpend;
    return returnValue;
  }
```

- **返回值是"上一次"的 `nextFreeTicketMicros`（修改前的）**，也就是"你要等到的时刻"。所以调用方拿到的是**历史欠账**，本次请求自己要延后到什么时候是另一回事。
- `freshPermits * stableIntervalMicros` 强转 `(long)` 是**向零截断**；`saturatedAdd` 防 long 溢出。这里没有取整补偿——Guava 接受这一次截断误差，因为它不进入 `storedPermits`。

### 1.6 等待语义：`acquire` vs `tryAcquire(timeout)`

`RateLimiter.java` L304–308：

```java
  public double acquire(int permits) {
    long microsToWait = reserve(permits);
    stopwatch.sleepMicrosUninterruptibly(microsToWait);
    return 1.0 * microsToWait / SECONDS.toMicros(1L);
  }
```

L410–428：

```java
  public boolean tryAcquire(int permits, long timeout, TimeUnit unit) {
    long timeoutMicros = max(unit.toMicros(timeout), 0);
    checkPermits(permits);
    long microsToWait;
    synchronized (mutex()) {
      long nowMicros = stopwatch.readMicros();
      if (!canAcquire(nowMicros, timeoutMicros)) {
        return false;
      } else {
        microsToWait = reserveAndGetWaitLength(permits, nowMicros);
      }
    }
    stopwatch.sleepMicrosUninterruptibly(microsToWait);
    return true;
  }

  private boolean canAcquire(long nowMicros, long timeoutMicros) {
    return queryEarliestAvailable(nowMicros) - timeoutMicros <= nowMicros;
  }
```

- **`tryAcquire` 不是"不等待"**：只有 `canAcquire` 判假才立即 `false`；判真之后它**照样睡** `microsToWait`。
- `tryAcquire(int permits)`（L366–368）等价 `tryAcquire(permits, 0, MICROSECONDS)`，即 timeout=0 → 条件退化成 `nextFreeTicketMicros <= now`，这才是真正的"立刻拿"。
- `reserveAndGetWaitLength`（L435–438）才是把"时刻"换成"时长"的地方（`max(momentAvailable - nowMicros, 0)`）。

并发（L316–321、L462–492）：

```java
  final long reserve(int permits) {
    checkPermits(permits);
    synchronized (mutex()) {
      return reserveAndGetWaitLength(permits, stopwatch.readMicros());
    }
  }
```

睡在锁外。`SleepingStopwatch` 里作者自己留了疑问（L466–470）：

```java
    /*
     * We always hold the mutex when calling this. TODO(cpovirk): Is that important? Perhaps we need
     * to guarantee that each call to reserveEarliestAvailable, etc. sees a value >= the previous?
     * Also, is it OK that we don't hold the mutex when sleeping?
     */
```

### 1.7 时间源：单调钟，不是墙钟

`RateLimiter.java` L475–491：

```java
    public static SleepingStopwatch createFromSystemTimer() {
      return new SleepingStopwatch() {
        final Stopwatch stopwatch = Stopwatch.createStarted();
        ...
```

`Stopwatch.java` L143：`this.ticker = Ticker.systemTicker();`
`Ticker.java` L44–53：

```java
  public static Ticker systemTicker() {
    ...
          return System.nanoTime();
```

→ **Guava 全用 `System.nanoTime()`，回拨免疫**。代价是**这个值不可跨进程比较**，所以 Guava 的桶结构天生只适用于进程内。

### 1.8 `SmoothWarmingUp` 的预热算法

L226–242：

```java
    @Override
    void doSetRate(double permitsPerSecond, double stableIntervalMicros) {
      double oldMaxPermits = maxPermits;
      double coldIntervalMicros = stableIntervalMicros * coldFactor;
      thresholdPermits = 0.5 * warmupPeriodMicros / stableIntervalMicros;
      maxPermits =
          thresholdPermits + 2.0 * warmupPeriodMicros / (stableIntervalMicros + coldIntervalMicros);
      slope = (coldIntervalMicros - stableIntervalMicros) / (maxPermits - thresholdPermits);
      if (oldMaxPermits == Double.POSITIVE_INFINITY) {
        // if we don't special-case this, we would get storedPermits == NaN, below
        storedPermits = 0.0;
      } else {
        storedPermits =
            (oldMaxPermits == 0.0)
                ? maxPermits // initial state is cold
                : storedPermits * maxPermits / oldMaxPermits;
      }
    }
```

- `coldFactor` 默认 3.0（L198 `create(permitsPerSecond, warmupPeriod, unit, 3.0, ...)`）。
- 三个公式都能从类注释 L144–206 的梯形图推出来；注释里明确说 `thresholdPermits` 到 0 的时间是 `warmupPeriod/2` 是为了兼容 coldFactor 硬编码 3 的老实现。
- **`oldMaxPermits == 0.0` 时 `storedPermits = maxPermits`（初始为冷）**；`oldMaxPermits == +∞` 单列一支避免 NaN。这两支是"改速率"这个操作里最容易写漏的边界。
- 花存量令牌的时间 L245–261 用梯形积分：

```java
      if (availablePermitsAboveThreshold > 0.0) {
        double permitsAboveThresholdToTake = min(availablePermitsAboveThreshold, permitsToTake);
        double length =
            permitsToTime(availablePermitsAboveThreshold)
                + permitsToTime(availablePermitsAboveThreshold - permitsAboveThresholdToTake);
        micros = (long) (permitsAboveThresholdToTake * length / 2.0);
        permitsToTake -= permitsAboveThresholdToTake;
      }
      // measuring the integral on the left part of the function (the horizontal line)
      micros += (long) (stableIntervalMicros * permitsToTake);
```

**对我们的启示**：`nextFreeTicketMicros`（"下一次可发放时刻"，可正可负）比"上次访问时间 + 欠账计数器"更耐用——`tryAcquire` 只要一次减法就能判断，不需要试算。本地桶要跨进程/跨重启就得放弃 `nanoTime`，那就得自己承担回拨；Guava 这里没有可抄的答案。

> ⚠️ **2026-09-22 注记（[票 05](../issues/05-grill-local-bucket-and-admission.md) Q3 / Q6，两条都推翻）**：① "跨进程就得放弃 `nanoTime`"**不适用于本设计**——本地桶**不跨进程**（每 SDK 实例一个桶），跨进程的那部分（租约）在协议里只传**时长**，所以 `nanoTime` 差值够用、**不必放弃单调钟**。② `nextFreeTicketMicros` 这个锚点**不采纳**：它是为"欠账可以推到未来"设计的，而我们的桶**不预支**（拒了就是拒了），只记"上次补充时刻 + 余数"。

---

## 2. Bucket4j

**来源**：`bucket4j/bucket4j`，master，commit `5f3336b92feed0e2b08e8c7212a4448387cd123b`（当日 API 探到的 HEAD）。
主要文件：`bucket4j-core/src/main/java/io/github/bucket4j/TimeMeter.java`、`.../BucketState64BitsInteger.java`、`.../ConsumptionProbe.java`、`.../MathType.java`、`.../Refill.java`、`.../Bandwidth.java`；`bucket4j-redis/bucket4j-jedis/src/main/java/io/github/bucket4j/redis/jedis/cas/JedisBasedProxyManager.java`；`bucket4j-redis/bucket4j-redis-common/src/main/java/io/github/bucket4j/redis/consts/LuaScripts.java`；`bucket4j-core/src/main/java/io/github/bucket4j/distributed/proxy/...`。

### 2.1 时间源：`TimeMeter` 明确区分"本地钟"与"分布式钟"

`TimeMeter.java` L22–74：

```java
    /**
     * Returns {@code true} if implementation of clock behaves the similar way as {@link System#currentTimeMillis()},
     * in other words if implementation can be used in distributed scenarios
     * and returns approximately the same time in all machines in cluster
     *
     * @return {@code true} if implementation can be used as wall clock
     */
    boolean isWallClockBased();

    /**
     * The implementation of {@link TimeMeter} which works around {@link java.lang.System#nanoTime}
     */
    TimeMeter SYSTEM_NANOTIME = new TimeMeter() {
        @Override
        public long currentTimeNanos() {
            return System.nanoTime();
        }

        @Override
        public boolean isWallClockBased() {
            return false;
        }
        ...
    };
    ...
    TimeMeter SYSTEM_MILLISECONDS = new TimeMeter() {
        @Override
        public long currentTimeNanos() {
            long nowMillis = System.currentTimeMillis();
            return TimeUnit.MILLISECONDS.toNanos(nowMillis);
        }

        @Override
        public boolean isWallClockBased() {
            return true;
        }
        ...
    };
```

这是全套实现里对"本地桶 vs 中心配额"这个问题**唯一显式表态**的地方：单进程用 `nanoTime`，一旦进分布式就必须换墙钟，并且用 `isWallClockBased()` 把这个要求变成类型系统里可断言的东西。注意它**没有**提供任何回拨保护。

> ⚠️ **2026-09-22 注记（[票 05](../issues/05-grill-local-bucket-and-admission.md) Q3）**："一旦进分布式就必须换墙钟"**只对"要跨机比较时间戳"的实现成立**——Bucket4j 把**整份桶状态**存进 Redis（多进程共享同一个桶），它的时间轴因此必须跨机可比。本设计的本地桶**不同步状态**、协议里只传**时长**，两端各用各自的单调钟 ⇒ **不换墙钟**，回拨照 §2.2 的 `max(0, now − last)` 防一行。这条仍是 Bucket4j 的准确描述，但**不是本设计的约束**。

### 2.2 补令牌的数值处理：**整数 + 余数累加器**

`BucketState64BitsInteger.refill`（L447–525）。这是本票最值得抄的一段：

```java
    private void refill(int bandwidthIndex, Bandwidth bandwidth, long currentTimeNanos) {
        long previousRefillNanos = getLastRefillTimeNanos(bandwidthIndex);
        if (currentTimeNanos <= previousRefillNanos) {
            return;
        }

        if (bandwidth.isRefillIntervally()) {
            long incompleteIntervalCorrection = (currentTimeNanos - previousRefillNanos) % bandwidth.getRefillPeriodNanos();
            currentTimeNanos -= incompleteIntervalCorrection;
        }
        if (currentTimeNanos <= previousRefillNanos) {
            return;
        } else {
            setLastRefillTimeNanos(bandwidthIndex, currentTimeNanos);
        }
```

三个边界一次写全：**时钟回拨/不前进 → 直接 return（`currentTimeNanos <= previousRefillNanos`）**；intervally 模式把时间**向下取整到整周期边界**；取整后如果又缩回过去，再 return 一次。

接着（L473–511）：

```java
        long durationSinceLastRefillNanos = currentTimeNanos - previousRefillNanos;
        long newSize = currentSize;

        if (durationSinceLastRefillNanos > refillPeriodNanos) {
            long elapsedPeriods = durationSinceLastRefillNanos / refillPeriodNanos;
            long calculatedRefill = elapsedPeriods * refillTokens;
            newSize += calculatedRefill;
            if (newSize > capacity) {
                resetBandwidth(bandwidthIndex, capacity);
                return;
            }
            if (newSize < currentSize) {
                // arithmetic overflow happens. This mean that tokens reached Long.MAX_VALUE tokens.
                // just reset bandwidth state
                resetBandwidth(bandwidthIndex, capacity);
                return;
            }
            durationSinceLastRefillNanos %= refillPeriodNanos;
        }


        long roundingError = getRoundingError(bandwidthIndex);
        long dividedWithoutError = multiplyExactOrReturnMaxValue(refillTokens, durationSinceLastRefillNanos);
        long divided = dividedWithoutError + roundingError;
        if (divided < 0 || dividedWithoutError == Long.MAX_VALUE) {
            // arithmetic overflow happens.
            // there is no sense to stay in integer arithmetic when having deal with so big numbers
            long calculatedRefill = (long) ((double) durationSinceLastRefillNanos / (double) refillPeriodNanos * (double) refillTokens);
            newSize += calculatedRefill;
            roundingError = 0;
        } else {
            long calculatedRefill = divided / refillPeriodNanos;
            if (calculatedRefill == 0) {
                roundingError = divided;
            } else {
                newSize += calculatedRefill;
                roundingError = divided % refillPeriodNanos;
            }
        }
        ...
        setCurrentSize(bandwidthIndex, newSize);
        setRoundingError(bandwidthIndex, roundingError);
    }
```

要点：

- **令牌数是 `long`**（`MathType.INTEGER_64_BITS`，`MathType.java` 里现在只剩这一个枚举值，javadoc：*"The default math precision that uses integer arithmetic with 64 bits numbers."*；`BucketState64BitsInteger.getMathType()` L617–619 返回它）。
- **除不尽的余数不丢，存回状态**（`setRoundingError`），下次分子里加上。`divided = refillTokens * duration + roundingError; calculatedRefill = divided / refillPeriodNanos; roundingError = divided % refillPeriodNanos;` —— 这就在整数域里拿回了浮点的精度，而且**长期运行零漂移**（余数守恒）。这正回答本票问的"double vs long 的漂移"：bucket4j 的答案是"long + 一个余数字段"。
- `calculatedRefill == 0` 时**显式保留整份 `divided` 到余数**（`roundingError = divided`），否则低速率下会被反复截断成 0。
- **溢出两条路**：乘法用 `multiplyExactOrReturnMaxValue`；一旦 `dividedWithoutError == Long.MAX_VALUE` 或 `divided < 0`（long 溢出成负），才**降级到 double 一次性算完**并清空余数——"there is no sense to stay in integer arithmetic when having deal with so big numbers"。
- **`newSize < currentSize` 也当溢出处理**，直接 `resetBandwidth(..., capacity)`（灌满），不试图挽救。

### 2.3 `refillIntervally` vs greedy：差异落在"时间对齐"和"等待时间算法"

`refill` 里的 `incompleteIntervalCorrection`（L453–456）只在 `isRefillIntervally()` 下做：把 `currentTimeNanos` 向下对齐到 `refillPeriodNanos` 的整倍。所以 interval 模式是**固定窗口整周期补给**，greedy 模式是**连续补给**。

等待时间算法分两支（L553–566）：

```java
    private long calculateDelayNanosAfterWillBePossibleToConsumeForGreedyBandwidth(int bandwidthIndex, Bandwidth bandwidth, long deficit) {
        long refillPeriodNanos = bandwidth.getRefillPeriodNanos();
        long refillPeriodTokens = bandwidth.getRefillTokens();
        long divided = multiplyExactOrReturnMaxValue(refillPeriodNanos, deficit);
        if (divided == Long.MAX_VALUE) {
            // math overflow happen.
            // there is no sense to stay in integer arithmetic when having deal with so big numbers
            return (long)((double) deficit / (double)refillPeriodTokens * (double)refillPeriodNanos);
        } else {
            long correctionForPartiallyRefilledToken = getRoundingError(bandwidthIndex);
            divided -= correctionForPartiallyRefilledToken;
            return divided / refillPeriodTokens;
        }
    }
```

注意 `correctionForPartiallyRefilledToken`：**算等待时间时要把已经攒着的余数减掉**——否则会系统性多等。interval 分支（L568+）先用 `timeOfNextRefillNanos = previousRefillNanos + refillPeriodNanos` 算到下一个整周期，`if (deficit <= refillTokens) return waitForNextRefillNanos;`。

历史 API：`Refill.java` L31 javadoc *"This class is deprecated, you should use {@link Bandwidth#builder()}"*；L65–71 里 `of()` 与 `smooth()` 都直接调 `greedy(tokens, period)`；`intervally`（L90）与 `intervallyAligned`（L102）仍在。`Bandwidth.java` L83 默认走 `Refill.greedy(capacity, period)`。

### 2.4 `ConsumptionProbe` 的返回值

`ConsumptionProbe.java` L45–165。四个字段：

```java
    private final boolean consumed;
    private final long remainingTokens;
    private final long nanosToWaitForRefill;
    private final long nanosToWaitForReset;
```

工厂方法（L116–129）：

```java
    public static ConsumptionProbe consumed(long remainingTokens, long nanosToWaitForReset) {
        return new ConsumptionProbe(true, remainingTokens, 0, nanosToWaitForReset);
    }

    public static ConsumptionProbe rejected(long remainingTokens, long nanosToWaitForRefill, long nanosToWaitForReset) {
        return new ConsumptionProbe(false, remainingTokens, nanosToWaitForRefill, nanosToWaitForReset);
    }

    private ConsumptionProbe(boolean consumed, long remainingTokens, long nanosToWaitForRefill, long nanosToWaitForReset) {
        this.consumed = consumed;
        this.remainingTokens = Math.max(0L, remainingTokens);
```

- **`consumed == true` 时 `nanosToWaitForRefill` 恒为 0**（不是"还剩多少时间补满"，那是 `nanosToWaitForReset`）。
- 构造器里 `Math.max(0L, remainingTokens)` —— 拒绝时也不能回负数。
- `getNanosToWaitForReset()` javadoc（L158–159）：*"Time in nanos which need to wait until bucket will be fully refilled to its maximum"*；`calculateFullRefillingTime`（L406–418）里若已满直接 `return 0L`。

这三种量（剩余 / 补到能过 / 补满）覆盖了"拒绝时该等多久"和"配额何时彻底归零"两个问题，值得直接照搬进我们的 probe 结构。

### 2.5 Redis 后端：**令牌数学不在 Lua 里**，只有 CAS

`LuaScripts.java` 一共 4 个脚本，全部是 CAS，没有一条令牌运算：

```java
    public final static String SCRIPT_COMPARE_AND_SWAP_PX =
            "if redis.call('get', KEYS[1]) == ARGV[1] then " +
                "redis.call('psetex', KEYS[1], ARGV[3], ARGV[2]); " +
                "return 1; " +
            "else " +
                "return 0; " +
            "end";
```

`JedisBasedProxyManager.compareAndSwap`（L202–232）按"有没有原值 / 有没有 TTL"四选一分派：

```java
        long ttlMillis = expirationStrategy.calculateTimeToLiveMillis(newState, currentTimeNanos());
        if (ttlMillis > 0) {
            if (originalData == null) {
                // nulls are prohibited as values, so "replace" must not be used in such cases
                byte[][] keysAndArgs = {key, newData, encodeLong(ttlMillis)};
                Object res = redisApi.eval(LuaScripts.SCRIPT_SET_NX_PX.getBytes(StandardCharsets.UTF_8), 1, keysAndArgs);
                return res != null && !res.equals(0L);
            } else {
                byte[][] keysAndArgs = {key, originalData, newData, encodeLong(ttlMillis)};
                Object res = redisApi.eval(LuaScripts.SCRIPT_COMPARE_AND_SWAP_PX.getBytes(StandardCharsets.UTF_8), 1, keysAndArgs);
                return res != null && !res.equals(0L);
            }
        } else {
            ...
```

**桶状态是一整份序列化二进制 blob，`get` 回来在本地算完再 CAS 写回。** 与 Redisson 的路线完全相反。代价是冲突要重试：`AbstractCompareAndSwapBasedProxyManager` L57–89：

```java
        int attempt = 0;
        int maxAttempts = retryStrategy.isPresent() ? Integer.MAX_VALUE : maxRetries.orElse(Integer.MAX_VALUE);
        ...
        while (attempt < maxAttempts) {
            ...
            if (retryStrategy.isPresent()) {
                ...
                RetryDecision retryDecision = retryStrategy.get().shouldRetry(metadata);
                if (!retryDecision.shouldRetry()) {
                    throw BucketExceptions.maxRetriesExceeded(attempt + 1);
                }
                sleepBeforeRetry(timeout, retryDecision);
            }
            attempt++;
        }
        throw BucketExceptions.maxRetriesExceeded(maxAttempts);
```

**注意**：`（票 00 里的未核实项 3 —— "Bucket4j 每次都远程"依据的是 API 文档而非源码"）现在坐实了，并且比"每次都远程"更复杂——见下。**

### 2.6 意外收获：bucket4j 的"本地先花 + 中心批量同步"三件套

这是全套实现里跟 gatekeeper 形态**最接近**的部分。`bucket4j-core/src/main/java/io/github/bucket4j/distributed/proxy/optimization/` 下有四个优化：

**`DelayParameters.java` L33–56** —— 两个旋钮：

```java
public class DelayParameters {

    public final long maxUnsynchronizedTokens;
    public final long maxUnsynchronizedTimeoutNanos;

    /**
     * Creates the new instance of {@link DelayParameters}
     *
     * @param maxUnsynchronizedTokens threshold that describes how many tokens can be consumed locally without synchronization with external storage. Must be a positive number.
     * @param maxTimeoutBetweenSynchronization threshold that describes how long bucket proxy can act locally without synchronization with external storage. Must be a positive duration.
     */
```

**`PredictiveOptimization.java` L35–45** —— 作者把代价写在了 javadoc 里：

```java
/**
 * Optimization that can serve requests locally without synchronization with external storage until thresholds are not violated.
 * This optimization is based on top of {@link BatchingOptimization} and {@link DelayOptimization},
 * and in additionally this optimization tries to predict aggregated consumption rate in whole cluster in order to reduce the risk of overconsumption that caused by {@link DelayOptimization}.
 *
 * <p>Usage of this optimization can lead to temporal over-consumption because the synchronization with external storage is performed periodically when thresholds are violated,
 * as well as under-consumption in case of the wrong prediction of aggregated consumption rate.
 */
```

**`PredictiveCommandExecutor.java`** —— 真正的主循环：

L107–152 本地快路径（`tryConsumeLocally`）：

```java
    private <T> CommandResult<T> tryConsumeLocally(RemoteCommand<T> command) {
        long currentTimeNanos = timeMeter.currentTimeNanos();
        if (isNeedToExecuteRemoteImmediately(command, currentTimeNanos)) {
            return null;
        }


        long predictedConsumptionSinceLastSync = sampling.predictedConsumptionByOthersSinceLastSync(currentTimeNanos);
        if (predictedConsumptionSinceLastSync == Long.MAX_VALUE) {
            return null;
        }

        long predictedConsumptionByOthersSinceLastLocalCall = predictedConsumptionSinceLastSync - speculativelyConsumedByPredictionTokens;
        if (predictedConsumptionByOthersSinceLastLocalCall < 0) {
            predictedConsumptionByOthersSinceLastLocalCall = 0;
        }
        ...
```

L70–86 快路径命中就不发远程：

```java
    public <T> CommandResult<T> execute(RemoteCommand<T> command) {
        CommandResult<T> result = tryConsumeLocally(command);
        if (result != null) {
            // remote call is not needed
            listener.incrementSkipCount(1);
            return result;
        }
```

L187–193 **发送时带上"欠了多少"和"给我个快照"**：

```java
    private <T> MultiCommand prepareRemoteCommand(RemoteCommand<T> command) {
        List<RemoteCommand<?>> commands = new ArrayList<>(3);
        commands.add(new ConsumeIgnoringRateLimitsCommand(this.postponedToConsumeTokens));
        commands.add(command);
        commands.add(new CreateSnapshotCommand());
        return new MultiCommand(commands);
    }
```

L195–208 回来时**归零欠账并整体替换本地状态**：

```java
    private void rememberRemoteCommandResult(MultiCommand multiCommand, CommandResult<MultiResult> commandResult) {
        postponedToConsumeTokens = 0;
        speculativelyConsumedByPredictionTokens = 0;
        ...
        state = (RemoteBucketState) snapshotResult.getData();
        long now = timeMeter.currentTimeNanos();
        long consumedTokens = multiCommand.getConsumedTokens(commandResult.getData());
        sampling.rememberRemoteCommandResult(consumedTokens, state.getRemoteStat().getConsumedTokens(), now);
    }
```

L154–160 本地额度上限就用 `maxUnsynchronizedTokens` 卡：

```java
    private boolean isLocalExecutionResultSatisfiesThreshold(long locallyConsumedTokens) {
        if (locallyConsumedTokens == Long.MAX_VALUE || postponedToConsumeTokens + locallyConsumedTokens < 0) {
            // math overflow
            return false;
        }
        return postponedToConsumeTokens + locallyConsumedTokens <= delayParameters.maxUnsynchronizedTokens;
    }
```

`PredictionParameters.java` L32–37：`DEFAULT_MIN_SAMPLES = 2; DEFAULT_MAX_SAMPLES = 10;` + `sampleMaxAgeNanos`——预测"别人消耗了多少"用**最近 2–10 个样本**做滑动估计。

**关键结构性事实**：bucket4j 这版是"**上报欠账**"模型（客户端说"我多花了 N 个，扣我"），并借 `CreateSnapshotCommand` 把服务端权威状态整份拉回覆盖本地。**不是**"领一批令牌 + TTL"。

**对我们的启示**：`maxUnsynchronizedTokens` + `maxUnsynchronizedTimeoutNanos` 这一对（欠账上限 + 最大不同步时长）是"本地桶"在分布式下必须暴露的两个旋钮，bucket4j 把它摆在 builder 参数里而不是藏进实现；而且它明确写了会**超发**（temporal over-consumption），把选择权交给调用方。整数 + 余数累加器的 `refill` 是本地桶该抄的数值手法（零漂移、无 double）。

> ⚠️ **2026-09-22 注记（[票 04](../issues/04-grill-lease-model.md) / [票 05](../issues/05-grill-local-bucket-and-admission.md) Q7）**：第一句**已作废**——速率租约口径下没有"欠账 / 不同步发放量"这个量，这一对旋钮**不在本设计的机制里、不外露**。**后半句仍然有效且已被采纳**：整数 + 余数累加器的 `refill` 就是本设计本地桶的数值手法（票 05 Q6 追加"令牌单位 = 10⁶ 微令牌"以容纳实数份额）。

---

## 3. Redisson `RRateLimiter`

**来源**：`redisson/redisson` master，`redisson/src/main/java/org/redisson/RedissonRateLimiter.java`（当日抓取，全文 27,397 字节 470+ 行）；`redisson/src/main/java/org/redisson/api/RateType.java`。

### 3.1 状态布局

L47–60：

```java
    String getPermitsName() {
        return suffixName(getRawName(), "permits");
    }

    String getClientPermitsName() {
        return suffixName(getPermitsName(), getServiceManager().getId());
    }

    String getValueName() {
        return suffixName(getRawName(), "value");
    }

    String getClientValueName() {
        return suffixName(getValueName(), getServiceManager().getId());
    }
```

- 主 key 是 **hash**：`{rate, interval, type, keepAliveTime}`（见 3.4）。
- `*:value` 是**普通 string，当前剩余券数**。
- `*:permits` 是 **ZSET**：score = 毫秒时间戳，member = `struct.pack('Bc0I', len(clientId), clientId, permits)`。
- `type` 语义见 `RateType.java` L28/L33：`OVERALL`（序数 0）、`PER_CLIENT`（序数 1）。Lua 里 `if type == '1'` 就是切到带 client id 后缀的那套 key（L208–211）。

### 3.2 `tryAcquire` 的 Lua（L200–270）逐段

```lua
local rate = redis.call('hget', KEYS[1], 'rate');
local interval = redis.call('hget', KEYS[1], 'interval');
local type = redis.call('hget', KEYS[1], 'type');
assert(rate ~= false and interval ~= false and type ~= false, 'RateLimiter is not initialized')

local valueName = KEYS[2];
local permitsName = KEYS[4];
if type == '1' then
    valueName = KEYS[3];
    permitsName = KEYS[5];
end;

assert(tonumber(rate) >= tonumber(ARGV[1]), 'Requested permits amount cannot exceed defined rate');
```

`rate` / `interval` 的语义就是 **`rate` 张券 / `interval` 毫秒**（`setRate(type, rate, rateInterval, unit)` → `ARGV[2] = rateInterval.toMillis()`，见 3.4）。`interval` 是**滑动窗口长度**，不是补券周期。

**回收过期券**（L218–239）：

```lua
local currentValue = redis.call('get', valueName);
local res;
if currentValue ~= false then
    local expiredValues = redis.call('zrangebyscore', permitsName, 0, tonumber(ARGV[2]) - interval);
    local released = 0;
    for i, v in ipairs(expiredValues) do
        local random, permits = struct.unpack('Bc0I', v);
        released = released + permits;
    end;

    if released > 0 then
        redis.call('zremrangebyscore', permitsName, 0, tonumber(ARGV[2]) - interval);
        if tonumber(currentValue) + released > tonumber(rate) then
            local values = redis.call('zrange', permitsName, 0, -1);
            local used = 0;
            for i, v in ipairs(values) do
                local random, permits = struct.unpack('Bc0I', v);
                used = used + permits;
            end;
            currentValue = tonumber(rate) - used;
        else
            currentValue = tonumber(currentValue) + released;
        end;
        redis.call('set', valueName, currentValue);
    end;
```

- `ARGV[2]` 是**客户端传进来的 `System.currentTimeMillis()`**（L270），不是 Redis 的 `TIME`。
- `zrangebyscore permitsName 0 (now - interval)` —— 早于"窗口起点"的券就是过期的，遍历求和后 `zremrangebyscore` 一次性删。
- **`currentValue + released > rate` 时不全信增量，改用全表重算 `currentValue = rate - used`**。这一段是防"因为并发/时钟抖动导致 `value` 与 ZSET 对不上"的兜底——**用 ZSET 作为真相源，`value` 只是缓存**。这条对我们中心账本是否要维护"派生计数器"很有参考价值：要么每次重算，要么准备好一致性修复分支。

**扣减与返回等待时间**（L241–254）：

```lua
    if tonumber(currentValue) < tonumber(ARGV[1]) then
        local firstValue = redis.call('zrange', permitsName, 0, 0, 'withscores');
        res = 3 + interval - (tonumber(ARGV[2]) - tonumber(firstValue[2]));
    else
        redis.call('zadd', permitsName, ARGV[2], struct.pack('Bc0I', string.len(ARGV[3]), ARGV[3], ARGV[1]));
        redis.call('decrby', valueName, ARGV[1]);
        res = nil;
    end;
else
    redis.call('set', valueName, rate);
    redis.call('zadd', permitsName, ARGV[2], struct.pack('Bc0I', string.len(ARGV[3]), ARGV[3], ARGV[1]));
    redis.call('decrby', valueName, ARGV[1]);
    res = nil;
end;
```

- **返回值 `res = 3 + interval - (now - firstValue[2])`**，其中 `firstValue` 是 ZSET 里**最早那张券**（score 最小）。`now - firstValue[2]` 是它已存在的时长，`interval - 那个` 是它还剩多久过期，`+3` 是余量（ms）。**`3` 这个魔法数没有注释，也没有任何常量定义**。
- `res = nil` 表示成功；客户端把 `EVAL_NULL_BOOLEAN` 解成 `true`。
- 首次使用（`currentValue == false`）走 else 分支：**先 `set value = rate` 再扣**——即把上限当作初始额度。

**TTL 维护**（L256–267）：

```lua
local keepAliveTime = redis.call('hget', KEYS[1], 'keepAliveTime'); 
if (keepAliveTime ~= false and tonumber(keepAliveTime) > 0) then 
      redis.call('pexpire', KEYS[1], keepAliveTime); 
      redis.call('pexpire', valueName, keepAliveTime); 
      redis.call('pexpire', permitsName, keepAliveTime); 
else 
      local ttl = redis.call('pttl', KEYS[1]); 
      if ttl > 0 then 
          redis.call('pexpire', valueName, ttl); 
          redis.call('pexpire', permitsName, ttl); 
      end; 
end; 
return res;
```

**三个 key 的过期必须一起续**——这是"限流器 + 过期"的经典坑：主 key 活着但 ZSET 被 TTL 清掉，会退化成"额度突然回满"。我们中心账本的每个租约都要检查这类"多 key 同生共死"。

### 3.3 等待语义：两层重试，都不阻塞中心

`acquireAsync`（L98–102）：

```java
    public RFuture<Void> acquireAsync(long permits) {
        CompletionStage<Void> f = tryAcquireAsync(permits, Duration.ofMillis(-1)).thenApply(res -> null);
        return new CompletableFutureWrapper<>(f);
    }
```

`timeoutInMillis == -1` 走无限重试（L165–170）：

```java
            if (timeoutInMillis == -1) {
                getServiceManager().newTimeout(t -> {
                    tryAcquireAsync(permits, timeoutInMillis, startTime, promise);
                }, delay, TimeUnit.MILLISECONDS);
                return;
            }
```

`tryAcquire(permits, timeout)`（L172–193）：

```java
            long elapsed = System.currentTimeMillis() - startTime;
            long remains = timeoutInMillis - elapsed;
            if (remains <= 0) {
                promise.complete(false);
                return;
            }

            if (remains < delay) {
                getServiceManager().newTimeout(t -> {
                    promise.complete(false);
                }, remains, TimeUnit.MILLISECONDS);
            } else {
                getServiceManager().newTimeout(t -> {
                    long newElapsed = System.currentTimeMillis() - startTime;
                    if (timeoutInMillis <= newElapsed) {
                        promise.complete(false);
                        return;
                    }

                    tryAcquireAsync(permits, timeoutInMillis, startTime, promise);
                }, delay, TimeUnit.MILLISECONDS);
            }
```

**关键设计**：服务端返回的 `delay` 只是"建议下次重试的时刻"，客户端**用本地 `System.currentTimeMillis()` 记的 `startTime` 判断剩余**（`remains = timeoutInMillis - elapsed`）。**当 `remains < delay` 时不再重试，直接定时 `remains` 后返回 false**——省掉一次注定失败的往返。`startTime` 在整条重试链里被传递，所以超时是**端到端的总预算**而不是每次调用的预算。

### 3.4 `rate` / `interval` 的写入

`trySetRateAsync`（L366–381）用 `hsetnx` 做"只在没初始化时写"：

```java
        return commandExecutor.evalWriteNoRetryAsync(getRawName(), LongCodec.INSTANCE, RedisCommands.EVAL_BOOLEAN,
                    "redis.call('hsetnx', KEYS[1], 'rate', ARGV[1]);"
                        + "redis.call('hsetnx', KEYS[1], 'interval', ARGV[2]);"
                        + "redis.call('hsetnx', KEYS[1], 'keepAliveTime', ARGV[3]);"
                        + "local res = redis.call('hsetnx', KEYS[1], 'type', ARGV[3]);"
```

（注：L374 用的 `ARGV[3]` 写 `type` 与上面写 `keepAliveTime` 重复，看起来是上游的一处笔误；实际 `setRateAsync`（L404–425）用的是正确的 `hset` 且末尾 `redis.call('del', valueName, permitsName);` 清空状态。）

`setRateAsync` 里对 `keepAliveTime` 有显式约束（L405–407）：

```java
        if (!keepAliveTime.isZero() && keepAliveTime.toMillis() < rateInterval.toMillis()) {
            throw new IllegalArgumentException("The parameter keepAliveTime should be greater than or equal to rateInterval");
        }
```

**`keepAliveTime >= rateInterval` 是被强制校验的**——过期时间不能短于窗口长度，否则 ZSET 会在窗口内被清掉。

### 3.5 `release` 的落码

`releaseAsync`（L275–338）。先是入参守卫（L276–281）：负数抛、0 直接返回。

回收段（L296–310）：

```lua
local toRelease = tonumber(ARGV[1]);
local values = redis.call('zrange', permitsName, 0, -1, 'withscores');
for i = 1, #values - 1, 2 do
    if toRelease <= 0 then break; end;
    local v = values[i];
    local score = values[i + 1];
    local random, permits = struct.unpack('Bc0I', v);
    redis.call('zrem', permitsName, v);
    if permits <= toRelease then
        toRelease = toRelease - permits;
    else
        local remained = permits - toRelease;
        redis.call('zadd', permitsName, score, struct.pack('Bc0I', string.len(random), random, remained));
        toRelease = 0;
    end;
end;
```

- 按 score 升序（**最早的那张券先退**）；一张整券能被扣完就 `zrem`，扣不完就把剩余部分**以原来的 score 重新 zadd 回去**。
- **注意：这里没有先做 `zrangebyscore ... 0 (now-interval)` 回收过期券**，退的是 ZSET 里现存的券（包括已过期但还没被 `tryAcquire` 清理掉的）。跟 `tryAcquire` 的处理并不对称。

上限截断段（L312–323）：

```lua
local currentValue = redis.call('get', valueName);
if currentValue == false then 
    currentValue = tonumber(rate);
else 
    currentValue = tonumber(currentValue);
end;

local newValue = currentValue + tonumber(ARGV[1]);
if newValue > tonumber(rate) then 
    newValue = tonumber(rate);
end;
redis.call('set', valueName, newValue);
```

**`value` 与 ZSET 两条线分别封顶**：`value` 用 `min(rate, ...)` 截断，ZSET 靠上面逐张退券自然不超过已发出的券数。这解释了为什么 `currentValue + released > rate` 那个兜底分支存在——两条线没有强同步。

**对我们的启示**：Redisson 的 `value` 是"派生缓存 + 权威在 ZSET"的写法，代价是每次都要准备一致性修复分支；我们如果中心账本同时维护"配额计数器"和"租约集合"，会继承同样的复杂度——要么只留一个真相源，要么抄它这个全表重算兜底。另外 `acquire(timeout)` 的"`remains < delay` 就直接放弃、不白跑一趟"是纯客户端的收益，零成本可抄。

---

## 4. Sentinel 集群流控

**来源**：`alibaba/Sentinel` master（当日抓取）。
文件：
- `sentinel-core/src/main/java/com/alibaba/csp/sentinel/cluster/{ClusterConstants,TokenService}.java`、`.../cluster/client/ClusterTokenClient.java`、`.../slots/statistic/base/LeapArray.java`、`.../util/TimeUtil.java`
- `sentinel-cluster/sentinel-cluster-common-default/src/main/java/com/alibaba/csp/sentinel/cluster/{request/ClusterRequest,response/ClusterResponse,request/data/FlowRequestData,response/data/FlowTokenResponseData}.java`
- `sentinel-cluster/sentinel-cluster-client-default/src/main/java/com/alibaba/csp/sentinel/cluster/client/{DefaultClusterTokenClient,NettyTransportClient}.java`、`.../client/config/ClusterClientConfig.java`、`.../client/handler/TokenClientHandler.java`
- `sentinel-cluster/sentinel-cluster-server-default/src/main/java/com/alibaba/csp/sentinel/cluster/flow/{DefaultTokenService,ClusterFlowChecker}.java`、`.../flow/statistic/metric/{ClusterMetric,ClusterMetricLeapArray}.java`、`.../server/connection/ConnectionManager.java`

### 4.1 协议字段与每请求往返内容

`ClusterConstants.java` L24–28、L43–45：

```java
    public static final int MSG_TYPE_PING = 0;
    public static final int MSG_TYPE_FLOW = 1;
    public static final int MSG_TYPE_PARAM_FLOW = 2;
    public static final int MSG_TYPE_CONCURRENT_FLOW_ACQUIRE = 3;
    public static final int MSG_TYPE_CONCURRENT_FLOW_RELEASE = 4;
    ...
    public static final int DEFAULT_CLUSTER_SERVER_PORT = 18730;
    public static final int DEFAULT_REQUEST_TIMEOUT = 20;
    public static final int DEFAULT_CONNECT_TIMEOUT_MILLIS = 10 * 1000;
```

`ClusterRequest<T>` 只有三个字段：`int id; int type; T data;`。`ClusterResponse<T>` 四个：`int id; int type; int status; T data;`。

**请求体（客户端→服务端）** `FlowRequestData.java` L22–26：

```java
public class FlowRequestData {

    private long flowId;
    private int count;
    private boolean priority;
```

**响应体（服务端→客户端）** `FlowTokenResponseData.java` L22–25：

```java
public class FlowTokenResponseData {

    private int remainingCount;
    private int waitInMs;
```

**所以每请求往返的是：** `{flowId(规则ID), count(要几个), priority(是否可占未来额度)}` → `{status(OK/SHOULD_WAIT/BLOCKED/NO_RULE_EXISTS/FAIL/BAD_REQUEST), remainingCount, waitInMs}`。

`remainingCount` / `waitInMs` 都是 `int`，且服务端 `setRemaining((int) nextRemaining)`（见 4.2）显式截断：

```java
            // Remaining count is cut down to a smaller integer.
            return new TokenResult(TokenResultStatus.OK)
                .setRemaining((int) nextRemaining)
                .setWaitInMs(0);
```

**没有租约，没有令牌批次，没有客户端 ID**——客户端不告诉服务端"我是谁、我有多少存量"。

### 4.2 服务端判定 `ClusterFlowChecker.acquireClusterToken`（L55–112）

```java
    static TokenResult acquireClusterToken(/*@Valid*/ FlowRule rule, int acquireCount, boolean prioritized) {
        Long id = rule.getClusterConfig().getFlowId();

        if (!allowProceed(id)) {
            return new TokenResult(TokenResultStatus.TOO_MANY_REQUEST);
        }

        ClusterMetric metric = ClusterMetricStatistics.getMetric(id);
        if (metric == null) {
            return new TokenResult(TokenResultStatus.FAIL);
        }

        double latestQps = metric.getAvg(ClusterFlowEvent.PASS);
        double globalThreshold = calcGlobalThreshold(rule) * ClusterServerConfigManager.getExceedCount();
        double nextRemaining = globalThreshold - latestQps - acquireCount;

        if (nextRemaining >= 0) {
            // TODO: checking logic and metric operation should be separated.
            metric.add(ClusterFlowEvent.PASS, acquireCount);
            metric.add(ClusterFlowEvent.PASS_REQUEST, 1);
            if (prioritized) {
                // Add prioritized pass.
                metric.add(ClusterFlowEvent.OCCUPIED_PASS, acquireCount);
            }
```

拒绝 + 优先权分支（L84–111）：

```java
        } else {
            if (prioritized) {
                // Try to occupy incoming buckets.
                double occupyAvg = metric.getAvg(ClusterFlowEvent.WAITING);
                if (occupyAvg <= ClusterServerConfigManager.getMaxOccupyRatio() * globalThreshold) {
                    int waitInMs = metric.tryOccupyNext(ClusterFlowEvent.PASS, acquireCount, globalThreshold);
                    // waitInMs > 0 indicates pre-occupy incoming buckets successfully.
                    if (waitInMs > 0) {
                        ClusterServerStatLogUtil.log("flow|waiting|" + id);
                        return new TokenResult(TokenResultStatus.SHOULD_WAIT)
                            .setRemaining(0)
                            .setWaitInMs(waitInMs);
                    }
                    // Or else occupy failed, should be blocked.
                }
            }
            // Blocked.
            metric.add(ClusterFlowEvent.BLOCK, acquireCount);
```

**`SHOULD_WAIT` 的语义**：不是"你等一会儿再来"，而是"**这笔已经算你过了，未来窗口欠着**"，服务端已经把 `WAITING` 记上（`tryOccupyNext` 里 `add(ClusterFlowEvent.WAITING, acquireCount)`）。这是"借未来的额度"，不是"排队"。

`calcGlobalThreshold`（L38–48）给出两种阈值口径：

```java
    private static double calcGlobalThreshold(FlowRule rule) {
        double count = rule.getCount();
        switch (rule.getClusterConfig().getThresholdType()) {
            case ClusterRuleConstant.FLOW_THRESHOLD_GLOBAL:
                return count;
            case ClusterRuleConstant.FLOW_THRESHOLD_AVG_LOCAL:
            default:
                int connectedCount = ClusterFlowRuleManager.getConnectedCount(rule.getClusterConfig().getFlowId());
                return count * connectedCount;
        }
    }
```

**`AVG_LOCAL` 需要"当前连着多少个客户端"**，服务端从连接注册表拿（`ClusterFlowRuleManager` → `ConnectionManager.getConnectedCount(namespace)`，`ConnectionManager.java` L47–51）：

```java
    public static int getConnectedCount(String namespace) {
        AssertUtil.notEmpty(namespace, "namespace should not be empty");
        ConnectionGroup group = CONN_MAP.get(namespace);
        return group == null ? 0 : group.getConnectedCount();
    }
```

### 4.3 "客户端定时上报的统计"——**不存在**

翻遍 `sentinel-cluster-client-default` 的 main 源码，客户端**只有两类出站消息**：`channelActive` 时的一次 ping（`TokenClientHandler.java` L68–74）：

```java
    private void fireClientPing(ChannelHandlerContext ctx) {
        // Data body: namespace of the client.
        ClusterRequest<String> ping = new ClusterRequest<String>().setId(0)
            .setType(ClusterConstants.MSG_TYPE_PING)
            .setData(ConfigSupplierRegistry.getNamespaceSupplier().get());
        ctx.writeAndFlush(ping);
    }
```

以及每次 `requestToken`。**没有周期性统计上报**。服务端的 `ClusterMetric` 完全由"经过的 token 请求"自身累加而成（`ClusterFlowChecker` 里的 `metric.add(...)`）。客户端唯一被服务端感知到的长期状态，就是 WebSocket/Netty 连接是否还活着（`ConnectionManager` 按 namespace 计数）。

**对我们的启示很直接**：Sentinel 用"连接在册"替代"心跳上报"，服务端不需要信任客户端报来的数字；代价是中心无法知道客户端**本地**还剩多少（因为压根没有本地桶）。

### 4.4 `getAvg` 是滑动窗口均值，不是令牌桶

`ClusterMetric.java` L39–42、L70–72：

```java
    public void add(ClusterFlowEvent event, long count) {
        metric.currentWindow().value().add(event, count);
    }
    ...
    public double getAvg(ClusterFlowEvent event) {
        return getSum(event) / metric.getIntervalInSecond();
    }
```

`getSum` 遍历 `metric.values()`（`LeapArray` 的所有桶）求和，`getIntervalInSecond()` 是整个滑动窗口的秒数。所以 `latestQps` 是**跨整个窗口的平均 QPS**。窗口参数来自服务端配置：`ClusterMetricStatistics` L63 `new ClusterMetric(ClusterServerConfigManager.getSampleCount(), ...)`。

### 4.5 `tryOccupyNext`：预占"即将进入"的桶（L79–98）

```java
    public int tryOccupyNext(ClusterFlowEvent event, int acquireCount, double threshold) {
        double latestQps = getAvg(ClusterFlowEvent.PASS);
        if (!canOccupy(event, acquireCount, latestQps, threshold)) {
            return 0;
        }
        metric.addOccupyPass(acquireCount);
        add(ClusterFlowEvent.WAITING, acquireCount);
        return 1000 / metric.getSampleCount();
    }

    private boolean canOccupy(ClusterFlowEvent event, int acquireCount, double latestQps, double threshold) {
        long headPass = metric.getFirstCountOfWindow(event);
        long occupiedCount = metric.getOccupiedCount(event);
        //  bucket to occupy (= incoming bucket)
        //       ↓
        // | head bucket |    |    |    | current bucket |
        // +-------------+----+----+----+----------- ----+
        //   (headPass)
        return latestQps + (acquireCount + occupiedCount) - headPass <= threshold;
    }
```

**返回的 `waitInMs` 是固定值 `1000 / sampleCount`（一个桶的宽度）**，不是算出来的精确等待时间。`- headPass` 是因为 head 桶即将过期，可以把它让出的额度算进未来。这套"借头桶"的逻辑比 Redisson 的 `firstValue` 精确得多，但代价是它假设了**窗口均匀划分**。

### 4.6 `LeapArray`：滑动窗口与时钟回拨

`sentinel-core/.../statistic/base/LeapArray.java` L41–72、L100–202：

```java
    protected int windowLengthInMs;
    protected int sampleCount;
    protected int intervalInMs;
```

```java
    private int calculateTimeIdx(/*@Valid*/ long timeMillis) {
        long timeId = timeMillis / windowLengthInMs;
        // Calculate current index so we can map the timestamp to the leap array.
        return (int)(timeId % array.length());
    }

    protected long calculateWindowStart(/*@Valid*/ long timeMillis) {
        return timeMillis - timeMillis % windowLengthInMs;
    }
```

`currentWindow(timeMillis)`（L116–202）三种分支：

```java
            } else if (windowStart == old.windowStart()) {
                return old;
            } else if (windowStart > old.windowStart()) {
                if (updateLock.tryLock()) {
                    try {
                        // Successfully get the update lock, now we reset the bucket.
                        return resetWindowTo(old, windowStart);
                    } finally {
                        updateLock.unlock();
                    }
                } else {
                    // Contention failed, the thread will yield its time slice to wait for bucket available.
                    Thread.yield();
                }
            } else if (windowStart < old.windowStart()) {
                // Should not go through here, as the provided time is already behind.
                return new WindowWrap<T>(windowLengthInMs, windowStart, newEmptyBucket(timeMillis));
            }
```

- 空桶用 `array.compareAndSet(idx, null, window)` 建，失败 `Thread.yield()` 重来（L147–154）。
- 需要重置时用 `updateLock.tryLock()`（**条件锁，只在桶过期时争用**），失败也是 `Thread.yield()`。
- **时钟回拨的落码**（L197–200）：`windowStart < old.windowStart()` 时**不抛错、不改状态**，返回一个**游离的空桶**（不在数组里）。注释原文：*"Should not go through here, as the provided time is already behind."* —— 即"回拨期间的数据直接丢弃"。这比抛异常/写坏状态温和，但也不是修正。

### 4.7 `TimeUtil`：**故意做粗的缓存墙钟**

`sentinel-core/.../util/TimeUtil.java`。类注释 L29–37：

```java
 * <p>
 * Here we should see that not all the time TimeUtil should
 * keep looping 1_000 times every second (Actually about 800/s due to some losses).
 * <pre>
 * * In idle conditions it just acts as System.currentTimeMillis();
 * * In busy conditions (significantly more than 1_000/s) it keeps loop to reduce costs.
 * </pre>
```

构造器 L82–105 起一个后台线程：

```java
        Thread daemon = new Thread(this);
        daemon.setDaemon(true);
        daemon.setName("sentinel-time-tick-thread");
        daemon.start();
```

`run()`（L107–135）：

```java
        while (true) {
            // Mechanism optimized since 1.8.2
            this.check();
            if (this.state == STATE.RUNNING) {
                this.currentTimeMillis = System.currentTimeMillis();
                this.statistics.currentWindow(this.currentTimeMillis).value().getWrites().increment();
                try {
                    TimeUnit.MILLISECONDS.sleep(1);
                } catch (Throwable e) {
                }
                continue;
            }
            if (this.state == STATE.IDLE) {
                try {
                    TimeUnit.MILLISECONDS.sleep(300);
                } catch (Throwable e) {
                }
                continue;
            }
```

读路径 `currentTime(boolean)`（L198–211）：

```java
    private long currentTime(boolean innerCall) {
        long now = this.currentTimeMillis;
        Statistic val = this.statistics.currentWindow(now).value();
        if (!innerCall) {
            val.getReads().increment();
        }
        if (this.state == STATE.IDLE || this.state == STATE.PREPARE) {
            now = System.currentTimeMillis();
            this.currentTimeMillis = now;
            if (!innerCall) {
                val.getWrites().increment();
            }
        }
        return now;
    }
```

状态切换阈值（L43–45、L176–194）：`HITS_LOWER_BOUNDARY = 800`、`HITS_UPPER_BOUNDARY = 1200` 次/秒，`CHECK_INTERVAL = 3000` ms。

**结论**：Sentinel 全用墙钟（`System.currentTimeMillis()`），且**在高负载时刻意让整机只留 1ms 粒度的近似时间**以省掉每请求的 syscall。这对"热路径不调时钟"是一个真实先例——但代价是时间戳精度被牺牲，且**回拨没有任何处理**（`TimeUtil` 里没有单调性检查）。`LeapArray` 的"游离空桶"是唯一的软着陆。

**对我们的启示**：Sentinel 证明了"每请求远程"在**中心只维护一个滑动窗口计数器**时是可以跑的（服务端无状态、无租约、无回收），代价是客户端完全没有本地缓冲、中心故障就等于没有限流。它的 `SHOULD_WAIT + waitInMs`（借未来窗口）和 `AbandonAction` 式的"头桶让渡"值得我们对照租约模型——但注意 Sentinel 的"借"是服务端单方面记账，客户端并没有真的等。

---

## 5. Doorman（Go）

**来源**：`youtube/doorman` master（当日抓取）。
文件：`go/server/doorman/{algorithm,server,resource,store}.go`、`go/client/doorman/client.go`、`proto/doorman/doorman.proto`、`doc/design.md`、`doc/simplecluster/config.yml`。

### 5.1 租约结构：**速率租约，不是令牌租约**

`go/server/doorman/store.go` L19–36：

```go
// Lease represents a lease on capacity for some resource for some client.
type Lease struct {
	// Expiry is the time at which this lease expires.
	Expiry time.Time

	// RefreshInterval is the interval at which the client should
	// poll for updates for this resource.
	RefreshInterval time.Duration

	// Has is how much capacity was given to this client.
	Has float64

	// Wants is how much capacity was requested.
	Wants float64

	// Subclients is the number of subclients of this client.
	Subclients int64
}
```

全都是 `float64` **速率**（req/s），**没有令牌数、没有桶**。`Expiry` 直接是 `time.Time`（墙钟绝对时刻），不是时长。

### 5.2 存储与过期清理

`leaseStoreImpl`（L105–181）：

```go
type leaseStoreImpl struct {
	id       string
	leases   map[string]Lease
	sumWants float64
	sumHas   float64
	count    int64
}
```

`Assign`（L153–167）—— **增量维护聚合值，且在原地改 map 里的 Lease**：

```go
func (store *leaseStoreImpl) Assign(client string, leaseLength, refreshInterval time.Duration, has, wants float64, subclients int64) Lease {
	lease := store.leases[client]

	store.sumHas += has - lease.Has
	store.sumWants += wants - lease.Wants
	store.count += subclients - lease.Subclients

	lease.Has, lease.Wants = has, wants
	lease.Expiry = time.Now().Add(leaseLength)
	lease.RefreshInterval = refreshInterval
	lease.Subclients = subclients
	store.leases[client] = lease

	return lease
}
```

**注意 `lease.Expiry = time.Now().Add(leaseLength)` 在续租时整体重置**，不是"首次到期时间 + n×间隔"——所以客户端的续租周期决定真实租约长度。

`Clean`（L169–181）：

```go
func (store *leaseStoreImpl) Clean() int {
	when := time.Now()
	result := 0

	for client, lease := range store.leases {
		if when.After(lease.Expiry) {
			store.Release(client)
			result++
		}
	}

	return result
}
```

**惰性全表扫描**——没有定时器、没有优先队列。触发点见 5.3。

`Release`（L142–151）：

```go
func (store *leaseStoreImpl) Release(client string) {
	lease, ok := store.leases[client]
	if !ok {
		return
	}
	store.sumWants -= lease.Wants
	store.sumHas -= lease.Has
	store.count -= lease.Subclients
	delete(store.leases, client)
}
```

### 5.3 清理的触发时机：持锁内、每次决策前

`go/server/doorman/resource.go` L100–113：

```go
func (res *Resource) Decide(request Request) Lease {
	// NOTE(ryszard): Eventually the refresh interval should depend
	// on the level of the server in the tree.
	res.mu.Lock()
	defer res.mu.Unlock()

	res.store.Clean()

	if res.learningModeEndTime.After(time.Now()) {
		log.V(2).Infof("decision in learning mode for %v", res.ID)
		return res.learner(res.store, res.capacity(), request)
	}
	return res.algorithm(res.store, res.capacity(), request)
}
```

`doc/design.md` L500–512 解释了这里的取舍：

> ### Step 1: Cleanup step
>
> In the cleanup step the server goes through its internal state and removes all entries of which the lease has expired.
>
> Notes:
>
> + This cleanup operation can be done at any time, but it is convenient to do it now.
> + Since a server will never hand out a lease on capacity that is longer than its own lease on the capacity it got from a lower level server it can assume that an expired lease on its own capacity for a resource means that all leases of clients and servers that got capacity from it have also expired.
> + **Since all times are maintained in seconds, the server only has to perform a cleanup if one hasn't been done yet in the current (second granularity) timestamp. This provides for a simple optimization.**

（最后那条"秒粒度去重"在 `Clean()` 的实现里**并没有真的做**——实现是每次全扫。文档描述的是设计意图。）

### 5.4 服务端每请求交换的字段

`proto/doorman/doorman.proto` L22–51：

```proto
message Lease {
  required int64 expiry_time = 1;
  required int64 refresh_interval = 2;
  required double capacity = 3;
}
...
message ResourceRequest {
  required string resource_id = 1;
  required int64 priority = 2;

  required double wants = 4;
  ...
}
message GetCapacityRequest {
  required string client_id = 1;
  repeated ResourceRequest resource = 2;
}
...
message ResourceResponse {
  required string resource_id = 1;
  ...
  optional double safe_capacity = 3;
}
```

`expiry_time` 是 **Unix 秒**（`item.lease.Expiry.Unix()`，server.go L786）、`refresh_interval` 也是**秒**（`int64(item.lease.RefreshInterval.Seconds())`）。客户端拿 `time.Unix(res.lease.GetExpiryTime(), 0)` 还原（client.go L508–510）。

服务端 `GetCapacity` 组装（server.go L764–790）：

```go
	for _, req := range in.Resource {
		request := clientRequest{
			client:     client,
			resID:      req.GetResourceId(),
			has:        req.GetHas().GetCapacity(),
			wants:      req.GetWants(),
			subclients: 1,
		}

		requests = append(requests, request)
	}

	server.getCapacity(requests, itemsC)

	// We collect the assigned leases.
	for range in.Resource {
		item := <-itemsC
		resp := &pb.ResourceResponse{
			ResourceId: proto.String(item.id),
			Gets: &pb.Lease{
				RefreshInterval: proto.Int64(int64(item.lease.RefreshInterval.Seconds())),
				ExpiryTime:      proto.Int64(item.lease.Expiry.Unix()),
				Capacity:        proto.Float64(item.lease.Has),
			},
		}
		server.getOrCreateResource(item.id).SetSafeCapacity(resp)
		out.Response = append(out.Response, resp)
	}
```

**每请求往返：** `{client_id, [{resource_id, priority, wants, has}]}` → `[{resource_id, gets:{capacity, expiry_time, refresh_interval}, safe_capacity}]`。
注意 `has` 是**客户端自报**（`req.GetHas().GetCapacity()`），服务端会拿它做对账但**以自己为准**——`algorithm.go` L101–103：

```go
		if r.Has != old.Has {
			log.Errorf("client %v is confused: says it has %v, was assigned %v.", r.Client, r.Has, old.Has)
		}
```

### 5.5 客户端 `wants` 怎么来的：应用声明，不是观测

`client.go` L501–534：

```go
	// mu guards access to wants.
	...
	wants float64
```
```go
	// Wants returns the currently desired capacity. It takes care of
	// ...
func (res *resourceImpl) Wants() float64 {
	...
	return res.wants
```

```go
func (res *resourceImpl) Ask(wants float64) error {
	if wants <= 0 {
		return ErrInvalidWants
	}
	...
	res.wants = wants
```

`Resource(id, capacity)` 时把 `wants` 初始化成 `capacity`（L410），之后由应用调 `Ask()` 改。`performRequests`（L313–326）打包：

```go
	in := &pb.GetCapacityRequest{ClientId: proto.String(client.id)}

	// Adds all resources in this client's resource registry to the
	// request.
	for id, resource := range client.resources {
		in.Resource = append(in.Resource, &pb.ResourceRequest{
			Priority:   proto.Int64(resource.priority),
			ResourceId: proto.String(id),
			Wants:      proto.Float64(resource.Wants()),
			Has:        resource.lease,
		})
	}
```

**没有"根据近期实际流量自动推算 wants"的逻辑**——`go/client/recipe/recipe.go` 里是压测用的脚本化 QPS 变化，不是自适应。所以 `wants` 是**配置值**，服务端拿它做 fair-share 分摊。

### 5.6 客户端侧"租约到期即归零"的落码

`client.go` L334–349：

```go
	if err != nil {
		log.Errorf("GetCapacityRequest: %v", err)

		// Expired resources only need to be handled if
		// the RPC failed: otherwise the client has gotten a
		// refreshed lease.
		for _, res := range client.resources {
			if res.expires().Before(time.Now()) {
				res.lease = nil
				// FIXME(ryszard): This probably should be the safe
				// capacity instead.
				res.capacity <- 0.0
			}
		}
		return timeutil.Backoff(minBackoff, maxBackoff, retryNumber), retryNumber + 1
	}
```

**这是"租约到期归零"在本仓里唯一一处代码**：只有 RPC **失败**时才检查过期（成功时一定拿到了新租约）。归零动作 = `res.lease = nil` + 向 buffered channel 推 `0.0`（注释里的 FIXME 说明作者知道这里应该推 `safe_capacity` 而不是 0）。

`doc/design.md` L281 的规范表述：

> When the lease expires the client should remove the capacity and lease fields and behave as if no capacity is available.

### 5.7 降级三档 + safe_capacity

`doc/design.md` L283–295：

> 1. In **pessimistic** mode it can behave as if a capacity of zero has been granted, and effectively stop using the resource. ...
> 2. In **optimistic** mode it can behave as if it got all of the capacity it requested. ...
> 3. In **safe** mode the client is allowed to use the resource up to the per-client safe capacity limit of the resource.

`safe_capacity` 由服务端算并回传（`resource.go` L81–96）：

```go
	// If the resource configuration does not have a safe capacity
	// configured we return a dynamic safe capacity which equals
	// the capacity divided by the number of clients that we
	// know about.
	if res.config.SafeCapacity == nil {
		resp.SafeCapacity = proto.Float64(*res.config.Capacity / float64(res.store.Count()))
	} else {
		resp.SafeCapacity = proto.Float64(*res.config.SafeCapacity)
	}
```

**"中心不可达时的保底额度 = 总容量 / 中心已知的客户端数"**——中心必须知道客户端数量，这跟 Sentinel 的 `getConnectedCount` 是同一类需求。

### 5.8 续租节流与默认参数

`doc/design.md` L386–393：

> Clients are allowed to send GetCapacity requests before the refresh interval has expired. Good reasons to do so are:
>
> 1. The capacity needed for a resource has changed significantly and the client wants to update its capacity lease right now.
> 2. The client wants to obtain capacity for a new resource and decides to renew its capacity for the other resources in its internal state as well (this behaviour is encouraged).
>
> That said **a client may not send more GetCapacity requests for a given resource than once every five seconds. If it does send a request for capacity for a resource inside of five seconds since the last request it is silently ignored.** This feature protects the server from running potentially expensive algorithms during client initialisation when multiple resources are created, each potentially leading to a GetCapacity request (depending on how the client library is structured).

示例配置 `doc/simplecluster/config.yml`：`lease_length: 15` / `refresh_interval: 5`（另一组是 60 / 15）。单位是秒。

### 5.9 `ReleaseCapacity` 的服务端落码

`server.go` L662–712。核心只有几行：

```go
	client := in.GetClientId()

	// Takes the server lock because we are reading the resource map below.
	server.mu.RLock()
	defer server.mu.RUnlock()

	for _, resourceID := range in.ResourceId {
		// If the server does not know about the resource we don't have to do
		// anything.
		if res, ok := server.resources[resourceID]; ok {
			res.store.Release(client)
		}
	}
```

**整资源放弃，不接受"退 N 个"**。客户端侧触发点 `removeResource`（client.go L288–304）在资源从注册表删除时调用。

**对我们的启示**：(1) `Expiry` 存绝对时刻 + 惰性全扫，实现极简，但在客户端数大时是全表 O(n)——我们要想清楚 `Clean` 是走定时器还是搭车；(2) 客户端自报 `has` 但服务端不信，只记日志——这允许"客户端本地计数不精确"而不污染中心账本，值得抄；(3) 只有租约到期才归零这一条，在 Doorman 里是**在 RPC 失败分支里**实现的，说明"正常续租路径不需要处理过期"。

---

## 6. 对照：Envoy RLS 与 envoyproxy/ratelimit

### 6.1 Envoy 文档：为什么不做本地桶，以及官方怎么描述"本地桶 + 中心"

来源：`envoyproxy/envoy` main，`docs/root/intro/arch_overview/other_features/global_rate_limiting.rst`（3,875 字节，全文已读）。

适用规模（L9–15）：

> The most common case is when a large number of hosts are forwarding to a small number of hosts and the average request latency is low (e.g., connections/requests to a database server).

每请求远程（L30–35）：

> * **HTTP level rate limit filter**: Envoy will call the rate limit service **for every new request** on the listener where the filter is installed and where the route table specifies that the global rate limit service should be called.

**官方对"本地桶 + 中心"的表态（L43–48）——这段几乎就是 gatekeeper 的立项理由**：

> Note that Envoy also supports local rate limiting. Local rate limiting can be used in conjunction with global rate limiting to reduce load on the global rate limit service. For example, **a local token bucket rate limit can absorb very large bursts in load that might otherwise overwhelm a global rate limit service. Thus, the rate limit is applied in two stages. The initial coarse grained limiting is performed by the token bucket limit before a fine grained global limit finishes the job.**

**分级口径**：本地桶 = **粗粒度 + 吸收突发**；中心 = **细粒度 + 收口**。这句话值得直接写进我们 spec 的设计动机。

第二形态（L50–56）：

> Quota based rate limiting
> ^^^^^^^^^^^^^^^^^^^^^^^^^
>
> **Open source reference implementation of the rate limiting service is currently unavailable.** The rate limit quota extension can be presently used with the Google Cloud Rate Limit Service.

### 6.2 envoyproxy/ratelimit：每次请求就是一次 Redis pipeline

`src/redis/fixed_cache_impl.go` L35–38：

```go
func pipelineAppend(client Client, pipeline *Pipeline, key string, hitsAddend uint64, result *uint64, expirationSeconds int64) {
	*pipeline = client.PipeAppend(*pipeline, result, "INCRBY", key, hitsAddend)
	*pipeline = client.PipeAppend(*pipeline, nil, "EXPIRE", key, expirationSeconds)
}
```

`DoLimit`（L95–242）依次：生成本次请求的 cache key（L105）→ 查本地负缓存（L119–135）→ 组织 pipeline（L183–202）→ `PipeDo` 一次往返（L214–219）→ 用返回的 `results[i]`（= INCRBY 后的值）和 `limitBeforeIncrease = limitAfterIncrease - hitsAddends[i].Value` 判定（L224–239）。

复杂度不在"原子性"上：**计数器是固定窗口计数，不是令牌桶，所以不需要 Lua**。每个 key 上的 `INCRBY` 本身原子；`EXPIRE` 只是 TTL 兜底（key 里已经嵌了窗口起点，见 6.3）。

### 6.3 key 就是"窗口标识"

`src/limiter/cache_key.go` L84–89：

```go
	if this.useCalendarMonth && limit.Limit.Unit == pb.RateLimitResponse_RateLimit_MONTH {
		// Calendar months vary in length, so bucket by the start of the
		// current UTC calendar month rather than a fixed-size divider.
		bucketStart = utils.MonthStartUnix(now)
	} else {
		divider := utils.UnitToDivider(limit.Limit.Unit)
		bucketStart = (now / divider) * divider
	}
	b.WriteString(strconv.FormatInt(bucketStart, 10))
```

`utils.UnitToDivider`（`src/utils/utilities.go` L20–40）把单位换成秒：SECOND=1、MINUTE=60、HOUR=3600、DAY=86400、WEEK=604800、**MONTH=2592000（固定 30 天）**、YEAR=31536000。

时间源（`src/utils/time.go` L21–23）：

```go
func (this *timeSourceImpl) UnixNow() int64 {
	return time.Now().Unix()
}
```

**墙钟秒**。所以固定窗口 = `floor(now/divider)*divider`，全部由墙钟决定；回拨/大步进都会直接换窗口。

回给调用方的重置时间（`utilities.go` L53–60）：

```go
func CalculateReset(unit *pb.RateLimitResponse_RateLimit_Unit, timeSource TimeSource, useCalendarMonth bool) *durationpb.Duration {
	nowUnix := timeSource.UnixNow()
	if useCalendarMonth && *unit == pb.RateLimitResponse_RateLimit_MONTH {
		return &durationpb.Duration{Seconds: MonthExpirationSeconds(nowUnix)}
	}
	sec := UnitToDivider(*unit)
	return &durationpb.Duration{Seconds: sec - nowUnix%sec}
}
```

### 6.4 它的"本地"只做了一件事：**超限负缓存**

`src/limiter/base_limiter.go` L90–99：

```go
func (this *BaseRateLimiter) IsOverLimitWithLocalCache(key string) bool {
	if this.localCache != nil {
		// Get returns the value or not found error.
		_, err := this.localCache.Get([]byte(key))
		if err == nil {
			return true
		}
	}
	return false
}
```

写入在 L136–148（freecache，TTL = 整个窗口长度）：

```go
			if this.localCache != nil {
				// Set the TTL of the local_cache to be the entire duration.
				// Since the cache_key gets changed once the time crosses over current time slot, the over-the-limit
				// cache keys in local_cache lose effectiveness.
				...
				err := this.localCache.Set([]byte(key), []byte{}, int(this.ExpirationSeconds(limitInfo.limit.Limit.Unit)))
```

README L1294–1298：

> Ratelimit optionally uses freecache as its local caching layer, **which stores the over-the-limit cache keys, and thus avoids reading the redis cache again for the already over-the-limit keys.** The local cache size can be configured via `LocalCacheSizeInBytes`... If `LocalCacheSizeInBytes` is 0, local cache is disabled.

**关键区分**：这是**负缓存**（"这个 key 已经超了，别再问 Redis"），**不是本地令牌桶**。它不做扣减、不做推断、不返还；被缓存期间该 key 一律直接拒绝。所以它**不会超发**（保守方向），但会**欠发**（窗口边界上的抖动被放大成一整个窗口）。

### 6.5 抵消往返成本的手段：pipeline + 写缓冲 + 独立 per-second 连接

README L1366：

> By default, for each request, ratelimit will pick up a connection from pool, write multiple redis commands in a single write then reads their responses in a single read. This reduces network delay.

L1370–1373：

> 1. `REDIS_PIPELINE_WINDOW` & `REDIS_PERSECOND_PIPELINE_WINDOW`: controls how often buffered writes are flushed to the network connection. When set to a non-zero value (e.g., **150us-500us**), radix v4 will buffer multiple concurrent write operations and flush them together...
> ...
> **Write buffering is disabled by default (window = 0).** For optimal performance, set `REDIS_PIPELINE_WINDOW` to 150us-500us depending on your latency requirements and load patterns.

另有 `perSecondClient`（L26–30、L48–59）：**第二类限流（秒级）单独走一个 Redis 客户端**，避免慢的窗口跟快的窗口抢连接。

### 6.6 退款路径

`base_limiter.go` L17–27：

```go
// DecrementScript atomically decrements a rate limit counter, floored at 0.
// If the key does not exist there is nothing to refund, so it returns 0 without
// creating a phantom key.
const DecrementScript = `
local current = redis.call('GET', KEYS[1])                   -- get current count
if current == false then return 0 end                        -- key absent: nothing to refund
local new_val = math.floor(math.max(0, tonumber(current) - tonumber(ARGV[1]))) -- subtract hits, floor at 0
redis.call('SET', KEYS[1], tostring(new_val))                -- persist new value
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]))             -- reset TTL
return new_val                                               -- return count after decrement
`
```

**两条可抄的纪律**：`key 不存在 → 返回 0 且不创建（no phantom key）`；`floor at 0`（不会退成负计数）。退款的响应语义（L176–190）也单列：

```go
// GetResponseDescriptorStatusForNegativeHits generates a response for a negative-hit
// (decrement/refund) request. Refunds release capacity rather than consuming it, so they
// always return OK regardless of the counter value and never trigger over-limit side
// effects (over-limit stats, local-cache poisoning).
```

### 6.7 对照增量：Envoy RLQS 协议——**这就是"客户端本地桶 + 中心配额"的官方协议**

`envoyproxy/envoy` main，`api/envoy/service/rate_limit_quota/v3/rlqs.proto`。文件头注释 L20–49：

```
// The Rate Limit Quota Service (RLQS) is a Envoy global rate limiting service that allows to
// delegate rate limit decisions to a remote service. The service will aggregate the usage reports
// from multiple data plane instances, and distribute Rate Limit Assignments to each instance
// based on its business logic. The logic is outside of the scope of the protocol API.
//
// The protocol is designed as a streaming-first API. It utilizes watch-like subscription model.
// The data plane groups requests into Quota Buckets as directed by the filter config,
// and periodically reports them to the RLQS server along with the Bucket identifier, ...
// Once RLQS server has collected enough reports to make a decision, it'll send back the assignment with the rate limiting instructions.
//
// The first report sent by the data plane is interpreted by the RLQS server as a "watch" request,
// indicating that the data plane instance is interested in receiving further updates for the
// ``BucketId``. From then on, RLQS server may push assignments to this instance at will, even if
// the instance is not sending usage reports. It's the responsibility of the RLQS server
// to determine when the data plane instance didn't send ``BucketId`` reports for too long,
// and to respond with the AbandonAction, indicating that the server has now stopped sending quota
// assignments for the ``BucketId`` bucket, and the data plane instance should abandon it.
//
// If for any reason, the RLQS client doesn't receive the initial assignment for the reported bucket,
// in order to prevent memory exhaustion, the data plane will limit the time such bucket
// is retained. The exact time to wait for the initial assignment is chosen by the filter,
// and may vary based on the implementation.
// Once the duration ends, the data plane will stop reporting bucket usage, reject any enqueued
// requests, and purge the bucket from the memory. Subsequent requests matched into the bucket
// will re-initialize the bucket in the "no assignment" state, restarting the reports.
```

**客户端上报的字段**（L63–98）——只有三个业务量：

```proto
message RateLimitQuotaUsageReports {
  message BucketQuotaUsage {
    BucketId bucket_id = 1 [(validate.rules).message = {required: true}];

    // Time elapsed since the last report.
    google.protobuf.Duration time_elapsed = 2 ...

    // Requests the data plane has allowed through.
    uint64 num_requests_allowed = 3;

    // Requests throttled.
    uint64 num_requests_denied = 4;
  }

  string domain = 1 ...
  repeated BucketQuotaUsage bucket_quota_usages = 2 ...
}
```

**服务端下发**（L100–200）：

```proto
message RateLimitQuotaResponse {
  message BucketAction {
    message QuotaAssignmentAction {
      // A duration after which the assignment is be considered ``expired``. ...
      google.protobuf.Duration assignment_time_to_live = 2 [(validate.rules).duration = {gte {}}];

      // Configures the local rate limiter for the request matched to the bucket.
      // If not set, allow all requests.
      type.v3.RateLimitStrategy rate_limit_strategy = 3;
    }

    message AbandonAction {
    }

    BucketId bucket_id = 1 ...

    oneof bucket_action {
      ...
      QuotaAssignmentAction quota_assignment_action = 2;
      AbandonAction abandon_action = 3;
    }
  }

  // An ordered list of actions to be applied to the buckets. The actions are applied in the
  // given order, from top to bottom.
  repeated BucketAction bucket_action = 1 ...
}
```

**租约的"续"与"换"**（L116–136）——这是最该抄进票 04 的一段：

```
    // **Expiring the assignment**
    //
    // The duration of the assignment defined in the assignment_time_to_live field. When the duration
    // runs off, the assignment is ``expired``, and no longer ``active``.
    // The data plane should stop applying the rate limiting strategy to the bucket, and transition
    // the bucket to the "expired assignment" state. This activates the behavior configured in the
    // expired_assignment_behavior field.
    //
    // **Replacing the assignment**
    //
    // * If the rate limiting strategy is different from bucket's ``active`` assignment, or
    //   the current bucket assignment is ``expired``, the data plane must immediately
    //   end the current assignment, report the bucket usage, and apply the new assignment.
    //   The new assignment becomes bucket's ``active`` assignment.
    // * If the rate limiting strategy is the same as the bucket's ``active`` (not ``expired``)
    //   assignment, **the data plane should extend the duration of the ``active`` assignment
    //   for the duration of the new assignment** provided in the assignment_time_to_live
    //   field. The ``active`` assignment is considered unchanged.
```

**三条落码事实**：

1. 租约字段就叫 `assignment_time_to_live`，是**时长不是绝对时刻**（`google.protobuf.Duration`）；`0` 表示"立即过期"（L143–145），**服务端用 `ttl = 0` 主动让客户端提前降级**（注释里举的例子是"server going into restart"）。
2. **"续租"不重置配额**：策略没变就只延 `ttl`，`active` assignment 视为未变；策略变了才"立即结束 + 上报用量 + 换新的"。这跟 Doorman 的 `Assign` 无脑重置 `Expiry` 不同（见 5.2）。
3. **服务端有显式召回**（`AbandonAction`，L159–190）：

```
      //   the assignment. While expiring the assignment just transitions the bucket to
      //   the "expired assignment" state; abandoning the assignment completely erases
      //   the bucket from the data plane memory, and stops the usage reports.
```
```
    // Abandon action for the bucket. Indicates that the RLQS server will no longer be
    // sending updates for the given bucket_id.
    //
    // If no requests are reported for a bucket, after some time the server considers the bucket
    // inactive. The server stops tracking the bucket, and instructs the the data plane to abandon
    // the bucket via this message.
```

**注意**：这与 [票 00](../research/00-lease-reclaim-prior-art.md) 的结论"服务端主动召回在一手源码里找不到任何实现"**有出入**——RLQS 的 proto 明确定义了 `AbandonAction`（由"太久没收到上报"触发）。票 00 当时只翻到了 Doorman/Sentinel/Envoy 的文档层，没有翻 rlqs.proto。**建议在票 04 里修正这条**：召回在 RLQS 里是"服务端判定 bucket 失活 → 通知客户端删除"，语义是**弃桶而不是退零头**，与票 00 的结论仍一致（仍不是"退回本批没用完的零头"），但"找不到任何实现"这个措辞需要放宽为"协议里有、开源实现里没有"。

---

## ⚠️ 未核实项

1. **commit SHA 大多没拿到。** 只有 bucket4j 探到 `5f3336b92feed0e2b08e8c7212a4448387cd123b`。Redisson / Sentinel / Doorman / envoyproxy-ratelimit / envoy 的 HEAD SHA 因 GitHub API 限流（`API rate limit exceeded`）与 `git ls-remote` 被环境挡住而**未取到**；本文引用的行号均对应 **2026-09-22 抓取的 master / main 快照**，日后上游改动会导致行号漂移。Guava 例外，用 tag `v33.4.0`，行号稳定。
2. **Sentinel token 请求超时的单位是推断的。** `ClusterConstants.DEFAULT_REQUEST_TIMEOUT = 20` 与 `promise.await(ClusterClientConfigManager.getRequestTimeout())`（`NettyTransportClient.java` L224）我判定是 **20 毫秒**，依据是 Netty `ChannelFuture.await(long timeoutMillis)` 的语义。**没有读 Netty 源码确认重载解析**，也没有找到 Sentinel 侧把 20 解释成秒的代码。高置信但仍是推断。
3. **Sentinel 服务端滑动窗口的默认窗口长度 / sampleCount 未读。** 只看清 `ClusterMetricStatistics` L63 从 `ClusterServerConfigManager.getSampleCount()` 取值；`getSampleCount()` / 对应 interval 的默认数值没有读。所以 `tryOccupyNext` 返回的 `1000 / sampleCount` 具体是多少毫秒未确定。
4. **Redisson 的 `res = 3 + interval - (...)` 里那个 `3` 没有找到任何解释**（无注释、无常量定义）。我按"毫秒余量"理解，但这是**推测，不是源码事实**。
5. **Redisson `trySetRateAsync` L374 疑似上游笔误**：`hsetnx KEYS[1] 'keepAliveTime' ARGV[3]` 与随后 `hsetnx KEYS[1] 'type' ARGV[3]` 用了同一个 `ARGV[3]`（而 `ARGV[3]` 是 `type.ordinal()`）。我只做了静态阅读，没有跑测试确认，也没有找上游 issue。
6. **bucket4j `Sampling` 的预测数学未逐行引用。** `Sampling.java`（5,403 字节）已下载但未展开其 `predictedConsumptionByOthersSinceLastSync` 的公式；`AsyncBatchingExecutor` 的批处理窗口/触发条件同样未逐行读。所以 2.6 节只能给到"有这层优化 + 参数名与语义"，给不到公式。
7. **bucket4j `MathType` 的历史。** 当前 master 只剩 `INTEGER_64_BITS` 一个枚举值；**我没有去查 `DOUBLE` 是哪个版本被移除的**，也没有核对 7.x 的 `RefillStyle`（`SMOOTH` / `INTERVALLY`）API，所以"refillGreedy vs refillIntervally 的区别"只覆盖了 master 的 `Bandwidth.isRefillIntervally()` 分支，没有覆盖历史公开 API 的对照。
8. **是否真的"每次都远程"未做运行时验证。** bucket4j Redis 后端的 `tryConsume` 是否必然触发一次 `eval`（在开启 Delay/Predictive 优化后显然不是），只有源码路径支持；**没有起 Redis 跑一遍数命令**。同理 Redisson 的往返次数也只是从 Lua 调用点推断。
9. **Doorman `FairShare` 的迭代再分配第二段未读完。** `algorithm.go` 在 L140 之后还有"extra 重新分配"的循环，我只读到注释，没有逐行引用。"5s 内静默忽略"这条来自 `doc/design.md` L390–393，**我没有在 `server.go` 里找到对应的实现代码**，所以它是"文档声明"而非"落码事实"。
10. **`Learn`/`learning mode` 未读。** `Resource.Decide` 里 `res.learner(...)` 走的是 `Learn(algo)` 分支，该函数实现在 `algorithm.go` 后半段，本次未读。
11. **Envoy RLQS 的服务端行为只能从 proto 注释推断。** 官方 `global_rate_limiting.rst` L54 明说 *"Open source reference implementation of the rate limiting service is currently unavailable."* 所以 6.7 节全部是**协议契约**，没有任何服务端/客户端实现代码可核。`expired_assignment_behavior` / `no_assignment_behavior` / `reporting_interval` 三个字段的具体取值也只在 HTTP filter 的 proto 里，本次未读。
12. **全部为静态源码阅读，未跑任何一条实现。** 无实测数据、无压测、无故障注入。文中出现的"每请求往返一次"等性能判断均源自代码结构与文档措辞，不是实测。
