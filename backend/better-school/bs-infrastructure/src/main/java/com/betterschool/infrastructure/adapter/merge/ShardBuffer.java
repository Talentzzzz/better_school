package com.betterschool.infrastructure.adapter.merge;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 单个分片的合并缓冲区——整个方案的核心，只做三件事：收件、攒批、单飞触发 flush。
 *
 * <h2>入口驱动 vs 出口驱动，差别就在这个类里</h2>
 * 等那 5ms 的是<b>定时器队列</b>，不是某条线程。提交线程把件塞进缓冲区就立刻返回（或 park 在
 * 自己的 future 上），flush 线程被唤醒时缓冲区里已经是满的一批——攒批成本从消费侧搬到了提交侧
 * （提交侧只多一次 {@code add}，消费侧省掉 N 次 {@code poll}）。
 *
 * <h2>单飞：为什么需要「运行标志 + 代际号」双保险（I2）</h2>
 * 同一分片一旦出现两条并发 flush，热点行的行锁就会重新排队，合并的收益立刻消失。天真的做法是
 * 「{@code cancel()} 失败 ⟹ flush 已在执行」，但 {@link java.util.concurrent.Future#cancel(boolean)}
 * <b>对正在执行的任务同样返回 true</b>：{@code FutureTask} 的 state 在 callable 执行期间仍是 NEW，
 * 只有结束时才流转。照那套写法，攒满提前触发会把「正在落库的那一批」的 future 取消掉再排一条新的
 * ——于是两条 flush 同时跑（{@code singleFlightNeverTwoConcurrentFlushes} 能稳定复现）。
 *
 * <p>所以这里用两个独立判据：
 * <ul>
 *   <li>{@code running}：flush 体正在执行（锁内维护），提前触发时据此直接放弃；</li>
 *   <li>{@code generation}：在途链的代号。每次排定新 flush 自增，旧代号的任务即使被调度器放进来
 *       执行，也会在锁内发现「自己已被取代」而立刻退出。</li>
 * </ul>
 * 两者叠加后，「同一分片至多一条 flush 体在执行」由代码结构保证，而不是靠时序运气。
 *
 * <h2>三条结构性结论</h2>
 * <ol>
 *   <li>{@code scheduled} 是「在途链」的存在标志，同一分片永远只有一条；</li>
 *   <li>{@code scheduled == false} 且无在途 flush ⟹ 缓冲区必空（{@link #isIdle()} 的判据）。
 *       注意反过来不成立：<b>排空超时时会置 {@code scheduled = false} 而不等那条已经在落库的
 *       flush 收尾</b>，所以空闲判定必须连 {@code running} 一起看，否则会把「正在落库」
 *       误判成「已排空」（停机流程会提前返回）；</li>
 *   <li>锁只保护缓冲区与标志位，<b>绝不跨落库调用</b>（I3）——否则单热点下所有提交线程
 *       会被一次落库卡住。</li>
 * </ol>
 *
 * <p>锁用 {@link ReentrantLock} 而不是 {@code synchronized}：虚拟线程阻塞在 {@code synchronized}
 * 上会钉住载体线程（pinning），单热点下所有提交线程争同一把 monitor 会把载体线程钉死。
 */
@Slf4j
final class ShardBuffer {

    /** 新缓冲区的初始容量：按单批上限预分配，避免高频扩容（上限是 shardCapacity，不扩容也不缩容） */
    private static final int INITIAL_CAPACITY = 256;

    private final int shardNo;
    private final int maxBatch;
    private final int capacity;
    private final long maxFlushDelayMillis;
    private final ScheduledExecutorService scheduler;
    /** 摘批后的落库回调：整批交给引擎，由引擎做「动作 × 行主键」二次分组（锁外执行） */
    private final Consumer<List<Pending>> flushCallback;
    /** 排空信号：每走完一批就叫一声，让 awaitIdle 不用轮询 */
    private final Runnable flushDoneSignal;

    private final ReentrantLock lock = new ReentrantLock();

    /** 待合并缓冲区（锁内访问）。双缓冲换批：flush 时整块摘走，O(1) 而不是 O(n) 前移 */
    private List<Pending> pending = new ArrayList<>(INITIAL_CAPACITY);
    /** 是否有在途 flush 链（排队中或执行中）；false 时缓冲区必空。volatile 供无锁判读 */
    private volatile boolean scheduled;
    /** 当前在途的 flush 任务（锁内访问） */
    private ScheduledFuture<?> flight;
    /** flush 体正在执行。{@code cancel()} 对运行中的任务也返回 true，所以不能用它判；volatile 供空闲判定读 */
    private volatile boolean running;
    /** 在途链代号：每次排定自增，旧代号的任务执行前会发现自己已作废（锁内访问） */
    private long generation;
    /** {@code pending.size()} 的无锁镜像：只用于积压统计与空闲判定，真实判定一律在锁内 */
    private volatile int size;
    /** 停机标记：一旦置位，后续收件一律 {@link SubmitOutcome#CLOSED} */
    private volatile boolean closed;

    ShardBuffer(int shardNo,
                MergeConfig config,
                ScheduledExecutorService scheduler,
                Consumer<List<Pending>> flushCallback,
                Runnable flushDoneSignal) {
        this.shardNo = shardNo;
        this.maxBatch = config.maxBatch();
        this.capacity = config.shardCapacity();
        this.maxFlushDelayMillis = config.maxFlushDelayMillis();
        this.scheduler = scheduler;
        this.flushCallback = flushCallback;
        this.flushDoneSignal = flushDoneSignal;
    }

    /**
     * 收件（I5：满即拒，不阻塞、不扩容、不挤掉已入队件）。
     *
     * @return {@code ACCEPTED} 已入队；{@code BUFFER_FULL} 满了；{@code CLOSED} 已停机
     */
    SubmitOutcome offer(Pending item) {
        lock.lock();
        try {
            if (closed) {
                return SubmitOutcome.CLOSED;
            }
            if (pending.size() >= capacity) {
                return SubmitOutcome.BUFFER_FULL;
            }
            pending.add(item);
            size = pending.size();

            if (!scheduled) {
                // 分片首个请求：排定 maxFlushDelayMillis 后的 flush，后续请求只入缓冲区、等同一个窗口
                scheduled = true;
                return scheduleNext(maxFlushDelayMillis) ? SubmitOutcome.ACCEPTED : SubmitOutcome.CLOSED;
            }
            if (pending.size() >= maxBatch) {
                // 攒满：取消窗口等待、改排立即 flush（正在落库时什么也不做，见 bringForward）
                bringForward();
            }
            return SubmitOutcome.ACCEPTED;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 攒满后提前触发：把「等窗口」换成「立刻 flush」。
     *
     * <p>正在落库时什么也不做——那一批的收尾会看到积压，自动以 0 延迟续排，效果等价于
     * 「提前触发」，但不会破坏单飞。
     */
    private void bringForward() {
        if (running || flight == null || flight.isDone()) {
            return;
        }
        if (flight.cancel(false)) {
            // 代际号自增：万一这个任务已被调度器取出、正在往 runFlush 里走，
            // 它会发现自己的代号过期而直接退出
            scheduleNext(0);
        }
    }

    private boolean scheduleNext(long delayMs) {
        long scheduledGeneration = ++generation;
        try {
            flight = scheduler.schedule(() -> runFlush(scheduledGeneration), delayMs, TimeUnit.MILLISECONDS);
            return true;
        } catch (RejectedExecutionException e) {
            // 只在停机阶段出现：把缓冲区里的件一次性显式失败，绝不让调用方干等到超时
            failAllLocked("调度器已关闭");
            return false;
        }
    }

    /**
     * flush 主体：锁内摘批 → <b>锁外</b>落库 → 锁内续排。
     *
     * <p>它在执行池线程上跑，不在提交线程上——池线程只在真正落库的那几百微秒里被占用，
     * 等待窗口由定时器承担、不占任何线程。
     */
    private void runFlush(long myGeneration) {
        List<Pending> pendingList;
        lock.lock();
        try {
            if (closed || myGeneration != generation) {
                // 已被更新的一代取代（提前触发），或已停机：什么都不做
                return;
            }
            if (pending.isEmpty()) {
                // 窗口内一件都没来（或已被上一次 flush 带走）：熄火，等下一个件重新点火
                scheduled = false;
                flight = null;
                size = 0;
                return;
            }
            running = true;
            pendingList = takeBatch();
        } finally {
            lock.unlock();
        }

        try {
            flushCallback.accept(pendingList);
        } catch (RuntimeException e) {
            // 引擎内部已按组兜住落库异常；能漏到这里的只有引擎自身的 bug
            log.error("分片 {} 的 flush 兜底异常（{} 件可能未回填）", shardNo, pendingList.size(), e);
        } finally {
            lock.lock();
            try {
                running = false;
                if (pending.isEmpty()) {
                    scheduled = false;
                    flight = null;
                } else if (!closed) {
                    // flush 期间又来了新件：攒满则立即续跑（背靠背），否则再等一个窗口
                    scheduleNext(pending.size() >= maxBatch ? 0 : maxFlushDelayMillis);
                }
            } finally {
                lock.unlock();
            }
            // 唤醒排空等待者：它们只需要知道「又有一批走完了」
            flushDoneSignal.run();
        }
    }

    /** 锁内摘批。整批不超过上限时零拷贝换缓冲，超批时才搬一次剩余部分（保序，I6） */
    private List<Pending> takeBatch() {
        int buffered = pending.size();
        if (buffered <= maxBatch) {
            List<Pending> pendingList = pending;
            pending = new ArrayList<>(INITIAL_CAPACITY);
            size = 0;
            return pendingList;
        }
        List<Pending> pendingList = new ArrayList<>(pending.subList(0, maxBatch));
        List<Pending> restList = new ArrayList<>(buffered - maxBatch);
        restList.addAll(pending.subList(maxBatch, buffered));
        pending = restList;
        size = restList.size();
        return pendingList;
    }

    /**
     * 停机：置停机位 + 把缓冲区剩下的件显式回填 {@link MergeState#FAILED}（I4）。
     *
     * <p>只回填、不落库：这些件从未进入事务，行一定没动，对外语义是「可安全重试」，
     * 比让调用方等到超时再猜要诚实。浏览件没有 future，回填是空操作，但<b>件数必须计入</b>
     * {@code drainedFailed}——否则「丢了多少」在读数里看不见。
     *
     * @return 被回填的件数
     */
    int failRemaining(String reason) {
        lock.lock();
        try {
            return failAllLocked(reason);
        } finally {
            lock.unlock();
        }
    }

    /** 锁内：清空缓冲区并回填失败。{@code complete()} 只 unpark 等待线程，不会回调进本类，持锁安全 */
    private int failAllLocked(String reason) {
        closed = true;
        scheduled = false;
        flight = null;
        size = 0;
        if (pending.isEmpty()) {
            return 0;
        }
        List<Pending> leftoverList = pending;
        pending = new ArrayList<>(INITIAL_CAPACITY);
        int filled = 0;
        for (Pending item : leftoverList) {
            if (item.complete(MergeState.FAILED, null)) {
                filled++;
            }
        }
        log.warn("分片 {} 未落库即回填 {} 件（原因：{}）", shardNo, leftoverList.size(), reason);
        return filled;
    }

    /** 缓冲区里的件数（无锁读） */
    int pendingSize() {
        return size;
    }

    /**
     * 是否已排空：没有在途 flush 链、缓冲区空、且没有一条 flush 体正在落库。
     *
     * <p>第三个条件不能省：排空超时会把 {@code scheduled} 置 false 并回填剩余件，但那条已经
     * 摘走一批的 flush 仍可能阻塞在落库上——只看 {@code scheduled} 会把「还在落库」当成
     * 「已排空」，停机流程当场返回，进程随后被杀，那批件就真丢了。
     */
    boolean isIdle() {
        return !scheduled && size == 0 && !running;
    }
}
