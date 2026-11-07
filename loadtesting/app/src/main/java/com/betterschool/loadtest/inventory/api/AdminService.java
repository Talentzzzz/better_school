package com.betterschool.loadtest.inventory.api;

import com.betterschool.loadtest.inventory.config.InventoryProperties;
import com.betterschool.loadtest.inventory.config.RedisKeys;
import com.betterschool.loadtest.inventory.metrics.BucketLayout;
import com.betterschool.loadtest.inventory.metrics.RunIdResolver;
import com.betterschool.loadtest.inventory.metrics.RunStats;
import com.betterschool.loadtest.inventory.repository.InventoryRepository;
import com.betterschool.loadtest.inventory.strategy.StrategyRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 压测运维面：重置一轮、读一轮的账。
 * <p>
 * 重置顺序有讲究：<b>先换 runId</b>，再清库、再灌 Redis。
 * 换 runId 之后消费者立刻开始丢弃上一轮的迟到消息，避免上一轮的扣减打到新一轮台账上。
 */
@Service
public class AdminService {

    private static final Logger log = LoggerFactory.getLogger(AdminService.class);
    private static final List<String> STRATEGIES = List.of("db", "redis_mq", "merge");

    private final InventoryRepository repository;
    private final StringRedisTemplate redis;
    private final InventoryProperties properties;
    private final BucketLayout bucketLayout;
    private final RunStats stats;
    private final RunIdResolver runIdResolver;
    private final StrategyRegistry strategyRegistry;
    private final TransactionTemplate tx;

    public AdminService(InventoryRepository repository, StringRedisTemplate redis,
                        InventoryProperties properties, BucketLayout bucketLayout,
                        RunStats stats, RunIdResolver runIdResolver,
                        StrategyRegistry strategyRegistry, TransactionTemplate tx) {
        this.repository = repository;
        this.redis = redis;
        this.properties = properties;
        this.bucketLayout = bucketLayout;
        this.stats = stats;
        this.runIdResolver = runIdResolver;
        this.strategyRegistry = strategyRegistry;
        this.tx = tx;
    }

    /**
     * 重置一轮：清明细/订单、台账置回 stock、重建分桶、桶余量灌回 Redis、清合并窗口、计数清零。
     * 幂等，可以反复调。压测每换一个策略都要先调一次。
     */
    public Map<String, Object> reset(long skuId, long stock, Integer bucketCount) {
        int buckets = bucketCount == null ? properties.getBucketCount() : bucketCount;
        buckets = Math.max(1, Math.min(buckets, 64));
        if (stock < 0) {
            throw new IllegalArgumentException("stock 不能为负数");
        }

        // 1) 先切轮次：此刻起，旧 runId 的 MQ 消息都会被消费者丢弃
        String runId = UUID.randomUUID().toString();
        redis.opsForValue().set(RedisKeys.run(skuId), runId);
        runIdResolver.invalidate(skuId);

        // 2) 清库 + 台账 + 分桶
        final int finalBuckets = buckets;
        final long finalStock = stock;
        tx.executeWithoutResult(status -> {
            repository.deleteRunData(skuId);
            repository.activateLedger(skuId, finalStock);
            repository.replaceBuckets(skuId, finalBuckets, finalStock);
        });

        // 3) 灌 Redis 桶余量 + 清合并去重 key
        List<InventoryRepository.BucketRow> rows = repository.buckets(skuId);
        for (InventoryRepository.BucketRow row : rows) {
            redis.opsForValue().set(RedisKeys.bucket(skuId, row.bucketId()), String.valueOf(row.redisQty()));
            redis.delete(RedisKeys.mergeDedupe(skuId, row.bucketId()));
        }
        bucketLayout.set(rows.size());

        // 4) 计数清零
        stats.reset();
        log.info("压测环境已重置 sku={} stock={} buckets={} runId={}", skuId, stock, rows.size(), runId);

        Map<String, Object> result = stats(skuId);
        result.put("runId", runId);
        result.put("note", "重置成功。请等待 3 秒让上一轮的在途 MQ 消息过期，再开始施压。");
        return result;
    }

    /** 一轮的账：台账 + Redis + 明细 + 订单 + 应用计数 + 一致性判定 */
    public Map<String, Object> stats(long skuId) {
        InventoryRepository.LedgerRow ledger = repository.ledger(skuId);
        long total = ledger == null ? 0 : ledger.total();
        long surplus = ledger == null ? 0 : ledger.surplus();
        long waiting = ledger == null ? 0 : ledger.waiting();
        long issued = ledger == null ? 0 : ledger.issued();

        List<InventoryRepository.BucketRow> buckets = repository.buckets(skuId);
        List<String> keys = new ArrayList<>(buckets.size());
        for (InventoryRepository.BucketRow row : buckets) {
            keys.add(RedisKeys.bucket(skuId, row.bucketId()));
        }
        List<String> values = keys.isEmpty() ? List.of() : redis.opsForValue().multiGet(keys);
        long redisTotal = 0;
        List<Map<String, Object>> bucketStats = new ArrayList<>(buckets.size());
        for (int i = 0; i < buckets.size(); i++) {
            String raw = values == null ? null : values.get(i);
            Long qty = raw == null ? null : parseLong(raw);
            long value = qty == null ? -1 : qty;
            if (value > 0) {
                redisTotal += value;
            }
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("bucketId", buckets.get(i).bucketId());
            one.put("redisQty", value);
            one.put("ledgerCopy", buckets.get(i).redisQty());
            bucketStats.add(one);
        }

        InventoryRepository.PendingStat pending = repository.pendingStat(skuId);
        long orders = repository.orderCount(skuId);
        long mergedRows = repository.mergedRows(skuId);
        long sold = waiting + issued;

        Map<String, Long> rawCounters = stats.snapshot();
        Map<String, Long> counters = new LinkedHashMap<>(rawCounters);
        counters.keySet().removeIf(key -> key.startsWith("cost_nanos:"));

        Map<String, Object> ledgerMap = new LinkedHashMap<>();
        ledgerMap.put("total", total);
        ledgerMap.put("surplus", surplus);
        ledgerMap.put("waiting", waiting);
        ledgerMap.put("issued", issued);

        Map<String, Object> redisMap = new LinkedHashMap<>();
        redisMap.put("total", redisTotal);
        redisMap.put("buckets", bucketStats);

        Map<String, Object> mergeMap = new LinkedHashMap<>();
        mergeMap.put("pendingRows", pending.rows());
        mergeMap.put("pendingQty", pending.qty());
        mergeMap.put("mergedRows", mergedRows);

        Map<String, Object> invariant = new LinkedHashMap<>();
        // db 策略不碰 Redis，桶余量保持初始值属于正常，不能拿它做对账判据
        boolean redisUsed = rawCounters.getOrDefault("calls:redis_mq", 0L) > 0
                || rawCounters.getOrDefault("calls:merge", 0L) > 0;
        boolean redisMatches = !redisUsed || redisTotal == surplus;
        invariant.put("sold", sold);
        invariant.put("balanced", surplus + sold == total);
        invariant.put("oversold", surplus < 0 || sold > total);
        // 排空后应满足：Redis 余量 == 台账余量（Redis 是余量面，台账是真相）
        invariant.put("redisInUse", redisUsed);
        invariant.put("redisMatchesLedger", redisMatches);
        invariant.put("drained", pending.rows() == 0 && redisMatches);

        Map<String, Object> serverSide = new LinkedHashMap<>();
        for (String name : STRATEGIES) {
            long calls = rawCounters.getOrDefault("calls:" + name, 0L);
            long nanos = rawCounters.getOrDefault("cost_nanos:" + name, 0L);
            double avgMs = calls == 0 ? 0d : (nanos / 1_000_000.0) / calls;
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("calls", calls);
            one.put("avgServerMs", Math.round(avgMs * 100) / 100.0);
            serverSide.put(name, one);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("skuId", skuId);
        out.put("now", OffsetDateTime.now().toString());
        out.put("strategies", strategyRegistry.names());
        out.put("ledger", ledgerMap);
        out.put("redis", redisMap);
        out.put("merge", mergeMap);
        out.put("orders", orders);
        out.put("counters", counters);
        out.put("serverSide", serverSide);
        out.put("invariant", invariant);
        out.put("buckets", buckets.size());
        out.put("resetAt", stats.resetAt());
        return out;
    }

    private Long parseLong(String raw) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
