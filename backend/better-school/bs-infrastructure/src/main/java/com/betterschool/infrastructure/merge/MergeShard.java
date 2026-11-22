package com.betterschool.infrastructure.merge;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 一个合并分片：一个缓冲队列（装 {@link MergeItem}）+ 一条常驻排空线程。
 *
 * <p>一个实例只对应一个路由分片，因此「同一分片恒一条线程」是结构保证的：摘批与等待在同一把锁内，
 * 任何时刻最多一个批次在途，无需额外标志。攒批窗口从首件起算，攒满 {@code maxBatch}
 * 由 {@link #offer} 提前唤醒摘批；上一批剩下的积压立即续跑（已等过一个窗口，再等只是白加时延）。
 * 锁只保护队列，<b>绝不跨落库调用</b>；用 {@link ReentrantLock} 而非 {@code synchronized}
 * （虚拟线程撞 monitor 会钉住载体线程）。
 *
 * <p>不认识业务、也不认识引擎的分组回填：摘出来的批交给构造时传入的 {@code flusher}
 * （引擎的 flush 路径），每走完一批叫一声 {@code idleSignal}（引擎的排空等待靠它，不用轮询）。
 */
@Slf4j
final class MergeShard<T, R> {

    /** 初始容量：常数预分配，攒批期间基本不扩容（硬上限 shardCapacity） */
    private static final int INITIAL_CAPACITY = 256;

    private final int shardNo;
    private final int maxBatch;
    private final int capacity;
    private final long maxFlushDelayNanos;
    /** 摘出来的批怎么落库——交给引擎的 flush 路径（分组 + 回填），本类不关心 */
    private final Consumer<List<MergeItem<T, R>>> flusher;
    /** 每走完一批叫一声：引擎的排空等待靠它 */
    private final Runnable idleSignal;

    private final ReentrantLock lock = new ReentrantLock();
    /** 攒批条件：offer 首件 / 攒满、failRemaining 停机时 signal（唯一等待者是本分片的 flush 线程） */
    private final Condition batchReady = lock.newCondition();

    /** 待合并缓冲区（锁内访问）。双缓冲换批：flush 时整块摘走，O(1) */
    private List<MergeItem<T, R>> pending = new ArrayList<>(INITIAL_CAPACITY);
    /** {@code pending.size()} 的无锁镜像：只用于积压统计与空闲判定，真实判定一律在锁内 */
    private volatile int size;
    /** 停机标记：线程观察到 {@code closed} 且缓冲空即退出 */
    private volatile boolean closed;
    /** 是否有批次在途（已摘批、flush 尚未收尾）：缓冲空不等于空闲，摘批后的那批才是 */
    private volatile boolean flushing;

    private final Thread flushThread;

    MergeShard(int shardNo, MergeConfig config, Consumer<List<MergeItem<T, R>>> flusher, Runnable idleSignal) {
        this.shardNo = shardNo;
        this.maxBatch = config.maxBatch();
        this.capacity = config.shardCapacity();
        this.maxFlushDelayNanos = TimeUnit.MILLISECONDS.toNanos(config.maxFlushDelayMillis());
        this.flusher = flusher;
        this.idleSignal = idleSignal;
        this.flushThread = Thread.ofPlatform()
                .daemon(true)
                .name("merge-flush-" + shardNo)
                .start(this::flushLoop);
    }

    /**
     * 收件（满即拒：不阻塞、不扩容、不挤掉已入队件）。
     *
     * @return false = 未入队（缓冲区满或已停机）；件从未进过缓冲区，必未 settle
     */
    boolean offer(MergeItem<T, R> item) {
        lock.lock();
        try {
            if (closed || pending.size() >= capacity) {
                return false;
            }
            boolean wasEmpty = pending.isEmpty();
            pending.add(item);
            size = pending.size();

            if (wasEmpty || pending.size() >= maxBatch) {
                // 首件开窗口；攒满则提前唤醒立即摘批（线程正在落库时 signal 落空无害，收尾会重查谓词）
                batchReady.signal();
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 停机：置停机位 + 把缓冲区剩下的件显式回填 {@code FAILED}，并 signal 唤醒线程退出。
     * 只回填、不落库：这些件从未进入事务，对外语义是「可安全重试」。
     *
     * @return 被回填的件数
     */
    int failRemaining(String reason) {
        lock.lock();
        try {
            closed = true;
            if (pending.isEmpty()) {
                return 0;
            }
            List<MergeItem<T, R>> leftover = pending;
            pending = new ArrayList<>(INITIAL_CAPACITY);
            size = 0;
            int filled = 0;
            for (MergeItem<T, R> item : leftover) {
                if (item.fail()) {
                    filled++;
                }
            }
            log.warn("分片 {} 未落库即回填 {} 件（原因：{}）", shardNo, leftover.size(), reason);
            return filled;
        } finally {
            // complete() 只 unpark 等待线程，不会回调进本类，持锁 signal 安全
            batchReady.signalAll();
            lock.unlock();
        }
    }

    int pendingSize() {
        return size;
    }

    /** 是否已排空：缓冲空且没有在途批（摘批那一刻起就算在途，见 {@link #takeBatch}） */
    boolean isIdle() {
        return size == 0 && !flushing;
    }

    /** 等线程退出（停机收尾用）。线程从不被 interrupt，超时意味着它阻塞在落库上 */
    boolean joinFlushThread(long timeoutMs) {
        if (!flushThread.isAlive()) {
            return true;
        }
        try {
            flushThread.join(timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return !flushThread.isAlive();
    }

    /** 主循环：等「该 flush」或「该退出」→ 锁内 swap 摘批 → 锁外落库 → 叫一声排空信号 */
    private void flushLoop() {
        // 当前合并窗口的截止时刻；0 = 没有在数的窗口
        long deadline = 0L;
        // 上一批摘批时留下的积压：收尾后背靠背立即续跑，不重新开窗
        boolean carryOver = false;

        while (true) {
            List<MergeItem<T, R>> batch;
            lock.lock();
            try {
                while (true) {
                    if (closed && pending.isEmpty()) {
                        return;
                    }
                    if (pending.isEmpty()) {
                        deadline = 0;
                        awaitSignal();
                        continue;
                    }
                    long now = System.nanoTime();
                    if (deadline == 0) {
                        deadline = carryOver ? now : now + maxFlushDelayNanos;
                    }
                    if (pending.size() >= maxBatch || now >= deadline) {
                        batch = takeBatch();
                        carryOver = !pending.isEmpty();
                        deadline = 0;
                        break;
                    }
                    awaitDeadline(deadline - now);
                }
            } finally {
                lock.unlock();
            }

            try {
                flusher.accept(batch);
            } catch (RuntimeException e) {
                // 引擎内部已按组兜住落库异常；能漏到这里的只有引擎自身的 bug
                log.error("分片 {} 的 flush 兜底异常（{} 件可能未回填）", shardNo, batch.size(), e);
            } finally {
                flushing = false;
                idleSignal.run();
            }
        }
    }

    /** 闲置等待：等首件 / 停机 signal。吃掉外来中断——线程生命周期只由 closed 控制 */
    private void awaitSignal() {
        try {
            batchReady.await();
        } catch (InterruptedException ignored) {
            // Condition 抛出时已清中断标记，循环重查谓词即可
        }
    }

    /** 睡到攒批截止（到点返回，或被攒满 / 停机 signal 提前叫醒） */
    private void awaitDeadline(long remainNanos) {
        try {
            batchReady.awaitNanos(remainNanos);
        } catch (InterruptedException ignored) {
            // 同上
        }
    }

    /** 锁内摘批（同时置「在途」位）。整批不超上限时零拷贝换缓冲，超批时才搬一次剩余部分（保序） */
    private List<MergeItem<T, R>> takeBatch() {
        flushing = true;
        int buffered = pending.size();
        if (buffered <= maxBatch) {
            List<MergeItem<T, R>> batch = pending;
            pending = new ArrayList<>(INITIAL_CAPACITY);
            size = 0;
            return batch;
        }
        List<MergeItem<T, R>> batch = new ArrayList<>(pending.subList(0, maxBatch));
        List<MergeItem<T, R>> rest = new ArrayList<>(buffered - maxBatch);
        rest.addAll(pending.subList(maxBatch, buffered));
        pending = rest;
        size = rest.size();
        return batch;
    }
}
