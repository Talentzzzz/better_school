package com.betterschool.infrastructure.adapter.merge;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 缓冲里的一件：事件 + 行主键 + （可选）等待者的 future。
 *
 * <p><b>不等结果的人连 future 都不分配</b>（{@code future == null}）：10k/s 的浏览如果每人一个
 * {@code CompletableFuture}，就是每秒一万次分配 + park/unpark，比落库本身还贵。这就是两个入口
 * 分开的理由（spec §2.2 否决了 {@code submit(key, payload, waitMode)}）。
 *
 * <h2>恰一个终态（I1 的一半）</h2>
 * {@link #complete(MergeState, Enum)} 用 {@link AtomicBoolean} 抢一次性：落库方法漏回填、
 * 排空超时、并发补齐三条路径都可能碰同一件，只有第一个赢家能写结果——第二个人返回 {@code false}，
 * 调用方据此决定要不要计 {@code failed}。{@code CompletableFuture.complete} 本身也是幂等的，
 * 但计数不能重复算，所以幂等必须在本类里体现，而不是依赖 future 的返回值。
 */
final class Pending {

    private final String shardKey;
    private final CountEvent event;
    /** 等待者的 future；{@code null} = fire-and-forget（浏览类），回填是空操作 */
    private final CompletableFuture<MergeOutcome<LikeState>> future;
    private final AtomicBoolean settled = new AtomicBoolean();

    private Pending(String shardKey, CountEvent event, CompletableFuture<MergeOutcome<LikeState>> future) {
        this.shardKey = shardKey;
        this.event = event;
        this.future = future;
    }

    /** 等结果的件（点赞 / 取消赞 / 评论赞） */
    static Pending waiting(String shardKey, CountEvent event) {
        return new Pending(shardKey, event, new CompletableFuture<>());
    }

    /** 不等结果的件（浏览）：不分配 future */
    static Pending fireAndForget(String shardKey, CountEvent event) {
        return new Pending(shardKey, event, null);
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
        if (future != null) {
            future.complete(new MergeOutcome<>(state, result));
        }
        return true;
    }

    /** 是否已有终态（落库后的「漏回填」判定用） */
    boolean isSettled() {
        return settled.get();
    }

    /**
     * 阻塞等结果（只有 {@code submitAndWait} 会调）。
     *
     * <p>{@link ExecutionException} 不该出现（引擎只用 {@code complete} 回填，从不用
     * {@code completeExceptionally}），真出现也按 {@link MergeState#FAILED} 兜住——不把技术细节
     * 泄给调用方。
     */
    MergeOutcome<LikeState> await(long timeoutMs) throws TimeoutException, InterruptedException {
        if (future == null) {
            throw new IllegalStateException("fire-and-forget 的件不该有人等结果");
        }
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            return MergeOutcome.failed();
        }
    }
}
