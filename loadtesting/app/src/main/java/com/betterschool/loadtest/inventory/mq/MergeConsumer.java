package com.betterschool.loadtest.inventory.mq;

import com.betterschool.loadtest.inventory.config.InventoryProperties;
import com.betterschool.loadtest.inventory.merge.BucketLocks;
import com.betterschool.loadtest.inventory.merge.MergeEngine;
import com.betterschool.loadtest.inventory.metrics.RunIdResolver;
import com.betterschool.loadtest.inventory.metrics.RunStats;
import com.betterschool.loadtest.inventory.repository.InventoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 合并唤醒的消费端：消息本身不带业务数据，只是「这个桶该结一次账了」的节拍。
 * <p>
 * 一条唤醒最多合 {@code max-cells-per-wake} 个时间格（默认 1，和线上一致）。
 * 合完还有积压就再补一条延迟消息自我续命 —— 线上靠持续不断的流量自然唤醒，
 * 压测里流量会停，不续命的话尾部的格要等 60s 兜底才翻账，收敛时间就没法测了。
 */
@Component
@RocketMQMessageListener(
        topic = InventoryMqProducer.TOPIC,
        selectorExpression = InventoryMqProducer.TAG_MERGE,
        consumerGroup = "lt-merge-consumer",
        consumeThreadNumber = 8)
public class MergeConsumer implements RocketMQListener<String> {

    private static final Logger log = LoggerFactory.getLogger(MergeConsumer.class);

    private final MergeEngine mergeEngine;
    private final InventoryRepository repository;
    private final InventoryMqProducer producer;
    private final InventoryProperties properties;
    private final BucketLocks bucketLocks;
    private final RunIdResolver runIdResolver;
    private final RunStats stats;
    private final ObjectMapper objectMapper;

    public MergeConsumer(MergeEngine mergeEngine, InventoryRepository repository,
                         InventoryMqProducer producer, InventoryProperties properties,
                         BucketLocks bucketLocks, RunIdResolver runIdResolver,
                         RunStats stats, ObjectMapper objectMapper) {
        this.mergeEngine = mergeEngine;
        this.repository = repository;
        this.producer = producer;
        this.properties = properties;
        this.bucketLocks = bucketLocks;
        this.runIdResolver = runIdResolver;
        this.stats = stats;
        this.objectMapper = objectMapper;
    }

    @Override
    public void onMessage(String body) {
        MergeMessage message;
        try {
            message = objectMapper.readValue(body, MergeMessage.class);
        } catch (Exception e) {
            log.warn("无法解析合并唤醒消息，直接丢弃: {}", body, e);
            stats.inc("consume_bad_message");
            return;
        }
        if (!runIdResolver.matches(message.runId(), message.skuId())) {
            stats.inc("mq_skip:merge");
            return;
        }

        long skuId = message.skuId();
        int bucketId = message.bucketId();
        if (!bucketLocks.tryLock(skuId, bucketId)) {
            // 同桶已有线程在合，这条消息直接放弃（对方会处理或续命）
            stats.inc("merge_busy");
            return;
        }

        int mergedCells = 0;
        long mergedRows = 0;
        try {
            int limit = Math.max(1, properties.getMerge().getMaxCellsPerWake());
            for (int i = 0; i < limit; i++) {
                MergeEngine.MergeResult outcome;
                try {
                    outcome = mergeEngine.mergeOldestCell(skuId, bucketId);
                } catch (MergeEngine.CellStolenException e) {
                    stats.inc("merge_lost_cell");
                    break;
                }
                if (!outcome.merged()) {
                    break;
                }
                mergedCells++;
                mergedRows += outcome.rows();
                stats.add("merge_qty", outcome.qty());
            }
        } finally {
            bucketLocks.unlock(skuId, bucketId);
        }

        if (mergedCells == 0) {
            stats.inc("merge_empty");
        } else {
            stats.inc("merge_runs");
            stats.add("merge_rows", mergedRows);
        }

        // 还有未合并的格 → 1s 后再来一次，直到排空
        if (repository.hasPending(skuId, bucketId)) {
            try {
                producer.sendMergeDelay(new MergeMessage(runIdResolver.current(skuId), skuId, bucketId));
                stats.inc("merge_chain");
            } catch (RuntimeException e) {
                log.debug("合并续命消息发送失败，等兜底扫描 sku={} bucket={}", skuId, bucketId, e);
            }
        }
    }
}
