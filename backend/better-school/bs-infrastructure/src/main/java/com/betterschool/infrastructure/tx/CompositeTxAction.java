package com.betterschool.infrastructure.tx;

/**
 * 组合事务编排回调：在组合事务端口的一个物理事务内依次执行的多域动作。
 * <p>
 * 仓库纪律下的形状（照营销侧 {@code PointsDeductPort}，docs/adr/0014）：先消费方域自己的动作，
 * 再经端口委托目标域动作——参与域仓储同为 REQUIRED 自动并入同一物理事务。任一步抛出运行时
 * 异常即整事务回滚（外层既有事务随之一并回滚），业务失败口径照仓储惯例抛 {@code BusinessException}。
 *
 * @param <T> 编排结果类型（无结果时用 {@code Void} / {@code null}）
 */
@FunctionalInterface
public interface CompositeTxAction<T> {

    /** 在组合事务内执行的编排动作；只允许运行时异常上抛（整事务回滚的唯一出口）。 */
    T runInTx();
}
