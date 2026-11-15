package com.betterschool.infrastructure.adapter.merge;

import java.util.Objects;

/**
 * {@code submitAndWait} 的返回体：一个终态 + 业务结果（只有 {@code COMPLETED} 才有结果）。
 *
 * <p>用「终态 + 业务结果」同一个对象而不是「结果对象 + 散落的 status 字段」：调用方只做一次
 * {@code switch (outcome.state())}，不存在「拿了 result 忘了看 state」的可能。
 *
 * @param state  终态
 * @param result 业务结果；{@code COMPLETED} 之外恒为 {@code null}
 * @param <T>    业务结果类型
 */
public final class MergeOutcome<T> {

    private final MergeState state;
    private final T result;

    private MergeOutcome(MergeState state, T result) {
        this.state = state;
        this.result = result;
    }

    public static <T> MergeOutcome<T> completed(T result) {
        return new MergeOutcome<>(MergeState.COMPLETED, result);
    }

    public static <T> MergeOutcome<T> timeout() {
        return new MergeOutcome<>(MergeState.TIMEOUT, null);
    }

    public static <T> MergeOutcome<T> bufferFull() {
        return new MergeOutcome<>(MergeState.BUFFER_FULL, null);
    }

    public static <T> MergeOutcome<T> closed() {
        return new MergeOutcome<>(MergeState.CLOSED, null);
    }

    public static <T> MergeOutcome<T> failed() {
        return new MergeOutcome<>(MergeState.FAILED, null);
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
        if (!(o instanceof MergeOutcome<?> other)) return false;
        return Objects.equals(state, other.state)
                && Objects.equals(result, other.result);
    }

    @Override
    public int hashCode() {
        return Objects.hash(state, result);
    }

    @Override
    public String toString() {
        return "MergeOutcome[state=" + state + ", result=" + result + "]";
    }
}
