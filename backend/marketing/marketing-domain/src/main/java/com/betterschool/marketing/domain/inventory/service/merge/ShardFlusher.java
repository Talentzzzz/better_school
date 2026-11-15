package com.betterschool.marketing.domain.inventory.service.merge;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/**
 * 单个分片的常驻排空线程：收件、攒批、单飞 flush。
 *
 * <h2>线程模型：每分片一条常驻 daemon 平台线程</h2>
 * 形状取自 RocketMQ {@code GroupCommitService}（单常驻线程 + 双缓冲换批 + 锁外落库），
 * 但唤醒原语不用它的 {@code ServiceThread}（reset-latch），而用 {@link ReentrantLock} +
 * {@link Condition} 的标准条件等待协议——谓词锁内检查、park 前最后再查、signal 持锁。
 * 源码考据（wayfinder 票 001）发现 latch 方案存在已定性的丢失唤醒竞态（apache/rocketmq
 * issue #5710 / #10543，后者于 2026-06 定性为 bug 并改用 LockSupport + deadline 循环修复），
 * 而 Condition 协议在机制层面免疫该竞态——丢失的 signal 只影响提前唤醒的及时性，
 * 不影响正确性：超时后照常醒来重查谓词。
 *
 * <h2>单飞：结构即保证</h2>
 * 同一分片出现两条并发 flush，台账行的行锁就会重新排队，合并收益立刻消失。旧实现靠
 * {@code running}/{@code generation} 一套任务替换机制防它；这里分片串行化落在「同一分片
 * 恰一条线程、摘批与等待在同一把锁内」上——任何时刻最多只有一个批次在途，无需额外标志。
 *
 * <h2>三条结构性结论</h2>
 * <ol>
 *   <li>状态收敛为 {@code pending} + {@code size} 镜像 + {@code closed}：没有「在途链」
 *       概念——线程活着且缓冲非空就是在攒批/落库，线程是单飞的载体而非被调度的任务；</li>
 *   <li>攒批窗口从「分片首件」起算（{@code maxFlushDelayMillis}），攒满 {@code maxBatch}
 *       由 offer 持锁 signal 提前唤醒立即摘批；上一批收尾时剩下的积压（背靠背）不重新开窗，
 *       立即续跑——积压件已经等过一个窗口，再等一个只是白加时延；</li>
 *   <li>锁只保护缓冲区与标志位，<b>绝不跨落库调用</b>——否则单热点下所有提交线程会被一次落库卡住。
 *       用 {@link ReentrantLock} 而非 {@code synchronized}：虚拟线程阻塞在 monitor 上会钉住载体线程。</li>
 * </ol>
 */
@Slf4j
final class ShardFlusher {

    /** 新缓冲区的初始容量：常数预分配，攒批期间基本不扩容（硬上限是 shardCapacity，不扩容也不缩容） */
    private static final int INITIAL_CAPACITY = 256;

    private final int shardNo;
    private final int maxBatch;
    private final int capacity;
    private final long maxFlushDelayNanos;
    /** 摘批后的落库回调：整批交给引擎，由引擎做「动作 × segment × invId」二次分组与逐件回填（锁外执行） */
    private final Consumer<List<Pending>> flushCallback;
    /** 排空信号：每走完一批就叫一声，让 awaitIdle 不用轮询 */
    private final Runnable flushDoneSignal;

    private final ReentrantLock lock = new ReentrantLock();
    /** 攒批条件：offer 首件/攒满、failRemaining 停机时 signal（唯一等待者是本分片的 flush 线程） */
    private final Condition batchReady = lock.newCondition();

    /** 待合并缓冲区（锁内访问）。双缓冲换批：flush 时整块摘走，O(1) 而不是 O(n) 前移 */
    private List<Pending> pending = new ArrayList<>(INITIAL_CAPACITY);
    /** {@code pending.size()} 的无锁镜像：只用于积压统计与空闲判定，真实判定一律在锁内 */
    private volatile int size;
    /** 停机标记：failRemaining 置位；线程观察到 {@code closed} 且缓冲空即退出 */
    private volatile boolean closed;

    /** 本分片的常驻 flush 线程（构造即启动，daemon 不阻塞 JVM 停机） */
    private final Thread flushThread;

    ShardFlusher(int shardNo,
                 MergeConfig config,
                 Consumer<List<Pending>> flushCallback,
                 Runnable flushDoneSignal) {
        this.shardNo = shardNo;
        this.maxBatch = config.maxBatch();
        this.capacity = config.shardCapacity();
        this.maxFlushDelayNanos = TimeUnit.MILLISECONDS.toNanos(config.maxFlushDelayMillis());
        this.flushCallback = flushCallback;
        this.flushDoneSignal = flushDoneSignal;
        this.flushThread = Thread.ofPlatform()
                .daemon(true)
                .name("inventory-merge-flush-" + shardNo)
                .start(this::flushLoop);
    }

    /**
     * 收件（满即拒：不阻塞、不扩容、不挤掉已入队件）。
     *
     * @return false = 未入队（缓冲区满或已停机）；件从未进过缓冲区，必未 settle
     */
    boolean offer(Pending item) {
        lock.lock();
        try {
            if (closed || pending.size() >= capacity) {
                return false;
            }
            boolean wasEmpty = pending.isEmpty();
            pending.add(item);
            size = pending.size();

            if (wasEmpty) {
                // 分片首件：叫醒线程开一个 maxFlushDelayNanos 的合并窗口
                batchReady.signal();
            } else if (pending.size() >= maxBatch) {
                // 攒满：提前唤醒、立即摘批（线程正在落库时 signal 落空无害——收尾重查谓词会看到积压）
                batchReady.signal();
            }
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 停机：置停机位 + 把缓冲区剩下的件显式回填 {@code FAILED}，并 signal 唤醒线程退出循环。
     * 只回填、不落库：这些件从未进入事务，台账一定没动，对外语义是「可安全重试」。
     * 正在落库的那一批不受影响——它会在收尾后观察到 {@code closed} 且缓冲空，自然退出。
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
            List<Pending> leftover = pending;
            pending = new ArrayList<>(INITIAL_CAPACITY);
            size = 0;
            int filled = 0;
            for (Pending item : leftover) {
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

    /** 缓冲区里的件数（无锁读） */
    int pendingSize() {
        return size;
    }

    /**
     * 是否已排空：缓冲区空。在途的那一批不在 {@code pending} 里（已摘批、尚未回填），
     * 这个空档由 {@code InventoryMerger#close} 的 join 兜底等完——线程只在
     * {@code closed} 且缓冲空时退出，join 返回即代表在途 flush 已收尾。
     */
    boolean isIdle() {
        return size == 0;
    }

    /**
     * 等线程退出（停机收尾用）。线程从不被 interrupt（关闭走 closed + signal），
     * 超时意味着它大概率阻塞在落库上——daemon 线程不阻塞 JVM 停机，放弃等待即可。
     *
     * @return true = 线程已退出
     */
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

    /**
     * 主循环：等「该 flush」或「该退出」→ 锁内 swap 摘批 → <b>锁外</b>落库 → 逐件回填
     * （回调内）→ 叫一声排空信号 → 回到等待。
     */
    private void flushLoop() {
        // 当前合并窗口的截止时刻（System.nanoTime 口径）；0 = 没有在数的窗口
        long batchDeadlineNanos = 0L;
        // 上一批摘批时缓冲里留有超出 maxBatch 的积压：这批收尾后背靠背立即续跑，不重新开窗
        boolean carryOver = false;

        while (true) {
            List<Pending> batch;
            lock.lock();
            try {
                while (true) {
                    if (closed && pending.isEmpty()) {
                        // 停机且排空：收工（在途批已在上面收尾）
                        return;
                    }
                    if (pending.isEmpty()) {
                        // 闲置：等首件（offer signal）或停机（failRemaining signal）
                        batchDeadlineNanos = 0;
                        awaitSignal();
                        continue;
                    }
                    long now = System.nanoTime();
                    if (batchDeadlineNanos == 0) {
                        // 两条进门路：摘批剩下的积压（背靠背）立即续跑；闲置后的首件开新窗口
                        batchDeadlineNanos = carryOver ? now : now + maxFlushDelayNanos;
                    }
                    if (pending.size() >= maxBatch || now >= batchDeadlineNanos) {
                        batch = takeBatch();
                        carryOver = !pending.isEmpty();
                        batchDeadlineNanos = 0;
                        break;
                    }
                    // 窗口未到且未攒满：等到截止时刻。攒满/停机的 signal 会提前唤醒——
                    // 用 awaitNanos 表达 awaitUntil(deadline)，标准条件等待协议免疫丢失唤醒
                    awaitBatchDeadline(batchDeadlineNanos - now);
                }
            } finally {
                lock.unlock();
            }

            try {
                flushCallback.accept(batch);
            } catch (RuntimeException e) {
                // 引擎内部已按组兜住落库异常；能漏到这里的只有引擎自身的 bug
                log.error("分片 {} 的 flush 兜底异常（{} 件可能未回填）", shardNo, batch.size(), e);
            } finally {
                // 唤醒排空等待者：它们只需要知道「又有一批走完了」
                flushDoneSignal.run();
            }
        }
    }

    /** 闲置等待：等首件 / 停机 signal 唤醒。吃掉外来中断——线程生命周期只由 closed 控制 */
    private void awaitSignal() {
        try {
            batchReady.await();
        } catch (InterruptedException ignored) {
            // Condition 抛出时已清中断标记，循环重查谓词即可，不会自旋
        }
    }

    /** 睡到攒批截止（awaitNanos(剩余时间) ≡ awaitUntil(deadline)）：到点返回，或被攒满/停机 signal 提前叫醒 */
    private void awaitBatchDeadline(long remainNanos) {
        try {
            batchReady.awaitNanos(remainNanos);
        } catch (InterruptedException ignored) {
            // 同上：标记已清，醒来重查谓词
        }
    }

    /** 锁内摘批。整批不超过上限时零拷贝换缓冲，超批时才搬一次剩余部分（保序） */
    private List<Pending> takeBatch() {
        int buffered = pending.size();
        if (buffered <= maxBatch) {
            List<Pending> batch = pending;
            pending = new ArrayList<>(INITIAL_CAPACITY);
            size = 0;
            return batch;
        }
        List<Pending> batch = new ArrayList<>(pending.subList(0, maxBatch));
        List<Pending> rest = new ArrayList<>(buffered - maxBatch);
        rest.addAll(pending.subList(maxBatch, buffered));
        pending = rest;
        size = rest.size();
        return batch;
    }
}
