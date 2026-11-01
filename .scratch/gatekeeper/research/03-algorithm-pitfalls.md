# 03 调研：算法细节与陷阱清单

_调研日期 2026-09-22。回答 [票 03](../issues/03-research-algorithm-pitfalls.md) 的 6 个问题，供 [票 04](../issues/04-grill-lease-model.md)（在飞量上界 / 租约口径）与 [票 05](../issues/05-grill-local-bucket-and-admission.md)（本地桶 / 时间源）拍板。**只收事实与推导，不做选型判断。**_

> ⚠️ **2026-09-22 更新（§4 已作废）**：[票 04](../issues/04-grill-lease-model.md) 选定**速率租约**（中心下发份额速率，不扣令牌），本报告 §4「在飞量的上界推导」的**前提被换掉、整节作废**，其重推归 [票 12](../issues/12-research-rate-lease-bounds.md)。§1（精度）/ §2（时间源）/ §3（突发语义）/ §5（幂等）**不受影响**，仍是票 05 / 06 / 08 的依据；**§6.2 要重新读**——速率口径采纳的账本形状正是它判为"不可行"的"设计①"，见该节注记。
>
> ⚠️ **2026-09-22 追加（[票 05](../issues/05-grill-local-bucket-and-admission.md) 已裁）**：① §2.4 / §2.5 的"用谁的时钟"**已裁为**单调钟差值 + 租约只传时长（见 §2.5 末尾注记）；② §5.4 的"fail-open 的本地速率必须是 `R/N` 而不是 `R`"在速率口径下**自动满足**（本地跑的速率就是份额），见该节注记；③ **§3.2 的两行对照表已过时**——它把 gatekeeper 的本地突发上界写成"批量 `B`"（令牌口径），速率口径下是**份额 × burstSeconds**，且本地桶**自己会补令牌**（是整形器，不是搬运工），见该节注记；④ §1.4 的取舍已定：时间轴用 **`long` 纳秒**（§1.4 本来就把它作为结论给出）；令牌数 §1.4 说"`double` 或 `long` 定点都可以、**不是选型依据**"——**票 05 Q6 选了 `long` 定点（微令牌，1 req = 10⁶）**，理由是跨语言照协议实现时消掉浮点舍入的分歧。

## 阅读约定

- 标 **「逐字引用」** 的块是从来源原文复制的，附 URL；方块内不做改写。
- 标 **「本节为推导，未实测」** 的段落，是我基于已引用的源码/文档做的推理与算术，**没有跑过基准或压测**。数值是算出来的，不是测出来的。
- 标 **「未核实」** 的转到文末 `## ⚠️ 未核实项`。

## 摘要（6 条一句话结论）

1. **精度**：Guava 用 `long`（微秒）记时间戳 + `double` 记令牌数。相对误差的量级是 **R×10⁻⁶**（R 单位 permits/s），只在 R 逼近 10⁶/s 时失控——Guava 源码里有一条 TODO 明说这条路径的上限是"微秒精度"。
2. **时间源**：`System.nanoTime` 的规范**没有承诺单调**，但 HotSpot 在 Linux 上把 `may_skip_backward` 显式声明为 `false`；JVM 不给跨进程保证（原点任意、可能为负）。**租约必须用"时长"而不是"绝对时刻"表达**，两端各用各自的单调时钟记时。Doorman 的租约用的却是墙钟 `seconds since the epoch`，这是一处可对照的先例。
3. **突发语义**：Guava `SmoothBursty` 默认 `maxBurstSeconds = 1.0`，即 `capacity = rate × 1s`；`SmoothWarmingUp` 解决的是"被限流的那个下游自己需要预热"的场景（冷因子硬编码 3.0），不是"客户端冷启动"。
4. **在飞量上界（本票核心）**：**瞬时突发 ≤ min{ K + f·N·B , R·T + 2K }**（K = 规则桶容量）。**上界里没有 N 出现在主导项**——N 大时上界被 `R·T + 2K` 封顶。但"在飞量 ≤ C×配额"这条约束**能不能兜住，完全看"配额"锚在什么上**：锚在 `R·T` 时 C=2 等于放行 20 倍突发（无效）；锚在 K 时 C=1 才等于"突发 ≤ 2 倍"。⚠️ **本条随 §4 一并作废**（[票 04](../issues/04-grill-lease-model.md) 选定速率租约，其前提"中心扣账发令牌"被换掉）；重推归 [票 12](../issues/12-research-rate-lease-bounds.md)。
5. **重试幂等**：HTTP POST 非幂等（RFC 9110 §9.2.2），超时重试必然重复领。幂等键要用客户端生成的 UUID（Stripe 的既有形状），服务端用 `(clientId, idempotencyKey)` 去重并保留一段时间。
6. **中心容量**：**不要按租约条目记账**（每 key 每客户端一条 ≈ 100 B，N=200 时 10k key 就要 200 MB）。用"近 T 秒发放量"的时间环近似在飞量，每 key ≈ (76 + 8T) 字节。心跳/续租**必须按客户端聚合**，绝不能按 (key, 客户端) 打点。⚠️ **前半句的前提随 §4 作废**（时间环是为"在飞上限"服务的，该机制已撤销）；而**速率口径恰恰要按"每 (key, 客户端) 一条租约"记账**——即 §6.2 的"设计①"，选它就要认下这笔内存账，量级待 [票 12](../issues/12-research-rate-lease-bounds.md) 重算。**最后一句（按客户端聚合）不受影响**，且被票 04 采纳为协议的硬要求。

---

## 1. 数值精度与漂移

### 1.1 Guava 用的是什么（参考实现的基线）

Guava `SmoothRateLimiter` 的状态字段（[SmoothRateLimiter.java](https://github.com/google/guava/blob/master/guava/src/com/google/common/util/concurrent/SmoothRateLimiter.java)）：

```java
  /** The currently stored permits. */
  double storedPermits;

  /** The maximum number of stored permits. */
  double maxPermits;

  double stableIntervalMicros;

  /** ... After granting a request, this is pushed further in the future. ... */
  private long nextFreeTicketMicros = 0L; // could be either in the past or future
```

**逐字引用**（同上，`reserveEarliestAvailable`）：

> `this.nextFreeTicketMicros = LongMath.saturatedAdd(nextFreeTicketMicros, waitMicros);`

即：**令牌数是 `double`，时间轴是 `long`（微秒），时间轴推进时显式做饱和加法防溢出。** 这是"用定点整数管时间、用浮点管数量"的一条真实取舍。

惰性补令牌的算式（同上，`resync`）：

```java
  final void resync(long nowMicros) {
    if (nowMicros > nextFreeTicketMicros) {
      double newPermits = (nowMicros - nextFreeTicketMicros) / coolDownIntervalMicros();
      storedPermits = min(maxPermits, storedPermits + newPermits);
      nextFreeTicketMicros = nowMicros;
    }
  }
```

注意三件事：**(a)** 补令牌是"算一次差值除以间隔"，不是"每个请求累加一个 delta"——所以浮点误差**不按请求数累积**；**(b)** `nowMicros` 是从 `Stopwatch.elapsed(MICROSECONDS)` 来的，**纳秒被截断到微秒**；**(c)** `resync` 里 `nextFreeTicketMicros = nowMicros` 把亚微秒余量丢在 `storedPermits` 的 `double` 里（未丢，但被截断的微秒部分丢了）。

Guava 自己知道这条路径的精度上限——**逐字引用**（[RateLimiter.java](https://github.com/google/guava/blob/master/guava/src/com/google/common/util/concurrent/RateLimiter.java)，类声明上方的 TODO）：

> `// TODO(user): switch to nano precision. A natural unit of cost is "bytes", and a micro precision`
> `// would mean a maximum rate of "1MB/s", which might be small in some cases.`

### 1.2 double vs 定点：误差量级推算

**本节为推导，未实测。**

分两块看，别混为一谈。

**(a) 浮点（double）的舍入误差** —— 因为补令牌是"每次 acquire 算一次差值"，误差**不随请求数累积**。单次 `storedPermits + newPermits` 的相对误差 ≤ 2⁻⁵³ ≈ 1.11×10⁻¹⁶。设 `storedPermits` 的量级是 K：

```
单次绝对误差 ≤ K × 1.11e-16
```

代入 K = 10⁶ 令牌 → 单次误差 ≤ 1.1×10⁻¹⁰ 个令牌。一周按 1000 req/s 计 ≈ 6.05×10⁸ 次 resync，最坏情况（每次都往同方向舍入）误差 ≤ 6.05e8 × 1.11e-10 / 1e6 ... 直接算：6.05e8 × 1.1e-10 = 0.067 个令牌。**一周漂移不到 0.1 个令牌，可忽略。**

真正的浮点风险不是累积，而是**把大数值的绝对时间戳塞进 double**：epoch 毫秒 ≈ 1.75×10¹²，double 在该量级上的 ULP 是 2⁻⁵² × 2⁴¹ ≈ **244 微秒**。所以"把墙钟时刻存成 double"会直接丢掉亚毫秒分辨率。**时间戳一律用 long，不用 double**，这条没有争议。

**(b) 定点整数（long 纳秒 / long 微秒）的截断误差** —— 截断是**系统性**的（永远向下），会累积，要算。

两条截断路径：

| 路径 | 每次误差 | 后果 |
|---|---|---|
| `(long)(freshPermits * stableIntervalMicros)` 把等待微秒数截断 | < 1 µs 的"未来时间"丢失 | **少节流 → 超发** |
| `Stopwatch.elapsed(MICROSECONDS)` 把纳秒截断到微秒 | < 1 µs 的"已过时间"丢失 | 少补令牌 → 保守 |

**推导超发量**：设速率为 R（permits/s），观察窗 W 秒。窗口内 reserve 次数 ≈ R·W 次，每次最多丢 1 µs 的节流时间，累计丢 < R·W×10⁻⁶ 秒。在这段"被偷回来的时间"里限流器按 R 放行，多放行的令牌数：

```
Δextra ≤ R × (R·W×10⁻⁶) = R²·W·10⁻⁶
相对超发 = Δextra / (R·W) = R × 10⁻⁶
```

即**相对超发是与窗口无关的常数 `R×10⁻⁶`**：

- R = 1,000/s → 0.1% 超发（无所谓）
- R = 100,000/s → 10% 超发（要警惕）
- R = 1,000,000/s → `stableIntervalMicros = 1.0`，截断归零；再高一点（R = 1.5×10⁶/s → interval = 0.667µs）**每次等待都被截断成 0，限流失效**。

**结论：R ≈ 10⁶/s 是"微秒时间轴"的硬顶。** 这也正好解释了 Guava 那条 TODO 的由来。若 gatekeeper 用 `long` 纳秒记时间轴（而不是微秒），这个硬顶抬到 10⁹/s，同时 `long` 纳秒的溢出边界是 292 年（见下），完全够。

**(c) 定点令牌数（long 缩放）的截断** —— 若不用 double 而用 `long` 存"微令牌"（1 令牌 = 10⁶ 单位），每次补令牌截断 < 1 单位 = 10⁻⁶ 令牌，也是系统性少补。一周 1000 ops/s：

```
6.05e8 次 × 1e-6 令牌 = 605 个令牌
相对误差 = 605 / (6.05e8 次×1 令牌) ≈ 1e-6
```

**1e-6 相对漂移，可忽略。** 定点 vs 浮点在"数量"这一侧的差别（1e-16 vs 1e-6）是 10 个数量级，但两者都远低于限流器需要的精度（1e-3），所以**这条不是选型依据**——真正决定成败的是**时间轴用什么单位**（微秒 vs 纳秒），不是令牌数用什么类型。

### 1.3 单调递增时间戳的溢出边界

**逐字引用**（Java 21 `System.nanoTime()` javadoc，[docs.oracle.com](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/System.html)）：

> `Differences in successive calls that span greater than approximately 292 years (2^63 nanoseconds) will not correctly compute elapsed time due to numerical overflow.`
>
> `To compare elapsed time against a timeout, use`
> `  if (System.nanoTime() - startTime >= timeoutNanos) ...`
> `instead of`
> `  if (System.nanoTime() >= startTime + timeoutNanos) ...`
> `because of the possibility of numerical overflow.`

**算术**（本节为推导，未实测）：

| 时间轴单位 | long 可表示的跨度 | 换算 |
|---|---|---|
| 纳秒 | 2⁶³ ns | 9.223×10¹⁸ ns = 292.28 年 |
| 微秒 | 2⁶³ µs | 292,277 年 |
| epoch 纳秒（1970 起） | 到 2⁶³ ns 为止 | **2262-04 前后溢出** |

要点：

- **用"差值"就永远不会溢出**（`long` 纳秒的 292 年 ≫ JVM 进程寿命）。javadoc 明说要用差值形式。
- **用"绝对截止时刻"就有风险**：`deadline = System.nanoTime() + T` 在 `nanoTime()` 已经很大的进程里会溢出。Linux 上 `nanoTime ≈ 开机以来的纳秒`（见 §2.2 的 HotSpot 源码），开机 292 年才会溢出；但**规范不承诺原点**，所以这不是可以依赖的性质。
- 如果租约里改用"epoch 纳秒"当绝对时间戳，边界是 **2262 年**——工程上够用，但要知道这是个存在的 cliff。
- 顺带一提：Guava 的 `Uninterruptibles.sleepUninterruptibly` 用的是 `long end = System.nanoTime() + remainingNanos;`（**逐字引用**，[Uninterruptibles.java](https://github.com/google/guava/blob/master/guava/src/com/google/common/util/concurrent/Uninterruptibles.java)），也就是 javadoc 警告过的加法形式；而 Guava 在自己需要睡眠纳秒数时还专门写了 `OverflowAvoidingLockSupport` 把 `parkNanos` 的参数夹到 `(1L + Integer.MAX_VALUE) * 1_000_000_000L - 1L`（**逐字引用**，[OverflowAvoidingLockSupport.java](https://github.com/google/guava/blob/master/guava/src/com/google/common/util/concurrent/OverflowAvoidingLockSupport.java) 注释：`Works around an android bug, where parking for more than INT_MAX seconds can produce an abort signal on 32 bit devices running Android Q.`）。**同一个库在两个地方对溢出的态度不同**——说明这类溢出是"看平台、看调用点"的，不是一劳永逸的。

### 1.4 落到 gatekeeper 的结论（事实层面）

- 时间轴用 **`long` + 纳秒单调时钟**，不用微秒（抬掉 10⁶/s 硬顶），不用 double 存时间戳。
- 令牌数用 `double` 或 `long` 定点都可以（误差 1e-16 vs 1e-6，都远够）；**但两者都不能解决"时间轴单位"的问题**。
- 截止时刻一律 **存"剩余时长"或"基于单调差值的 deadline"**，避免 `nanoTime() + T` 的绝对形式。
- 长时运行的漂移量级：**浮点/定点在数量侧都是 1e-6 相对误差以下，一周不到 1 个令牌**——**漂移不是 gatekeeper 的主要风险**，主要风险在 §4 的在飞量和 §2 的时间源。

---

## 2. 时间源

### 2.1 `nanoTime` 的规范语义

**逐字引用**（Java 21 `System.nanoTime()` javadoc，[docs.oracle.com](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/lang/System.html)）：

> `Returns the current value of the running Java Virtual Machine's high-resolution time source, in nanoseconds.`
>
> `This method can only be used to measure elapsed time and is not related to any other notion of system or wall-clock time. The value returned represents nanoseconds since some fixed but arbitrary origin time (perhaps in the future, so values may be negative). The same origin is used by all invocations of this method in an instance of a Java virtual machine; other virtual machine instances are likely to use a different origin.`
>
> `This method provides nanosecond precision, but not necessarily nanosecond resolution (that is, how frequently the value changes) - no guarantees are made except that the resolution is at least as good as that of currentTimeMillis().`
>
> `The values returned by this method become meaningful only when the difference between two such values, obtained within the same instance of a Java virtual machine, is computed.`

三条硬事实：

1. **原点任意，可能为负** → 不能持久化、不能跨进程比较、不能当绝对时间用。
2. **不同 JVM 实例的原点不同** → **客户端和服务端的 `nanoTime` 之间没有任何可比性**。这一条直接决定了租约的表达方式（§2.4）。
3. **只有"同一 JVM 内两个值相减"才有意义** → 租约的本地时钟必须存在客户端自己的 `nanoTime` 差值里。

对比**逐字引用**（Java 21 `System.currentTimeMillis()` javadoc）：

> `Note that while the unit of time of the return value is a millisecond, the granularity of the value depends on the underlying operating system and may be larger. For example, many operating systems measure time in units of tens of milliseconds.`

### 2.2 `nanoTime` 会不会回退？

分三层回答，**规范、实现、实践不一致**。

**规范层：没有承诺单调。** 上面那段 javadoc 通篇没有出现 "monotonic" 一词——它只说"只能用来测流逝时间"。

**实现层（Linux）：HotSpot 显式声明不回退。** **逐字引用**（OpenJDK `src/hotspot/os/posix/os_posix.cpp`，[GitHub](https://github.com/openjdk/jdk/blob/master/src/hotspot/os/posix/os_posix.cpp)）：

```cpp
jlong os::javaTimeNanos() {
  struct timespec tp;
  int status = clock_gettime(CLOCK_MONOTONIC, &tp);
  assert(status == 0, "clock_gettime error: %s", os::strerror(errno));
  jlong result = jlong(tp.tv_sec) * NANOSECS_PER_SEC + jlong(tp.tv_nsec);
  return result;
}

void os::javaTimeNanos_info(jvmtiTimerInfo *info_ptr) {
  // CLOCK_MONOTONIC - amount of time since some arbitrary point in the past
  info_ptr->max_value = all_bits_jlong;
  info_ptr->may_skip_backward = false;      // not subject to resetting or drifting
  info_ptr->may_skip_forward = false;       // not subject to resetting or drifting
  info_ptr->kind = JVMTI_TIMER_ELAPSED;     // elapsed not CPU time
}
```

注释里的 `// not subject to resetting or drifting` 和两个 `false` 是 **JDK 自己的声明**：在走这条通用 `CLOCK_MONOTONIC` 路径的平台（`!__APPLE__ && !__AIX__`，即 Linux/Windows 的通用分支与多数 Unix）上，`nanoTime` 既不回退也不跳变。**注意源码注释说 macOS 与 AIX 是平台特定实现**（上方注释：`macOS and AIX have platform specific implementations for javaTimeNanos()`），也就是说这条强保证**不覆盖 macOS**。

**实践层：回退是被报告过的问题。** **逐字引用**（David Holmes 在 JDK-8040140 评审串里的话，[mail.openjdk.org](https://mail.openjdk.org/pipermail/hotspot-runtime-dev/2014-April/011380.html)）：

> `The primary use of getTimeNanos() is the Java System.nanoTime() call. It is used for timestamping and tracking elapsed time. Time going backwards can cause hangs, inconsistent output etc.`
>
> `But note that the monotonicity check can also cause hangs, or at least delays, due to time standing still. We've had a number of bugs filed on that in the past. So things can go wrong either way. We guard against a buggy OS where we can, but I think we need to be able to tell the VM there is no need.`

同串里提到的开关叫 `AssumeMonotonicOSTimers`（用在 Solaris 分支上，跳过"取 max"的防护以换取性能）。我 grep 了 `jdk17u` 的 `src/hotspot/share/runtime/globals.hpp`，**没有找到该 flag**——它属 JDK 8 时代的实验性开关，在当代 JDK 里已检索不到。

**给 gatekeeper 的净结论**：`nanoTime` 在主流 Linux 部署上是单调的（JDK 自己声明 `may_skip_backward = false`），但这是**实现性质而非规范保证**。防御成本很低：**每个 key 维护 `lastSeenNanos`，新值 `<=` 旧值时按"时间没走"处理（不回退、不补令牌、不推进 deadline）**，等价于 `max(0, now - last)` 的写法。这个防御值得做，因为它把"一次 OS/虚拟化层的时序抖动"从"令牌凭空多出来"降级为"这一拍不补令牌"。

### 2.3 墙钟：NTP 校正与回拨

**逐字引用**（chrony `makestep` 指令文档，[chrony-project.org](https://chrony-project.org/doc/4.5/chrony.conf.html)）：

> `Normally chronyd will cause the system to gradually correct any time offset, by slowing down or speeding up the clock as required. In certain situations, e.g. when chronyd is initially started, the system clock might be so far adrift that this slewing process would take a very long time to correct the system clock.`
>
> `This directive forces chronyd to step the system clock if the adjustment is larger than a threshold value, but only if there were no more clock updates since chronyd was started than the specified limit.`
>
> `On most systems it is desirable to step the system clock only on boot, before starting programs that rely on time advancing monotonically forwards.`

最后一句是运维侧的常识：**跳变只应该在启动时发生**。默认示例 `makestep 0.1 3` 的意思是"偏移 > 0.1s 就跳，但只在前 3 次校时"。也就是说：**正常运行中的 NTP 是"缓慢 slew"而不是"跳变"**——这对限流器是好消息，因为 slew 是连续的小幅变速（典型 ≤ 500 ppm，即每秒最多改 0.5 ms），不会造成跨秒级的 `currentTimeMillis` 回退。

坏消息是**闰秒**。**逐字引用**（LKML，John Stultz 的修复补丁说明，[lkml.org](https://lkml.org/lkml/2012/7/5/440)）：

> `[PATCH 0/3] Fix for leapsecond caused hrtimer/futex issue`

同串（Linux 内核归档，[lkml.iu.edu](https://lkml.iu.edu/hypermail/linux/kernel/1207.0/02318.html)）记录了后果：2012-06-30 的闰秒让内核时间keeping 没有通知 hrtimer 子系统，`CLOCK_REALTIME` 定时器早触发一秒，MySQL / Firefox / **Java** 等进程出现 100% CPU 的 futex 风暴，RHEL6 上的 JVM 会被拖住，workaround 是 `date -s "\`date\`"` 或重启。

**Java 侧的处置**（**逐字引用**，OpenJDK hotspot-runtime-dev 串「Leap Second Kernel issue (6/30/2012) and JDK-6900441」，[mail.openjdk.org](https://mail.openjdk.org/pipermail/hotspot-runtime-dev/2015-April/014538.html)）：David Holmes 确认那是内核 bug 不是 Java bug，并指出 JDK-6900441 的修复**把相对等待切到了 `CLOCK_MONOTONIC`**，而 `CLOCK_MONOTONIC` 在闰秒时不被调整，所以 Java 的相对计时不再受这个内核问题影响。

**推论（本节为推导，未实测）**：`nanoTime`（走 `CLOCK_MONOTONIC`）**不受 NTP 与闰秒影响**；`currentTimeMillis`（走 `CLOCK_REALTIME`）**会受闰秒与 `makestep` 影响，可能重复或回退**。所以：**限流的计时一律用单调时钟；墙钟只用于"人类可读的日志/展示"**。

### 2.4 两端时间不同源时，租约怎么算

**本节为推导，未实测**（事实依据是 §2.1 的三条硬事实与下面引用的 Doorman 数据）。

先把"用谁的时间"拆成三种语义——它们常被混为一谈：

| 语义 | 谁的时间在判定 | 两端时钟不同源时的后果 |
|---|---|---|
| **(a) 客户端侧的租约有效期**（"我还能用这 20 个令牌到什么时候"） | 客户端自己的时钟 | 只要客户端本地用 `nanoTime` 差值算，**完全不受跨机时钟影响**。这是免费的正确做法 |
| **(b) 中心侧的租约有效期**（"这个客户端的在飞量什么时候从账上消掉"） | 中心自己的时钟 | 中心用**自己的**单调时钟结算，**不接受客户端上报的时间戳做权威**。同样是免费的正确做法 |
| **(c) 客户端和服务端对"到期时刻"达成一致**（绝对时间戳） | 需要两钟同源 | **做不到**。`nanoTime` 原点不同（javadoc 明说），墙钟有偏差与漂移 |

**(c) 是陷阱所在。** 具体偏差的量化（**推导**）：设客户端墙钟比中心快 δ 秒。

- δ > 0（客户端钟快）：客户端认为租约早 δ 秒到期，**提前丢弃** → 浪费的配额最多 `R·δ`... 更准确地说，客户端少用了 δ 秒的授权，中心的账上却有它 → 这一段在飞量虚高。
- δ < 0（客户端钟慢）：客户端认为租约晚 |δ| 秒到期 → **中心已经认为它过期了，客户端还在花** → 实现在飞量比账上多，最多多出 `R·|δ|` 个令牌。δ = 1 s、R = 1000/s → **多出 1000 个令牌的在飞量**，相当于凭空多出 1 秒的桶。

**所以"用谁的时间"的正确答法是：两边各用各的单调时钟，租约在协议里只传"时长"（`leaseTtlMillis` 这种相对量），不传绝对到期时刻。** 中心的账本按"发放时刻（中心单调时钟）+ T"自己结算，客户端的本地桶按"收到时刻（客户端单调时钟）+ T"自己结算。两边各自闭环，无需对齐时钟。

**HTTP 世界里有一个现成的同构先例**：**逐字引用**（RFC 9111 §4.2.1，[rfc-editor.org](https://www.rfc-editor.org/rfc/rfc9111.html#section-4.2.1)）：

> `Note that this calculation is intended to reduce clock skew by using the clock information provided by the origin server whenever possible.`

HTTP 缓存算新鲜度时，并不是拿本机时钟去和 `Expires` 比，而是算 **`Expires − Date`（两个值都来自服务端时钟）再和本地已流逝时间比**。这就是"把跨机比较换成同源差值"的标准手法。**gatekeeper 的租约应当照抄这个形状。**

**反例（真实先例）**：Doorman 的租约用的恰恰是墙钟绝对时刻——**逐字引用**（[doorman.proto](https://github.com/youtube/doorman/blob/master/proto/doorman/doorman.proto)）：

> `// Encapsulates a lease on capacity with a expiry_time in seconds`
> `// since the epoch.`
> `message Lease {`
> `  required int64 expiry_time = 1;`
> `  required int64 refresh_interval = 2;`
> `  required double capacity = 3;`
> `}`

Doorman 之所以能这么干：它的客户端和中心都在同一个数据中心的 NTP 同步域里，且租约长 5 分钟、续租 5 秒，秒级偏差被 5 分钟的量级吸收掉了。**如果 gatekeeper 的部署前提（内网 + 统一 NTP）和它一致，用墙钟也"没有实际出事"；但用单调时长没有成本，且不依赖运维前提。** 事实摆在这里，选哪条留给票 05。

> ⚠️ **2026-09-22 已裁**（[票 05](../issues/05-grill-local-bucket-and-admission.md) Q3）：选**单调钟差值 + 租约只传时长**——本地记"收到时刻（自己的 `nanoTime`）+ ttl"，中心用自己的单调钟结算到期，**两侧各自闭环、不对齐时钟**；Doorman 的墙钟绝对时刻之路**不取**（它依赖"内网 NTP + 5 分钟租约"这个运维前提）。防回拨照本节建议写一行（`now <= lastRefillNanos` ⇒ 按"时间没走"处理）。

### 2.5 结论

- `nanoTime` 在 Linux 上单调（JDK 声明 `may_skip_backward = false`），但这是实现性质，防御写一行 `max(0, now − last)` 就够；macOS/AIX 是平台特定实现，不受这条声明覆盖。
- 墙钟 `currentTimeMillis` 会受 `makestep` 与闰秒影响，**限流的判定不用它**。
- 跨机时钟无需对齐：**租约传"时长"，两端各用各自单调时钟**；绝对到期时刻是陷阱（δ = 1 s 就能多出 `R` 个令牌的在飞量）。
- Doorman 用墙钟 `expiry_time` 是先例，但它靠"内网 NTP + 5 分钟租约"这个前提成立。

---

## 3. 突发语义与预热

### 3.1 `capacity` 与 `rate` 的配比

**Guava 默认：`capacity = rate × 1 秒`。** **逐字引用**（[RateLimiter.java](https://github.com/google/guava/blob/master/guava/src/com/google/common/util/concurrent/RateLimiter.java)）：

> `The returned RateLimiter ensures that on average no more than permitsPerSecond are issued during any given second, with sustained requests being smoothly spread over each second. When the incoming request rate exceeds permitsPerSecond the rate limiter will release one permit every (1.0 / permitsPerSecond) seconds. When the rate limiter is unused, bursts of up to permitsPerSecond permits will be allowed, with subsequent requests being smoothly limited at the stable rate of permitsPerSecond.`

配比由 `maxBurstSeconds` 这个乘数控制——**逐字引用**（[SmoothRateLimiter.java](https://github.com/google/guava/blob/master/guava/src/com/google/common/util/concurrent/SmoothRateLimiter.java)）：

> `This implements a "bursty" RateLimiter, where storedPermits are translated to zero throttling.`
> `The maximum number of permits that can be saved (when the RateLimiter is unused) is defined in terms of time, in this sense: if a RateLimiter is 2qps, and this time is specified as 10 seconds, we can save up to 2 * 10 = 20 permits.`

即 **`K = maxBurstSeconds × R`**，默认 `maxBurstSeconds = 1.0`（`RateLimiter.create` 内部 `new SmoothBursty(stopwatch, /* maxBurstSeconds= */ 1.0)`）。**Guava 把"突发量"表达成了一个"秒数"**——这是个值得沿用的界面：配比不是"capacity : rate = 5 : 1000"这种裸数字，而是"允许多少秒的流量一次性通过"。

Guava 还给了一段说明为什么默认是 1 秒而不是 0——**逐字引用**（同文件，`create(double permitsPerSecond)` 的注释）：

> `The default RateLimiter configuration can save the unused permits of up to one second. This is to avoid unnecessary stalls in situations like this: A RateLimiter of 1qps, and 4 threads, all calling acquire() at these moments:`
> `T0 at 0 seconds / T1 at 1.05 seconds / T2 at 2 seconds / T3 at 3 seconds`
> `Due to the slight delay of T1, T2 would have to sleep till 2.05 seconds, and T3 would also...`

即：**桶容量存在的理由不是"奖励突发"，而是"吸收调度抖动"**。T1 晚了 50ms，如果没有桶，T2 就得被推迟 50ms——纯漏桶会把一次抖动放大成连锁延迟。这是个**与"要不要允许突发"无关**的、更基础的理由。gatekeeper 设计本地桶时同理：**本地桶哪怕容量极小，也不能是 0**。

### 3.2 "冷启动突发"是不是我们想要的

**本节为推导，未实测**（推导基于 §3.1 的引用事实）。

要把两种"空闲累积"分开：

| | Guava `SmoothBursty` 的位置 | gatekeeper 的位置 |
|---|---|---|
| 空闲时谁在攒 | 本地限流器自己攒 `storedPermits`（上限 `maxBurstSeconds × R`） | **中心**攒（中心的桶）；本地只有"已领但未花"的批量令牌 |
| 空闲很久的客户端能瞬间打满吗 | 能，最多打满 `capacity` | 能，最多打满**本地持有的批量 B**（本地桶不随时间增长） |

**关键区别：Guava 的突发上界是 `K`（规则自己允许的量）；gatekeeper 的本地突发上界是 `B`（领来的批量），而 B 通常远小于 K，也远小于 R·T。** 所以：

> ⚠️ **2026-09-22 注记（[票 05](../issues/05-grill-local-bucket-and-admission.md) 已裁）**：**上面这张表与下面这段描述是"令牌口径"下的读法，已不适用**——[票 04](../issues/04-grill-lease-model.md) 选速率租约后，本地桶**自己会补令牌**（是整形器，不是搬运工），突发上界是 **份额 × burstSeconds**（全局相加 ≤ `R × burstSeconds`，**与 N 无关**），不再有"本地持有的批量 `B`"这个量；"空闲客户端的批量会过期作废"（本节最后一条）同样不成立（没有批量）。本节**仍然成立**的部分：§3.1 的结论（逐字）「**桶容量存在的理由不是"奖励突发"，而是"吸收调度抖动"**」，以及 `SmoothWarmingUp` 的语义错配（分布式下"空闲"是键全局空闲，不是实例空闲）——后者归票 07 的中心侧发放策略（本设计 v1 不做）。

- **单个空闲客户端的冷启动突发 = B，不随空闲时长增长**（本地桶不补令牌，只搬运）。这一点比 Guava 更保守。
- **但是**"空闲很久"在 gatekeeper 里的真实风险不是"某个客户端攒多了"，而是**全局的空闲累积**：键空闲 10 分钟 → 中心桶按 R 攒满到 K（不超 K）→ 恢复正常流量瞬间，`f·N` 个客户端同时拿着各自的 B 冲过来 → 突发 = K + f·N·B（§4）。**这才是"冷启动突发"在分布式版本里的真正形状**：不是客户端攒的，是 N 个客户端各自持有的批量叠加起来的。

- 还有一个 gatekeeper 特有的反向问题：**空闲客户端的批量令牌会过期作废**。若中心在发放时就扣账且过期不退回，则键恢复流量时会发现"中心的桶被作废的令牌吃掉了"，表现为**欠服务**。§4.8 的算例 B 会量化这一条（90% 作废）。

### 3.3 `SmoothWarmingUp` 解决的是什么

**逐字引用**（[RateLimiter.java](https://github.com/google/guava/blob/master/guava/src/com/google/common/util/concurrent/RateLimiter.java)，`create(double permitsPerSecond, long warmupPeriod, TimeUnit unit)`）：

> `Creates a RateLimiter with the specified stable throughput, given as "permits per second" (commonly referred to as QPS, queries per second), and a warmup period, during which the RateLimiter smoothly ramps up its rate, until it reaches its maximum rate at the end of the period (as long as there are enough requests to saturate it). Similarly, if the RateLimiter is left unused for a duration of warmupPeriod, it will gradually return to its "cold" state, i.e. it will go through the same warming up process as when it was first created.`
>
> `The returned RateLimiter is intended for cases where the resource that actually fulfills the requests (e.g., a remote server) needs "warmup" time, rather than being immediately accessed at the stable (maximum) rate.`

也就说：**`SmoothWarmingUp` 的受保护对象是被调用的下游（如冷缓存、需要 JIT/连接池预热的服务），不是调用方**。它做的是"限流器在冷状态下故意少放行，让下游慢慢热起来"。

实现上，冷因子是**硬编码 3.0**（**逐字引用**，`RateLimiter.create` 内：`return create(permitsPerSecond, warmupPeriod, unit, 3.0, SleepingStopwatch.createFromSystemTimer());`），形状是一段梯形积分——**逐字引用**（[SmoothRateLimiter.java](https://github.com/google/guava/blob/master/guava/src/com/google/common/util/concurrent/SmoothRateLimiter.java) 的类注释图）：

```
 *          ^ throttling
 *          |
 *    cold  +                  /
 * interval |                 /.
 *          |                / .
 *          |               /  .   ← "warmup period" is the area of the trapezoid between
 *          |              /   .     thresholdPermits and maxPermits
 *          |             /    .
 *          |            /     .
 *  stable  +-----------/-------|---------------→ storedPermits
 *          0  thresholdPermits  maxPermits
```

以及 `maxPermits` 的推导（同注释）：`maxPermits = thresholdPermits + 2 * warmupPeriod / (stableInterval + coldInterval)`。

**给 gatekeeper 的净结论**：`SmoothWarmingUp` 的场景（保护冷下游）在分布式限流器里依然成立，但**它不该由"客户端本地桶"来承担**——本地桶本来就是搬运工，无法制造"全局冷状态"。如果 gatekeeper 要这个语义，它属于**中心侧的一次发放策略**（键刚从空闲转热时，中心按比例少发放，而不是直接放行到 R）。这是选型问题，留票 04。

> ⚠️ **2026-09-22 注记（[票 05](../issues/05-grill-local-bucket-and-admission.md) Q1）**："本地桶本来就是搬运工"这半句**在速率口径下不成立**（本地桶按份额自补，是整形器——见 §3.2 的注记）。**其余仍成立**：预热语义（键从空闲转热时中心少发）不该由客户端本地桶承担；本设计 v1 **不做**它。

**另外注意一个语义错配**：`SmoothWarmingUp` 的"冷状态"是由**空闲时长**触发的，而 gatekeeper 里"空闲"是**这个键全局没有请求**，不是"某个客户端实例没有请求"。一个键全局热但某个实例刚重启，该实例应该立刻拿到足额配额（它不冷）；一个键全局冷，所有实例都该被"预热"限制。**这两件事在 Guava 的单一进程模型里是同一件事，在分布式模型里不是。** 这是个容易照抄错的地方。

---

## 4. 在飞量的上界推导（本票核心）

> ⚠️ **本节已作废（2026-09-22）**：[票 04](../issues/04-grill-lease-model.md) 选定**速率租约**（中心发份额速率、发放不扣令牌），本节式 (1) 的令牌桶窗口性质与全部推论（(2)–(9')）**不再适用**；重推归 [票 12](../issues/12-research-rate-lease-bounds.md)。**⚠️ 2026-09-22 重推已完成**：[research/12](12-rate-lease-bounds.md)（带仿真）——瞬时突发 ≤ `Σ max(份额×burstSeconds, 1)`（正常路径 = `R × burstSeconds`，Σ份额 ≤ R 是硬不变量）、窗口 Δ 放行 ≤ `R(Δ+burstSeconds)`、收敛 ≤ 2 个续租周期无震荡、CPU 账 `Σ n_k²/T`、内存账 91 B/条；§4.8 的三条失效模式在速率口径下全部消失（[research/12](12-rate-lease-bounds.md) §6）。**原文保留，仅作历史与"哪些量级观念仍成立"的参照**——仍成立的部分：(a) 式 (2) 的形状（客户端囤积会被中心的发放速率先天封顶，即"在飞量不随 N 增长"这个直觉）；(b) §4.9「长期速率准的'长期'是分钟量级」；(c) §4.10 最后一段已经把"动态整形 = 速率租约"这条指出来了。

**本节为推导，未实测。** 下面的公式与算例全部是纸面推算；未做仿真、未做压测。请按"待验证的模型"使用，不要当成实测结论。

### 4.1 记号

| 符号 | 含义 | 单位 |
|---|---|---|
| `R` | 规则的全局速率（该 key 的长期允许速率） | permits/s |
| `K` | 规则的桶容量（允许的瞬时突发量） | permits |
| `T` | 租约时长 | s |
| `N` | 该 key 的客户端实例总数 | 个 |
| `f` | 瞬时活跃客户端比例（同一瞬间有请求要放行的实例占比），`0 < f ≤ 1` | 无量纲 |
| `B` | 单次批量租约的大小 | permits |
| `I(t)` | **在飞量 / 悬浮量**：t 时刻"中心已发放、客户端尚未消耗、且尚未过期"的令牌数 | permits |
| `G(t)` | 中心到 t 为止的**累计发放量**（含已过期作废的） | permits |
| `λ` | 可容忍的突发倍数（突发 ≤ λ·K 即认为"兜住了"） | 无量纲 |
| `C` | 在飞量上限的倍数（`在飞量 ≤ C × 配额`） | 无量纲 |

### 4.2 中心自身的发放量满足令牌桶性质

设中心对每个 key 维护一个速率为 `R`、容量为 `K` 的权威桶，发放时扣账、**过期不退**（"过期不退"是与 [research/00](00-lease-reclaim-prior-art.md) 的"不做运行期归还"自洽的选择）。则对任意时刻 `t` 与任意窗口 `Δ ≥ 0`：

```
(1)   G(t+Δ) − G(t) ≤ R·Δ + K        （标准令牌桶窗口性质）
```

> 若改成"过期退回"，式 (1) 需要改成对"有效发放"成立，但下面推出的上界不变（见 §4.9 的说明）。

### 4.3 在飞量自身的上界

`I(t)` 只包含"未过期"的令牌，所以它只能来自最近 `T` 秒的发放：

```
(2)   I(t) ≤ G(t) − G(t−T) ≤ R·T + K
```

**这是最重要的一条：在飞量的上界与 N、B 都无关**，只由 `R·T + K` 决定。直觉：**中心自己的发放速率就是 R，它在 T 秒内无论如何发不出超过 `R·T + K` 个令牌，而这些就是"可能还悬浮着"的全部令牌。**

### 4.4 全局瞬时放行量的上界

设我们考察窗口 `[t, t+Δ]`，`Δ → 0`。这段时间里被真正**放行**（客户端消耗掉）的请求数是：

```
放行量 = [G(t+Δ) − I(t+Δ)] − [G(t) − I(t)]
       = (G(t+Δ) − G(t)) + (I(t) − I(t+Δ))
       ≤ (R·Δ + K) + I(t)                      ← 用式 (1)，且 I(t)−I(t+Δ) ≤ I(t)
       ≤ R·Δ + K + (R·T + K)                   ← 用式 (2)
       = R·(Δ + T) + 2K
```

**推出主公式：**

```
(3)   任意窗口 Δ 内放行量 ≤ R·(Δ + T) + 2K
```

两个特例：

```
(3a)  瞬时突发（Δ → 0）：  Burst ≤ R·T + 2K
(3b)  长期平均（Δ ≫ T）：  avg ≤ R + (R·T + 2K) / Δ
```

**式 (3b) 就是"长期速率准、瞬时突发超标"这句话的定量版本**——超出量固定为 `R·T + 2K`，摊到窗口 Δ 上逐渐归零。

**另有一条不经过中心桶、只经由客户端囤积的更紧的界**（当 f·N·B 较小时它更紧）：每个客户端若只允许"本地空了才领"且**同一 key 同时只有一个在途领请求（single-flight）**，则任一时刻每个活跃客户端最多持有 `B`：

```
(4)   I(t) ≤ f·N·B
```

再把式 (2)、(4) 与"在飞上限 `C·配额`"一起代入式 (3)：

```
(5)   瞬时突发 ≤ K + min{ f·N·B ,  R·T + K ,  C·配额 }
```

（式 (3a) 的 `R·T + 2K` 与式 (5) 的 `K + (R·T+K)` 是同一个量，只是拆法不同。）

**主公式 (5) 的读法**：

- 括号里是**在飞量** `I` 的三个候选上界，取最小者。
- **突发 = K + 在飞量**：K 是中心那一瞬间还能放出的（桶里存着的），在飞量是客户端手里攒着的。**两项在极端情况下可以同时取到**（构造：`t−T` 时刻中心一次性发掉满桶 K 并在此后持续按 R 发放；这些令牌全部未被消耗；同时桶在这段时间里又攒回了 K）。
- **N 只出现在第一项 `f·N·B`**。当 N 大时这一项被 `R·T + K` 压过，**突发上界不再随 N 增长**。

### 4.5 算例

**算例 A（典型）**：`R = 1000/s`，`maxBurstSeconds = 1` ⇒ `K = 1000`，`T = 10 s`，`N = 200`，`B = 20`，`f = 1`。

```
在飞界①  f·N·B      = 1 × 200 × 20 = 4,000
在飞界②  R·T + K    = 1000×10 + 1000 = 11,000
在飞界③  (无上限约束)
I ≤ min(4000, 11000) = 4,000

瞬时突发 ≤ K + I = 1000 + 4000 = 5,000
        = 5.0 × K = 5.0 秒的流量
硬上界（式 3a） R·T + 2K = 10,000 + 2,000 = 12,000
min(5000, 12000) = 5,000  ← 界① 主导

长期：要让平均速率落在 R 的 5% 以内，需要窗口
   Δ ≥ 20 × (R·T + 2K) / R = 20 × 12,000/1000 = 240 s
```

**算例 B（N 很大）**：`R = 1000/s`，`K = 1000`，`T = 10 s`，`N = 5000`，`B = 20`，`f = 1`。

```
在飞界①  f·N·B      = 1 × 5000 × 20 = 100,000   ← 涨了 25 倍
在飞界②  R·T + K    = 11,000                    ← 没变
I ≤ min(100000, 11000) = 11,000

瞬时突发 ≤ K + I = 12,000，且 min(12,000, R·T+2K = 12,000) = 12,000
        = 12.0 × K

N 从 200 → 5000（25 倍），突发上界只从 5,000 → 12,000（2.4 倍）。
```

**算例 C（小 N）**：`R = 100/s`，`K = 100`，`T = 5 s`，`N = 4`，`B = 20`，`f = 1`。

```
在飞界①  f·N·B      = 80
在飞界②  R·T + K    = 100×5 + 100 = 600
I ≤ 80
瞬时突发 ≤ 100 + 80 = 180 = 1.8 × K     ← 小 N 时很舒服
```

### 4.6 「在飞量上限 = C × 配额」能不能兜住突发？

**本节为推导，未实测。**

**结论先说：能，但"配额"必须锚在 K 上；锚在别处时这条约束大概率是个空转的摆设。**

把它代进式 (5)，约束生效的前提是 `C·配额 < min(f·N·B, R·T + K)`。看两种"配额"的定义：

**定义甲：配额 = R·T（一个租约周期该 key 的正当流量）。** 这是最自然的直觉——"一个租约期内它本该用掉多少"。

- `C = 2` ⇒ 在飞上限 = `2 × R·T`
- 算例 A：`2 × 1000 × 10 = 20,000`，而 `min(f·N·B, R·T+K) = 4,000`
- `I ≤ min(4000, 20000) = 4,000` —— **约束完全没有生效（是 min 里最大的那个）**
- 代入 A 的数：`C=2` 实际放行的突发是 **5,000**（5 倍 K），而 `C·R·T = 20,000` 相当于 **20 秒的流量**
- **所以"C=2 × 配额 = 2RT"在 T=10s 的配置下，等价于"允许 20 倍突发"。兜不住。**

要让这条约束真正把突发压到 `λ·K`，需要：

```
C·配额 ≤ (λ−1)·K     ⇒（配额 = R·T 时）  C ≤ (λ−1)·K/(R·T) = (λ−1)·W/T
```

代入 `λ=2, W=1s, T=10s`：**`C ≤ 0.1`**。默认的 `C=2` 比有效上限大了 **20 倍**。

**定义乙：配额 = K（规则的桶容量）。**

- `C = 1` ⇒ 在飞 ≤ K ⇒ **突发 ≤ 2K**，正好对应"允许 2 倍突发"
- `C` 就是"允许多超几倍"，`C=2` ⇒ 突发 ≤ 3K
- 这才是 `C` 这个旋纽**唯一能被人读懂**的用法

**所以：`C` 这个数只有在"配额 = K"时才有效；如果配额定义成 `R·T`，`C` 必须重算成 `C ≤ (λ−1)·W/T`，否则 `C=2` 就是个听起来很保守、实际放行 20 倍突发的数字。**

### 4.7 `C` 的有效区间与可行条件

**本节为推导，未实测。**

约束 `C·配额` 不能无限压小，因为它要能服务所有瞬时活跃客户端。每个活跃客户端至少要被允许持有 `B` 个令牌（否则它每次只能领到零头，批量预领的意义消失，退化成每请求远程）：

```
(6)   可行下界：C·配额 ≥ f·N·B
(7)   突发上界：C·配额 ≤ (λ−1)·K            （配额 = K 时）
```

两者联立，**存在可行 C 的必要条件**：

```
(8)   f·N·B ≤ (λ−1)·K        等价于     B ≤ (λ−1)·K / (f·N)
```

**这个式 (8) 是全篇最有用的一条设计公式。** 代入三个算例（取 `λ = 2`，即"突发不超过规则桶的 2 倍"）：

| 算例 | `f·N·B` | `(λ−1)·K` | 是否可行 | 由 (8) 反解的最大批量 `B_max` |
|---|---|---|---|---|
| A（N=200, K=1000, B=20） | 4,000 | 1,000 | **不可行** | `B ≤ 5` |
| B（N=5000, K=1000, B=20） | 100,000 | 1,000 | **不可行** | `B ≤ 0.2`（即 B<1，**根本做不到**） |
| C（N=4, K=100, B=20） | 80 | 100 | 可行 | `B ≤ 25` |

**读法**：

- 算例 A 里想把突发压到 2 倍，批量必须从 20 降到 5。**"允许 2 倍突发"与"一次领 20 个"在 N=200、T=10s、R=1000/s 下不能共存。**
- 算例 C 是这套机制**舒服工作的区间**：实例数少、批量可以给得大。
- 算例 B 是**机制失效的区间**：`B_max < 1` 意味着连"每个客户端手里有 1 个令牌"这个最低要求都满足不了 → **必须放弃"每实例本地持令牌"这个前提**（要么取消批量预领、每请求远程，要么接受远超 2 倍的突发）。

**顺带得到中心侧 QPS 的下界。** 在持续饱和（所有活跃客户端都在要令牌）时，中心被在飞上限逼着只能给每个客户端 `C·配额/N` 个令牌：`B_eff = C·配额/N`。中心每秒发放 `R` 个令牌，于是：

```
(9)   中心每秒处理的最小租约请求数 = R / B_eff = R·N / (C·配额)
```

取 `配额 = K = R·W`（W = `maxBurstSeconds`）：

```
(9')  center_lease_QPS ≥  R·N / (C·R·W)  =  N / (C·W)      ← R 被约掉了！
```

**这个结果值得反复看：在飞上限越紧，中心要处理的租约请求数越多，而且这个下界与速率 R 无关，只与实例数 N、突发窗口 W 和倍数 C 有关。**

| | N | W | C | 中心租约 QPS 下界 |
|---|---|---|---|---|
| 算例 A | 200 | 1s | 1 | **200 /s** |
| 算例 A | 200 | 1s | 2 | 100 /s |
| 算例 B | 5000 | 1s | 1 | **5,000 /s** |
| 算例 B | 5000 | 1s | 2 | 2,500 /s |

**即使这个 key 只有 1 QPS 的流量，只要 N 是 5000、在飞上限设成 1 倍桶容量，中心就要扛 5000 QPS 的租约请求。** 这是"在飞上限"这个旋钮**真正的价格**：它不是免费的保险，它是把突发风险换成中心 QPS 的兑换器。

### 4.8 N 很大（几千实例）时这套还成立吗

**本节为推导，未实测。**

分两半回答，**结论是相反的**：

**好的一半：在飞量上界不随 N 增长。** 由式 (2)(5)，当 `N·B ≳ R·T + K` 时，突发上界被 `R·T + 2K` 封顶。算例 B 里 N 是算例 A 的 25 倍，突发上界只从 5,000 涨到 12,000。**原因很朴素：中心自己的发放速率就是 R，它在 T 秒内发不出更多令牌，客户端想囤也囤不到。** 所以"实例数一多，这套就雪崩"这个担心，**在上界这个维度上不成立**。

**坏的一半：大 N 下有三个真实的失效模式。**

**(i) 批量过期浪费（最严重）。** 每个实例的正当需求是 `r_i = R/N`，在租约期 T 内它最多消耗 `r_i·T`，但中心发给它一整个 B：

```
浪费率 = 1 − min(B, r_i·T)/B
算例 B：r_i = 1000/5000 = 0.2/s，T = 10s ⇒ 能用 0.2×10 = 2 个，B = 20
        浪费率 = 1 − 2/20 = 90%
```

**90% 的发放量在租约到期时作废。** 若采用"发放即扣账、过期不退"（与"不做运行期归还"自洽），这 90% 会**永久吃掉中心的桶**，表现为**键长期欠服务**（实际放行只有应有速率的 1/10）——除非中心在过期时把作废量退回账。**"过期退不退回中心账"这一点必须在票 04 明确，它在大 N 下会放大成 10 倍量级的服务偏差。**

反过来，如果**过期退回**：那中心就允许"作废的重新发"，键的长期速率恢复到 R，代价是需要中心跟踪过期（时间环即可，见 §6.1）。**这个设计点的收益/代价都很大，必须显式裁决。**

**(ii) 中心 QPS 下界爆炸。** 式 (9') 已量化：N = 5000、C = 1 时是 5,000 QPS/key。

**(iii) 批量预领名存实亡。** 由式 (8)，N = 5000 时 `B_max = 0.2`。要么 B = 1 且接受 `λ ≥ 6`（突发至少 6 倍），要么放弃本地预领。**"几千实例下还能一次领 20 个"是不成立的**——不是实现问题，是算术问题。

**结论**：N 大时"上界"没问题，"收益"有问题。局部批量预领的价值随 N 下降（`B_eff ≈ K/N`，N 一大就接近 1），**而它的全部风险（突发 ×λ）和中心成本（N/(C·W) QPS）不下降**。所以**大 N 场景下更合理的方向是把突发窗口 W（也就是 K/R）调大**——由式 (9')，QPS 下界 ∝ 1/W，放宽 W 是唯一能同时松掉 (ii) 和 (iii) 的旋钮，代价是 λ 变大。

### 4.9 长期速率仍然是准的：量化

**本节为推导，未实测。** 式 (3b) 已给出：

```
窗口 Δ 的平均放行率 ≤ R + (R·T + 2K)/Δ
```

也就是**超出量是一个固定的令牌数 `R·T + 2K`，摊到越长的窗口越小**。要让它落在 R 的 `ε` 以内：

```
Δ ≥ (R·T + 2K) / (ε·R)
```

| 算例 | `R·T + 2K` | ε = 5% 时需要的窗口 |
|---|---|---|
| A | 12,000 | `12000/(0.05×1000) = 240 s` |
| B | 12,000 | 240 s |
| C | 700 | `700/(0.05×100) = 140 s` |

**所以"长期速率准"的"长期"是分钟量级**（算例 A/B 是 4 分钟），不是"一秒一秒地准"。如果验收口径里有"任意 1 秒窗口不超 R"，这套机制**必然不满足**；如果口径是"任意 1 分钟窗口平均不超 R"，那要检查 `Δ ≥ (R·T+2K)/(εR)` 是否成立。**这条建议直接落进票 09 的验收口径。**

### 4.10 与票 05「本地整形」的关系

**本节为推导，未实测。**

票 05 问"本地是纯搬运桶还是本地整形器"。从上界角度看二者的差别是：

- **纯搬运桶**：每个实例瞬时放行 ≤ B，全局瞬时放行 ≤ K + f·N·B（式 4、5 的完整形态）。
- **本地整形器**（本地把这 B 个令牌按 `R/N` 摊平着花）：单个实例的瞬时放行率被压到 `r_i`，**全局瞬时放行率 ≤ Σ r_i = R**（在上界意义上直接达标）。

但整形买来的这个上界**有前提**：`r_i` 必须真的等于该实例应得的份额，而"应得份额"要由中心分配。**如果份额是静态的 `R/N` 而实际流量是偏斜的（一个实例吃了 90% 流量），静态整形会把这个实例饿死**——它手里有令牌（或者被允许领），但被自己的本地漏桶卡住。所以：

- 静态整形 = **用"分配公平"假设换"突发达标"**，前提不成立时会引入新的错配。
- 动态整形（中心按实例发不同份额）= 那就回到了 Doorman 的**速率租约**——这也正好呼应 [map](../map.md) Notes 里那句"若最终选速率租约，本问题自然消解"。

**事实层面就到这里，选哪条留给票 04/05。**

---

## 5. HTTP 层的原子性与重试（幂等）

### 5.1 为什么"超时重试 = 重复领"是必然的

**逐字引用**（RFC 9110 §9.2.2 "Idempotent Methods"，[rfc-editor.org](https://www.rfc-editor.org/rfc/rfc9110.html#section-9.2.2)）：

> `A request method is considered "idempotent" if the intended effect on the server of multiple identical requests with that method is the same as the effect for a single such request. Of the request methods defined by this specification, PUT, DELETE, and safe request methods are idempotent.`
>
> `Idempotent methods are distinguished because the request can be repeated automatically if a communication failure occurs before the client is able to read the server's response. ... It knows that repeating the request will have the same intended effect, even if the original request succeeded, though the response might differ.`
>
> `A client SHOULD NOT automatically retry a request with a non-idempotent method unless it has some means to know that the request semantics are actually idempotent, regardless of the method, or some means to detect that the original request was never applied.`

领租约的语义是"给我 B 个令牌"——**这是扣减操作的 POST，不是幂等的**。而且失败模式**恰好是 RFC 描述的那个最难的情况**：连接在读到响应前断开，客户端**无法区分**"请求没到"和"请求到了、扣了账、响应丢了"。两种情况客户端都只有超时这一个信号。

**两种损失的量化（推导）**：

| 客户端行为 | 实际发生 | 后果 |
|---|---|---|
| 超时后**不重试** | 服务端已扣账并发牌，响应丢失 | **B 个令牌永久丢失**（若过期不退则吃掉中心的桶；若过期退回则只是延迟一拍） |
| 超时后**重试** | 服务端第二次扣账 | **重复领 B 个**，在飞量凭空多出 B |

**重试是二者中更坏的一个**，因为它的方向是"多放行"（违反限流语义），而"不重试"的方向是"少放行"（安全但损失吞吐）。**所以必须有幂等键，让重试表现为"第一次请求"而不是"第二次请求"。**

### 5.2 幂等键的形状

**逐字引用**（Stripe 的幂等请求文档，[docs.stripe.com](https://docs.stripe.com/api/idempotent_requests)）：

> `Stripe's idempotency works by saving the resulting status code and body of the first request made for any given idempotency key, regardless of whether it succeeds or fails. Subsequent requests with the same key return the same result, including 500 errors.`
>
> `A client generates an idempotency key, which is a unique key that the server uses to recognize subsequent retries of the same request. ... we suggest using V4 UUIDs, or another random string with enough entropy to avoid collisions. Idempotency keys are up to 255 characters long.`
>
> `You can remove keys from the system automatically after they're at least 24 hours old. We generate a new request if a key is reused after the original is pruned. The idempotency layer compares incoming parameters to those of the original request and errors if they're not the same to prevent accidental misuse.`

抽出可搬运的四条设计要素：

1. **键由客户端生成**（V4 UUID / 足够熵的随机串），不是服务端分配。
2. **服务端缓存"第一次的响应体"，不缓存"处理结果"之外的东西**——重试直接回放原响应（含 `granted`、`leaseId`、`ttl`）。这样重试**不扣账**。
3. **有保留期**（Stripe 是 24h），过期剪除后同键会被当成新请求。所以**保留期必须 ≫ 客户端的最大重试窗口**。
4. **同键不同参数要报错**，不能静默按新请求处理。

**对 gatekeeper 的推论（推导）**：租约请求的幂等键应当 **一次性、每"批"一个**，而不是"每客户端一个"或"每 key 一个"。

- **不能每客户端一个**：那样第二次真的想领令牌时会被当成重试，拿到的是第一次的旧响应。
- **不能每 key 一个**：一个客户端对同一 key 的多批请求会互相冲突。
- **正确形状**：客户端为**每一次发起（含其所有重试）**生成一个新 UUID，重试时复用；成功收到响应后作废。

**服务端的去重表内存账（推导）**：键 UUID 16 字节 + 响应体（`granted` 4B + `leaseId` 16B + `ttl` 8B + 状态码 ≈ 32B）+ 表开销 ≈ **80–120 字节/条**。保留期内有多少条 = 中心租约 QPS × 保留期。用式 (9) 的算例 B（5,000 QPS）× 60 秒保留期 = **30 万条 ≈ 30 MB**（单 key）；再乘 key 数就爆了。**所以幂等去重表要么复用"该客户端最近一次请求"的短窗口（比如只保留 1 个心跳周期），要么必须分片 + 设硬上限。这个账要进票 06 的分片容量规划。**

> ⚠️ **2026-09-22 已由 [票 06](../issues/06-grill-center-sharding-and-ha.md) Q13 交付**（本条手递的"账"）：两条缓解措施**都采纳**——保留期压到**一个 `refresh_interval`**（只服务"超时重试"这个窗口，不上 60s）+ **按接收分片本地存放 + 硬上限**（超限逐出最旧）。**上面这个 5,000 QPS 是 §4 令牌口径下的批量领用 QPS，已随 §4 作废**；速率口径下全中心 QPS ≤ `实例数 × min(订阅 key 数, 分片数) / T`（5000 实例 × 8 分片 / 5s = 8000 QPS，票 06 Q12 拆批），故 ≈ **4 万条 ≈ 4 MB 全中心**，分片后每实例更小。另：**这张表不参与分配决策**（它不是 [票 04](../issues/04-grill-lease-model.md) Q10 说的"派生计数器"，是请求级缓存），这句要连同账一起进 spec。

### 5.3 服务端怎么识别"同一个客户端"

**逐字引用**（[doorman.proto](https://github.com/youtube/doorman/blob/master/proto/doorman/doorman.proto)）：

> `message GetCapacityRequest {`
> `  required string client_id = 1;`
> `  repeated ResourceRequest resource = 2;`
> `}`

Doorman 的方案是客户端持有一个 `client_id` 字符串（注册时分配），且**一次请求带上该客户端所有资源的诉求**（`repeated ResourceRequest`）。配合 [research/00](00-lease-reclaim-prior-art.md) 已引的 design.md：

> `If a client crashes the server will automatically forget about the client when all of its leases expire.`

**这里有一个原样照搬会出事的坑（推导）**：**`client_id` 不能是"实例身份"，而必须是"实例身份 + 世代号"**。一个 pod 崩了又用同一个 `client_id`/实例名重启，服务端账上还记着它崩溃前持有的在飞量；新实例不知道那些租约，会以为自己干净——于是**旧的租约在账上挂着（占着在飞额度，浪费）+ 新实例又领一批（在飞量翻倍）**。

这一族问题的标准解法（分布式系统里反复出现，可作形状参考）是给实例身份加一个单调递增的**世代/epoch**：

- **Kafka 幂等生产者**用 `(ProducerId, ProducerEpoch)` 成对递增来"隔离僵尸生产者"。**逐字引用**（KIP-98 的 Producer API 变更，[cwiki.apache.org](https://cwiki.apache.org/confluence/display/KAFKA/KIP-98+-+Exactly+Once+Delivery+and+Transactional+Messaging)）：
  > `void beginTransaction() throws ProducerFencedException;`
  > `... @throws ProducerFencedException if another producer is with the same transactional.id is active.`
  > （`initTransactions` 的注释：`Ensures any transactions initiated by previous instances of the producer are completed. If the previous instance had failed with a transaction in progress, it will be aborted.`）
- 到了 gatekeeper：客户端注册时拿 `(clientId, epoch)`，服务端**遇到更大 epoch 就作废该 clientId 下所有旧租约**（把它们从在飞账上清掉）。旧实例即使还在跑，它的请求也会被 epoch 检查挡掉。

**这是本票建议里唯一一条"从别处搬形状"的设计，请票 04 明确要不要做。**

### 5.4 网络分区期间两边各自的状态会漂移成什么样

**本节为推导，未实测。**

分区（客户端收不到中心、中心收不到客户端）时的默认行为由 [map](../map.md) 决定是 **fail-open**（本地桶按已知规则自跑）。两侧状态各自漂移：

| | 客户端侧 | 中心侧 |
|---|---|---|
| 手里的批量令牌 | 继续花，花完就没了 | **账上仍记着这些在飞量**（直到租约过期） |
| 没花完的令牌 | 到期本地丢弃 | 账上同步消失（中心用**自己的**时钟结算） |
| 旧的 ticket 缓存 / 规则 | 继续用旧规则（可能已被中心改过） | 规则已更新 |
| 恢复瞬间 | 拿旧规则+旧状态继续要令牌 | 可能已把该 key 的规则/配额改过 |

**最危险的一格是"fail-open 时客户端本地按什么速率跑"。** 如果本地兜底桶的速率用的是**规则里的 R**，那么 N 个实例在分区期间各自按 R 跑：

```
分区期间全局放行率可达 N × R     ← N=200 时就是 200 倍超发
```

**Doorman 在协议里已经给这个洞留了补丁**——**逐字引用**（[doorman.proto](https://github.com/youtube/doorman/blob/master/proto/doorman/doorman.proto)）：

> `// Capacity to use in "safe" mode.`
> `// You can set this to a number and then the client will use that number`
> `// as the capacity in case it cannot refresh its lease.`
> `// If this field is absent the system returns a dynamic safe capacity in the`
> `// response, which is the available capacity divided by the number of`
> `// clients that the server knows about.`

也就是 **`safe_capacity` 默认 = R / N**（"available capacity divided by the number of clients that the server knows about"）。**fail-open 的本地速率应当是 `R/N` 而不是 `R`**，并且 N 要是中心最后知道的那个数（分区期间新扩的实例它不知道 → 这是残余风险，要记）。Doorman 的做法说明"fail-open 用全局速率"是个已知错误。

> ⚠️ **2026-09-22 注记（[票 05](../issues/05-grill-local-bucket-and-admission.md) Q3）**：这条警告在**速率租约**口径下**自动满足**——本地 fail-open 时跑的速率**就是自己的份额**（构造上 ≈ `R/N` 量级、且 Σ份额 ≤ R），**不需要额外实现**。代价是它退化成"构造性质"而非"可配置的旋钮"；若想要 Doorman 那种显式保底值，得另加一个 `safeShare` 字段（票 05 明确**本版不做**，形状留票 07 / 08）。⚠️ 残余风险照旧：分区前份额远大于 `R/N` 的实例（唯一活跃者），fail-open 期间会按那个大份额跑。

**另外一条**：分区恢复后，客户端**必须把本地残留的批量令牌全部作废**（或由中心按 epoch 作废），否则会同时存在"客户端按旧批发放行"和"中心按新配额发放"。这是 §5.3 的 epoch 机制的第二个用途。

> ⚠️ **2026-09-22 注记（[票 05](../issues/05-grill-local-bucket-and-admission.md) Q3）**：速率口径下**没有"本地残留的批量令牌"这个量**，这一条**不适用**。对应的安全机制换成两条已定的口径：**份额变了就换租约、立即生效**（RLQS 措辞，票 04 Q9），加上 `clientId` 的世代号（§5.3）——"分区恢复后本地按什么份额跑"由**新租约**决定，不需要"作废一批令牌"这个动作。

**事实层面到这里。**"分区时中心该不该继续按满额发放给没分区的客户端"是个策略问题，留票 04。

---

## 6. 中心的容量账

**本节为推导，未实测。** 所有字节数是按 Java 对象布局算出来的估计，未做 heap dump 验证。

### 6.1 每个 key 的权威状态最少要存什么

先问"最少要存什么"——把字段分成**必须**和**取决于设计选择**两类：

**必须（无论哪种设计）**：

| 字段 | 类型 | 为什么必须 |
|---|---|---|
| 令牌数（当前桶存量） | `long`（定点，见 §1.4）或 `double` | 发放时要扣、惰性补时要加 |
| 上次补令牌时刻 | `long`（中心自己的单调纳秒） | 惰性补令牌的基准点（Guava 的 `nextFreeTicketMicros` 等价物） |
| 规则引用 / 版本 | `int` 或引用 | 规则热更新时要能识别"这个 key 现在该用哪条规则"；引用而非拷贝，规则本身全局一份 |

**取决于设计选择**：

| 字段 | 什么时候需要 | 代价 |
|---|---|---|
| **在飞量账**（近 T 秒的发放量） | 只有当"在飞上限 `C·配额`"要真正生效时才需要；不设上限则**不需要**，因为式 (2) 的 `I ≤ R·T + K` 是**免费**的 | 见 6.2 |
| 在飞量账的实现形态 | 见 6.2，两种差 10 倍以上 | |
| 幂等去重表 | 见 §5.2，与保留期成正比 | 可能比桶状态本身大得多 |
| 上次请求的响应（幂等回放） | 幂等键的方案需要 | 与去重表同量级 |
| 冷热状态（`SmoothWarmingUp`） | 若做中心侧预热（§3.3） | 几个字节 |

**关键观察（推导）："在飞量上限"这个旋钮的价格就是"在飞量账"这块状态。** 式 (2) 已经免费给出了 `I ≤ R·T + K`；想出比这更紧的界（比如 `C·K`，C<1 或 T 很大时），就必须真的知道当前在飞量，就必须记账。**"不设上限"不是"放弃保护"，而是"接受 R·T + K 这个免费上界"。** 这句话建议原样进票 04。

### 6.2 两种账本的内存账

> ⚠️ **2026-09-22 注记**：[票 04](../issues/04-grill-lease-model.md) 选定**速率租约**后，本节的"设计①（按租约条目记）"**不是被否决的选项，而是被采纳的形状**（账本 = 每 `(key, 实例)` 一条租约记录）——本节当时是在"每 key 一个桶就够"的框架下把它判为不可行的，那个框架随 §4 一起作废。因此下面"按租约存条目不可行"这个结论**要读成"选速率租约就要付这笔内存账"**；真实的每条成本比这里的 99 B 可能更紧凑（Doorman 用两个 `float64` + 一个 `time.Time`），量级由 [票 12](../issues/12-research-rate-lease-bounds.md) 重算。**"设计②（时间环）"则随在飞上限撤销而不再需要**。

**设计 ①（按租约条目记）**：`Map<LeaseId, Lease>`，每条记 `(permits, clientId, expiresAt)`。

估算（64 位 JVM + 压缩指针，**推导**）：

```
HashMap.Node（hash 4 + key ref 4 + val ref 4 + next ref 4 + 对象头 12 → 对齐 32）  ≈ 32 B
key：Long 装箱（头 12 + long 8 → 对齐 24）                                        ≈ 24 B
val：Lease 对象（头 12 + permits 8 + expiresAt 8 + clientRef 4 → 32）             ≈ 32 B
table 槽位（8 B × 1.33 负载因子）                                                 ≈ 11 B
                                                                   每租约合计 ≈ 99 B
```

每 key 有 N 个客户端各持 1 条租约时：

| | 每 key | 10k keys | 1M keys |
|---|---|---|---|
| N = 200 | 200 × 99 ≈ **19.8 KB** | **198 MB** | **19.8 GB** |
| N = 5000 | 5000 × 99 ≈ **495 KB** | **4.95 GB** | **495 GB** |

**结论：按租约存条目不可行。** 10k key / 200 实例就已经 200 MB，1M key 直接爆炸。

**设计 ②（按时间环近似，推荐形状）**：不记"谁持有什么"，只记"最近 T 秒每秒发放了多少"。

```
long tokens                       8 B     当前桶存量
long lastRefillNanos              8 B     上次补充时刻
int  ruleRef / ruleVersion        8 B     规则指针（含对齐）
long[W] ring（每桶 1 秒的发放量）  8·T B   用 T 秒的环近似"近 T 秒发放量"（W=1s 时 T 个桶）
int  ringCursor                   4 B
对象头 + HashMap 桶节点           ≈ 40 B
                                 ─────
                        每 key ≈ 76 + 8·T 字节
```

| T | 每 key | 10k keys | 1M keys |
|---|---|---|---|
| 5 s | 116 B | 1.16 MB | **116 MB** |
| 10 s | 156 B | 1.56 MB | **156 MB** |
| 30 s | 316 B | 3.16 MB | **316 MB** |
| 60 s | 556 B | 5.56 MB | 556 MB |

**再退一步：如果不要"在飞上限"（接受式 2 的免费上界），账本连时间环都不用**：

```
tokens 8 + lastRefillNanos 8 + ruleRef 8 + 头/节点 40 = 每 key 64 B
10k keys → 0.64 MB      1M keys → 64 MB
```

**这张表是 §4.6 的"价格"的另一面**：`C·配额` 这个约束，价格就是多一个 `8·T` 字节的时间环 + 每小时一次环的推进。

**1M key 的量级评估（推导）**：156 MB 的常驻桶状态对单实例偏重（还要留堆给别的），所以：
- 1M key 应当**分片**（票 06 的活）；按 8 分片 → 每实例 ~20 MB，很舒服。
- **冷 key 必须能驱逐**。驱逐时的语义选择（"重置为满桶"还是"重置为空桶"）是有后果的：重置为满桶 ⇒ 冷 key 一被访问就发一个满桶突发（`K` 个令牌），等价于"每个冷 key 恢复时都有一次 K 的突发"；重置为空桶 ⇒ 冷 key 恢复时要先"充值"才能服务，有延迟。**这个选择要进票 04/06，且是一个会被忽略的突发来源。**

> ⚠️ **2026-09-22 已裁**（[票 05](../issues/05-grill-local-bucket-and-admission.md) Q11）：**客户端侧两条都不取**——本地桶**逐出整个条目**（容量上限 + 近似 LRU），下次访问重新走"同步首租"，既不会"重置为满桶"白送一个大突发，也没有"重置为空桶"的充值延迟；丢掉的只是桶里残余的 ≤ `份额 × burstSeconds` 个令牌，方向**保守**。**中心侧**的冷 key 驱逐（本节语境）仍归票 06。
>
> ⚠️ **2026-09-22 已由 [票 06](../issues/06-grill-center-sharding-and-ha.md) 裁**（本节末尾手递的那条）：**与客户端侧同口径，逐出整条**——删掉该 key 在中心的**全部** `(key, 实例)` 记录；触发 = 该 key 在 `lease_length` 内**没有任何续租**（即"已经没人在用它"）。被误逐的后果 = 那些实例下轮续租按新客户端在册、拿保守份额，一个 `refresh_interval` 内收敛（与冷启动同路径）。**每实例承载 key 数的上限默认值**交 [票 09](../issues/09-grill-observability-and-acceptance.md) 的容量账。

### 6.3 心跳与续租带来的请求 QPS 怎么估

分三项估，**它们量级差 4 个数量级**，所以必须分开。

**(i) 领租约（按需，随流量）**

```
每 key 的租约 QPS ≈ 该 key 的实际消耗速率 / B
全中心 QPS ≈ Σ_k (R_k / B_k)
```

全是 20 的批量、全网总流量 100,000/s 时：`100,000/20 = 5,000 QPS`。**这是正常负载。**

**(ii) 在飞上限逼出来的额外请求（推导，见式 9'）**

```
QPS 下界 ≈ N / (C·W)      ← 与 R 无关！
```

N = 5000、W = 1s、C = 1 → **5,000 QPS/key**。这一项在"单 key 几千实例"时会主导。

> ⚠️ **2026-09-22 本项随 [票 04](../issues/04-grill-lease-model.md) 一并失效**：`N/(C·W)` 是"在飞量上限 `C×配额`"逼出来的额外请求，该机制已被撤销（见 §4 节首与 §6.2 的注）。速率口径下不存在这一项。

**(iii) 心跳 / 续租 —— 这是最容易炸的一项**

**错误做法：按 (key, 客户端) 打点**（每个客户端为它订阅的每个 key 各发一次心跳，间隔 H 秒）：

```
QPS = N_keys × N_instances / H
算例：10,000 keys × 200 instances / 10 s = 200,000 QPS   ← 不可行
      10,000 keys × 5000 instances / 10 s = 5,000,000 QPS ← 荒谬
```

**正确做法：按客户端聚合**——一个客户端一个心跳，把这个客户端订阅的**所有 key 的诉求打包在一个请求里**。这正是 Doorman 的形状：**逐字引用**（[doorman.proto](https://github.com/youtube/doorman/blob/master/proto/doorman/doorman.proto)）：

> `message GetCapacityRequest {`
> `  required string client_id = 1;`
> `  repeated ResourceRequest resource = 2;`
> `}`

同算例：`200 instances / 10 s = 20 QPS`；`5000 instances / 10 s = 500 QPS`。**同一份数据，聚合与否差 10,000 倍。**

**对协议的直接要求（推导，交票 08）**：gatekeeper 的租约接口**必须是批量的**——一个 HTTP 请求带多个 key 的诉求。否则客户端订阅 50 个 key 时中心 QPS 直接 ×50。这条与 [map](../map.md) 里定下的"批量租约接口"一致，此处给出的是**为什么这一条是硬要求而不是优化**。

**还有一项：惊群。** 如果所有客户端的租约在同一个时刻到期（例如中心重启后统一发放），恢复瞬间会有一波 `f·N·B` 的集中请求。标准缓解是**给租约 TTL 加抖动**（`T × (1 ± jitter)`）。这属于事实性建议，无引用，标为推导。

### 6.4 容量账的结论

> ⚠️ **2026-09-22 本节前三条被 [票 04](../issues/04-grill-lease-model.md) 反转（§6.2 有注、本节原先漏了，[票 06](../issues/06-grill-center-sharding-and-ha.md) 收口时补上）**：①"每 key 三个字段 ≈ 64 B"是 **O(key)** 形状，已被 **O(key × 该 key 在册实例数)** 取代（票 04 选了速率租约，账本 = 每 `(key, 实例)` 一条租约记录，即 §6.2 的"设计①"）；②"要'在飞上限'就得加时间环"——**在飞上限机制已撤销，时间环不必存在**；③**"绝不按租约条目记账"读反了**——按租约条目记**正是被采纳的形状**，这笔内存账是选速率租约的**已知代价**（量级待 [票 12](../issues/12-research-rate-lease-bounds.md) 重算）。末两条（聚合、批量接口是硬要求）**仍然成立**；批量接口在 [票 06](../issues/06-grill-center-sharding-and-ha.md) Q12 后被细化为"**按分片拆批**"，全中心 QPS ≤ `实例数 × min(订阅 key 数, 分片数) / T`。

- 每 key 最少三个字段（令牌、上次补充时刻、规则引用）≈ **64 B**，10k key 不到 1 MB。
- 要"在飞上限"就得加时间环，**每 key 76 + 8T 字节**（T = 租约时长秒数），1M keys 约 156 MB（T=10s）。
- **绝不按租约条目记账**（99 B/条 × N 条/key，10k key 就 200 MB 起）。
- **心跳必须按客户端聚合**；按 (key, 客户端) 打点在 10k key × 200 实例下是 200,000 QPS。
- **协议的批量接口是硬要求**（Doorman 的 `repeated ResourceRequest` 形状），不是优化。

---

## ⚠️ 未核实项

以下条目**没有验证过**，不得当成已定论引用或写进 spec。

1. **§4 全部推导未实测。** 式 (3)(5)(8)(9') 与三个算例都是纸面推算，**没有跑仿真、没有压测**。特别是"在飞量的最坏情况能同时取到 `K` 与 `R·T + K`"这个构造，是我在纸面上凑出来的场景，真实系统里能否复现未验证。**建议票 04 定完租约口径后，用一个小仿真（离散事件，几十分钟能写）复核 §4.5 的三个算例。**
2. **§1.2 的 `R×10⁻⁶` 相对超发量级是推导，未做基准。** 我读的是 Guava 源码的截断点，推的是误差上界；没有写 microbenchmark 实测过 Guava 在 R = 10⁵ ~ 10⁶/s 下的实际超发比例。**"R = 10⁶/s 是硬顶"这句话的根据是"微秒时间轴的截断"，不是实测。**
3. **JVM 对象内存布局的字节数全部是估算**（§5.2、§6.2），基于"64 位 HotSpot + 压缩指针 + 8 字节对齐"的通用规则手算。**未做 JOL / heap dump 验证**，误差可能在 ±20% 量级。数量级结论（"按租约记账不可行"）有多大幅度余量，但具体数字不要直接引用。
4. **`nanoTime` 在 macOS / AIX 上的行为未读实现。** OpenJDK 的 `os_posix.cpp` 里 `javaTimeNanos` 被 `#if !defined(__APPLE__) && !defined(AIX)` 排除，注释说这两个平台有各自实现（macOS 走 `mach_absolute_time`）。我**没有读** `os_bsd.cpp` 的 macOS 分支（该文件的 curl 多次失败）。所以"macOS 上 `may_skip_backward` 是否也是 false"**未核实**。
5. **`AssumeMonotonicOSTimers` 的现状未完全核实。** 我在 `jdk17u` 的 `globals.hpp` 里 grep 不到该 flag，据此推断它已不在当代 JDK；但没做全仓检索，也没找到"哪个 bug 号删掉了它"。
6. **`System.nanoTime` 不单调的现场证据是二手**（来自 JDK-8040140 评审串与搜索结果的转述），**我没有读到 JDK-6458294 的原文**（搜索结果显示它 "contains a fair amount of detail"，但未取得页面）。所以"多核 CPU 各自的 TSC 起点不同导致 nanoTime 回退"这个机理**未核实**。
7. **Doorman 的 `safe_capacity` 默认值（available capacity / number of known clients）是从 proto 注释读的**，未读它的服务端实现确认这个除法真的被算出来、以及"N 是全局已知客户端数还是该资源的客户端数"。如果不带 `safe_capacity` 字段时服务端返回的是别的值，§5.4 的结论要改。
8. **Stripe 的"24 小时"保留期不一定适合 gatekeeper**——它是在线支付场景的取值。**gatekeeper 该取多少我没算**（取决于最大重试窗口与租约 TTL），只给了"保留期 ≫ 重试窗口"这个约束。
9. **§6.3 的"惊群"与"TTL 加抖动"是工程常识，我没有引任何来源**，标为推导。
10. **`capacity = rate × 1s` 这个 Guava 默认值是否适合限流的"通用"语义，我没有找到讨论它的权威材料**（Guava 只给了"避免不必要 stall"这个理由）。§3.2 里"Guava 的突发上界是 K / gatekeeper 的本地突发上界是 B"是我基于两边代码的对比，**未在任何文献里见过这个对比**。
11. **式 (1) 的令牌桶窗口性质假设"中心真的在扣账"。** 我在全文假设中心是权威、发放即扣账；如果最终设计改成"中心只发速率、不记令牌"（Doorman 那路），**§4 的整个推导不适用**，需要按速率租约重推（见 §4.10 最后一段）。**这条不是"未核实"，是"前提依赖"——票 04 若选速率租约，本报告的 §4 要整节作废重写。**
