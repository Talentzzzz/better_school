package com.betterschool.infrastructure.merge.count;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 队列里的一件：事件 + 行主键 + 终态。
 *
 * <p>统一等待语义后<b>每一件都有人等结果</b>（浏览也走 {@code commit}），件不再区分
 * fire-and-forget 与 waiting 两种形态。
 *
 * <h2>恰一个终态（I1 的一半）</h2>
 * {@link #complete(MergeState, Enum)} 用 {@link AtomicReference} 的 CAS 抢一次性：落库方法漏回填、
 * 停机回填、并发补齐三条路径都可能碰同一件，只有第一个赢家能写结果——第二个人返回 {@code false}，
 * 调用方据此决定要不要计 {@code failed}。
 *
 * <p><b>「有没有落定」与「落定成什么」必须由同一个原子变量承载</b>（与旧引擎
 * {@code MergeItem} 同口径）：拆成「布尔门闩 + 结果容器」两步写，读侧会撞上一个
 * 「门闩已开、结果还没放进去」的中间态——等待者在那条缝里读到「已落定但没有结果」，
 * 只能要么悬挂要么谎报 {@code FAILED}，而那正是热点行上把「已提交」说成「确定没落」的成因。
 */
final class Pending {

    private final String shardKey;
    private final CountEvent event;
    /** 终态本身：{@code null} = 未落定。一次原子读同时回答「好了没」和「结果是什么」 */
    private final AtomicReference<MergeResult<LikeState>> terminal = new AtomicReference<>();

    Pending(String shardKey, CountEvent event) {
        this.shardKey = shardKey;
        this.event = event;
    }

    String shardKey() {
        return shardKey;
    }

    CountEvent event() {
        return event;
    }

    /**
     * 回填业务结果。
     *
     * @return true = 本次是第一个终态（调用方可以计数）；false = 已经有人回填过
     */
    boolean complete(LikeState result) {
        return complete(MergeState.COMPLETED, result);
    }

    /**
     * 回填任意终态。
     *
     * @return true = 本次是第一个终态；false = 已终态（幂等，不重复唤醒、不重复计数）
     */
    boolean complete(MergeState state, LikeState result) {
        return terminal.compareAndSet(null, new MergeResult<>(state, result));
    }

    /** 是否已有终态（等待者协议的谓词之一；收集段也用它提前收手） */
    boolean isSettled() {
        return terminal.get() != null;
    }

    /**
     * 取已落定的终态；未落定返回 {@code null}（一步读出，不存在「已落定但取不到结果」的缝）。
     *
     * <p>等待者协议不在本对象上阻塞——等待发生在组的条件变量上（同一处顺带感知接班与停机），
     * 本对象只承载终态。
     */
    MergeResult<LikeState> resultIfSettled() {
        return terminal.get();
    }
}
