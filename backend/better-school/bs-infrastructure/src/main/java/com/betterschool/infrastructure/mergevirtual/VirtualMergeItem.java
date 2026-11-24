package com.betterschool.infrastructure.mergevirtual;

import com.betterschool.infrastructure.merge.MergeResult;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * 队列里的一件：载荷 + 路由键 + 终态。包内可见——引擎造它，{@link VirtualMergeGroup} 攒它。
 *
 * <p>机制与旧引擎 {@code com.betterschool.infrastructure.merge.MergeItem} 同形（spec：
 * 「MergeItem 的终态 CAS + 精确 unpark 机制原样复用」），按新包边界平移：
 *
 * <ul>
 *   <li><b>终态只占一个变量</b>：「有没有落定」与「落定成什么」由同一个 {@link AtomicReference}
 *       承载——{@code null} 即未落定。拆成「布尔门闩 + 结果容器」会留出「门闩已开、结果还没写进去」
 *       的中间态，读侧就得为它编兜底分支；</li>
 *   <li><b>等待就在件自己身上</b>：提交者 {@link #parkUntilWoken} 在这件上 park，终态落定时
 *       {@link #settle} 精确 unpark 它主人——不广播、不碰组锁。本模型没有接班点名
 *       （leader 不退位），件的 owner 只服务于这一个 unpark；</li>
 *   <li><b>settle 用 CAS 抢一次性</b>：落库回填、supervisor 整组回填、停机回填三条路径都可能碰
 *       同一件，只有第一个赢家能写终态（I1「恰一终态」的落点）。</li>
 * </ul>
 */
final class VirtualMergeItem<T, R> {

    /** 终态本身：{@code null} = 未落定。一次原子读同时回答「好了没」和「结果是什么」 */
    private final AtomicReference<MergeResult<R>> terminal = new AtomicReference<>();
    /** 造这件的那根线程（= 等它的那根）：终态落定时精确 unpark 它，不唤醒无关线程 */
    private final Thread owner = Thread.currentThread();
    private final T payload;
    private final String shardKey;

    VirtualMergeItem(T payload, String shardKey) {
        this.payload = payload;
        this.shardKey = shardKey;
    }

    T payload() {
        return payload;
    }

    String shardKey() {
        return shardKey;
    }

    /**
     * 睡一觉，最多 {@code nanos}（不碰组锁、不广播）。
     *
     * <p>本模型下等待者只有一种醒来：自己的件被回填（{@link #settle} 里 unpark）；探测间隔兜底
     * 防信号丢失。<b>本方法只负责睡，不负责判断</b>——醒来之后该干什么，由调用方在循环顶部
     * 重查谓词决定（这里只有一条谓词：件好没好）。
     *
     * <p>先查后睡不丢唤醒：调用方进入本方法前刚查过谓词，{@code unpark} 的许可会留到这一次
     * park，醒来再查必然可见。
     */
    void parkUntilWoken(long nanos) {
        if (nanos > 0) {
            LockSupport.parkNanos(this, nanos);
        }
        if (Thread.interrupted()) {
            // 恢复标记不吞中断：外层继续按谓词收敛（件仍会被回填，不会悬着）
            Thread.currentThread().interrupt();
        }
    }

    /** @return true = 本次是第一个终态 */
    boolean complete(R result) {
        return settle(MergeResult.completed(result));
    }

    /** 失败态终结（落库抛异常 / supervisor 整组回填 / 漏回填 / 停机排空）：事务已回滚或从未开始，可安全重试 */
    boolean fail() {
        return settle(MergeResult.failed());
    }

    /**
     * 终态是否已落定——<b>只看状态，不取结果</b>。
     *
     * <p>和 {@link #result()} 分两步写是安全的：终态单向（{@code null} → 非 {@code null}），
     * 本方法返回 {@code true} 之后取结果只会看到「已落定」。
     */
    boolean isCompleted() {
        return terminal.get() != null;
    }

    /**
     * 已落定的终态。<b>前提：{@link #isCompleted()} 已为 {@code true}。</b>
     *
     * @throws IllegalStateException 件尚未落定就被取结果
     */
    MergeResult<R> result() {
        MergeResult<R> settled = terminal.get();
        if (settled == null) {
            throw new IllegalStateException("件尚未落定就取结果（shardKey=" + shardKey + "）");
        }
        return settled;
    }

    private boolean settle(MergeResult<R> outcome) {
        if (!terminal.compareAndSet(null, outcome)) {
            return false;
        }
        // 精确唤醒主人（不是广播）：每件恰好一次
        LockSupport.unpark(owner);
        return true;
    }
}
