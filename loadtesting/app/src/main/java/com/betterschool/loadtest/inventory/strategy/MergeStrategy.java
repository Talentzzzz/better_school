package com.betterschool.loadtest.inventory.strategy;

import com.betterschool.loadtest.inventory.config.InventoryProperties;
import com.betterschool.loadtest.inventory.config.RedisKeys;
import com.betterschool.loadtest.inventory.metrics.BucketLayout;
import com.betterschool.loadtest.inventory.metrics.RunIdResolver;
import com.betterschool.loadtest.inventory.metrics.RunStats;
import com.betterschool.loadtest.inventory.mq.InventoryMqProducer;
import com.betterschool.loadtest.inventory.mq.MergeMessage;
import com.betterschool.loadtest.inventory.repository.InventoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 合并扣减（线上同款语义）：
 * <pre>
 *   请求线程：Lua（查余量 + DECRBY + 窗口内首个请求负责叫醒）
 *            → 落一条未合并明细（业务唯一键防重）→ 返回 200
 *   合并线程：每秒被唤醒一次，只合该桶最老的一个时间格：
 *            UPDATE 明细 state=1 → UPDATE 台账（一次批量）→ 递减桶副本
 * </pre>
 * 关键点：<b>台账总量在合并时才动</b>，明细是事实源、台账是真相、Redis 只是余量面。
 * 因此写放大从「每条一次台账 UPDATE」降到「每秒每桶一次」，代价是台账短暂滞后（在途量 waiting_qty 可观察）。
 */
@Component
public class MergeStrategy implements DeductStrategy {

    private static final Logger log = LoggerFactory.getLogger(MergeStrategy.class);

    private final StringRedisTemplate redis;
    private final RedisScript<List> mergeDeductScript;
    private final InventoryRepository repository;
    private final InventoryMqProducer producer;
    private final InventoryProperties properties;
    private final BucketLayout layout;
    private final RunIdResolver runIdResolver;
    private final RunStats stats;

    public MergeStrategy(StringRedisTemplate redis,
                         RedisScript<List> mergeDeductScript,
                         InventoryRepository repository,
                         InventoryMqProducer producer,
                         InventoryProperties properties,
                         BucketLayout layout,
                         RunIdResolver runIdResolver,
                         RunStats stats) {
        this.redis = redis;
        this.mergeDeductScript = mergeDeductScript;
        this.repository = repository;
        this.producer = producer;
        this.properties = properties;
        this.layout = layout;
        this.runIdResolver = runIdResolver;
        this.stats = stats;
    }

    @Override
    public String name() {
        return "merge";
    }

    @Override
    public DeductResult deduct(long skuId, int qty, String orderNo) {
        long begin = System.nanoTime();
        long windowMs = properties.getMerge().getDedupeWindowMs();
        int buckets = layout.count();
        int start = Math.floorMod(orderNo.hashCode(), buckets);
        try {
            for (int i = 0; i < buckets; i++) {
                int bucketId = (start + i) % buckets;
                String bucketKey = RedisKeys.bucket(skuId, bucketId);
                String dedupeKey = RedisKeys.mergeDedupe(skuId, bucketId);
                List<?> result = redis.execute(mergeDeductScript, List.of(bucketKey, dedupeKey),
                        String.valueOf(qty), String.valueOf(windowMs));
                if (result == null || result.size() < 2) {
                    stats.inc("error:" + name());
                    return DeductResult.error("REDIS_NO_RESULT");
                }
                long code = ((Number) result.get(0)).longValue();
                boolean notify = ((Number) result.get(1)).longValue() == 1L;
                if (code != 0L) {
                    // -1 桶不存在 / -2 余量不足：换下一个桶继续试
                    continue;
                }

                try {
                    repository.insertDetail(skuId, bucketId, qty, orderNo);
                } catch (DuplicateKeyException e) {
                    // 撞业务唯一键 = 这一单已经落过（并发重试）：回补本次 Redis 扣减，按成功回放
                    release(bucketKey, dedupeKey, qty);
                    stats.inc("dup:" + name());
                    stats.inc("ok:" + name());
                    return DeductResult.ok();
                } catch (RuntimeException e) {
                    // 明细没落上：回补 Redis，保持「Redis 余量 == 台账 + 在途」可对账
                    release(bucketKey, dedupeKey, qty);
                    stats.inc("error:" + name());
                    throw e;
                }

                stats.inc("ok:" + name());
                if (notify) {
                    // 窗口内只有第一笔负责叫醒；发失败不阻塞业务，兜底扫描会把它捞回来
                    stats.inc("merge_wake");
                    try {
                        producer.sendMergeDelay(new MergeMessage(
                                runIdResolver.current(skuId), skuId, bucketId));
                    } catch (RuntimeException e) {
                        stats.inc("merge_wake_fail");
                        log.warn("合并唤醒消息发送失败，等兜底扫描补发 sku={} bucket={}", skuId, bucketId, e);
                    }
                }
                return DeductResult.ok();
            }
            stats.inc("soldout:" + name());
            return DeductResult.soldOut();
        } finally {
            stats.add("cost_nanos:" + name(), System.nanoTime() - begin);
            stats.inc("calls:" + name());
        }
    }

    private void release(String bucketKey, String dedupeKey, int qty) {
        redis.opsForValue().increment(bucketKey, qty);
        redis.delete(dedupeKey);
    }
}
