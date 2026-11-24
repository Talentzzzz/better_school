package com.betterschool.infrastructure.merge;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 一个 invId 的热点组（内核「每行一个 Queue」的对应物）：动态创建、空闲即乐观删除。
 *
 * <p><b>双层并发控制</b>：map 层用 {@code ConcurrentHashMap} 乐观探测（无锁寻组），
 * 组内一把 {@link ReentrantLock} + 一个条件变量——高并发入队不挤在一张全局锁上，不同 invId
 * 的组彼此完全并行。
 *
 * <h2>执行权协议：一个 leader 独占「收集 + 落库」</h2>
 * <ol>
 *   <li><b>执行权</b>（{@code hasLeader}）：没有 leader 时由到达者竞选上位；在任者在收集窗口内吸收
 *       follower，攒满 / 停机则提前发车；</li>
 *   <li><b>持有到本批落库结束</b>：leader 摘批后<b>不放权</b>，直接在自己的请求线程上落库
 *       （一组一个事务），落库完再看队列——还有件就接着收集下一批，没件才放手。因此
 *       「同一 invId 任一时刻至多一条 flush」是由<b>执行权互斥直接保证</b>的，不需要额外的
 *       「落库权」标志。收集与落库因此是串行的：落库期间队列只进不出，代价换来的是少一个状态、
 *       少一条「等落库权」的等待路径、少一类竞态；</li>
 *   <li><b>乐观删除</b>：落库收尾时队列空 → 置 {@code removed} 并请引擎从 map 摘除。
 *       删除与「迟到入队」的竞态由 {@code removed} 标志 + 重试闭环：迟到者拿到已删除的组会换组
 *       重建，同 key 永远收敛到同一个活组，不会出现两组合并刷同一行；</li>
 *   <li><b>接班</b>：等待者在<b>自己的件上 park</b>（{@link MergeItem#parkUntilWoken}），终态落定由
 *       {@link MergeItem#settle} 精确 unpark；执行权空缺时由放权方<b>点名</b>一位还在等的件主
 *       （{@link #yieldLeadership()}）接手，不广播。组空闲即整体消亡，长尾 invId 不产生常驻
 *       内存开销。</li>
 * </ol>
 *
 * <p><b>改这里之前先读这条</b>：单飞现在是「执行权独占」的推论，不是独立约束。若将来要在收集与
 * 落库之间做流水线重叠（让下一批 leader 在上一批落库时就开始攒批），<b>必须同时引入一个落库权
 * 标志</b>——否则两条 flush 会并发写同一行，行锁排队之外，还会让「先探余量再扣」这类判断读到
 * 扣减前的快照。
 *
 * <p>锁只保护队列与标志位，<b>绝不跨落库调用</b>（I3）；用 {@link ReentrantLock} 而非
 * {@code synchronized}（虚拟线程撞 monitor 会钉住载体线程）。本类不认识业务、也不认识回填——
 * 摘出的批交给引擎的 flush 路径。
 */
final class MergeGroup<T, R> {

    /**
     * 窗口已过又无件可发时的重查间隔，与引擎等待者的探测间隔同口径：{@code awaitNanos} 唤不醒
     * 「别人的件被回填」这件事（unpark 只作用于 {@code park}），只能靠它定期重查谓词。
     */
    private static final long WAKEUP_PROBE_MS = 5;

    /** 入队结果：{@code GROUP_REMOVED} = 组刚被乐观删除，调用方必须换组重试 */
    enum EnqueueOutcome {
        ENQUEUED,
        BUFFER_FULL,
        CLOSED,
        GROUP_REMOVED
    }

    private final String key;
    private final int maxBatch;
    private final int capacity;
    private final long windowNanos;

    private final ReentrantLock lock = new ReentrantLock();
    /**
     * 执行权持有者专用条件：<b>只剩 leader 在等</b>（等窗口 / 攒满 / 停机），同一时刻至多一个，
     * 所以这里的 signalAll 最多叫醒一根线程，不再是跟随者的惊群源。跟随者睡在自己的件上。
     */
    private final Condition ready = lock.newCondition();

    /** 待合并队列（锁内访问，FIFO——同一 invId 的落库顺序 = 入队顺序） */
    private final ArrayDeque<MergeItem<T, R>> queue = new ArrayDeque<>();

    /** 停机位：置位后收集窗口作废，leader 立即发车排空（close 的第一步） */
    private volatile boolean closed;

    /**
     * 本组是否有人在任（锁内守卫）：在任者独占「收集 + 落库」，因此同一时刻至多一条 flush 在途。
     *
     * <p>只记「有没有人」，不记「是谁」——组里不存 leader 引用：队列里每件都挂着自己的主线程
     * （{@link MergeItem#owner()}），卸任时遍历队列即可点名下一位。
     *
     * <p>兼任「本组有线程正在干活」的判据——{@link #isIdle()} 用它表示「没有在途落库」。
     * 这个等式成立的前提是<b>任期覆盖落库段</b>；若将来把落库拆出任期做流水线重叠，
     * 必须补一个独立的落库权标志。
     */
    private boolean hasLeader;
    /** 本组已从 map 乐观删除：新到达者必须换组重建，绝不许再往里入队（锁内守卫） */
    private boolean removed;

    MergeGroup(String key, MergeConfig config) {
        this.key = key;
        this.maxBatch = config.maxBatch();
        this.capacity = config.shardCapacity();
        this.windowNanos = TimeUnit.MILLISECONDS.toNanos(config.maxFlushDelayMillis());
    }

    String key() {
        return key;
    }

    /**
     * 收件（满即拒：不阻塞、不扩容、不挤掉已入队件——I5）。
     *
     * @return {@code GROUP_REMOVED} = 本组已被乐观删除，调用方必须换组重试（竞态自愈闭环）
     */
    EnqueueOutcome enqueue(MergeItem<T, R> item, boolean accepting) {
        lock.lock();
        try {
            if (removed) {
                return EnqueueOutcome.GROUP_REMOVED;
            }
            if (!accepting || closed) {
                return EnqueueOutcome.CLOSED;
            }
            if (queue.size() >= capacity) {
                return EnqueueOutcome.BUFFER_FULL;
            }
            queue.addLast(item);
            if (queue.size() >= maxBatch) {
                // 攒满提前唤醒：leader 若在窗口等待中则立即发车（正在落库时 signal 落空无害，
                // 它回到收集段时会重查谓词，届时队列已满，直接发车）
                ready.signalAll();
            }
            return EnqueueOutcome.ENQUEUED;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 接管执行权：空缺即成功。等待者在件上醒来后的第一件事就是调它。
     *
     * @return true = 本线程现在持有执行权，可以收集并独占落库
     */
    boolean tryPromote() {
        lock.lock();
        try {
            if (removed || hasLeader) {
                return false;
            }
            hasLeader = true;
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 收集段：等到「有件可发 + 触发条件」（窗口到点 / 攒满 / 停机）就摘批发车。
     *
     * <p><b>摘批不放权</b>：调用方拿到批后直接去落库，落库期间仍然持有执行权——所以这一刻不可能
     * 有第二个线程在写同一行。
     *
     * <p><b>没有总预算、不因等待而退出</b>：窗口到点仍无「可发」条件就接着攒，攒满或停机才发车；
     * 窗口已过又一件都没有时，退回探测间隔轮询——自己的件可能被别的批回填，而 {@code unpark}
     * 唤不醒 {@link Condition#awaitNanos}，只能靠超时重查发现「该收手了」。
     *
     * @param mine 本线程自己的件（它被回填 / 失败时立即收手）
     * @return 摘走的批（非空）；{@code null} = 未发车（自己的件已终态 / 被中断），执行权已交还
     */
    List<MergeItem<T, R>> takeForFlush(MergeItem<T, R> mine) {
        lock.lock();
        try {
            long windowDeadline = 0L;
            while (true) {
                if (mine.isCompleted()) {
                    // 自己的件已被别人回填（停机回填 / 别的批）：收手，执行权交还
                    yieldLeadershipLocked();
                    return null;
                }
                long now = System.nanoTime();
                if (windowDeadline == 0) {
                    windowDeadline = now + windowNanos;
                }
                if (!queue.isEmpty() && (closed || queue.size() >= maxBatch || now >= windowDeadline)) {
                    return takeBatch();
                }
                long remainNanos = now < windowDeadline
                        ? windowDeadline - now
                        : TimeUnit.MILLISECONDS.toNanos(WAKEUP_PROBE_MS);
                try {
                    ready.awaitNanos(remainNanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    yieldLeadershipLocked();
                    return null;
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 收尾把组标记为「已移除」（死组）：队列空就置 {@code removed}，让迟到入队者拿到
     * {@code GROUP_REMOVED} 去换组重建。
     *
     * <p><b>本方法不碰 map</b>：group 不持有容器，从 map 摘掉这一步由引擎
     * {@code removeIfEmpty(group)} 紧接着做——两步必须成对，所以配对收在引擎那一个方法里，
     * 不散落到各个调用点（漏掉摘除会让换组重试拿到同一个死组，变成纯自旋）。
     *
     * <p>「判空」与「置死」必须在同一临界区。拆开的话，中间可能有人 {@code enqueue} 成功——件进了
     * 一个即将消失的组，从此没人收集（孤儿件）。
     *
     * <p>队列还有件则返回 {@code false}，由<b>持有执行权的本线程</b>接着收集下一批——不唤醒任何人，
     * 因为这一刻没有第二个线程有资格碰这个组。
     *
     * <p>调用者必是执行权持有者，所以这里只判队列空；没有执行权的路径要用
     * {@link #markRemovedIfIdle()}，那个还得确认没人在用。
     *
     * @return true = 组已置死，调用方（引擎）必须接着把它从 map 摘掉
     */
    boolean markRemovedIfEmpty() {
        lock.lock();
        try {
            if (queue.isEmpty()) {
                removed = true;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** 停机第一步：置停机位并叫醒在位 leader——窗口作废，立即发车排空。 */
    void markClosed() {
        lock.lock();
        try {
            closed = true;
            ready.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /**
     * 残余回填：把还在队列里的件显式回填 {@code FAILED}（这些件从未进入事务，可安全重试），
     * 并置删除位。正在落库的那批不在队列里、不受影响——它由持有者自己收尾。
     *
     * @return 被回填的件数
     */
    int failRemaining(String reason) {
        lock.lock();
        try {
            closed = true;
            removed = true;
            if (queue.isEmpty()) {
                ready.signalAll();
                return 0;
            }
            List<MergeItem<T, R>> leftover = new ArrayList<>(queue);
            queue.clear();
            int filled = 0;
            for (MergeItem<T, R> item : leftover) {
                if (item.fail()) {
                    filled++;
                }
            }
            ready.signalAll();
            return filled;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 队列空、且无人持有执行权时把组标记为「已移除」（入队被拒后的兜底，防空组滞留 map）。
     *
     * <p>与 {@link #markRemovedIfEmpty()} 的分工：那个由<b>执行权持有者自己</b>调用，只需判空——owner
     * 就是调用者本人；这个由<b>没有执行权的路径</b>调用，必须额外确认没人在用，否则会把一个正在
     * 收集的组标记成死的。
     *
     * <p>同样<b>不碰 map</b>：摘除由引擎 {@code removeIfIdle(group)} 紧接着做，配对收在引擎里。
     *
     * @return true = 组已置死，调用方（引擎）必须接着把它从 map 摘掉
     */
    boolean markRemovedIfIdle() {
        lock.lock();
        try {
            if (!queue.isEmpty() || hasLeader) {
                return false;
            }
            removed = true;
            return true;
        } finally {
            lock.unlock();
        }
    }

    int pendingSize() {
        lock.lock();
        try {
            return queue.size();
        } finally {
            lock.unlock();
        }
    }

    /** 是否已排空：队列空且执行权不在任何人手里（锁内判定，队列是唯一真相） */
    boolean isIdle() {
        lock.lock();
        try {
            return queue.isEmpty() && !hasLeader;
        } finally {
            lock.unlock();
        }
    }

    /** 锁内摘批（≤maxBatch，FIFO 保序） */
    private List<MergeItem<T, R>> takeBatch() {
        int n = Math.min(maxBatch, queue.size());
        List<MergeItem<T, R>> batch = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            batch.add(queue.pollFirst());
        }
        return batch;
    }

    /**
     * 交还执行权并点名接班（引擎在自己的件已完成、但队列还有别人的件时调）。
     *
     * <p>不 {@code signalAll}：{@code ready} 上的等待者必持有执行权，而执行权互斥、此刻正握在
     * 本线程手里——这一刻那条 Condition 上不可能有人。想接班的人睡在自己的件上，只能 unpark。
     */
    void yieldLeadership() {
        lock.lock();
        try {
            yieldLeadershipLocked();
        } finally {
            lock.unlock();
        }
    }

    /** {@link #yieldLeadership()} 的锁内版本：放权，再点名队头第一位还在等的件主接班 */
    private void yieldLeadershipLocked() {
        hasLeader = false;
        // 一次只点一个：接班是接力不是投票，接上的人自己会继续干到队列空。件的终态单向、不可逆，
        // 已终态的件直接跳过——绝不会叫醒一个已经拿结果离开的线程（不像 Thread.isAlive() 有竞态）。
        // 点名落空（那位恰好也刚走，或 unpark 到一根正在同一循环里的线程）不致命：unpark 的许可
        // 会留到他下一次 park，醒来重查谓词；真丢了也有 WAKEUP_PROBE_MS 兜底探测
        for (MergeItem<T, R> item : queue) {
            if (!item.isCompleted()) {
                LockSupport.unpark(item.owner());
                return;
            }
        }
    }
}
