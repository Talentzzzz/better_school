package com.betterschool.loadtest.inventory.mq;

/**
 * redis_mq 策略的消息体：一条扣减一条消息，消费端逐条扣台账。
 */
public record DeductMessage(String runId, long skuId, int bucketId, int qty, String orderNo) {
}
