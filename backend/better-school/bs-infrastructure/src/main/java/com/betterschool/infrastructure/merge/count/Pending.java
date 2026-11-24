package com.betterschool.infrastructure.merge.count;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 队列里的一件：事件 + 行主键 + 等待者的 future。
 *
 * <p>统一等待语义后<b>每一件都有人等结果</b>（浏览也走 {@code commit}），件不再区分
 * fire-and-forget 与 waiting 两种形态——future 一律分配。
 *
 * <h2>恰一个终态（I1 的一半）</h2>
 * {@link #complete(MergeState, Enum)} 用 {@link AtomicBoolean} 抢一次性：落库方法漏回填、
 * 停机回填、并发补齐三条路径都可能碰同一件，只有第一个赢家能写结果——第二个人返回 {@code false}，
 * 调用方据此决定要不要计 {@code failed}。{@code CompletableFuture.complete} 本身也是幂等的，
 * 但计数不能重复算，所以幂等必须在本类里体现，而不是依赖 future 的返回值。
 */
final class Pending {

    private final String shardKey;
    private final CountEvent event;
    private final CompletableFuture<MergeResult<LikeState>> future = new CompletableFuture<>();
    private final AtomicBoolean settled = new AtomicBoolean();

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
        if (!settled.compareAndSet(false, true)) {
            return false;
        }
        future.complete(new MergeResult<>(state, result));
        return true;
    }

    /** 是否已有终态（等待者协议的谓词之一；leader 收集段也用它提前收手） */
    boolean isSettled() {
        return settled.get();
    }

    /**
     * 取已落定的终态；未落定返回 {@code null}。
     *
     * <p>等待者协议不用 {@code future.get} 阻塞——等待发生在组的条件变量上（同一处顺带感知
     * 接班与停机），future 只承载终态与业务结果。
     */
    MergeResult<LikeState> resultIfSettled() {
        if (!settled.get()) {
            return null;
        }
        MergeResult<LikeState> result = future.getNow(null);
        // 理论到不了：settle 与 future.complete 同步完成。真出现按失败态兜住，不外泄技术细节
        return result != null ? result : new MergeResult<>(MergeState.FAILED, null);
    }
}
