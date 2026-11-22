package com.betterschool.marketing.infrastructure.merge;

/**
 * 提交结果：<b>引擎终态 + 业务结果</b>（只有 {@code COMPLETED} 才有结果）。
 *
 * <p>两件事分开装：「件有没有被送到落库函数」是基础设施事实（{@link State}），
 * 「落库怎么裁的」（扣到了 / 余量不足 / 确认成功）是域事实（{@link #result()}）。
 *
 * <p>{@link MergeEngine#commit} 只会产出 {@code COMPLETED} / {@code TIMEOUT} / {@code FAILED} 三态：
 * {@code BUFFER_FULL} / {@code CLOSED} 是「件没进缓冲」的入队事实，引擎在内部就把它降级成同步落库了，
 * 不外返给调用方。
 *
 * @param <R> 业务结果类型，由 {@link MergeStrategy} 决定
 */
public final class MergeResult<R> {

    /** 引擎终态：只说件走到哪一步，降级动作不在这里（见 {@link MergeEngine#commit}） */
    public enum State {

        /** 已落库并回填业务结果 */
        COMPLETED,

        /** 等结果超时：件仍在途，之后大概率仍会落库（结果未知，≠ 失败） */
        TIMEOUT,

        /** 分片缓冲已满：件从未入队、必未落库。引擎内部的入队事实，{@code commit} 就地降级，不外返 */
        BUFFER_FULL,

        /** 引擎已停机：件从未入队、必未落库。同上，引擎内部的入队事实 */
        CLOSED,

        /** 落库抛异常 / 漏回填 / 停机排空：事务已回滚或从未开始 ⇒ 可安全重试 */
        FAILED
    }

    private final State state;
    private final R result;

    /** 包内可见：只由本包的回填与降级路径构造 */
    MergeResult(State state, R result) {
        this.state = state;
        this.result = result;
    }

    public static <R> MergeResult<R> completed(R result) {
        return new MergeResult<>(State.COMPLETED, result);
    }

    public static <R> MergeResult<R> timeout() {
        return new MergeResult<>(State.TIMEOUT, null);
    }

    public static <R> MergeResult<R> bufferFull() {
        return new MergeResult<>(State.BUFFER_FULL, null);
    }

    public static <R> MergeResult<R> closed() {
        return new MergeResult<>(State.CLOSED, null);
    }

    public static <R> MergeResult<R> failed() {
        return new MergeResult<>(State.FAILED, null);
    }

    public State state() {
        return state;
    }

    public R result() {
        return result;
    }

    /** 是否拿到了业务结果（唯一能读 {@link #result()} 的情形） */
    public boolean isCompleted() {
        return state == State.COMPLETED;
    }

    @Override
    public String toString() {
        return "MergeResult[" + state + ", result=" + result + "]";
    }
}
