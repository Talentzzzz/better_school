package com.betterschool.marketing.domain.inventory.service.merge;

import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 库存侧合并器配置（不可变，构造即校验——配错必须启动瞬间炸掉，而不是上线后每笔预扣都超时）。
 *
 * <p>三条硬约束：{@code shardCount} 必须是 2 的幂（{@code hash & mask} 路由）；
 * {@code waitTimeoutMs > maxFlushDelayMillis}（否则每笔都先超时后落库）；
 * {@code shardCapacity >= maxBatch}（否则永远攒不满一批就被拒）。
 * {@code shardCount} 同时是 flush 并发（per-shard 常驻线程模型，线程数 = 分片数），
 * 真正的上限「连接池 − 2」在装配层（QuotaMergeConfig）校验——连接池属于应用装配层，引擎不认识 Hikari。
 *
 * @param shardCount     分片数（= 缓冲区个数 = 常驻 flush 线程数），2 的幂
 * @param maxFlushDelayMillis       合并窗口：分片首个请求起算 maxFlushDelayMillis 后 flush，攒满可提前触发
 * @param maxBatch       单次 flush 最多取走的件数
 * @param waitTimeoutMs  等结果的调用方最长等待（含排队 + 落库），超时返回 {@code TIMEOUT_UNKNOWN}
 * @param shardCapacity  单分片缓冲区上限，满即 {@code BUFFER_FULL}（入口背压 → 调用方同步降级）
 * @param drainTimeoutMs 停机排空（停收 → 排空 → signal + join）的等待上限
 */
@EqualsAndHashCode
@ToString
public final class MergeConfig {

    /**
     * 分片的本职是路由桶（合并粒度由 awardId 分组决定，与分片数无关），8 片足够摊平 offer 争抢；
     * 而 per-shard 线程模型把 flush 并发与分片数绑死（受「连接池 − 2」封顶），默认取下限。
     * 例外：单分片 offer 成为瓶颈或需要更大路由空间时重议（wayfinder 地图 fog）。
     */
    private static final int DEFAULT_SHARD_COUNT = 8;
    private static final int DEFAULT_WINDOW_MS = 5;
    private static final int DEFAULT_MAX_BATCH = 200;
    private static final int DEFAULT_WAIT_TIMEOUT_MS = 100;
    private static final int DEFAULT_SHARD_CAPACITY = 2000;
    private static final long DEFAULT_DRAIN_TIMEOUT_MS = 5_000;

    private final int shardCount;
    private final int maxFlushDelayMillis;
    private final int maxBatch;
    private final int waitTimeoutMs;
    private final int shardCapacity;
    private final long drainTimeoutMs;

    public MergeConfig(int shardCount,
                       int maxFlushDelayMillis,
                       int maxBatch,
                       int waitTimeoutMs,
                       int shardCapacity,
                       long drainTimeoutMs) {
        require(shardCount > 0, "merge.inventory.shard-count 必须 > 0（当前 %s）", shardCount);
        require(Integer.bitCount(shardCount) == 1,
                "merge.inventory.shard-count(%s) 必须是 2 的幂：分片路由用 hash & (n-1)，非 2 的幂会算出越界下标",
                shardCount);
        require(maxFlushDelayMillis >= 0, "merge.inventory.max-flush-delay-millis 必须 >= 0（当前 %s）", maxFlushDelayMillis);
        require(maxBatch > 0, "merge.inventory.max-batch 必须 > 0（当前 %s）", maxBatch);
        require(waitTimeoutMs > maxFlushDelayMillis,
                "merge.inventory.wait-timeout-ms(%s) 必须 > merge.inventory.max-flush-delay-millis(%s)，"
                        + "否则每一笔预扣都先超时后落库（= 全部 TIMEOUT_UNKNOWN）",
                waitTimeoutMs, maxFlushDelayMillis);
        require(shardCapacity >= maxBatch,
                "merge.inventory.shard-capacity(%s) 必须 >= merge.inventory.max-batch(%s)，否则永远攒不满一批就被拒绝",
                shardCapacity, maxBatch);
        require(drainTimeoutMs > 0, "merge.inventory.drain-timeout-ms 必须 > 0（当前 %s）", drainTimeoutMs);

        this.shardCount = shardCount;
        this.maxFlushDelayMillis = maxFlushDelayMillis;
        this.maxBatch = maxBatch;
        this.waitTimeoutMs = waitTimeoutMs;
        this.shardCapacity = shardCapacity;
        this.drainTimeoutMs = drainTimeoutMs;
    }

    /** 库存侧默认参数（{@code 8 / 5ms / 200 / 100ms / 2000 / 5s}） */
    public static MergeConfig quotaDefaults() {
        return new MergeConfig(DEFAULT_SHARD_COUNT, DEFAULT_WINDOW_MS, DEFAULT_MAX_BATCH,
                DEFAULT_WAIT_TIMEOUT_MS, DEFAULT_SHARD_CAPACITY, DEFAULT_DRAIN_TIMEOUT_MS);
    }

    public int shardCount() {
        return shardCount;
    }

    public int maxFlushDelayMillis() {
        return maxFlushDelayMillis;
    }

    public int maxBatch() {
        return maxBatch;
    }

    public int waitTimeoutMs() {
        return waitTimeoutMs;
    }

    public int shardCapacity() {
        return shardCapacity;
    }

    public long drainTimeoutMs() {
        return drainTimeoutMs;
    }

    /** 路由掩码：{@code shardCount} 是 2 的幂，故 {@code n - 1} 即全 1 位 */
    int mask() {
        return shardCount - 1;
    }

    /**
     * 分片路由：同一个 {@code invId} 永远落同一个分片（预扣与确认共用，撞片无害且是顺序的前提）。
     * inv_id 已 UUID 化，对字符串 hashCode 做高低位折叠（{@code h ^ (h >>> 16)}）再取掩码，
     * 避免只差在高位的大量 key 全挤进同一片；掩码恒正，负 hash 也算不出负下标。
     */
    int shardOf(String key) {
        int h = key.hashCode();
        return (h ^ (h >>> 16)) & mask();
    }

    private static void require(boolean condition, String template, Object... args) {
        if (!condition) {
            throw new IllegalArgumentException("[配置错误] " + String.format(template, args));
        }
    }
}
