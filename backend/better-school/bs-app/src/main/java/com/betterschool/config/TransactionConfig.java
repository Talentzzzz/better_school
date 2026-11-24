package com.betterschool.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 编程式事务模板：**仓储实现**内用 transactionTemplate.execute 把一次操作的多个 DB 副作用
 * 收成一个原子用例（chat：取号 + 落消息 + 扇出时间线 + 推送 outbox；idle：商品状态变更 + 搜索同步；
 * post：审核通过后帖子/评论计数增减；notification：取号 + 落库），传播恒 REQUIRED 以便并入调用方
 * 事务；失败整事务回滚。
 * 域服务不再注入本模板——需要事务时由仓储方法自身承载（一域一仓储，事务落位在仓储内）。
 */
@Configuration
public class TransactionConfig {

    @Bean
    public TransactionTemplate transactionTemplate(PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }
}
