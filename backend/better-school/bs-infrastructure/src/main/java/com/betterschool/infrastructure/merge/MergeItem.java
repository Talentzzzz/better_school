package com.betterschool.infrastructure.merge;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 缓冲里的一件：载荷 + 路由键 + future。包内可见——引擎造它，{@link MergeShard} 攒它。
 *
 * <p><b>不带策略</b>：一个引擎只服务一个用途，策略在引擎构造时就定死了，件只是被攒的载荷。
 * 于是 {@code T} / {@code R} 由引擎给定，落库回填与泛型强转都不再需要。
 *
 * <p>{@link #complete} 用 CAS 抢一次性：落库漏回填、排空超时、并发补齐三条路径都可能碰同一件，
 * 只有第一个赢家能写结果。
 */
final class MergeItem<T, R> {

    /** future 装的是引擎终态，不是业务结果——超时 / 失败也要能精确表达 */
    private final CompletableFuture<MergeResult<R>> future = new CompletableFuture<>();
    private final AtomicBoolean settled = new AtomicBoolean();
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

    /** @return true = 本次是第一个终态 */
    boolean complete(R result) {
        return settle(MergeResult.completed(result));
    }

    /** 失败态终结（落库抛异常 / 漏回填 / 停机排空）：事务已回滚或从未开始，可安全重试 */
    boolean fail() {
        return settle(MergeResult.failed());
    }

    MergeResult<R> await(long timeoutMs) throws TimeoutException, InterruptedException {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            // 引擎只回填终态、不抛进 future，真出现也按失败态兜住
            return MergeResult.failed();
        }
    }

    private boolean settle(MergeResult<R> outcome) {
        if (!settled.compareAndSet(false, true)) {
            return false;
        }
        future.complete(outcome);
        return true;
    }
}
