package com.betterschool.infrastructure.merge;

/**
 * 提交结果：<b>引擎终态 + 业务结果</b>（只有 {@code COMPLETED} 才有结果）。
 *
 * <p>两件事分开装：「件有没有被送到落库函数」是基础设施事实（{@link StateEnum}），
 * 「落库怎么裁的」（扣到了 / 余量不足 / 确认成功）是域事实（{@link #result()}）。
 *
 * <p>{@link MergeEngine#commit} 只会产出 {@code COMPLETED} / {@code FAILED} 两态：等待没有上限，
 * 所以不存在「结果未知」——返回时这件要么已提交、要么确定没提交。
 * 「缓冲满 / 停机中」这类入队失败不进枚举——件从未进队列、必未落库，对调用方而言与落库失败无差别，
 * 统一折成 {@link StateEnum#FAILED}（确定没落，可安全重试）。
 *
 * @param <R> 业务结果类型，由 {@link MergeStrategy} 决定
 */
public final class MergeResult<R> {

    /** 引擎终态：只说件走到哪一步 */
    public enum StateEnum {

        /** 已落库并回填业务结果 */
        COMPLETED,

        /** 落库抛异常 / 漏回填 / 停机排空 / 入队被拒：事务已回滚或从未开始 ⇒ 可安全重试 */
        FAILED
    }

    private final StateEnum state;
    private final R result;

    /** 包内可见：只由本包的回填路径构造 */
    MergeResult(StateEnum state, R result) {
        this.state = state;
        this.result = result;
    }

    public static <R> MergeResult<R> completed(R result) {
        return new MergeResult<>(StateEnum.COMPLETED, result);
    }

    public static <R> MergeResult<R> failed() {
        return new MergeResult<>(StateEnum.FAILED, null);
    }

    public StateEnum state() {
        return state;
    }

    public R result() {
        return result;
    }

    /** 是否拿到了业务结果（唯一能读 {@link #result()} 的情形） */
    public boolean isCompleted() {
        return state == StateEnum.COMPLETED;
    }

    @Override
    public String toString() {
        return "MergeResult[" + state + ", result=" + result + "]";
    }
}
