package com.betterschool.loadtest.inventory.merge;

import com.betterschool.loadtest.inventory.config.InventoryProperties;
import com.betterschool.loadtest.inventory.metrics.RunIdResolver;
import com.betterschool.loadtest.inventory.metrics.RunStats;
import com.betterschool.loadtest.inventory.mq.InventoryMqProducer;
import com.betterschool.loadtest.inventory.mq.MergeMessage;
import com.betterschool.loadtest.inventory.repository.InventoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 兜底扫描：唤醒消息丢了、消费失败了、流量突然停了，都会在这里被捞回来。
 * 线上是 60s 一档；压测为了尾部收敛更快，默认 5s（{@code inventory.merge.fallback-interval-ms}）。
 */
@Component
public class MergeFallbackScanner {

    private static final Logger log = LoggerFactory.getLogger(MergeFallbackScanner.class);

    private final InventoryRepository repository;
    private final InventoryMqProducer producer;
    private final InventoryProperties properties;
    private final RunIdResolver runIdResolver;
    private final RunStats stats;

    public MergeFallbackScanner(InventoryRepository repository, InventoryMqProducer producer,
                                InventoryProperties properties, RunIdResolver runIdResolver,
                                RunStats stats) {
        this.repository = repository;
        this.producer = producer;
        this.properties = properties;
        this.runIdResolver = runIdResolver;
        this.stats = stats;
    }

    @Scheduled(fixedDelayString = "${inventory.merge.fallback-interval-ms:5000}",
            initialDelayString = "${inventory.merge.fallback-interval-ms:5000}")
    public void scan() {
        List<InventoryRepository.BucketRef> stale;
        try {
            stale = repository.staleBuckets(properties.getMerge().getStaleSeconds(),
                    properties.getMerge().getScanLimit());
        } catch (RuntimeException e) {
            log.warn("兜底扫描查询失败", e);
            return;
        }
        if (stale.isEmpty()) {
            return;
        }
        int sent = 0;
        for (InventoryRepository.BucketRef ref : stale) {
            try {
                producer.sendMergeDelay(new MergeMessage(
                        runIdResolver.current(ref.skuId()), ref.skuId(), ref.bucketId()));
                sent++;
            } catch (RuntimeException e) {
                log.warn("兜底唤醒发送失败 sku={} bucket={}", ref.skuId(), ref.bucketId(), e);
            }
        }
        if (sent > 0) {
            stats.add("fallback_wake", sent);
        }
    }
}
