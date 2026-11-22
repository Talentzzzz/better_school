package com.betterschool.marketing.infrastructure.merge;

import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 引擎参数（不可变，构造即校验——配错要在启动瞬间炸掉，而不是上线后每笔提交都超时）。
 *
 * <p>引擎自身只校验三条：{@code shardCount} 是 2 的幂、{@code waitTimeoutMs > maxFlushDelayMillis}、
 * {@code shardCapacity >= maxBatch}。真正的并发上限「连接池 − 2」在装配层校验（引擎不认识 Hikari）。
 *
 * @param shardCount           分片数（= 缓冲区数 = 常驻 flush 线程数），2 的幂
 * @param maxFlushDelayMillis  合并窗口：分片首件起算这么久后 flush，攒满可提前触发
 * @param maxBatch             单次 flush 最多取走的件数
 * @param waitTimeoutMs        调用方最长等待（排队 + 落库），超时返回 {@code TIMEOUT}
 * @param shardCapacity        单分片缓冲上限，满即不再攒批（改由提交线程同步落库）
 * @param drainTimeoutMs       停机排空上限
 */
@EqualsAndHashCode
@ToString
public final class MergeConfig {

    /** 分片只决定路由桶，8 片够摊平 offer 争抢；flush 并发 = 分片数，默认取下限 */
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
        require(shardCount > 0 && Integer.bitCount(shardCount) == 1,
                "merge.batch.shard-count(%s) 必须是 2 的幂：路由用 hash & (n-1)", shardCount);
        require(maxFlushDelayMillis >= 0,
                "merge.batch.max-flush-delay-millis 必须 >= 0（当前 %s）", maxFlushDelayMillis);
        require(maxBatch > 0, "merge.batch.max-batch 必须 > 0（当前 %s）", maxBatch);
        require(waitTimeoutMs > maxFlushDelayMillis,
                "merge.batch.wait-timeout-ms(%s) 必须 > max-flush-delay-millis(%s)，否则每笔都先超时后落库",
                waitTimeoutMs, maxFlushDelayMillis);
        require(shardCapacity >= maxBatch,
                "merge.batch.shard-capacity(%s) 必须 >= max-batch(%s)，否则永远攒不满一批就被拒",
                shardCapacity, maxBatch);
        require(drainTimeoutMs > 0, "merge.batch.drain-timeout-ms 必须 > 0（当前 %s）", drainTimeoutMs);

        this.shardCount = shardCount;
        this.maxFlushDelayMillis = maxFlushDelayMillis;
        this.maxBatch = maxBatch;
        this.waitTimeoutMs = waitTimeoutMs;
        this.shardCapacity = shardCapacity;
        this.drainTimeoutMs = drainTimeoutMs;
    }

    /** 默认参数：{@code 8 / 5ms / 200 / 100ms / 2000 / 5s} */
    public static MergeConfig defaults() {
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

    /** 路由掩码：{@code shardCount} 是 2 的幂，{@code n - 1} 即全 1 位 */
    int mask() {
        return shardCount - 1;
    }

    /** 同一 shardKey 永远落同一分片；折叠高低位再取掩码，避免大量 key 挤进同一片（掩码恒正，负 hash 也安全） */
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
