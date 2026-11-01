package com.betterschool.infrastructure.adapter.merge;

/**
 * {@code submitAndWait} 的返回体：一个终态 + 业务结果（只有 {@code COMPLETED} 才有结果）。
 *
 * <p>用 record 而不是「结果对象 + status 字段」：调用方只做一次 {@code switch (outcome.state())}，
 * 不存在「拿了 result 忘了看 state」的可能。
 *
 * @param state  终态
 * @param result 业务结果；{@code COMPLETED} 之外恒为 {@code null}
 */
public record MergeOutcome<T>(MergeState state, T result) {

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

    /** 是否拿到了业务结果（唯一能读 {@link #result()} 的情形） */
    public boolean isCompleted() {
        return state == MergeState.COMPLETED;
    }
}
