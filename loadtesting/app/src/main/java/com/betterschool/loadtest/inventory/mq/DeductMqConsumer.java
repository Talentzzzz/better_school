package com.betterschool.loadtest.inventory.mq;

import com.betterschool.loadtest.inventory.metrics.RunIdResolver;
import com.betterschool.loadtest.inventory.metrics.RunStats;
import com.betterschool.loadtest.inventory.repository.InventoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * redis_mq 的消费端：一条消息 = 一次台账 UPDATE。
 * <p>
 * 幂等靠 uk_order_no：撞键说明这条消息重复投递，直接跳过（订单与台账在同一事务，不会出现只改一半）。
 * 消费失败抛异常，交给 RocketMQ 重试；因为整条消息是一个事务，重试不会双扣。
 */
@Component
@RocketMQMessageListener(
        topic = InventoryMqProducer.TOPIC,
        selectorExpression = InventoryMqProducer.TAG_DEDUCT,
        consumerGroup = "lt-deduct-consumer",
        consumeThreadNumber = 32)
public class DeductMqConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(DeductMqConsumer.class);

    private final InventoryRepository repository;
    private final ObjectMapper objectMapper;
    private final RunIdResolver runIdResolver;
    private final RunStats stats;
    private final TransactionTemplate tx;

    public DeductMqConsumer(InventoryRepository repository, ObjectMapper objectMapper,
                            RunIdResolver runIdResolver, RunStats stats, TransactionTemplate tx) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.runIdResolver = runIdResolver;
        this.stats = stats;
        this.tx = tx;
    }

    @Override
    public void onMessage(String body) {
        DeductMessage message;
        try {
            message = objectMapper.readValue(body, DeductMessage.class);
        } catch (Exception e) {
            log.warn("无法解析扣减消息，直接丢弃: {}", body, e);
            stats.inc("consume_bad_message");
            return;
        }
        if (!runIdResolver.matches(message.runId(), message.skuId())) {
            // 上一轮压测的迟到消息：不能打在新一轮的台账上
            stats.inc("mq_skip:deduct");
            return;
        }

        try {
            tx.executeWithoutResult(status -> {
                try {
                    repository.insertOrder(message.orderNo(), message.skuId(), message.qty(), "redis_mq");
                } catch (DuplicateKeyException e) {
                    stats.inc("dup:redis_mq");
                    return;
                }
                repository.applyIssued(message.skuId(), message.qty());
                stats.inc("applied:redis_mq");
            });
        } catch (RuntimeException e) {
            stats.inc("consume_error:redis_mq");
            throw e;
        }
    }
}
