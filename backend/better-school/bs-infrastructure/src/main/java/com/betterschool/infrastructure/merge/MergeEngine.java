package com.betterschool.infrastructure.merge;

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
 * 通用攒批引擎：<b>per-invId leader 模型</b>（对齐数据库内核热点组提交）。
 *
 * <p>没有分片数组、没有常驻后台线程——以 {@code invId}（业务行主键）为键的动态
 * {@link MergeGroup} 表替代固定分片：第一条到达的请求惰性创建该 invId 的 Group 并成为 leader，
 * <b>队列非空即发车</b>（没有收集窗口——收集窗就是落库耗时本身，见 {@link MergeGroup} 类注释的
 * 内核对照与结构安全性论证）；leader 带全队一次落库（一组一个事务不变，单批上界 {@code maxBatch}），
 * <b>落库期间不放权</b>——落库完还有件就接着摘下一批，队列空了才放手，所以同一行任一时刻
 * 只有它这一条 flush；
 * Group 空闲即从表中乐观删除（删除竞态由 {@code removed} 标志 + 换组重试闭环，DB 行锁 +
 * 唯一键再兜一层）。并行度随热点行数自伸缩，不再被分片数钉死。
 *
 * <pre>
 *   commit(item)
 *     ├─ 按 strategy.getShardKey(item) 找组（惰性创建），锁内入队
 *     ├─ 执行权空缺 → 接管：队列有货就摘批（单批 ≤maxBatch），队列空则等新件 / 等停机
 *     ├─ 执行权被占 → 睡在自己的件上等终态（无等待上限，终态/点名/探测三种唤醒）
 *     └─ 缓冲满 / 停机中 → 直接返回 FAILED（件从未入队、必未落库，调用方重试安全）
 *                    ↓（队列非空即由 leader 在自己的请求线程上发车）
 *   flush：锁内摘批（≤maxBatch）→ 锁外落库（一组一个事务，执行权不撒手）
 *          → 逐件回填 → 队列空则摘出 map；非空则本线程接着摘下一批
 * </pre>
 *
 * <p><b>一个引擎 = 一个用途 = 一个策略</b>：{@link MergeStrategy} 在构造时注入并定格，件里不带它。
 * 因此 {@code T} / {@code R} 就是这一类的载荷与结果。要接第二个用途就再建一个引擎——
 * 预扣与确认仍是两个实例，两类失败域不共享缓冲（story 22）。
 *
 * <p><b>与 mergevirtual 的并存定位</b>：本类（请求线程客串 leader、零后台线程、JDK 8+ 可用）是
 * 通用组件的<b>兜底形态</b>——不能用虚拟线程的项目也能直接拿走；{@code mergevirtual}（每组一根
 * 专属虚拟 leader，结构性单飞、零交接空档）是<b>有虚拟线程环境的终态形态</b>。两者是「不同运行时
 * 前提下的形态」，不是性能档位；语义变更必须在两包（含 count 子包共三处）同票落地。
 *
 * <p><b>不是 Spring 托管对象</b>：实例数与生命周期由持有者决定，所以由各写入组件 {@code new}
 * 出来、并在它自己的 {@code @PreDestroy} 里调 {@link #close()}。无后台线程可 join。
 *
 * <p>组内并发细节（执行权独占、乐观删除）在 {@link MergeGroup}；件在 {@link MergeItem}；
 * 本类只管寻组、驱动发车、回填与停机。
 *
 * <h2>五条不变量（改代码前先读）</h2>
 * I1 不丢件且恰一个终态（{@link MergeItem#complete} 幂等 CAS + 漏回填补 {@code FAILED}）、
 * I2 同 key 单飞（执行权独占：同一时刻只有 leader 会落库，结构即保证）、
 * I3 锁不跨 DB（临界区只碰内存）、
 * I4 停机三步（停收 → 排空 → 残余回填 {@code FAILED}）、
 * I5 背压有界（满即拒，不扩容、不挤掉已入队件）。
 *
 * <p><b>对外只有一个入口 {@link #commit}</b>：入队 → 等终态，两件事在同一个方法里闭环。接不住
 * （缓冲满 / 停机中）就<b>直接失败，不就地落库</b>——件从未进队列、必未落库，调用方稍后重试是安全的。
 * 所以调用方只会看到两态（{@code COMPLETED} / {@code FAILED}）。
 * 引擎不翻译业务语义，两态到域出口的翻译在各自的写入组件里。
 */
@Slf4j
public class MergeEngine<T, R> {

    /**
     * 等待者 park 的兜底探测间隔：正常由终态落定 / 接班点名精确 unpark，这里只防信号丢失、
     * 以及「交班点名恰好落空」时的接管空档（接管延迟上限就是一个探测间隔）。
     */
    private static final long WAKEUP_PROBE_MS = 5;

    private final MergeConfig config;
    /** 这一个引擎唯一的业务知识：怎么取路由键、怎么一组落一次 */
    private final MergeStrategy<T, R> strategy;
    /** invId（业务行主键）→ 热点组：惰性创建、空闲即乐观删除 */
    private final Map<String, MergeGroup<T, R>> groups = new ConcurrentHashMap<>();

    /** 排空通知：每走完一批就 signalAll，停机不用轮询 */
    private final ReentrantLock idleLock = new ReentrantLock();
    private final Condition idleSignal = idleLock.newCondition();

    /** 是否接收新件：停机第一步置 false，入口立刻开始拒绝 */
    private volatile boolean accepting = true;

    public MergeEngine(MergeConfig mergeConfig, MergeStrategy<T, R> strategy) {
        this.config = Objects.requireNonNull(mergeConfig, "MergeEngine.config 不能为 null");
        this.strategy = Objects.requireNonNull(strategy, "MergeEngine.strategy 不能为 null");
        log.info("合并引擎启动（per-invId leader 模型）：strategy={}, 发车条件=队列非空即发（无收集窗口）, "
                        + "maxBatch={}, groupCapacity={}, drainTimeoutMs={}",
                strategy.getName(), mergeConfig.maxBatch(), mergeConfig.shardCapacity(), mergeConfig.drainTimeoutMs());
    }

    /**
     * 提交一件并等结果：本类<b>唯一的对外入口</b>，入队与等待在同一个方法里闭环。
     *
     * <p>叫 {@code commit} 而不是 {@code write}/{@code submit}：返回时这件要么已随组提交、要么确定没提交
     * ——是「提交语义」，不是「投递语义」。
     *
     * <pre>
     *   入队成功 → 执行权空缺则自任 leader 收集发车；否则在自己的件上 park 等终态
     *   入队失败 → 件从未进队列、必未落库 → 直接返回 FAILED，调用方稍后重试
     * </pre>
     *
     * <p><b>没有等待上限</b>：一直等到件有终态为止。这不等于「可能永远等」——落库路径的终点只有两种：
     * SQL 正常返回（成功 / 业务失败），或 {@code socketTimeout} 到点抛异常（整组回填 {@code FAILED}）；
     * 停机则把残余件回填 {@code FAILED}。所以终点必定是「成了」或「确定没成」，没有「结果未知」。
     *
     * <p>{@code BUFFER_FULL} / {@code CLOSED}（缓冲满 / 停机中）<b>不会外返</b>：它们是「件没进去」的
     * 入队事实，在这里就折成 {@code FAILED}——件从未进队列、必未落库，重试安全。
     *
     * <p><b>刻意不在这里「就地落库一件」</b>：缓冲满意味着队列已经堆到上限，此时每件都去抢那有限的
     * DB 连接做单条事务，只会把「排队」变成「雪崩」。快速失败、让调用方退避重试，才是这一层的背压。
     *
     * @param item 件本体，原样交给 {@code strategy.getFlush()}
     * @return 终态 + 业务结果；只有 {@code COMPLETED} 才有结果
     */
    public MergeResult<R> commit(T item) {
        String key = strategy.getShardKey(item);
        MergeItem<T, R> mine = new MergeItem<>(item, key);
        MergeGroup<T, R> group = groups.computeIfAbsent(key, k -> new MergeGroup<>(k, config));
        MergeGroup.EnqueueOutcomeEnum outcome = group.enqueue(mine, accepting);
        // 乐观删除竞态自愈：撞上刚被摘除的组就换组重建（同 key 永远收敛到同一个活组）
        while (outcome == MergeGroup.EnqueueOutcomeEnum.GROUP_REMOVED) {
            group = groups.computeIfAbsent(key, k -> new MergeGroup<>(k, config));
            outcome = group.enqueue(mine, accepting);
        }
        // 入队结果逐种写清（不用「!= ENQUEUED」的兜底）：将来多一个拒收原因，必须在这里显式表态
        // 它算不算「件没进去、可安全重试」，不许静默穿过走到下面的等待循环
        switch (outcome) {
            case ENQUEUED -> {
                // 件已在队列里：往下走「等终态」
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

        // false = 刚经历过一次「接管但没发成车」（自己的件已落定 / 被中断），此刻再接管只会原样再退——
        // 先等一个唤醒再试，避免接管-退出的自旋
        boolean mayPromote = true;
        while (true) {
            // 判断归判断、取数归取数：终态单向，两步之间不会读到不一致的答案
            if (mine.isCompleted()) {
                return mine.result();
            }
            if (mayPromote && group.tryPromote()) {
                mayPromote = driveFlushCycles(group, mine);
                continue;
            }
            // 睡在自己的件上：终态落定 / 接班点名 / 探测间隔三种唤醒，均不碰组锁。
            // 只负责睡，醒来干什么由循环顶部重查谓词决定
            mine.parkUntilWoken(TimeUnit.MILLISECONDS.toNanos(WAKEUP_PROBE_MS));
            mayPromote = true;
        }
    }

    /**
     * 入队被拒的收尾（缓冲满 / 停机中两条路共用）：件从未进队列 ⇒ 必未落库 ⇒ 回 {@code FAILED}
     * 是安全的重试信号，调用方稍后重投即可。
     *
     * <p><b>刻意不在这里「就地落库一件」</b>：缓冲满意味着队列已经堆到上限，此时每件都去抢那有限的
     * DB 连接做单条事务，只会把「排队」变成「雪崩」。快速失败、让调用方退避重试，才是这一层的背压。
     *
     * <p>顺手把空组摘掉：被拒的这次入队没留下任何东西，别让一个没人用的空壳滞留在 map 里。
     */
    private MergeResult<R> rejectNotEnqueued(MergeGroup<T, R> group, String reason) {
        removeIfIdle(group);
        log.warn("合并不接件（{}），直接返回失败 strategy={}", reason, strategy.getName());
        return MergeResult.failed();
    }

    // ── 组的移除（「置死」与「从 map 摘掉」必须成对，所以配对只写在这两个方法里） ───────────

    /**
     * 收尾移除一个组（执行权持有者路径）：队列空就置死并摘出 map。
     *
     * <p>两步的配对刻意收在这里而不是散到调用点：只置死忘了摘除，换组重试的
     * {@code computeIfAbsent} 会把同一个死组反复递回来，那个循环不 park、不超时——纯自旋。
     * 调用点只说「移除它」，不可能只做半步。
     *
     * @return true = 组已移除（调用方不要再碰它）；false = 队列还有件，本线程接着收集下一批
     */
    private boolean removeIfEmpty(MergeGroup<T, R> group) {
        if (!group.markRemovedIfEmpty()) {
            return false;
        }
        groups.remove(group.key(), group);
        return true;
    }

    /** 同上，但没有执行权的路径用：还必须确认没人在用（只判空会摘掉一个正在收集的组） */
    private boolean removeIfIdle(MergeGroup<T, R> group) {
        if (!group.markRemovedIfIdle()) {
            return false;
        }
        groups.remove(group.key(), group);
        return true;
    }

    /** 当前缓冲件数（各组求和，锁内读真队列） */
    public int pending() {
        int total = 0;
        for (MergeGroup<T, R> group : groups.values()) {
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
     * <p>停收后先叫醒所有在位 leader 与等待者：leader 立即发车排空、等待者接班清队，
     * 在途 leader 与接班链排空即视为 idle。无后台线程可 join——发车都跑在调用方线程上，
     * 排空超时则把仍在队列里的件显式回填 {@code FAILED}（从未进入事务，可安全重试），
     * 绝不让调用方悬到超时。由持有者（写入组件的 {@code @PreDestroy}）调用——它依赖落库服务，
     * 所以必然早于连接池销毁，排空时连接仍然可用。
     */
    public void close() {
        accepting = false;
        for (MergeGroup<T, R> group : groups.values()) {
            group.markClosed();
        }
        boolean drained = awaitIdle(config.drainTimeoutMs());
        int left = 0;
        for (MergeGroup<T, R> group : List.copyOf(groups.values())) {
            left += group.failRemaining(drained ? "应用停机（竞态补收）" : "排空超时");
            groups.remove(group.key(), group);
        }
        if (left > 0) {
            log.warn("停机回填 {} 件未落库（从未进入事务，可安全重试） strategy={}", left, strategy.getName());
        }
        log.info("合并引擎已停止：strategy={}, drained={}, 剩余积压={}", strategy.getName(), drained, pending());
    }

    // ── 落库路径 ──────────────────────────────────────────────────────────────

    /**
     * 领导期：连续发车直到自己的件终态 / 组被摘除。
     *
     * <p><b>期间不撒手</b>：摘批 → 落库 → 再看队列，还有件就接着来下一批。落库那一段仍然持有执行权，
     * 所以同一行在这一刻不可能有第二条 flush——不需要额外的「落库权」标志。
     *
     * @return true = 还值得再尝试接管（发过车，或退出时已交权）；false = 这次接管没发成车
     *         （自己的件已被回填 / 被中断），先等一个唤醒再试
     */
    private boolean driveFlushCycles(MergeGroup<T, R> group, MergeItem<T, R> mine) {
        while (!mine.isCompleted()) {
            List<MergeItem<T, R>> batch = group.takeForFlush(mine);
            if (batch == null) {
                // 自己的件已被别人回填 / 被中断：takeForFlush 内部已交还执行权，此刻可能已闲
                signalIdle();
                return false;
            }
            flushOneGroup(batch);
            // 乐观删除：队列空则移除这个组（置死 + 摘出 map，两步在 removeIfEmpty 里配对）。
            // 执行权还在本线程手里，这一刻不可能有人成功入队；signalIdle 只在这里发——
            // 队列非空时 isIdle() 必为 false，通知等待者也是白醒
            if (removeIfEmpty(group)) {
                signalIdle();
                // 组已移除，执行权随之作废，无需交还
                return true;
            }
        }
        // 自己的件已完成而退出，但队列里可能还有别人的件：交权并点名接班
        group.yieldLeadership();
        signalIdle();
        return true;
    }

    /**
     * 一批落库：同组同 key（同 invId 同动作），一组一个事务（story 17/18）。
     * 收尾兜底：任何还没回填的件补失败态，绝不让调用方悬到超时。
     */
    private void flushOneGroup(List<MergeItem<T, R>> batch) {
        List<T> payloads = new ArrayList<>(batch.size());
        for (MergeItem<T, R> item : batch) {
            payloads.add(item.payload());
        }
        List<R> results = null;
        try {
            results = strategy.getFlush().flush(payloads);
        } catch (Throwable e) {
            // 接 Throwable 而非 RuntimeException：Error 穿透会让 leader 线程带着未交的执行权死亡
            // ——hasLeader 卡 true、被摘出队列的那批件永远没人回填，等待者空转悬挂到停机。
            // 接住则整组回填 FAILED（可安全重试），leader 存活并正常交权，自愈闭环成立
            log.error("落库失败（整组回滚，{} 件）：strategy={}, invId={}",
                    batch.size(), strategy.getName(), batch.get(0).shardKey(), e);
        }
        backfill(batch, results);
    }

    /**
     * 逐件回填：长度不足 / 该位是 null 都算漏回填，补失败态——宁可给调用方一个可重试的失败，
     * 也不给它一个「成功但没有结果」。
     */
    private void backfill(List<MergeItem<T, R>> items, List<R> results) {
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
        for (MergeGroup<T, R> group : groups.values()) {
            if (!group.isIdle()) {
                return false;
            }
        }
        return true;
    }
}
