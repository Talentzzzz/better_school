package com.betterschool.marketing.infrastructure.inventory.merge;

import com.betterschool.marketing.domain.inventory.model.valobj.ClaimItem;
import com.betterschool.marketing.domain.inventory.model.valobj.ClaimOutcome;
import com.betterschool.marketing.domain.inventory.model.valobj.DeductResult;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryDeductRequestVO;
import com.betterschool.marketing.types.inventory.InventorySegmentVO;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 库存侧合并器：<b>固定 N 个分片缓冲区 + 入口驱动定时器 + 固定大小共享执行池</b>（spec §2.2）。
 *
 * <h2>一件预扣的一生</h2>
 * <pre>
 *   submitAndWait(invId, request)
 *     ├─ 按 shardOf(invId) 路由到分片
 *     ├─ 塞进该分片缓冲区（首个请求排定 windowMs 后的 flush；攒满 maxBatch 提前触发）
 *     └─ park 在自己的 future 上，最多等 waitTimeoutMs
 *                    ↓（定时器到点，由执行池线程接管）
 *   flush：锁内整块摘批 → 锁外按「动作 × segment × invId」二次分组 → 一组一个事务落库 → 回填 → 有积压则续排
 * </pre>
 *
 * <h2>只有等结果一个入口</h2>
 * 两个 {@code submitAndWait} 是<b>重载</b>（靠入参类型区分：预扣 {@link InventoryDeductRequestVO}、
 * 确认 {@link ClaimItem}），内部落到两个各自 {@code @Transactional} 的组方法——<b>不合成一个方法</b>，
 * 因为两个失败域的语义不同（spec §4.5）。也<b>没有</b> fire-and-forget 入口：库存侧没有不等的调用方，
 * 不为对称造方法（spec §2.2）。
 *
 * <h2>成功 = 已提交</h2>
 * {@code DEDUCTED} / {@code CONFIRMED} 只在落库方法返回之后才回填，也就是 {@code COMMIT} 之后——
 * 调用方拿到成功时账已动，没有第二步、没有延迟消息、没有兜底扫描。
 *
 * <h2>背压与降级：引擎不说 {@code REJECTED}</h2>
 * 引擎只回答 {@code BUFFER_FULL / CLOSED / 超时 / 失败}，<b>降级动作全在调用方</b>：
 * 缓冲满或停机中 → 调用方线程用<b>同一个落库方法</b>扣组大小 1；等超时 → {@code TIMEOUT_UNKNOWN}
 * （不切兜底、抛可重试）。两套 SQL 迟早漂移，而漂移只在压力最大时暴露（spec §2.6）。
 *
 * <h2>五条不变量</h2>
 * I1 不丢件且恰一个终态（{@link Pending#complete} 幂等 + 漏回填补失败态）、
 * I2 同分片单飞（{@link ShardBuffer} 的 {@code running + generation} 双保险）、
 * I3 锁不跨 DB（临界区只碰内存）、I4 停机三步（停收 → 排空 → 关池）、
 * I5 背压有界（满即拒，不扩容、不挤掉已入队件）。
 *
 * <h2>装配</h2>
 * 本类必须拿到一个落库方法（{@link IQuotaMergeFlushHandler}）才有意义：真身是
 * {@code InventoryRepository}（06 接预扣；确认的一组由 07 接线）。实例由
 * {@code QuotaMergeConfig} 以 {@code @Bean} 装配（参数来自 {@code MergeConfig}），
 * {@link #close()} 上的 {@code @PreDestroy} 作为 bean 生效（单测与显式停机路径直接调 {@code close()}）。
 */
@Slf4j
public class QuotaMergeBuffer {

    /** 排空等待的兜底探测间隔：正常由 flush 完成信号唤醒，这里只防信号丢失 */
    private static final long IDLE_PROBE_MS = 5;

    private final MergeConfig config;
    private final IQuotaMergeFlushHandler flushHandler;
    private final ShardBuffer[] shards;
    private final ScheduledThreadPoolExecutor scheduler;

    /** 排空通知：flush 每走完一批就 signalAll，停机不用轮询 */
    private final ReentrantLock idleLock = new ReentrantLock();
    private final Condition idleSignal = idleLock.newCondition();

    /** 是否接收新件：停机第一步置 false，入口立刻开始拒绝（不再往缓冲区里塞） */
    private volatile boolean accepting = true;

    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong flushed = new AtomicLong();
    private final AtomicLong batches = new AtomicLong();
    private final AtomicLong rejectedFull = new AtomicLong();
    private final AtomicLong rejectedClosed = new AtomicLong();
    private final AtomicLong timedOut = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong drainedFailed = new AtomicLong();
    private final AtomicLong maxFlushNanos = new AtomicLong();

    public QuotaMergeBuffer(MergeConfig config, IQuotaMergeFlushHandler flushHandler) {
        if (flushHandler == null) {
            throw new IllegalArgumentException(
                    "[配置错误] IQuotaMergeFlushHandler 不能为 null：合并器没有出口等于丢弃所有扣减");
        }
        this.config = config;
        this.flushHandler = flushHandler;
        this.scheduler = new ScheduledThreadPoolExecutor(
                config.flushThreads(),
                Thread.ofPlatform().daemon(true).name("quota-merge-flush-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        // ★ 取消的定时任务立刻出队。攒满提前触发每秒会 cancel 上千个任务，
        //   不开这个策略，它们会一直躺在延时队列里等到原定窗口才被清理。
        this.scheduler.setRemoveOnCancelPolicy(true);

        this.shards = new ShardBuffer[config.shardCount()];
        for (int i = 0; i < config.shardCount(); i++) {
            shards[i] = new ShardBuffer(i, config, scheduler, this::flushBatch, this::signalIdle);
        }

        log.info("库存合并器启动：shardCount={}(mask={}), windowMs={}, maxBatch={}, waitTimeoutMs={}, "
                        + "shardCapacity={}, flushThreads={}, drainTimeoutMs={}",
                config.shardCount(), config.mask(), config.windowMs(), config.maxBatch(),
                config.waitTimeoutMs(), config.shardCapacity(), config.flushThreads(), config.drainTimeoutMs());
    }

    /**
     * 预扣（等结果）。
     *
     * @param invId   这条台账的主键，<b>由调用方显式给</b>（预扣与确认共用同一片，撞片无害）
     * @param request 请求本体；合并器不解析它推导 key，只在分组时读它的 {@code segment}
     * @throws IllegalArgumentException {@code request.invId()} 与 {@code invId} 不一致（同一行给了两个 key，
     *                                 会让两个分片各自合并同一行，单飞立刻失效）
     */
    public DeductResult submitAndWait(long invId, InventoryDeductRequestVO request) {
        if (request.invId() != invId) {
            throw new IllegalArgumentException(String.format(
                    "[调用错误] shardKey(%s) 必须等于 request.invId(%s)：同一行给两个 key 会让两个分片各自合并同一行",
                    invId, request.invId()));
        }
        Pending<DeductResult> item = Pending.deduct(invId, request);
        SubmitOutcome outcome = enqueue(item);
        if (outcome == SubmitOutcome.BUFFER_FULL) {
            return DeductResult.BUFFER_FULL;
        }
        if (outcome == SubmitOutcome.CLOSED) {
            // 入队后被立即回填（调度器已在停机）也算失败：件过了缓冲区一道手，不能再叫「没接住」
            return item.isSettled() ? DeductResult.FAILED : DeductResult.CLOSED;
        }
        try {
            return item.await(config.waitTimeoutMs());
        } catch (TimeoutException e) {
            // 件仍在缓冲区里，之后大概率仍会扣 —— 对外表达为「结果未知」，不是失败（票 05 §5）
            timedOut.incrementAndGet();
            return DeductResult.TIMEOUT_UNKNOWN;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 件没有丢（还在缓冲里），只是这个线程不再等了
            return DeductResult.FAILED;
        }
    }

    /**
     * 兑换确认（等结果）。
     *
     * @param invId 这条台账的主键；{@code invId <= 0}（不限量档 {@code draw_record.inv_id} 落 NULL）的短路
     *              在调用方做，不与「账不平」共用异常
     * @param item  业务身份 + 数量
     */
    public ClaimOutcome submitAndWait(long invId, ClaimItem item) {
        Pending<ClaimOutcome> pending = Pending.claim(invId, item);
        SubmitOutcome outcome = enqueue(pending);
        if (outcome == SubmitOutcome.BUFFER_FULL) {
            return ClaimOutcome.BUFFER_FULL;
        }
        if (outcome == SubmitOutcome.CLOSED) {
            return pending.isSettled() ? ClaimOutcome.FAILED : ClaimOutcome.CLOSED;
        }
        try {
            return pending.await(config.waitTimeoutMs());
        } catch (TimeoutException e) {
            timedOut.incrementAndGet();
            return ClaimOutcome.TIMEOUT_UNKNOWN;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ClaimOutcome.FAILED;
        }
    }

    /**
     * 唯一入队通道：停机检查 → 路由到分片收件 → 计数。两个入口只在「拒收怎么翻译」上不同，
     * 计数口径必须共用一份。
     *
     * <p><b>计数守恒</b>：{@code accepted == flushed + failed + drainedFailed + 在途 + timedOut 后的在途}
     * 之所以能对上，靠这里三条分支互斥且覆盖：
     * <ul>
     *   <li>{@code ACCEPTED} → {@code accepted++}；</li>
     *   <li>{@code BUFFER_FULL} → {@code rejectedFull++}（件从未入队）；</li>
     *   <li>{@code CLOSED} 且件已被回填 → {@code accepted++} 且 {@code failed++}
     *       （件确实进过缓冲区，只是调度器已关，立刻失败）；</li>
     *   <li>{@code CLOSED} 且件未被回填 → {@code rejectedClosed++}（件从未入队）。</li>
     * </ul>
     */
    private SubmitOutcome enqueue(Pending<?> item) {
        if (!accepting) {
            rejectedClosed.incrementAndGet();
            return SubmitOutcome.CLOSED;
        }
        SubmitOutcome outcome = shards[config.shardOf(item.shardKey())].offer(item);
        if (outcome == SubmitOutcome.ACCEPTED) {
            accepted.incrementAndGet();
        } else if (outcome == SubmitOutcome.BUFFER_FULL) {
            rejectedFull.incrementAndGet();
        } else if (item.isSettled()) {
            accepted.incrementAndGet();
            failed.incrementAndGet();
        } else {
            rejectedClosed.incrementAndGet();
        }
        return outcome;
    }

    /** 当前缓冲件数（全分片求和，无锁镜像） */
    public int pending() {
        int total = 0;
        for (ShardBuffer shard : shards) {
            total += shard.pendingSize();
        }
        return total;
    }

    /**
     * 等排空（{@code close()} 与测试用）。
     *
     * <p>判据是「所有分片都没有在途 flush 且缓冲空」，由 flush 完成信号唤醒，
     * 探测间隔只防信号丢失。
     */
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

    /** 读数快照（不依赖 micrometer，不开端点） */
    public MergeMetrics metrics() {
        return new MergeMetrics(pending(), accepted.get(), flushed.get(), batches.get(),
                rejectedFull.get(), rejectedClosed.get(), timedOut.get(), failed.get(),
                drainedFailed.get(), maxFlushNanos.get());
    }

    /**
     * 优雅停机（I4）：停收 → 排空 → 关池，顺序不能反。
     *
     * <p>先关池会把缓冲区里的件变成「永远等不到的 future」；排空超时则显式回填，让调用方立刻
     * 拿到可重试的失败态，而不是干等 {@code waitTimeoutMs} 后猜。回填件数计入 {@code drainedFailed}，
     * 这是「停机丢了多少」的唯一读数。
     *
     * <p>{@code HikariDataSource} 的销毁晚于本方法（Spring 按依赖逆序销毁），所以落库用的连接
     * 在这一步仍然可用——这句注释是为了锁住那个前提，不要调换顺序。
     */
    @PreDestroy
    public void close() {
        accepting = false;
        boolean drained = awaitIdle(config.drainTimeoutMs());
        for (ShardBuffer shard : shards) {
            int left = shard.failRemaining(drained ? "应用停机（竞态补收）" : "排空超时");
            if (left == 0) {
                continue;
            }
            if (drained) {
                // 正常排空后还会剩件，只可能是「close 与最后一个 offer 赛跑」漏进来的
                failed.addAndGet(left);
            } else {
                drainedFailed.addAndGet(left);
            }
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(config.drainTimeoutMs(), TimeUnit.MILLISECONDS)) {
                scheduler.shutdownNow();
                log.warn("库存合并执行池未在 {}ms 内结束，已强制中断", config.drainTimeoutMs());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
        log.info("库存合并器已停止：drained={}, 剩余积压={}, 读数={}", drained, pending(), metrics());
    }

    // ── 落库路径 ──────────────────────────────────────────────────────────────

    /**
     * 摘批后的落库：按「动作 × segment × invId」二次分组，一组一个事务。
     *
     * <p>一组一事务（不是一批一事务）：一组让锁集最小、失败只回滚一组、结果能精确回填到件；
     * 一批一事务会让两个不相干的行以不同顺序被锁，多出一片死锁面（spec §4.1）。
     */
    private void flushBatch(List<Pending<?>> batch) {
        Map<GroupKey, List<Pending<?>>> groups = new LinkedHashMap<>();
        for (Pending<?> item : batch) {
            groups.computeIfAbsent(new GroupKey(item.action(), item.segment(), item.shardKey()),
                    key -> new ArrayList<>()).add(item);
        }
        try {
            for (Map.Entry<GroupKey, List<Pending<?>>> entry : groups.entrySet()) {
                flushOneGroup(entry.getKey(), entry.getValue());
            }
        } finally {
            // 兜底：任何还没回填的件补失败态 —— 绝不让调用方悬到超时（I1）
            for (Pending<?> item : batch) {
                if (item.fail()) {
                    failed.incrementAndGet();
                }
            }
        }
    }

    /** 落库一组 + 回填 + 计数。落库方法的异常只吞掉本组，别的组照常跑 */
    private void flushOneGroup(GroupKey group, List<Pending<?>> items) {
        long startNanos = System.nanoTime();
        try {
            switch (group.action()) {
                case DEDUCT -> flushDeductGroup(group, items);
                case CLAIM -> flushClaimGroup(group, items);
            }
        } finally {
            batches.incrementAndGet();
            maxFlushNanos.accumulateAndGet(System.nanoTime() - startNanos, Math::max);
        }
    }

    /** 预扣组：落库 → 逐件回填 {@code DEDUCTED} / {@code INSUFFICIENT} */
    private void flushDeductGroup(GroupKey group, List<Pending<?>> items) {
        List<InventoryDeductRequestVO> requests = new ArrayList<>(items.size());
        for (Pending<?> item : items) {
            requests.add((InventoryDeductRequestVO) item.payload());
        }

        List<DeductResult> results = null;
        try {
            results = flushHandler.flushDeductGroup(group.segment(), group.shardKey(), requests);
        } catch (RuntimeException e) {
            log.error("预扣落库失败（整组回滚，{} 件）：segment={}, invId={}",
                    items.size(), group.segment(), group.shardKey(), e);
        }
        backfill(deductItems(items), results);
    }

    /** 确认组：落库 → 逐件回填 {@code CONFIRMED} */
    private void flushClaimGroup(GroupKey group, List<Pending<?>> items) {
        List<ClaimItem> claims = new ArrayList<>(items.size());
        for (Pending<?> item : items) {
            claims.add((ClaimItem) item.payload());
        }

        List<ClaimOutcome> results = null;
        try {
            results = flushHandler.flushClaimGroup(group.shardKey(), claims);
        } catch (RuntimeException e) {
            log.error("兑换确认落库失败（整组回滚，{} 件）：invId={}", items.size(), group.shardKey(), e);
        }
        backfill(claimItems(items), results);
    }

    /**
     * 逐件回填：长度不足 / 该位是 null 都算「漏回填」，补失败态——宁可给调用方一个可重试的失败，
     * 也不给它一个「成功但没有结果」。
     */
    private <R> void backfill(List<Pending<R>> items, List<R> results) {
        int resultCount = results == null ? 0 : results.size();
        if (resultCount > items.size()) {
            log.warn("落库方法返回的结果比入参多（{} > {}）：多出的忽略", resultCount, items.size());
        }
        for (int i = 0; i < items.size(); i++) {
            Pending<R> item = items.get(i);
            R result = i < resultCount ? results.get(i) : null;
            if (result != null && item.complete(result)) {
                flushed.incrementAndGet();
            } else if (item.fail()) {
                failed.incrementAndGet();
            }
        }
    }

    /**
     * 一个分片内的件按动作分好了，结果类型由动作决定 ⇒ 这一次转型是安全的
     * （{@code DEDUCT} 的件一定是 {@code Pending<DeductResult>}）。转型只发生在这两个方法里，
     * 不散落到回填逻辑中。
     */
    @SuppressWarnings("unchecked")
    private static List<Pending<DeductResult>> deductItems(List<Pending<?>> items) {
        return (List<Pending<DeductResult>>) (List<?>) items;
    }

    @SuppressWarnings("unchecked")
    private static List<Pending<ClaimOutcome>> claimItems(List<Pending<?>> items) {
        return (List<Pending<ClaimOutcome>>) (List<?>) items;
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

    /** 所有分片都空且无在途 flush（无锁：只读 volatile 镜像） */
    private boolean isIdle() {
        for (ShardBuffer shard : shards) {
            if (!shard.isIdle()) {
                return false;
            }
        }
        return true;
    }

    /** 二次分组的键：动作 × segment × 台账行主键 */
    private record GroupKey(QuotaAction action, InventorySegmentVO segment, long shardKey) {
    }
}
