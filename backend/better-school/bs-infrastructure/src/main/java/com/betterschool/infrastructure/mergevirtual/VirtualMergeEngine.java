package com.betterschool.infrastructure.mergevirtual;

import com.betterschool.infrastructure.merge.MergeConfig;
import com.betterschool.infrastructure.merge.MergeResult;
import com.betterschool.infrastructure.merge.MergeStrategy;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 通用攒批引擎：<b>专属虚拟线程 leader 模型</b>——每组恰一根专属虚拟 leader，独占「收集 + 落库」。
 *
 * <p>与 {@code com.betterschool.infrastructure.merge.MergeEngine}（请求线程客串 leader）同语义并存：
 * commit 对外语义不变（提交 → 等终态，{@code COMPLETED} / {@code FAILED} 两态），本类复用旧包的
 * 公开类型（{@link MergeConfig} / {@link MergeStrategy} / {@link MergeResult}），调用方切换零改动
 * ——只把引擎类名换掉。当前调用方<b>暂不切换</b>，替换决策在新引擎测试充分后另立票。
 *
 * <p><b>并存定位</b>：本类是<b>有虚拟线程环境的终态形态</b>（结构性单飞、零交接空档）；
 * 旧引擎是<b>通用组件的兜底形态</b>（JDK 8+、零后台线程，兼容不能用虚拟线程的项目）。两者是
 * 「不同运行时前提下的形态」，不是性能档位；语义变更必须在两包（含 count 子包共三处）同票落地。
 *
 * <pre>
 *   commit(item)
 *     ├─ 按 strategy.getShardKey(item) 找组（惰性创建），锁内入队（撞上死组则换组重建）
 *     ├─ 组内 claim-CAS 赢得 spawn 资格 → 恰好 spawn 一根专属虚拟 leader（组锁外！）
 *     ├─ 入队成功 → park 在自己的件上等终态（终态 CAS + 精确 unpark，无任何接班分支）
 *     └─ 缓冲满 / 停机中 → 直接返回 FAILED（件从未入队、必未落库，调用方重试安全）
 *                    ↓（leader 线程上）
 *   supervisor 循环：队列非空即摘批（单批 ≤maxBatch，无收集窗口）→ 一组一个事务落库 → 逐件回填
 *   → 队列空则置死组、摘出 map、线程退出；任何 Throwable 被就地消化为整组 FAILED 并继续
 * </pre>
 *
 * <h2>spawn 备忘（spec 固化的三点，改 spawn 路径前先读）</h2>
 * <ul>
 *   <li><b>错法 A</b>：在 {@code computeIfAbsent} 的 lambda 里 spawn——持 CHM 桶锁跑外部代码，
 *       且 leader 收尾的 {@code map.remove} 会撞同一把桶锁；</li>
 *   <li><b>错法 B</b>：{@code computeIfAbsent} 后无条件 spawn——每个提交者一根 leader，
 *       双 spawn = 两条事务并发写同一行；</li>
 *   <li><b>正确</b>：组内 claim-CAS（{@link VirtualMergeGroup#tryClaimLeader()}）恰好一次 +
 *       每个提交者兜底 claim。claim 与 start 之间创建者死亡（Throwable 解栈）时，
 *       {@link #ensureLeader} 的 catch 把 claim 放行，下一个提交者 CAS 赢得并 spawn——窗口自愈；
 *       残余长尾泄漏（组滞留 map）接受为已知无害。</li>
 * </ul>
 *
 * <h2>supervisor 与退出谓词（本模型仅有的两条结构性纪律）</h2>
 * <ul>
 *   <li><b>supervisor</b>：leader 主体是
 *       {@code while (true) { try { 收集 → 落库 → 回填 } catch (Throwable) { 整组回填 FAILED + error 日志 } }}
 *       ——Throwable 含 Error 一律就地消化，线程不死，崩溃恢复语义 = 整组 FAILED、从未进入事务
 *       （或已回滚）、可安全重试。<b>进程级 OOM 是例外</b>：catch 块本身也可能 OOM，引擎不假装能扛
 *       进程级灾难，归 ops 的 {@code ExitOnOutOfMemoryError} 策略；</li>
 *   <li><b>退出谓词</b>：循环只在「组已置死（{@link VirtualMergeGroup#settleAfterFlush()}）并从
 *       map 摘除」后退出；组内不存在提前 return 的合法路径——这是新模型唯一需要单测钉死的不变量。
 *       leader 不退位，就不需要接班。</li>
 * </ul>
 *
 * <h2>五条不变量（在新结构下重新论证，改代码前先读）</h2>
 * <ul>
 *   <li><b>I1 不丢件且恰一终态</b>：{@link VirtualMergeItem} 终态 CAS 幂等（回填 / supervisor 整组
 *       回填 / 停机回填三条路径并发只有第一个赢家）；supervisor 保证 leader 不死，收集-落库-回填链
 *       总有人走完；入队被拒的件从未进队列、必未落库，折成 {@code FAILED}；</li>
 *   <li><b>I2 同 key 单飞</b>：<b>由「每组恰一根专属 leader」结构直接保证</b>——落库只发生在 leader
 *       线程上，leader 不退位，同一行任一时刻至多一条 flush 在途。不再需要任何执行权标志；
 *       （将来若要在收集与落库之间做流水线重叠，必须引入独立落库权标志，属后续演进）</li>
 *   <li><b>I3 锁不跨 DB</b>：组锁只护队列与标志位；落库在锁外（组对象上没有任何跨落库持有的锁）；</li>
 *   <li><b>I4 停机三步</b>：停收（{@code accepting=false}）→ markClosed（停收、leader 立即
 *       排空）→ awaitIdle 超时后残余回填 {@code FAILED}。虚拟 leader 天生 daemon，close 无需
 *       join——停机不依赖后台线程 join；</li>
 *   <li><b>I5 背压有界</b>：满即拒、不扩容、不挤掉已入队件。<b>背压不加新闸门</b>（spec 拍板）：
 *       commit 阻塞到终态的语义下，在途请求数、tomcat 耗尽点、flush 并发度（= 活跃热点行数）
 *       与旧引擎完全相同。</li>
 * </ul>
 *
 * <h2>已知风险（显式记录，不被误当正确性缺陷）</h2>
 * <ul>
 *   <li><b>JDBC pinning（JDK 21）</b>：JDBC 驱动 {@code synchronized} 块可能钉住 leader 的载体
 *       线程（JEP 491 于 JDK 24 解决）——记录为性能风险；初始接受，基准对比后再决定是否升 JDK /
 *       审驱动版本；</li>
 *   <li><b>等待无上限的前提</b>：底层 SQL 必须有读超时（{@code socketTimeout}），否则一句永不返回的
 *       UPDATE 会让等待者一起挂死——与旧引擎同一条前提，配在 sharding.yaml 的 jdbcUrl 上。</li>
 * </ul>
 *
 * <p><b>不是 Spring 托管对象</b>：实例数与生命周期由持有者决定，由各写入组件 {@code new} 出来、
 * 并在它自己的 {@code @PreDestroy} 里调 {@link #close()}。
 *
 * <p>组内并发细节在 {@link VirtualMergeGroup}；件在 {@link VirtualMergeItem}；本类只管寻组、
 * spawn、supervisor 循环驱动、回填与停机。
 */
@Slf4j
public class VirtualMergeEngine<T, R> {

    /**
     * 等待者 park 的兜底探测间隔：正常由终态落定精确 unpark，这里只防信号丢失。
     */
    private static final long WAKEUP_PROBE_MS = 5;

    /**
     * leader 线程工厂：生产路径用默认虚拟线程（daemon、专用命名）。包内可见的构造参数
     * <b>仅供测试</b>注入「spawn 失败」，从外部钉住 claim 死亡窗口的自愈闭环（spec 新模型专属
     * 断言之四）；除它之外本实现零新增 seam。
     */
    interface LeaderThreadFactory {

        /**
         * @param leaderLoop supervisor 循环体（引擎私有，工厂不许包装丢异常）
         * @param key        组键（用于线程命名）
         * @return 已启动的 leader 线程
         */
        Thread newLeader(Runnable leaderLoop, String key);
    }

    private static final LeaderThreadFactory DEFAULT_LEADER_FACTORY =
            (leaderLoop, key) -> Thread.ofVirtual().name("merge-virtual-leader-" + key).start(leaderLoop);

    private final MergeConfig config;
    /** 这一个引擎唯一的业务知识：怎么取路由键、怎么一组落一次 */
    private final MergeStrategy<T, R> strategy;
    private final LeaderThreadFactory leaderFactory;
    /** invId（业务行主键）→ 热点组：惰性创建、leader 收尾即摘除 */
    private final Map<String, VirtualMergeGroup<T, R>> groups = new ConcurrentHashMap<>();

    /** 排空通知：每走完一批就 signalAll，停机不用轮询 */
    private final ReentrantLock idleLock = new ReentrantLock();
    private final Condition idleSignal = idleLock.newCondition();

    /** 是否接收新件：停机第一步置 false，入口立刻开始拒绝 */
    private volatile boolean accepting = true;

    public VirtualMergeEngine(MergeConfig config, MergeStrategy<T, R> strategy) {
        this(config, strategy, DEFAULT_LEADER_FACTORY);
    }

    VirtualMergeEngine(MergeConfig config, MergeStrategy<T, R> strategy, LeaderThreadFactory leaderFactory) {
        this.config = Objects.requireNonNull(config, "VirtualMergeEngine.config 不能为 null");
        this.strategy = Objects.requireNonNull(strategy, "VirtualMergeEngine.strategy 不能为 null");
        this.leaderFactory = Objects.requireNonNull(leaderFactory, "VirtualMergeEngine.leaderFactory 不能为 null");
        log.info("合并引擎启动（专属虚拟线程 leader 模型）：strategy={}, 发车条件=队列非空即发（无收集窗口）, "
                        + "maxBatch={}, groupCapacity={}, drainTimeoutMs={}",
                strategy.getName(), config.maxBatch(), config.shardCapacity(), config.drainTimeoutMs());
    }

    /**
     * 提交一件并等结果：本类<b>唯一的对外入口</b>，入队与等待在同一个方法里闭环。
     *
     * <p>叫 {@code commit} 而不是 {@code write}/{@code submit}：返回时这件要么已随组提交、要么确定没提交
     * ——是「提交语义」，不是「投递语义」。
     *
     * <pre>
     *   入队成功 → （若赢得 claim 则 spawn leader）→ park 在自己的件上等终态
     *   入队失败 → 件从未进队列、必未落库 → 直接返回 FAILED，调用方稍后重试
     * </pre>
     *
     * <p><b>没有等待上限</b>：一直等到件有终态为止。终点只有「成了」或「确定没成」——落库路径的
     * 终点是 SQL 正常返回或 {@code socketTimeout} 抛异常（supervisor 整组回填 {@code FAILED}）；
     * 停机则把残余件回填 {@code FAILED}。没有「结果未知」。
     *
     * <p>{@code BUFFER_FULL} / {@code CLOSED}（缓冲满 / 停机中）<b>不会外返</b>：它们是「件没进去」的
     * 入队事实，在这里就折成 {@code FAILED}——件从未进队列、必未落库，重试安全。
     * <b>刻意不在这里「就地落库一件」</b>：缓冲满意味着队列已堆到上限，此时每件都去抢有限的
     * DB 连接做单条事务，只会把「排队」变成「雪崩」。快速失败、让调用方退避重试，才是这一层的背压。
     *
     * @param item 件本体，原样交给 {@code strategy.getFlush()}
     * @return 终态 + 业务结果；只有 {@code COMPLETED} 才有结果
     */
    public MergeResult<R> commit(T item) {
        String key = strategy.getShardKey(item);
        VirtualMergeItem<T, R> mine = new VirtualMergeItem<>(item, key);
        VirtualMergeGroup<T, R> group = groups.computeIfAbsent(key, k -> new VirtualMergeGroup<>(k, config));
        VirtualMergeGroup.EnqueueOutcome outcome = group.enqueue(mine, accepting);
        // 乐观删除竞态自愈：撞上刚被摘除的组就换组重建（同 key 永远收敛到同一个活组），
        // 新组会 spawn 新 leader（spec story 13）
        while (outcome == VirtualMergeGroup.EnqueueOutcome.GROUP_REMOVED) {
            group = groups.computeIfAbsent(key, k -> new VirtualMergeGroup<>(k, config));
            outcome = group.enqueue(mine, accepting);
        }
        // spawn 兜底 claim：ENQUEUED / BUFFER_FULL 都试一次——满队也可能撞上「该有 leader 而没有」
        // 的自愈窗口（spawn 失败放行后的遗留件）；CLOSED 不试，停机排空由 close 的 failRemaining 兜底。
        // 注意：claim-CAS 在这里（commit 线程上），绝不在 computeIfAbsent 的 lambda 里（spawn 备忘·错法 A）
        if (outcome != VirtualMergeGroup.EnqueueOutcome.CLOSED) {
            ensureLeader(group);
        }
        // 入队结果逐种写清（不用「!= ENQUEUED」的兜底）：将来多一个拒收原因，必须在这里显式表态
        // 它算不算「件没进去、可安全重试」，不许静默穿过走到下面的等待循环
        switch (outcome) {
            case ENQUEUED -> {
                // 件已在队列里，专属 leader 会收集落库：往下走「park 等终态」
            }
            case BUFFER_FULL -> {
                return rejectNotEnqueued(group, "缓冲满（单组上限 " + config.shardCapacity() + " 件）");
            }
            case CLOSED -> {
                return rejectNotEnqueued(group, "停机中（引擎已停收）");
            }
            case GROUP_REMOVED -> throw new IllegalStateException(
                    "入队结果 GROUP_REMOVED 不该走到这里（上面的换组重试已消费）：key=" + key);
        }
        // commit 收敛为「入队 → park 等终态」（spec story 11）：没有 tryPromote、没有点名接班、
        // 没有交接探测——终态落定的精确 unpark 是唯一需要响应的唤醒，探测间隔只防信号丢失
        while (!mine.isCompleted()) {
            mine.parkUntilWoken(TimeUnit.MILLISECONDS.toNanos(WAKEUP_PROBE_MS));
        }
        return mine.result();
    }

    /**
     * spawn 兜底 claim：赢得 claim 的提交者立即（在组锁外）spawn 一根专属虚拟 leader。
     *
     * <p>claim 与 start 之间创建者死亡（任何 Throwable 解栈经过这里）时：releaseLeaderClaim
     * 放行 claim 并原样上抛——下一个提交者的 CAS 会赢得 claim 并 spawn，窗口自愈
     * （spec story 10）。放行必须 CAS、必须先于上抛，顺序不能反。
     */
    private void ensureLeader(VirtualMergeGroup<T, R> group) {
        if (!group.tryClaimLeader()) {
            return;
        }
        try {
            leaderFactory.newLeader(() -> runLeader(group), group.key());
        } catch (Throwable t) {
            group.releaseLeaderClaim();
            throw t;
        }
    }

    /**
     * 入队被拒的收尾（缓冲满 / 停机中两条路共用）：件从未进队列 ⇒ 必未落库 ⇒ 回 {@code FAILED}
     * 是安全的重试信号，调用方稍后重投即可。
     *
     * <p>顺手把「真正没人用」的空组摘掉：被拒的这次入队没留下任何东西，别让一个没人用的空壳
     * 滞留在 map 里（claim 已被占走的组不许摘，见 {@link VirtualMergeGroup#markRemovedIfIdle()}）。
     */
    private MergeResult<R> rejectNotEnqueued(VirtualMergeGroup<T, R> group, String reason) {
        removeIfIdle(group);
        log.warn("合并不接件（{}），直接返回失败 strategy={}", reason, strategy.getName());
        return MergeResult.failed();
    }

    // ── 组的移除（「置死」与「从 map 摘掉」必须成对，所以配对只写在这两个位置） ───────────

    /** 入队被拒路径的兜底摘除：确认没人用才置死并摘出 map（防空壳滞留） */
    private void removeIfIdle(VirtualMergeGroup<T, R> group) {
        if (group.markRemovedIfIdle()) {
            groups.remove(group.key(), group);
        }
    }

    /** 当前缓冲件数（各组求和，锁内读真队列） */
    public int pending() {
        int total = 0;
        for (VirtualMergeGroup<T, R> group : groups.values()) {
            total += group.pendingSize();
        }
        return total;
    }

    /** 等排空（{@link #close()} 与测试用）：判据是所有组队列空且没有在途 flush */
    public boolean awaitIdle(long timeoutMs) {
        if (isIdle()) {
            return true;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        idleLock.lock();
        try {
            while (!isIdle()) {
                long remainNanos = deadline - System.nanoTime();
                if (remainNanos <= 0) {
                    return false;
                }
                try {
                    idleSignal.awaitNanos(
                            Math.min(remainNanos, TimeUnit.MILLISECONDS.toNanos(WAKEUP_PROBE_MS)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return isIdle();
                }
            }
            return true;
        } finally {
            idleLock.unlock();
        }
    }

    /**
     * 优雅停机（I4）：停收 → 排空 → 残余回填 {@code FAILED}，顺序不能反。
     *
     * <p>停收后叫醒所有在位 leader：收集窗口作废、立即发车排空。虚拟 leader 天生 daemon、
     * close <b>无需 join</b>（spec story 18）——排空超时则把仍在队列里的件显式回填
     * {@code FAILED}（从未进入事务，可安全重试），绝不让调用方悬到超时。在途的那批不受停机
     * 回填影响，由 leader 自己收尾。由持有者（写入组件的 {@code @PreDestroy}）调用——它依赖
     * 落库服务，所以必然早于连接池销毁，排空时连接仍然可用。
     */
    public void close() {
        accepting = false;
        for (VirtualMergeGroup<T, R> group : groups.values()) {
            group.markClosed();
        }
        boolean drained = awaitIdle(config.drainTimeoutMs());
        int left = 0;
        for (VirtualMergeGroup<T, R> group : List.copyOf(groups.values())) {
            left += group.failRemaining();
            groups.remove(group.key(), group);
        }
        if (left > 0) {
            log.warn("停机回填 {} 件未落库（从未进入事务，可安全重试） strategy={}", left, strategy.getName());
        }
        log.info("合并引擎已停止：strategy={}, drained={}, 剩余积压={}", strategy.getName(), drained, pending());
    }

    // ── leader 的 supervisor 循环（专属虚拟线程上执行） ────────────────────────

    /**
     * supervisor 循环（spec 拍板形状）：{@code while(true) { try { 收集 → 落库 → 回填 }
     * catch (Throwable) { 整组回填 FAILED } }}。
     *
     * <p><b>退出谓词</b>：唯一出口是「{@link VirtualMergeGroup#settleAfterFlush()} 返回 true
     * （组已置死）+ 紧接着从 map 摘除」——组内没有提前 return 的合法路径。落库抛错不退出
     * （整组回填 FAILED 后继续收下一批）；被停机兜底置死的组（{@code takeForFlush} 返回
     * {@code null}）从这里自然收敛到出口。
     *
     * <p>崩溃恢复语义：任何 Throwable（含 Error）就地消化为整组 FAILED——那批件从未进入事务
     * （或事务已回滚），可安全重试；线程不死，引擎继续服务。
     */
    private void runLeader(VirtualMergeGroup<T, R> group) {
        while (true) {
            List<VirtualMergeItem<T, R>> batch = null;
            try {
                batch = group.takeForFlush();
                if (batch != null) {
                    flushOneGroup(batch);
                }
            } catch (Throwable t) {
                // Throwable 含 Error 一律就地消化（旧引擎 flushOneGroup 的 Error 缝隙在这里从
                // 结构上消掉了：supervisor 本来就接 Throwable）。进程级 OOM 是例外，归 ops 的
                // ExitOnOutOfMemoryError——catch 块本身也可能 OOM，引擎不假装能扛
                log.error("leader supervisor 吞掉 Throwable（整组回填 FAILED，线程继续）：strategy={}, invId={}",
                        strategy.getName(), group.key(), t);
                if (batch != null) {
                    backfill(batch, null);
                }
            }
            boolean retired = group.settleAfterFlush();
            if (retired) {
                groups.remove(group.key(), group);
            }
            signalIdle();
            if (retired) {
                // 退出谓词达成：组已置死并摘出 map，leader 退出（虚拟线程天生 daemon，无需 join）
                return;
            }
        }
    }

    /**
     * 一批落库：同组同 key（同 invId 同动作），一组一个事务（不变）。
     * <b>刻意不在这里 catch</b>：落库抛错交给 supervisor 的 {@code catch (Throwable)}——
     * 整组回填 FAILED 后循环继续，这正是 spec 固化的 supervisor 形状。
     */
    private void flushOneGroup(List<VirtualMergeItem<T, R>> batch) {
        List<T> payloads = new ArrayList<>(batch.size());
        for (VirtualMergeItem<T, R> item : batch) {
            payloads.add(item.payload());
        }
        List<R> results = strategy.getFlush().flush(payloads);
        backfill(batch, results);
    }

    /**
     * 逐件回填：长度不足 / 该位是 null 都算漏回填，补失败态——宁可给调用方一个可重试的失败，
     * 也不给它一个「成功但没有结果」。
     */
    private void backfill(List<VirtualMergeItem<T, R>> items, List<R> results) {
        int resultCount = results == null ? 0 : results.size();
        if (resultCount > items.size()) {
            log.warn("落库方法返回的结果比入参多（{} > {}）：多出的忽略", resultCount, items.size());
        }
        for (int i = 0; i < items.size(); i++) {
            R result = i < resultCount ? results.get(i) : null;
            if (result != null) {
                items.get(i).complete(result);
            } else {
                items.get(i).fail();
            }
        }
    }

    /** flush 完成回调：唤醒排空等待者。没人等时 tryLock 直接失败，省掉一次抢锁 */
    private void signalIdle() {
        if (!idleLock.tryLock()) {
            return;
        }
        try {
            idleSignal.signalAll();
        } finally {
            idleLock.unlock();
        }
    }

    /** 所有组都没有积压、也没有在途 flush（各组锁内判定） */
    private boolean isIdle() {
        for (VirtualMergeGroup<T, R> group : groups.values()) {
            if (!group.isIdle()) {
                return false;
            }
        }
        return true;
    }
}
