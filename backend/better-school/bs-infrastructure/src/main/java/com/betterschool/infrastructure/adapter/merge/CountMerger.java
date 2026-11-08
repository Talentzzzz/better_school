package com.betterschool.infrastructure.adapter.merge;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

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
 * 计数侧合并器：<b>固定 N 个分片缓冲区 + 入口驱动定时器 + 固定大小共享执行池</b>（spec §2.2）。
 *
 * <h2>一件请求的一生</h2>
 * <pre>
 *   submitAndWait(postId, event)
 *     ├─ 按 shardOf(postId) 路由到分片
 *     ├─ 塞进该分片缓冲区（首个请求排定 windowMs 后的 flush；攒满 maxBatch 提前触发）
 *     └─ park 在自己的 future 上，最多等 waitTimeoutMs
 *                    ↓（定时器到点，由执行池线程接管）
 *   flush：锁内整块摘批 → 锁外按「动作 × 行主键」二次分组 → 一组一个事务落库 → 回填 → 有积压则续排
 * </pre>
 *
 * <h2>两个入口，不做合并签名</h2>
 * {@link #submitAndWait} 返回 {@link MergeOutcome}（点赞 / 取消赞 / 评论赞），
 * {@link #submitAsync} 只返回 {@link SubmitOutcome}（浏览）。<b>不等的人连 future 都不分配</b>：
 * 10k/s 的浏览若每人一个 future，每秒就是一万次分配 + park/unpark，比落库本身还贵。
 *
 * <h2>一条实例</h2>
 * 点赞（等）与浏览（不等）共用同一份分片与线程池——两个实例要 2×256 个缓冲，而它们打的是
 * 同一批热点帖（同一条业务行必须落同一片，才能合并成一条 UPDATE）。
 *
 * <h2>背压与降级：引擎不说 {@code REJECTED}</h2>
 * 引擎只回答 {@code ACCEPTED / BUFFER_FULL / CLOSED}，<b>降级动作全在 case 层</b>：
 * 缓冲满或停机中 → 调用方线程用<b>同一个落库方法</b>扣组大小 1；等超时 → {@code RETRYABLE}。
 * 两套 SQL 迟早漂移，而漂移只在压力最大时暴露，所以降级必须复用同一个落库方法（spec §2.6）。
 *
 * <h2>五条不变量</h2>
 * I1 不丢件且恰一个终态（{@link Pending#complete} 幂等 + 漏回填补 {@code FAILED}）、
 * I2 同分片单飞（{@link ShardBuffer} 的 {@code running + generation} 双保险）、
 * I3 锁不跨 DB（临界区只碰内存）、I4 停机三步（停收 → 排空 → 关池）、
 * I5 背压有界（满即拒，不扩容、不挤掉已入队件）。
 *
 * <h2>装配</h2>
 * {@code @Component}：参数来自 {@code bs-app} 的 {@code CountMergeConfig}，出口是
 * {@link CountFlushDispatcher}（按动作把一组交给拥有那张表的域仓储：社区仓储的点赞 / 取消赞 /
 * 评论赞 / 帖子浏览，交易仓储的商品浏览）。调用方有两个：{@link CountMergeWriter}（点赞类，等结果）
 * 与 {@link ViewMergeWriter}（浏览，不等结果）——降级、超时翻译、丢弃计数都在那两个类里，
 * 本类不认识 HTTP 语义。
 */
@Slf4j
@Component
public class CountMerger {

    /** 排空等待的兜底探测间隔：正常由 flush 完成信号唤醒，这里只防信号丢失 */
    private static final long IDLE_PROBE_MS = 5;

    private final MergeConfig config;
    private final ICountFlushHandler flushHandler;
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

    /**
     * @param flushHandler 落库方法（实现是 {@link CountFlushDispatcher}）。{@code @Lazy} 不是可选项：
     *                     落库方法在 {@code CommunityRepository} / {@code TradeRepository} 上，
     *                     而这两个仓储的写入入口又要调 {@link CountMergeWriter} / {@link ViewMergeWriter}
     *                     → 本类 —— 出口与调用方互相指着，不延迟到 flush 时解析就是循环依赖。
     *                     合并器本来也只在落库那一刻才用到它，构造期不需要真的把它拿到手
     */
    public CountMerger(MergeConfig config, @Lazy ICountFlushHandler flushHandler) {
        if (flushHandler == null) {
            throw new IllegalArgumentException("[配置错误] ICountFlushHandler 不能为 null：合并器没有出口等于丢弃所有件");
        }
        this.config = config;
        this.flushHandler = flushHandler;
        this.scheduler = new ScheduledThreadPoolExecutor(
                config.flushThreads(),
                Thread.ofPlatform().daemon(true).name("count-merge-flush-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        // ★ 取消的定时任务立刻出队。攒满提前触发每秒会 cancel 上千个任务，
        //   不开这个策略，它们会一直躺在延时队列里等到原定窗口才被清理，
        //   256 分片 × N/s 的规模下就是实打实的内存与调度开销。
        this.scheduler.setRemoveOnCancelPolicy(true);

        this.shards = new ShardBuffer[config.shardCount()];
        for (int i = 0; i < config.shardCount(); i++) {
            shards[i] = new ShardBuffer(i, config, scheduler, this::flushBatch, this::signalIdle);
        }

        log.info("计数合并器启动：shardCount={}(mask={}), windowMs={}, maxBatch={}, waitTimeoutMs={}, "
                        + "shardCapacity={}, flushThreads={}, drainTimeoutMs={}",
                config.shardCount(), config.mask(), config.windowMs(), config.maxBatch(),
                config.waitTimeoutMs(), config.shardCapacity(), config.flushThreads(), config.drainTimeoutMs());
    }

    /**
     * 等结果的入口（点赞 / 取消赞 / 评论赞）。
     *
     * @param shardKey 这组要更新的那一行的<b>业务主键</b>（postId / commentId 等字符串），<b>由调用方显式给</b>
     * @param event    事件本体；合并器不解析它来推导 key
     */
    public MergeOutcome<LikeState> submitAndWait(String shardKey, CountEvent event) {
        Pending item = Pending.waiting(shardKey, event);
        SubmitOutcome outcome = enqueue(item);
        if (outcome != SubmitOutcome.ACCEPTED) {
            if (outcome == SubmitOutcome.BUFFER_FULL) {
                return MergeOutcome.bufferFull();
            }
            // 入队后被立即回填（调度器已在停机）也算失败：件过了缓冲区一道手，不能再叫「没接住」
            return item.isSettled() ? MergeOutcome.failed() : MergeOutcome.closed();
        }
        try {
            return item.await(config.waitTimeoutMs());
        } catch (TimeoutException e) {
            // 件仍在缓冲区里，之后大概率仍会落库 —— 对外表达为「结果未知」，不是失败
            timedOut.incrementAndGet();
            return MergeOutcome.timeout();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 件没有丢（还在缓冲里），只是这个线程不再等了
            return MergeOutcome.failed();
        }
    }

    /**
     * 不等结果的入口（帖子 / 商品浏览）：入队即返回，不分配 future。
     *
     * @return {@code ACCEPTED} 已入队；另外两个由 case 层转为「丢弃并 droppedViews++」
     */
    public SubmitOutcome submitAsync(String shardKey, CountEvent event) {
        return enqueue(Pending.fireAndForget(shardKey, event));
    }

    /**
     * 唯一入队通道：停机检查 → 路由到分片收件 → 计数。两个入口只在「要不要 future」和
     * 「拒收怎么翻译」上不同，计数口径必须共用一份。
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
    private SubmitOutcome enqueue(Pending item) {
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
     * 拿到可重试的结果，而不是干等 {@code waitTimeoutMs} 后猜。回填件数计入 {@code drainedFailed}，
     * 这是「停机丢了多少」的唯一读数。
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
                log.warn("计数合并执行池未在 {}ms 内结束，已强制中断", config.drainTimeoutMs());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
        log.info("计数合并器已停止：drained={}, 剩余积压={}, 读数={}", drained, pending(), metrics());
    }

    // ── 落库路径 ──────────────────────────────────────────────────────────────

    /**
     * 摘批后的落库：按「动作 × 行主键」二次分组，一组一个事务。
     *
     * <p>一组一事务（不是一批一事务）：一组让锁集最小、失败只回滚一组、结果能精确回填到件；
     * 一批一事务会让几个不相干的行以不同顺序被锁，多出一片死锁面。
     */
    private void flushBatch(List<Pending> pendingList) {
        Map<GroupKey, List<Pending>> groups = new LinkedHashMap<>();
        for (Pending item : pendingList) {
            groups.computeIfAbsent(new GroupKey(item.event().action(), item.shardKey()),
                    key -> new ArrayList<>()).add(item);
        }
        try {
            for (Map.Entry<GroupKey, List<Pending>> entry : groups.entrySet()) {
                flushOneGroup(entry.getKey(), entry.getValue());
            }
        } finally {
            // 兜底：任何还没回填的件补 FAILED —— 绝不让等待者悬到超时（I1）
            for (Pending item : pendingList) {
                if (item.complete(MergeState.FAILED, null)) {
                    failed.incrementAndGet();
                }
            }
        }
    }

    /** 落库一组 + 回填 + 计数。落库方法的异常只吞掉本组，别的组照常跑 */
    private void flushOneGroup(GroupKey group, List<Pending> pendingList) {
        List<CountEvent> countEventList = new ArrayList<>(pendingList.size());
        for (Pending item : pendingList) {
            countEventList.add(item.event());
        }

        long startNanos = System.nanoTime();
        List<LikeState> resultList = null;
        try {
            resultList = flushHandler.flushGroup(group.action(), group.shardKey(), countEventList);
        } catch (RuntimeException e) {
            log.error("计数落库失败（整组失败，{} 件）：action={}, shardKey={}",
                    pendingList.size(), group.action(), group.shardKey(), e);
        } finally {
            batches.incrementAndGet();
            maxFlushNanos.accumulateAndGet(System.nanoTime() - startNanos, Math::max);
        }

        int resultCount = resultList == null ? 0 : resultList.size();
        if (resultCount > pendingList.size()) {
            log.warn("落库方法返回的结果比入参多（{} > {}）：action={}, shardKey={}，多出的忽略",
                    resultCount, pendingList.size(), group.action(), group.shardKey());
        }
        for (int i = 0; i < pendingList.size(); i++) {
            Pending item = pendingList.get(i);
            // 长度不足 / 该位是 null 都算「漏回填」：宁可补 FAILED，也不给调用方一个「成功但没有结果」
            boolean inbound = i < resultCount && resultList.get(i) != null;
            if (inbound && item.complete(resultList.get(i))) {
                flushed.incrementAndGet();
            } else if (item.complete(MergeState.FAILED, null)) {
                // 落库方法漏回填（null / 长度不足 / 空元素）：合并器补 FAILED，件不悬着
                failed.incrementAndGet();
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

    /** 所有分片都空且无在途 flush（无锁：只读 volatile 镜像） */
    private boolean isIdle() {
        for (ShardBuffer shard : shards) {
            if (!shard.isIdle()) {
                return false;
            }
        }
        return true;
    }

    /** 二次分组的键：动作 × 行主键 */
    private record GroupKey(CountAction action, String shardKey) {
    }
}
