package cn.inventory.infrastructure.adapter.repository;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * 假事务管理器：只负责驱动 {@link FakeStockDao} 的提交 / 回滚边界，
 * 不碰真实数据源——仓储里 setRollbackOnly 的语义（静默回滚、结果照常返回）由
 * {@code TransactionTemplate} 保证，这里只需在 commit 时识别回滚标记。
 */
final class FakeTransactionManager implements PlatformTransactionManager {

    private final FakeStockDao dao;

    FakeTransactionManager(FakeStockDao dao) {
        this.dao = dao;
    }

    @Override
    public TransactionStatus getTransaction(TransactionDefinition definition) {
        dao.beginTx();
        return new SimpleTransactionStatus();
    }

    @Override
    public void commit(TransactionStatus status) {
        if (status.isRollbackOnly()) {
            dao.rollbackTx();
        } else {
            dao.commitTx();
        }
    }

    @Override
    public void rollback(TransactionStatus status) {
        dao.rollbackTx();
    }
}
