package com.betterschool.loadtest.inventory.config;

/**
 * Redis key 约定。全部带 lt:inv: 前缀，便于压测后在 Redis 里一眼认出。
 */
public final class RedisKeys {

    private RedisKeys() {
    }

    /** 桶余量：lt:inv:bucket:1001:0 */
    public static String bucket(long skuId, int bucketId) {
        return "lt:inv:bucket:" + skuId + ":" + bucketId;
    }

    /** 合并唤醒去重：lt:inv:merge:1001:0 */
    public static String mergeDedupe(long skuId, int bucketId) {
        return "lt:inv:merge:" + skuId + ":" + bucketId;
    }

    /** 当前压测轮次标识：reset 时换新，消费者据此丢弃上一轮的迟到消息 */
    public static String run(long skuId) {
        return "lt:inv:run:" + skuId;
    }
}
