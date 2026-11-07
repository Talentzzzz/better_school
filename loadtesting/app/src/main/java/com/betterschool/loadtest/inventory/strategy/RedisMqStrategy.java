package com.betterschool.loadtest.inventory.strategy;

import com.betterschool.loadtest.inventory.config.RedisKeys;
import com.betterschool.loadtest.inventory.metrics.BucketLayout;
import com.betterschool.loadtest.inventory.metrics.RunIdResolver;
import com.betterschool.loadtest.inventory.metrics.RunStats;
import com.betterschool.loadtest.inventory.mq.DeductMessage;
import com.betterschool.loadtest.inventory.mq.InventoryMqProducer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Redis 预扣 + 每条扣减发一条 MQ，消费端逐条扣 DB。
 * <pre>
 *   请求线程：Lua（查余量 + DECRBY）→ syncSend → 返回 200
 *   消费线程：INSERT order → UPDATE ledger（每条一次）
 * </pre>
 * 响应延迟里不含 DB 写入，所以 QPS 天花板比 db 高很多；代价是：
 * <ul>
 *   <li>消息量 = 请求量，MQ 与消费端成为新的瓶颈；</li>
 *   <li>台账行锁在消费端排队，DB 侧吞吐并不会因为 MQ 而变高；</li>
 *   <li>受理 ≠ 落库，最终一致有延迟（stats 的 drained 字段可观察）。</li>
 * </ul>
 */
@Component
public class RedisMqStrategy implements DeductStrategy {

    private final StringRedisTemplate redis;
    private final RedisScript<Long> plainDeductScript;
    private final InventoryMqProducer producer;
    private final BucketLayout layout;
    private final RunIdResolver runIdResolver;
    private final RunStats stats;

    public RedisMqStrategy(StringRedisTemplate redis,
                           RedisScript<Long> plainDeductScript,
                           InventoryMqProducer producer,
                           BucketLayout layout,
                           RunIdResolver runIdResolver,
                           RunStats stats) {
        this.redis = redis;
        this.plainDeductScript = plainDeductScript;
        this.producer = producer;
        this.layout = layout;
        this.runIdResolver = runIdResolver;
        this.stats = stats;
    }

    @Override
    public String name() {
        return "redis_mq";
    }

    @Override
    public DeductResult deduct(long skuId, int qty, String orderNo) {
        long begin = System.nanoTime();
        int buckets = layout.count();
        int start = Math.floorMod(orderNo.hashCode(), buckets);
        try {
            for (int i = 0; i < buckets; i++) {
                int bucketId = (start + i) % buckets;
                String key = RedisKeys.bucket(skuId, bucketId);
                Long code = redis.execute(plainDeductScript, List.of(key), String.valueOf(qty));
                if (code == null) {
                    stats.inc("error:" + name());
                    return DeductResult.error("REDIS_NO_RESULT");
                }
                if (code == 0L) {
                    try {
                        producer.sendDeduct(new DeductMessage(
                                runIdResolver.current(skuId), skuId, bucketId, qty, orderNo));
                    } catch (RuntimeException e) {
                        // 发送失败：把刚才扣掉的余量还回去，让「Redis 余量 == 台账 + 在途」继续成立
                        redis.opsForValue().increment(key, qty);
                        stats.inc("mq_send_fail");
                        stats.inc("error:" + name());
                        return DeductResult.error("MQ_SEND_FAIL");
                    }
                    stats.inc("ok:" + name());
                    return DeductResult.ok();
                }
                // -1 桶不存在 / -2 余量不足：换下一个桶继续试
            }
            stats.inc("soldout:" + name());
            return DeductResult.soldOut();
        } finally {
            stats.add("cost_nanos:" + name(), System.nanoTime() - begin);
            stats.inc("calls:" + name());
        }
    }
}
