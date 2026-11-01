package com.betterschool.marketing.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 编程式事务模板：仓储实现内用 transactionTemplate.execute 把一次操作的多个 DB 副作用收成一个原子用例
 * （points：桶 + 变动事件 outbox；raffle：抽奖记录 + 动作明细 + 中奖事件 outbox；inventory：合并组 /
 * 兑换确认的多步写），传播恒 REQUIRED 以便并入调用方事务；失败整事务回滚。
 * 域服务不再注入本模板——需要事务时由仓储方法自身承载。
 */
@Configuration
public class TransactionConfig {

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
