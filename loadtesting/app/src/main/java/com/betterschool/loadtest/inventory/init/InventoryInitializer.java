package com.betterschool.loadtest.inventory.init;

import com.betterschool.loadtest.inventory.config.InventoryProperties;
import com.betterschool.loadtest.inventory.config.RedisKeys;
import com.betterschool.loadtest.inventory.metrics.BucketLayout;
import com.betterschool.loadtest.inventory.repository.InventoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 启动自检：库是空的就把默认 SKU 建好，Redis 桶没预热就按台账灌一次。
 * <p>
 * 目的是「复制粘贴就能起」：不需要手工执行 SQL、也不需要手工 SET 库存。
 * 注意 SETNX 语义 —— Redis 里已有桶余量就不动它，避免重启应用把已扣掉的量又灌回去。
 */
@Component
public class InventoryInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(InventoryInitializer.class);

    private final InventoryRepository repository;
    private final StringRedisTemplate redis;
    private final InventoryProperties properties;
    private final BucketLayout bucketLayout;

    public InventoryInitializer(InventoryRepository repository, StringRedisTemplate redis,
                                InventoryProperties properties, BucketLayout bucketLayout) {
        this.repository = repository;
        this.redis = redis;
        this.properties = properties;
        this.bucketLayout = bucketLayout;
    }

    @Override
    public void run(ApplicationArguments args) {
        long skuId = properties.getDefaultSkuId();
        repository.ensureLedger(skuId, properties.getDefaultStock());

        if (repository.countBuckets(skuId) == 0) {
            repository.ensureBuckets(skuId, properties.getBucketCount(), properties.getDefaultStock());
        }

        List<InventoryRepository.BucketRow> buckets = repository.buckets(skuId);
        bucketLayout.set(buckets.size());

        int warmed = 0;
        for (InventoryRepository.BucketRow row : buckets) {
            Boolean created = redis.opsForValue().setIfAbsent(
                    RedisKeys.bucket(skuId, row.bucketId()), String.valueOf(row.redisQty()));
            if (Boolean.TRUE.equals(created)) {
                warmed++;
            }
        }

        if (redis.opsForValue().get(RedisKeys.run(skuId)) == null) {
            redis.opsForValue().set(RedisKeys.run(skuId), UUID.randomUUID().toString());
        }

        InventoryRepository.LedgerRow ledger = repository.ledger(skuId);
        log.info("库存压测环境就绪 sku={} 台账={} 桶数={} Redis预热桶数={}",
                skuId, ledger, buckets.size(), warmed);
        log.info("提示：开始压测前先调一次 POST /api/admin/reset?skuId={}&stock=你的初始库存", skuId);
    }
}
