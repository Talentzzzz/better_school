package com.betterschool.marketing.infrastructure.merge;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 通用攒批引擎：N 个合并分片（{@link MergeShard}，各一条常驻 daemon 线程做收件、攒批、单飞落库）+ 入口路由 + 降级。
 *
 * <p><b>一个引擎 = 一个用途 = 一个策略</b>：{@link MergeStrategy} 在构造时注入并定格，件里不带它。
 * 因此 {@code T} / {@code R} 就是这一类的载荷与结果，回填、降级、泛型转换都不再需要强转。
 * 要接第二个用途就再建一个引擎——不同用途的件不是靠「批内按策略身份分桶」隔离，而是根本不在同一个缓冲里。
 *
 * <pre>
 *   commit(item)
 *     ├─ 按 strategy.getShardKey(item) 路由到分片，塞进缓冲（首件起算窗口，攒满提前摘批）
 *     ├─ park 在自己的 future 上等终态，最多 waitTimeoutMs
 *     └─ 缓冲满 / 停机中 → 本线程用同一个落库函数落「组大小 1」（{@link #degrade}）
 *                    ↓（窗口到点 / 攒满，由分片自己的线程接管）
 *   flush：锁内整块摘批 → 锁外按 shardKey 分组 → 一组调一次 strategy.getFlush()（一组一个事务）
 *          → 逐件回填 → 有积压则背靠背续跑
 * </pre>
 *
 * <p><b>不是 Spring 托管对象</b>：每个引擎带自己的一组常驻线程，实例数与生命周期由持有者决定，
 * 所以由各写入组件 {@code new} 出来、并在它自己的 {@code @PreDestroy} 里调 {@link #close()}。
 *
 * <p>每分片的并发细节（锁、条件变量、双缓冲换批）在 {@link MergeShard}，缓冲里的件在 {@link MergeItem}；
 * 本类只管路由、分组、回填、降级与停机。
 *
 * <h2>五条不变量（改代码前先读）</h2>
 * I1 不丢件且恰一个终态（{@link MergeItem#complete} 幂等 CAS + 漏回填补 {@code FAILED}）、
 * I2 同分片单飞（落在「同一分片恰一条线程」上，结构即保证）、
 * I3 锁不跨 DB（临界区只碰内存）、
 * I4 停机三步（停收 → 排空 → signal + join）、
 * I5 背压有界（满即拒，不扩容、不挤掉已入队件）。
 *
 * <p><b>对外只有一个入口 {@link #commit}</b>：入队 → 等终态 → 接不住就地落库，三件事在同一个方法里闭环。
 * 所以调用方只会看到三态（{@code COMPLETED} / {@code TIMEOUT} / {@code FAILED}）；{@code BUFFER_FULL} /
 * {@code CLOSED} 是「件没进去」的内部事实，在 {@code commit} 里被同步降级消化掉，不外返。
 * 引擎不翻译业务语义，三态到域出口的翻译在各自的写入组件里。
 */
@Slf4j
public class MergeEngine<T, R> {

    /** 排空等待的兜底探测间隔：正常由 flush 完成信号唤醒，这里只防信号丢失 */
    private static final long IDLE_PROBE_MS = 5;

    private final MergeConfig config;
    /** 这一个引擎唯一的业务知识：怎么取路由键、怎么一组落一次 */
    private final MergeStrategy<T, R> strategy;
    /** 数组下标 = 分片号：{@code config.shardOf(key)} 直接寻址 */
    private final MergeShard<T, R>[] shards;

    /** 排空通知：flush 每走完一批就 signalAll，停机不用轮询 */
    private final ReentrantLock idleLock = new ReentrantLock();
    private final Condition idleSignal = idleLock.newCondition();

    /** 是否接收新件：停机第一步置 false，入口立刻开始拒绝 */
    private volatile boolean accepting = true;

    @SuppressWarnings("unchecked")
    public MergeEngine(MergeConfig config, MergeStrategy<T, R> strategy) {
        this.config = Objects.requireNonNull(config, "MergeEngine.config 不能为 null");
        this.strategy = Objects.requireNonNull(strategy, "MergeEngine.strategy 不能为 null");
        // 每分片一个 MergeShard、一条常驻 daemon 线程：构造即开跑；flush 并发 = shardCount
        this.shards = (MergeShard<T, R>[]) new MergeShard<?, ?>[config.shardCount()];
        for (int i = 0; i < shards.length; i++) {
            shards[i] = new MergeShard<>(i, config, this::flushBatch, this::signalIdle);
        }
        log.info("合并引擎启动：strategy={}, shardCount={}(mask={}), maxFlushDelayMillis={}, maxBatch={}, "
                        + "waitTimeoutMs={}, shardCapacity={}, drainTimeoutMs={}",
                strategy.getName(), config.shardCount(), config.mask(), config.maxFlushDelayMillis(),
                config.maxBatch(), config.waitTimeoutMs(), config.shardCapacity(), config.drainTimeoutMs());
    }

    /**
     * 提交一件并等结果：本类<b>唯一的对外入口</b>，入队、等待、兜底三件事在这一处闭环。
     *
     * <p>叫 {@code commit} 而不是 {@code write}/{@code submit}：返回时这件要么已随组提交、要么确定没提交
     * ——是「提交语义」，不是「投递语义」；而 {@code submit} 在本工程里另有「只管投、不等结果」的含义
     * （异步入口），用它会长期误导。
     *
     * <pre>
     *   入队成功 → 本线程 park 在自己的 future 上（最多 waitTimeoutMs）→ 终态原样返回
     *   入队失败 → 件从未进缓冲、必未落库 → 本线程用同一个落库函数落「组大小 1」（{@link #degrade}）
     * </pre>
     *
     * <p>{@code BUFFER_FULL} / {@code CLOSED}（缓冲满 / 停机中）因此<b>不会返给调用方</b>：它们是
     * 「件没进去」的内部事实，就地被 {@code degrade} 消化成 {@code COMPLETED} / {@code FAILED} /
     * {@code TIMEOUT}。调用方只会看到这三态，<b>永远不必自己判断要不要降级</b>。
     *
     * <p>降级用的是<b>同一个落库函数</b>（{@code strategy.getFlush()}）：另写一套同步版 SQL 迟早漂移，
     * 而漂移只在压力最大时暴露——那正是降级生效的时刻。
     *
     * @param item 件本体，原样交给 {@code strategy.getFlush()}
     * @return 终态 + 业务结果；只有 {@code COMPLETED} 才有结果
     */
    public MergeResult<R> commit(T item) {
        MergeItem<T, R> pending = new MergeItem<>(item, strategy.getShardKey(item));
        if (!enqueue(pending)) {
            // 未入队：满了或停机中。件从未进过缓冲区，必未落库 ⇒ 本线程就地落库
            return degrade(item, accepting ? "缓冲满" : "停机中");
        }
        try {
            return pending.await(config.waitTimeoutMs());
        } catch (TimeoutException e) {
            // 件仍在缓冲区里，之后大概率仍会落库 —— 对外是「结果未知」，不是失败
            return MergeResult.timeout();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 件没有丢（还在缓冲里），只是这个线程不再等了
            return MergeResult.failed();
        }
    }

    /** 当前缓冲件数（全分片求和，无锁镜像） */
    public int pending() {
        int total = 0;
        for (MergeShard<T, R> shard : shards) {
            total += shard.pendingSize();
        }
        return total;
    }

    /** 等排空（{@link #close()} 与测试用）：判据是所有分片缓冲空且没有在途批 */
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
                    idleSignal.awaitNanos(Math.min(remainNanos, TimeUnit.MILLISECONDS.toNanos(IDLE_PROBE_MS)));
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
     * 优雅停机：停收 → 排空 → 回填 + signal + join，顺序不能反。
     *
     * <p>先杀线程会把缓冲里的件变成「永远等不到的 future」；排空超时则显式回填，让调用方立刻拿到
     * 可重试的失败态。由持有者（写入组件的 {@code @PreDestroy}）调用——它依赖落库服务，
     * 所以必然早于连接池销毁，排空时连接仍然可用。
     */
    public void close() {
        accepting = false;
        boolean drained = awaitIdle(config.drainTimeoutMs());
        int left = 0;
        for (MergeShard<T, R> shard : shards) {
            left += shard.failRemaining(drained ? "应用停机（竞态补收）" : "排空超时");
        }
        if (left > 0) {
            log.warn("停机回填 {} 件未落库（从未进入事务，可安全重试） strategy={}", left, strategy.getName());
        }
        long joinDeadlineMillis = System.currentTimeMillis() + config.drainTimeoutMs();
        for (int i = 0; i < shards.length; i++) {
            long remainMs = Math.max(1, joinDeadlineMillis - System.currentTimeMillis());
            if (!shards[i].joinFlushThread(remainMs)) {
                log.warn("分片 {} 的 flush 线程未在 {}ms 内退出（大概率阻塞在落库上）：daemon 线程不阻塞 JVM 停机，放弃等待",
                        i, remainMs);
            }
        }
        log.info("合并引擎已停止：strategy={}, drained={}, 剩余积压={}", strategy.getName(), drained, pending());
    }

    // ── 落库路径 ──────────────────────────────────────────────────────────────

    /** 唯一入队通道：停机检查 → 路由到分片收件。false = 未入队（满了或已停机） */
    private boolean enqueue(MergeItem<T, R> pending) {
        return accepting && shards[config.shardOf(pending.shardKey())].offer(pending);
    }

    /**
     * 摘批后的落库：按 shardKey 分组，一组一个事务（锁集最小、失败只回滚一组、结果能精确回填到件）。
     * 收尾兜底：任何还没回填的件补失败态，绝不让调用方悬到超时。
     *
     * <p>只需按 shardKey 分组、不必再按策略分：一个批天然同策略（引擎只有一个）。
     */
    private void flushBatch(List<MergeItem<T, R>> batch) {
        Map<String, List<MergeItem<T, R>>> groups = new LinkedHashMap<>();
        for (MergeItem<T, R> pending : batch) {
            groups.computeIfAbsent(pending.shardKey(), key -> new ArrayList<>()).add(pending);
        }
        try {
            for (List<MergeItem<T, R>> group : groups.values()) {
                flushOneGroup(group);
            }
        } finally {
            for (MergeItem<T, R> pending : batch) {
                pending.fail();
            }
        }
    }

    /** 一组落一次：载荷按原顺序交回落库函数。异常只吞掉本组，别的组照常跑 */
    private void flushOneGroup(List<MergeItem<T, R>> items) {
        List<T> payloads = new ArrayList<>(items.size());
        for (MergeItem<T, R> item : items) {
            payloads.add(item.payload());
        }
        List<R> results = null;
        try {
            results = strategy.getFlush().flush(payloads);
        } catch (RuntimeException e) {
            log.error("落库失败（整组回滚，{} 件）：strategy={}, shardKey={}",
                    items.size(), strategy.getName(), items.get(0).shardKey(), e);
        }
        backfill(items, results);
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

    /** 同步降级：组大小 1 调同一个落库函数。抛异常 = 整组回滚（确定没落）；没回填 = 结果未知 */
    private MergeResult<R> degrade(T item, String reason) {
        log.warn("合并未接住（{}），降级为同步落库（组大小 1） strategy={}", reason, strategy.getName());
        List<R> results;
        try {
            results = strategy.getFlush().flush(List.of(item));
        } catch (RuntimeException e) {
            log.error("同步降级落库失败（整组回滚，确定没落） strategy={}", strategy.getName(), e);
            return MergeResult.failed();
        }
        if (results == null || results.size() != 1 || results.get(0) == null) {
            log.error("同步降级落库漏回填（事务可能已提交，按结果未知处理） strategy={}", strategy.getName());
            return MergeResult.timeout();
        }
        return MergeResult.completed(results.get(0));
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

    /** 所有分片都没有积压、也没有在途批（无锁：只读 volatile 镜像） */
    private boolean isIdle() {
        for (MergeShard<T, R> shard : shards) {
            if (!shard.isIdle()) {
                return false;
            }
        }
        return true;
    }
}
