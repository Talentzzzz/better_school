package com.betterschool.loadtest.inventory.mq;

/**
 * merge 策略的唤醒消息：只带「哪个桶」，不带任何一笔业务数据 ——
 * 合并的输入是明细表里的时间格，不是消息本身。这是它能把写放大从「每条一次」降到「每秒一次」的原因。
 */
public record MergeMessage(String runId, long skuId, int bucketId) {
}
