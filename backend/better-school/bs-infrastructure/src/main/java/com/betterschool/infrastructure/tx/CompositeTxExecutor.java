package com.betterschool.infrastructure.tx;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 组合事务编排组件（ADR-0014 组合事务端口模式的基础积木）：一个
 * {@code TransactionTemplate}（传播恒 REQUIRED，见 bs-app {@code TransactionConfig}）内编排多域
 * 动作——无既有事务则开一个，有则并入；任一步抛异常整物理事务回滚，参与域仓储同为 REQUIRED
 * 自动并入。「编排成功 ⇒ 各域动作同生共死」由这一个物理事务保证，不需要状态表、不需要补偿 job。
 * <p>
 * 为什么在本组件而不是 case 层或域服务：域服务不注入 {@code TransactionTemplate}、case 层
 * 不包多域事务（仓库纪律）；跨域资金一致性由基础设施层的端口适配器收口——消费方域在
 * {@code domain/<域>/adapter/port/} 声明端口接口，实现落本层并经本组件编排。
 */
@Component
@RequiredArgsConstructor
public class CompositeTxExecutor {

    private final TransactionTemplate transactionTemplate;

    /**
     * 在一个 REQUIRED 物理事务内执行编排动作并返回其结果；动作抛出的运行时异常原样上抛，
     * 整事务回滚。
     */
    public <T> T execute(CompositeTxAction<T> action) {
        return transactionTemplate.execute(status -> action.runInTx());
    }
}
