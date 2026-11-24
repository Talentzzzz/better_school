package com.betterschool.infrastructure.mergevirtual;

import com.betterschool.infrastructure.merge.MergeConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 一个 invId 的热点组（专属虚拟线程 leader 模型）：动态创建、flush 收尾即由 leader 摘除。
 *
 * <p><b>组退化为最小并发面</b>（spec story 12）：一把 {@link ReentrantLock} + 一个队列 +
 * {@code closed} 标志 + 单 leader 线程驱动——没有执行权标志、没有点名接班、没有交接探测，
 * 旧引擎的整套运行时领导权协议在本模型下<b>整体删除</b>。组内新增标志只有一个：
 * {@code leaderClaimed}（spawn 的 claim-CAS）。
 *
 * <h2>本组与旧引擎 MergeGroup 的差异（改代码前先读）</h2>
 * <ol>
 *   <li><b>leader 不住在请求线程里</b>：组创建时由首个提交者经 {@link #tryClaimLeader()} 恰好
 *       spawn 一根专属虚拟 leader（引擎侧闭环，见引擎类注释的 spawn 备忘）。leader 不退位、
 *       不接班，「谁在收集落库」由结构保证而不是协议推演——I2 同 key 单飞是「每组恰一根
 *       leader」的直接推论，不再是执行权互斥的推论；</li>
 *   <li><b>生命周期 = leader 的生命周期</b>：leader 落完一批队列即空 → {@link #settleAfterFlush()}
 *       置死（「判空」与「置死」同一临界区）→ 引擎从 map 摘除 → leader 线程退出
 *       （<b>退出谓词</b>：循环只在组已置死并摘除后退出，组内没有提前 return 的合法路径）；
 *       迟到入队者拿 {@code GROUP_REMOVED} 换组重建，新组 spawn 新 leader；</li>
 *   <li><b>在途 flush 的判据换了</b>：旧引擎 {@code hasLeader} 兼任「有人干活」；本模型 leader
 *       存活期间大部分时间在等件，不算「在途 flush」——改由 {@code flushing} 标志承担
 *       （摘批时置位、收尾清位，锁内守卫），{@link #isIdle()} = 队列空且无在途 flush。</li>
 * </ol>
 *
 * <p>锁只保护队列与标志位，<b>绝不跨落库调用</b>（I3）；用 {@link ReentrantLock} 而非
 * {@code synchronized}（虚拟线程撞 monitor 会钉住载体线程——JDK 21 下 JDBC pinning 同理，
 * 是性能风险不是正确性缺陷，见引擎类注释）。本类不认识业务、也不认识回填——摘出的批交给
 * 引擎的 flush 路径。
 *
 * <h2>为什么没有收集窗口（与 merge 包 {@code MergeGroup} 同口径）</h2>
 * <p>收集段只看队列：<b>队列非空就摘批发车</b>，没有窗口、也没有「攒满才发」——收集窗就是落库
 * 耗时本身（对照 PolarDB-X Inventory Hint 的组提交：内核的收集窗 = leader 持行锁处理的时间）。
 * 两条结构安全性：① 事务速率 = 1/flush 耗时 ≈ DB 单行事务周期 ⇒ <b>天然 ≤ DB 天花板</b>，不需要
 * 窗口「保护 DB」；② 合并比 = 到达速率 × flush 耗时，低速率每批 1 件、高速率自动变大而事务速率
 * 恒为 1/flush。{@code maxBatch} 只是单批上界，不是发车条件。决策与论证见
 * {@code backend/docs/architecture/merge-window-removal-spec.md}。
 */
final class VirtualMergeGroup<T, R> {

    /**
     * 队列空时 leader 的重查间隔（<b>不是收集窗口</b>）。防御性兜底：正常路径 leader 只在队列
     * 非空时进入收集（spawn 时队列已有件、上一批收尾队列非空才继续），空等只发生在
     * {@link #failRemaining()} 已经置死这类收尾窗口里——等待超时重查谓词即可退出。
     */
    private static final long WAKEUP_PROBE_MS = 5;

    /** 入队结果：{@code GROUP_REMOVED} = 组刚被摘除，调用方必须换组重试 */
    enum EnqueueOutcome {
        ENQUEUED,
        BUFFER_FULL,
        CLOSED,
        GROUP_REMOVED
    }

    private final String key;
    private final int maxBatch;
    private final int capacity;

    private final ReentrantLock lock = new ReentrantLock();
    /** leader 专用条件：<b>只剩 leader 在等</b>（等新件 / 等停机），signalAll 最多叫醒一根线程 */
    private final Condition ready = lock.newCondition();

    /** 待合并队列（锁内访问，FIFO——同一 invId 的落库顺序 = 入队顺序） */
    private final ArrayDeque<VirtualMergeItem<T, R>> queue = new ArrayDeque<>();

    /**
     * spawn 的 claim-CAS（组内唯一新增标志）：{@code false→true} 赢得 spawn 资格，恰好一次。
     * 不放在组锁里（引擎在 commit 路径上 lock-free 抢占）；spawn 失败时由引擎
     * {@link #releaseLeaderClaim()} 放行——「claim 与 start 之间创建者死亡」的窗口自愈。
     * 组摘除后此标志随组对象一起作废，新组从 false 起步。
     */
    private final AtomicBoolean leaderClaimed = new AtomicBoolean(false);

    /** 停机位：置位后入口停收、leader 立即排空（close 的第一步） */
    private volatile boolean closed;
    /** 在途 flush（锁内守卫）：摘批置位、{@link #settleAfterFlush()} 清位——isIdle 的判据 */
    private boolean flushing;
    /** 本组已置死：新到达者必须换组重建，绝不许再往里入队（锁内守卫） */
    private boolean removed;

    VirtualMergeGroup(String key, MergeConfig config) {
        this.key = key;
        this.maxBatch = config.maxBatch();
        this.capacity = config.shardCapacity();
    }

    String key() {
        return key;
    }

    /**
     * 收件（满即拒：不阻塞、不扩容、不挤掉已入队件——I5）。
     *
     * @return {@code GROUP_REMOVED} = 本组已被摘除，调用方必须换组重试（竞态自愈闭环）
     */
    EnqueueOutcome enqueue(VirtualMergeItem<T, R> item, boolean accepting) {
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
            // 队列非空即发：每进一件都要叫醒可能正空等的 leader（落库进行中时 signal 落空无害，
            // leader 回到收集段会重查队列；没有等待者时 signal 只是一次空队列判断）
            ready.signalAll();
            return EnqueueOutcome.ENQUEUED;
        } finally {
            lock.unlock();
        }
    }

    /**
     * leader spawn 的 claim-CAS：{@code false→true} 恰好一次。
     *
     * <p><b>每个提交者都尝试一次</b>（「后续提交者兜底 claim」）：正常情况首次成功、后续全部
     * CAS 失败（纳秒级开销）；spawn 失败被放行后，下一次提交自然接棒。赢家在<b>组锁外</b>
     * spawn——绝不许在 {@code computeIfAbsent} 的 lambda 里跑（持桶锁跑外部代码，且 leader
     * 收尾的 map.remove 会撞同一把桶锁）。
     *
     * @return true = 本提交者赢得 spawn 资格，必须立刻 spawn（失败要 {@link #releaseLeaderClaim()}）
     */
    boolean tryClaimLeader() {
        return leaderClaimed.compareAndSet(false, true);
    }

    /**
     * 放行 claim（引擎在 spawn 抛 Throwable 时调用）：CAS {@code true→false}，让下一个提交者
     * 兜底赢得 claim 并 spawn——「claim 与 start 之间创建者死亡」的窗口自愈。
     * <b>必须 CAS 而非直接 set</b>：放行与下一个提交者的 claim 可能并发，无脑 set 会把别人
     * 刚赢得的 claim 抹掉，造成双 spawn。
     */
    void releaseLeaderClaim() {
        leaderClaimed.compareAndSet(true, false);
    }

    /**
     * 收集段：<b>队列非空就摘批发车</b>——没有窗口、没有「攒满才发」，收集窗就是落库耗时本身
     * （论证见类注释末节）。
     *
     * <p><b>前置：队列非空</b>——leader 只在两种时刻进来：spawn 时（触发 spawn 的那件已在队列里）、
     * 上一批收尾队列非空继续下一批。队列只有 leader 一个消费者，进来的非空不会中途变空；空等只出现
     * 在 {@link #failRemaining()} 已置死的收尾窗口里，探测超时后重查谓词退出。
     *
     * <p><b>没有总预算、不因等待而退出</b>：退出谓词不允许本方法让 leader 终结——唯一让 leader
     * 看到终点的是 {@code null}：组已被置死（停机 {@link #failRemaining()} 的兜底路径）。
     *
     * @return 摘走的批（非空）；{@code null} = 组已被置死（引擎随后摘出 map），leader 应退出
     */
    List<VirtualMergeItem<T, R>> takeForFlush() {
        lock.lock();
        try {
            while (true) {
                if (removed) {
                    return null;
                }
                if (!queue.isEmpty()) {
                    return takeBatch();
                }
                try {
                    ready.awaitNanos(TimeUnit.MILLISECONDS.toNanos(WAKEUP_PROBE_MS));
                } catch (InterruptedException e) {
                    // 中断不是本引擎的停机协议（close 走 closed 标志）：清掉标志继续等。
                    // 不清的话 awaitNanos 会立即再抛，变成纯自旋；退出谓词不允许在这里 return
                    Thread.interrupted();
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * flush 收尾（锁内一个临界区做完三件事）：清在途标志；队列空则置死。
     *
     * <p><b>「判空」与「置死」必须在同一临界区</b>：拆开的话，中间可能有人 {@code enqueue}
     * 成功——件进了一个即将消失的组，从此没人收集（孤儿件）。入队侧的 {@code removed} 检查
     * 与这里互斥，闭环成立。
     *
     * <p><b>本方法不碰 map</b>：group 不持有容器，摘除由引擎紧接着做——两步必须成对，
     * 配对收在 leader 循环的那一个位置，漏掉摘除会让换组重试拿到同一个死组变成纯自旋。
     *
     * @return true = 组已置死（或本就已死），调用方（引擎）必须把它从 map 摘掉并终结 leader
     */
    boolean settleAfterFlush() {
        lock.lock();
        try {
            flushing = false;
            if (queue.isEmpty()) {
                removed = true;
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    /** 停机第一步：置停机位并叫醒 leader——入口停收，队列里剩下的件由 leader 立即排空。 */
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
     * 并置删除位。正在落库的那批不在队列里、不受影响——它由 leader 自己收尾。
     *
     * <p>与旧引擎的差别：本模型 leader 是后台线程、不会死也不会交权，所以没有「接班」路径——
     * 本方法总是由 close 调用；leader 醒来看到 {@code removed} 即退出（退出谓词达成）。
     *
     * @return 被回填的件数
     */
    int failRemaining() {
        lock.lock();
        try {
            closed = true;
            removed = true;
            if (queue.isEmpty()) {
                ready.signalAll();
                return 0;
            }
            List<VirtualMergeItem<T, R>> leftover = new ArrayList<>(queue);
            queue.clear();
            int filled = 0;
            for (VirtualMergeItem<T, R> item : leftover) {
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
     * 入队被拒后的兜底摘除（引擎 {@code removeIfIdle} 调用）：只摘「真正没人用」的组——
     * 队列空、无在途 flush、<b>且 claim 没被占走</b>（claim 已赢得但 leader 还没起跑的窗口里
     * 也不许摘，否则 spawn 出来的 leader 会服务一个已从 map 消失的组）。
     *
     * @return true = 组已置死，调用方必须把它从 map 摘掉
     */
    boolean markRemovedIfIdle() {
        lock.lock();
        try {
            if (!queue.isEmpty() || flushing || leaderClaimed.get()) {
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

    /** 是否已排空：队列空且没有在途 flush（锁内判定，队列是唯一真相；leader 等件不算在途） */
    boolean isIdle() {
        lock.lock();
        try {
            return queue.isEmpty() && !flushing;
        } finally {
            lock.unlock();
        }
    }

    /** 锁内摘批（≤maxBatch，FIFO 保序），并标记「在途 flush」开始 */
    private List<VirtualMergeItem<T, R>> takeBatch() {
        int n = Math.min(maxBatch, queue.size());
        List<VirtualMergeItem<T, R>> batch = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            batch.add(queue.pollFirst());
        }
        flushing = true;
        return batch;
    }
}
