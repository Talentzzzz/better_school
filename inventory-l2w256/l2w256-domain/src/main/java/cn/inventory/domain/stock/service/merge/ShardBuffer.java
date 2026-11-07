package cn.inventory.domain.stock.service.merge;

import cn.inventory.domain.stock.model.valobj.DeductResultVO;
import cn.inventory.domain.stock.model.valobj.MergeConfigVO;
import cn.inventory.types.enums.DeductStatus;
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
 * 单个分片的合并缓冲区 —— 整个方案的核心，只做三件事：收件、攒批、单飞触发 flush。
 *
 * <p><b>入口驱动 vs 出口驱动，差别就在这个类里</b>：等那 5ms 的是<b>定时器队列</b>，
 * 不是某条线程。提交线程把请求塞进缓冲区就立刻 park 在自己的 future 上；
 * flush 线程被唤醒时缓冲区里已经是满的 200 件——攒批成本从消费侧搬到了提交侧
 * （提交侧只多一次 {@code add}，消费侧省掉 200 次 {@code poll}，实测省 17µs/件）。
 *
 * <h2>单飞：为什么需要「运行标志 + 代际号」双保险</h2>
 * 同一分片一旦出现两条并发 flush，热点行的行锁就会重新排队，合并的收益立刻消失。
 * 天真的做法是「cancel 失败 ⟹ flush 已在执行」，但 {@link java.util.concurrent.Future#cancel(boolean)}
 * <b>对正在执行的任务同样返回 true</b>：{@code FutureTask} 的 state 在 callable 执行期间仍是 NEW，
 * 只有结束时才流转。照那套写法，攒满提前触发会把「正在落库的那一批」的 future 取消掉，
 * 再排一条新的——于是两条 flush 同时跑（单测 {@code singleFlight} 能稳定复现）。
 *
 * <p>所以这里用两个独立的判据：
 * <ul>
 *   <li>{@code running}：flush 体（摘批 + 落库）正在执行，锁内维护，提前触发时据此直接放弃；</li>
 *   <li>{@code generation}：在途链的代号。每次排定新 flush 自增，旧代号的任务即使被调度器
 *       放进来执行，也会在锁内发现「自己已被取代」而立刻退出。</li>
 * </ul>
 * 两者叠加后，「同一分片至多一条 flush 体在执行」是被代码结构保证的，而不是靠时序运气。
 *
 * <h2>三条不变量</h2>
 * <ol>
 *   <li>同一分片永远只有一条 flush 链（{@code scheduled} 是它的存在标志）；</li>
 *   <li>{@code scheduled == false} ⟹ 缓冲区必空且无在途 flush；</li>
 *   <li>锁只保护缓冲区与标志位，<b>绝不跨 DB 调用</b>——否则单热点下所有提交线程会被一次落库卡住。</li>
 * </ol>
 *
 * <p>锁用 {@link ReentrantLock} 而不是 {@code synchronized}：JDK21 虚拟线程阻塞在
 * {@code synchronized} 上会钉住载体线程（pinning），单热点下所有提交线程争同一把 monitor
 * 会把 4 个载体线程钉死，测出来的就不是方案本身了。
 */
@Slf4j
final class ShardBuffer {

    /** 新缓冲区的初始容量：按单批上限预分配，避免高频扩容（容量上限可能远大于此，按需增长） */
    private static final int INITIAL_CAPACITY = 256;

    private final int shardNo;
    private final int maxBatch;
    private final int capacity;
    private final long windowMs;
    private final ScheduledExecutorService scheduler;
    private final Consumer<List<MergeTask>> flushAction;
    private final Runnable flushDoneSignal;

    private final ReentrantLock lock = new ReentrantLock();

    /** 待合并缓冲区（锁内访问）。双缓冲换批：flush 时整块摘走，O(1) 而不是 O(n) 前移 */
    private List<MergeTask> pending = new ArrayList<>(INITIAL_CAPACITY);
    /** 是否有在途 flush 链（排队中或执行中）；false 时缓冲区必空。volatile 供无锁判读 */
    private volatile boolean scheduled;
    /** 当前在途的 flush 任务（锁内访问） */
    private ScheduledFuture<?> flight;
    /** flush 体正在执行（锁内访问）。cancel() 对运行中的任务也会返回 true，所以不能用它判断 */
    private boolean running;
    /** 在途链代号：每次排定自增，旧代号的任务执行前会发现自己已作废（锁内访问） */
    private long generation;
    /** {@code pending.size()} 的无锁镜像：只用于积压统计与空闲判定，真实判定一律在锁内 */
    private volatile int size;
    /** 停机标记：一旦置位，后续 offer 一律拒绝 */
    private volatile boolean closed;

    ShardBuffer(int shardNo,
                MergeConfigVO config,
                ScheduledExecutorService scheduler,
                Consumer<List<MergeTask>> flushAction,
                Runnable flushDoneSignal) {
        this.shardNo = shardNo;
        this.maxBatch = config.getMaxBatch();
        this.capacity = config.getShardCapacity();
        this.windowMs = config.getWindowMs();
        this.scheduler = scheduler;
        this.flushAction = flushAction;
        this.flushDoneSignal = flushDoneSignal;
    }

    /**
     * 收件。
     *
     * @return false = 缓冲区已满或已停机，调用方应立即返回背压拒绝（不占等待时间）
     */
    boolean offer(MergeTask task) {
        lock.lock();
        try {
            if (closed) {
                return false;
            }
            if (pending.size() >= capacity) {
                return false;
            }
            pending.add(task);
            size = pending.size();

            if (!scheduled) {
                // 分片首个请求：排定一个窗口后的 flush，后续请求只入缓冲区、等同一个窗口
                scheduled = true;
                return scheduleNext(windowMs);
            }
            if (pending.size() >= maxBatch) {
                // 攒满：取消窗口等待、改排立即 flush
                bringForward();
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 攒满后提前触发：把「等窗口」换成「立刻 flush」。
     *
     * <p>正在落库时什么也不做——那一批的收尾会看到积压，自动以 0 延迟续排，
     * 效果等价于「提前触发」，但不会破坏单飞。
     */
    private void bringForward() {
        if (running || flight == null || flight.isDone()) {
            return;
        }
        if (flight.cancel(false)) {
            // 代际号自增：万一这个任务已经被调度器取出、正在往 runFlush 里走，
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
            // 只在停机阶段出现：把缓冲区里的请求一次性显式失败，绝不让调用方干等到超时
            failAllLocked("调度器已关闭", DeductStatus.ERROR);
            return false;
        }
    }

    /**
     * flush 主体：锁内摘批 → 锁外落库 → 锁内续排。
     *
     * <p>它在<b>执行池线程</b>上运行，不在提交线程上——这就是「入口驱动」的分水岭：
     * 池线程只在真正落库的那几百微秒里被占用，等待窗口由定时器承担、不占任何线程。
     */
    private void runFlush(long myGeneration) {
        List<MergeTask> batch;
        lock.lock();
        try {
            if (closed || myGeneration != generation) {
                // 已被更新的一代取代（提前触发），或已停机：什么都不做
                return;
            }
            if (pending.isEmpty()) {
                // 窗口内一个请求都没来（或已被上一次 flush 带走）：熄火，等下一个请求重新点火
                scheduled = false;
                flight = null;
                size = 0;
                return;
            }
            running = true;
            batch = takeBatch();
        } finally {
            lock.unlock();
        }

        try {
            flushAction.accept(batch);
        } finally {
            lock.lock();
            try {
                running = false;
                if (pending.isEmpty()) {
                    scheduled = false;
                    flight = null;
                } else if (!closed) {
                    // flush 期间又来了新请求：攒满则立即续跑（背靠背），否则再等一个窗口
                    scheduleNext(pending.size() >= maxBatch ? 0 : windowMs);
                }
            } finally {
                lock.unlock();
            }
            // 唤醒 reset / 停机流程里的排空等待者：它们只需要知道「又有一批走完了」
            flushDoneSignal.run();
        }
    }

    /** 锁内摘批。整批不超过上限时零拷贝换缓冲，超批时才搬一次剩余部分 */
    private List<MergeTask> takeBatch() {
        int buffered = pending.size();
        if (buffered <= maxBatch) {
            List<MergeTask> batch = pending;
            pending = new ArrayList<>(INITIAL_CAPACITY);
            size = 0;
            return batch;
        }
        List<MergeTask> batch = new ArrayList<>(pending.subList(0, maxBatch));
        List<MergeTask> rest = new ArrayList<>(buffered - maxBatch);
        rest.addAll(pending.subList(maxBatch, buffered));
        pending = rest;
        size = rest.size();
        return batch;
    }

    /**
     * 停机时把缓冲区剩下的请求显式失败。
     *
     * <p>只完成 future、不落库：这些请求从未进入事务，库存一定没动，
     * 对外语义是「可安全重试」，比让调用方等到超时再猜要诚实。
     */
    void failRemaining(DeductStatus status, String reason) {
        lock.lock();
        try {
            failAllLocked(reason, status);
        } finally {
            lock.unlock();
        }
    }

    /** 锁内：清空缓冲区并回填失败。complete() 只 unpark 等待线程，不会回调进本类，持锁安全 */
    private void failAllLocked(String reason, DeductStatus status) {
        closed = true;
        scheduled = false;
        flight = null;
        size = 0;
        if (pending.isEmpty()) {
            return;
        }
        List<MergeTask> leftover = pending;
        pending = new ArrayList<>(INITIAL_CAPACITY);
        log.warn("分片 {} 未落库即丢弃 {} 个请求：{}", shardNo, leftover.size(), reason);
        for (MergeTask task : leftover) {
            task.getFuture().complete(DeductResultVO.of(task.getRequest().getBizNo(), status));
        }
    }

    /** 缓冲区里的请求数（无锁读） */
    int pendingSize() {
        return size;
    }

    /** 是否已排空：没有在途 flush、缓冲区也空 */
    boolean isIdle() {
        return !scheduled && size == 0;
    }
}
