package com.betterschool.infrastructure.merge;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * 队列里的一件：载荷 + 路由键 + 终态。包内可见——引擎造它，{@link MergeGroup} 攒它。
 *
 * <p><b>不带策略</b>：一个引擎只服务一个用途，策略在引擎构造时就定死了，件只是被攒的载荷。
 * 于是 {@code T} / {@code R} 由引擎给定，落库回填与泛型强转都不再需要。
 *
 * <p><b>等待就在件自己身上</b>：提交者 {@link #parkUntilWoken} 在这件上 park，终态落定时
 * {@link #settle} 精确 unpark 它主人——不广播、不碰组锁。同一件还被 {@link MergeGroup}
 * 用作「接班点名」的挂号单：件的终态位就是「主人还在不在等」的可靠判据。
 *
 * <p><b>终态只占一个变量</b>：「有没有落定」与「落定成什么」本来就是同一件事，所以由同一个
 * {@link AtomicReference} 承载——{@code null} 即未落定。拆成「布尔门闩 + 结果容器」两个字段
 * 会留出一个「门闩已开、结果还没写进去」的中间态，读侧就必须为它编一条兜底分支，而那条分支
 * 恰恰会在热点行上把「已提交」误报成「确定没落」。
 *
 * <p>{@link #settle} 用 CAS 抢一次性：落库漏回填、停机回填、并发补齐三条路径都可能碰同一件，
 * 只有第一个赢家能写终态。
 */
final class MergeItem<T, R> {

    /** 终态本身：{@code null} = 未落定。一次原子读同时回答「好了没」和「结果是什么」 */
    private final AtomicReference<MergeResult<R>> terminal = new AtomicReference<>();
    /** 造这件的那根线程（= 等它的那根）：终态落定时精确 unpark 它，不唤醒无关线程 */
    private final Thread owner = Thread.currentThread();
    private final T payload;
    private final String shardKey;

    MergeItem(T payload, String shardKey) {
        this.payload = payload;
        this.shardKey = shardKey;
    }

    T payload() {
        return payload;
    }

    String shardKey() {
        return shardKey;
    }

    /** 等它的那根线程：{@link MergeGroup} 点名接班时 unpark 它（件已终态则跳过） */
    Thread owner() {
        return owner;
    }

    /**
     * 睡一觉，最多 {@code nanos}（不碰组锁、不广播）。
     *
     * <p>三种醒来：终态落定（{@link #settle} 里 unpark）、被点名接班、探测间隔到点。
     * <b>本方法只负责睡，不负责判断</b>——醒来之后该干什么，由调用方在循环顶部重查谓词决定
     * （我的件好了 / 领导权空了我接班 / 预算耗尽）。接班点名走的正是这条路径
     * （被 unpark 但件仍未落定 → 回外层试抢领导权）。
     *
     * <p>先查后睡不丢唤醒：调用方进入本方法前刚查过谓词，{@code unpark} 的许可会留到这一次
     * park，醒来再查必然可见。
     */
    void parkUntilWoken(long nanos) {
        if (nanos > 0) {
            LockSupport.parkNanos(this, nanos);
        }
        if (Thread.interrupted()) {
            // 恢复标记不吞中断：外层继续按谓词收敛（件仍会被别人回填，不会悬着）
            Thread.currentThread().interrupt();
        }
    }

    /** @return true = 本次是第一个终态 */
    boolean complete(R result) {
        return settle(MergeResult.completed(result));
    }

    /** 失败态终结（落库抛异常 / 漏回填 / 停机排空）：事务已回滚或从未开始，可安全重试 */
    boolean fail() {
        return settle(MergeResult.failed());
    }

    /**
     * 终态是否已落定——<b>只看状态，不取结果</b>（等待者协议的谓词之一；leader 收集段也用它
     * 提前收手）。
     *
     * <p>和 {@link #result()} 分两步写是安全的：终态单向（{@code null} → 非 {@code null}），
     * 所以本方法返回 {@code true} 之后，再调 {@link #result()} 只会看到「已落定」，不会看到
     * 「又变回未落定」——中间不可能出现「说有终态、却取不到结果」的答案。
     */
    boolean isCompleted() {
        return terminal.get() != null;
    }

    /**
     * 已落定的终态。<b>前提：{@link #isCompleted()} 已为 {@code true}。</b>
     *
     * <p>不阻塞：等待在 {@link #parkUntilWoken} 里做。终态不可逆，所以「先判断、后取数」这两步
     * 之间只会发生「别人也把它落定」，不会发生「落定被撤销」——取不到只可能是调用方没判断就取，
     * 那是编码错误，直接抛，不用 {@code null} 糊过去。
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
        // 精确唤醒主人（不是广播）：每件恰好一次，n 个等待者不再产生 n 次惊群
        LockSupport.unpark(owner);
        return true;
    }
}
