package cn.inventory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

/**
 * 事务 Bean：一个事务管理器 + 一个编程式事务模板（传播恒 REQUIRED）
 *
 * <p>只给仓储用：仓储方法用模板把「流水插入 + 条件扣减」收成一个原子用例，
 * 域服务不注入 TransactionTemplate、不自持事务边界（见 CLAUDE.md 事务落位约定）。
 *
 * <p>为什么不用 {@code @Transactional}：注解式事务依赖代理，仓储内部方法互调会失效，
 * 而编程式模板的边界在代码里一眼可见，也不怕有人把方法改成 private。
 */
@Configuration
public class TransactionConfig {

    @Bean
    public PlatformTransactionManager transactionManager(DataSource stockDataSource) {
        return new DataSourceTransactionManager(stockDataSource);
    }

    @Bean
    public TransactionTemplate stockTransactionTemplate(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionTemplate.PROPAGATION_REQUIRED);
        return template;
    }
}
