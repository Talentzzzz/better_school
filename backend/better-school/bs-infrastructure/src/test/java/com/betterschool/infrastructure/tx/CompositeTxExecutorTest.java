package com.betterschool.infrastructure.tx;

import com.betterschool.types.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link CompositeTxExecutor} 的外显行为单测（纯内存，不起 Spring、不连库）。
 *
 * <p>事务机器是真的（{@code DataSourceTransactionManager}，连接用替身），REQUIRED 并入 /
 * 回滚传播全是 Spring 原生语义；「参与域仓储写行」由 {@link Ledger} 记账替身模拟——写进当前
 * 事务的缓冲、提交后才落到已提交视图，回滚 = 缓冲丢弃 = 该步从未发生。断言只看
 * {@code execute} 的返回值与提交后的可见状态，不测内部调用顺序、不数 mock 交互。
 */
class CompositeTxExecutorTest {

    private CompositeTxExecutor executor;
    private final Ledger ledger = new Ledger();

    @BeforeEach
    void setUp() throws SQLException {
        Connection connection = mock(Connection.class);
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        TransactionTemplate transactionTemplate =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        executor = new CompositeTxExecutor(transactionTemplate);
    }

    @AfterEach
    void tearDown() {
        TransactionSynchronizationManager.clear();
    }

    @Test
    @DisplayName("编排完成：结果原样返回，各步动作随提交一起可见")
    void orchestrationCompletesAndReturnsResult() {
        String result = executor.execute(() -> {
            ledger.write("订单状态翻转");
            ledger.write("钱包冻结流水");
            return "pay-result";
        });

        assertThat(result).isEqualTo("pay-result");
        assertThat(ledger.committed()).containsExactly("订单状态翻转", "钱包冻结流水");
    }

    @Test
    @DisplayName("任一步抛异常：整事务回滚，先落的状态不可见")
    void stepThrowingRollsBackWholeTransaction() {
        assertThatThrownBy(() -> executor.execute(() -> {
            ledger.write("订单状态翻转");
            ledger.write("钱包冻结流水");
            throw new BusinessException("余额不足，无法完成操作");
        })).isInstanceOf(BusinessException.class);

        assertThat(ledger.committed()).isEmpty();
    }

    @Test
    @DisplayName("嵌套编排并入外层事务：内层失败时外层动作一并回滚（若为独立嵌套事务，外层动作应已提交可见）")
    void innerFailureJoinsOuterTransactionAndRollsBackEverything() {
        assertThatThrownBy(() -> executor.execute(() -> {
            ledger.write("外层动作");
            executor.execute(() -> {
                ledger.write("内层动作");
                throw new BusinessException("内层失败");
            });
            return null;
        })).isInstanceOf(BusinessException.class);

        assertThat(ledger.committed()).isEmpty();
    }

    @Test
    @DisplayName("嵌套编排成功：内层动作随外层提交一起可见（同一物理事务）")
    void innerSuccessCommitsWithOuterTransaction() {
        String outerResult = executor.execute(() -> {
            ledger.write("外层动作");
            String innerResult = executor.execute(() -> {
                ledger.write("内层动作");
                return "inner-result";
            });
            return "outer:" + innerResult;
        });

        assertThat(outerResult).isEqualTo("outer:inner-result");
        assertThat(ledger.committed()).containsExactly("外层动作", "内层动作");
    }

    /**
     * 记账替身：模仿「域仓储写行」——写进当前事务的缓冲，afterCommit 才落到已提交视图；
     * 回滚 = 缓冲丢弃。{@code committed()} 即外部可见的库表状态。
     */
    private static final class Ledger {

        private final List<String> committed = new ArrayList<>();

        List<String> committed() {
            return committed;
        }

        void write(String entry) {
            Deque<String> existing =
                    (Deque<String>) TransactionSynchronizationManager.getResource(this);
            if (existing != null) {
                existing.add(entry);
                return;
            }
            Deque<String> fresh = new ArrayDeque<>();
            fresh.add(entry);
            TransactionSynchronizationManager.bindResource(this, fresh);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    committed.addAll(fresh);
                }

                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(Ledger.this);
                }
            });
        }
    }
}
