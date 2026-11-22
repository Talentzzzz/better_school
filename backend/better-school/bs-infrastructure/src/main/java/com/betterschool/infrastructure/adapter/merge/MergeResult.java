package com.betterschool.infrastructure.adapter.merge;

import java.util.Objects;

/**
 * {@code countMerger.commit(...)} 的返回体：一个终态 + 业务结果（只有 {@code COMPLETED} 才有结果）。
 *
 * <p>用「终态 + 业务结果」同一个对象而不是「结果对象 + 散落的 status 字段」：调用方只做一次
 * {@code switch (outcome.state())}，不存在「拿了 result 忘了看 state」的可能。
 * 引擎内部兜掉了「缓冲满 / 停机中」两种未入队态，所以这里实际只会是
 * {@code COMPLETED} / {@code TIMEOUT} / {@code FAILED}。
 *
 * @param state  终态
 * @param result 业务结果；{@code COMPLETED} 之外恒为 {@code null}
 * @param <T>    业务结果类型
 */
public final class MergeResult<T> {

    private final MergeState state;
    private final T result;

    // 包内可见：Pending.complete 需要回填 COMPLETED 之外的任意终态（工厂方法只覆盖固定四态）
    MergeResult(MergeState state, T result) {
        this.state = state;
        this.result = result;
    }

    public static <T> MergeResult<T> completed(T result) {
        return new MergeResult<>(MergeState.COMPLETED, result);
    }

    public static <T> MergeResult<T> timeout() {
        return new MergeResult<>(MergeState.TIMEOUT, null);
    }

    /** 分片满、件未入队：引擎内部的入队事实，{@code commit} 就地降级，不会作为返回值出现 */
    public static <T> MergeResult<T> bufferFull() {
        return new MergeResult<>(MergeState.BUFFER_FULL, null);
    }

    /** 停机中、件未入队：同上，引擎内部消化 */
    public static <T> MergeResult<T> closed() {
        return new MergeResult<>(MergeState.CLOSED, null);
    }

    public static <T> MergeResult<T> failed() {
        return new MergeResult<>(MergeState.FAILED, null);
    }

    public MergeState state() {
        return state;
    }

    public T result() {
        return result;
    }

    /** 是否拿到了业务结果（唯一能读 {@link #result()} 的情形） */
    public boolean isCompleted() {
        return state == MergeState.COMPLETED;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MergeResult<?> other)) return false;
        return Objects.equals(state, other.state)
                && Objects.equals(result, other.result);
    }

    @Override
    public int hashCode() {
        return Objects.hash(state, result);
    }

    @Override
    public String toString() {
        return "MergeResult[state=" + state + ", result=" + result + "]";
    }
}
