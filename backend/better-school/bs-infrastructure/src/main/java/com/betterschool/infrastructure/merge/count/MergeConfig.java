package com.betterschool.infrastructure.merge.count;

import java.util.Objects;

/**
 * 合并器配置（不可变，构造即校验）。
 *
 * <p><b>这些参数互相约束，配错了不是「慢」而是直接失效</b>，所以校验放在紧凑构造器里：
 * 配置错误必须在启动瞬间炸掉，而不是上线后才发现每个请求都超时。
 *
 * <p>per-invId leader 模型下没有分片数组、也没有常驻 flush 线程——flush 并发 = 并发热点
 * leader 数，天然受连接池约束，「shardCount ≤ 池 − 2」的装配校验随后台线程模型一并退役。
 *
 * <p>三条硬约束：
 * <ol>
 *   <li><b>{@code waitTimeoutMs > maxFlushDelayMillis}</b>：否则请求必然「先超时、后落库」，{@code timedOut}
 *       失去观测意义，调用方还会重试一次；</li>
 *   <li><b>{@code shardCapacity >= maxBatch}</b>：否则永远攒不满一批就被背压拒绝；</li>
 *   <li><b>{@code drainTimeoutMs > 0}</b>：停机排空没有上限等于没有停机。</li>
 * </ol>
 *
 * <p><b>{@code shardCapacity} 只当「降级触发点 + 内存上界」</b>，不做扩容：满了就让调用方走
 * 同步降级（组大小 1 调同一个落库方法），合并器自己永不扩容、永不丢弃已入队件。
 *
 * @param shardCount    【退役兼容参数】历史分片数的 yml 键（{@code merge.count.shard-count}）
 *                      保留一个版本，读进来只存不用——不再参与路由，也不再绑定任何线程
 * @param maxFlushDelayMillis      leader 收集窗口：首件建组起算这么久后发车，攒满可提前触发
 * @param maxBatch      单次 flush 最多取走的件数
 * @param waitTimeoutMs 等结果的调用方最长等待（含排队 + 收集 + 落库），超时返回 {@code TIMEOUT}
 * @param shardCapacity 单个 invId Group 的缓冲上限，满即 {@code BUFFER_FULL}（入口背压）
 * @param drainTimeoutMs 停机排空（停收 → 排空 → 残余回填）的等待上限
 */
public final class MergeConfig {

    private final int shardCount;
    private final int maxFlushDelayMillis;
    private final int maxBatch;
    private final int waitTimeoutMs;
    private final int shardCapacity;
    private final long drainTimeoutMs;

    /** 历史默认分片数：仅为 countDefaults() 数值口径兼容而保留，不再对应任何结构 */
    private static final int DEFAULT_SHARD_COUNT = 8;
    private static final int DEFAULT_WINDOW_MS = 5;
    private static final int DEFAULT_MAX_BATCH = 200;
    private static final int DEFAULT_WAIT_TIMEOUT_MS = 100;
    private static final int DEFAULT_SHARD_CAPACITY = 2000;
    private static final long DEFAULT_DRAIN_TIMEOUT_MS = 5_000;

    /**
     * 构造即校验：配置错误必须在启动瞬间炸掉。
     */
    public MergeConfig(int shardCount,
                       int maxFlushDelayMillis,
                       int maxBatch,
                       int waitTimeoutMs,
                       int shardCapacity,
                       long drainTimeoutMs) {
        require(shardCount > 0, "merge.count.shard-count 必须 > 0（当前 %s）", shardCount);
        require(maxFlushDelayMillis >= 0, "merge.count.max-flush-delay-millis 必须 >= 0（当前 %s）", maxFlushDelayMillis);
        require(maxBatch > 0, "merge.count.max-batch 必须 > 0（当前 %s）", maxBatch);
        require(waitTimeoutMs > maxFlushDelayMillis,
                "merge.count.wait-timeout-ms(%s) 必须 > merge.count.max-flush-delay-millis(%s)，否则请求必然先超时后落库",
                waitTimeoutMs, maxFlushDelayMillis);
        require(shardCapacity >= maxBatch,
                "merge.count.shard-capacity(%s) 必须 >= merge.count.max-batch(%s)，否则永远攒不满一批就被拒绝",
                shardCapacity, maxBatch);
        require(drainTimeoutMs > 0, "merge.count.drain-timeout-ms 必须 > 0（当前 %s）", drainTimeoutMs);
        this.shardCount = shardCount;
        this.maxFlushDelayMillis = maxFlushDelayMillis;
        this.maxBatch = maxBatch;
        this.waitTimeoutMs = waitTimeoutMs;
        this.shardCapacity = shardCapacity;
        this.drainTimeoutMs = drainTimeoutMs;
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MergeConfig other)) return false;
        return shardCount == other.shardCount
                && maxFlushDelayMillis == other.maxFlushDelayMillis
                && maxBatch == other.maxBatch
                && waitTimeoutMs == other.waitTimeoutMs
                && shardCapacity == other.shardCapacity
                && drainTimeoutMs == other.drainTimeoutMs;
    }

    @Override
    public int hashCode() {
        return Objects.hash(shardCount, maxFlushDelayMillis, maxBatch, waitTimeoutMs, shardCapacity, drainTimeoutMs);
    }

    @Override
    public String toString() {
        return "MergeConfig[shardCount=" + shardCount + "(退役兼容), maxFlushDelayMillis=" + maxFlushDelayMillis
                + ", maxBatch=" + maxBatch + ", waitTimeoutMs=" + waitTimeoutMs
                + ", shardCapacity=" + shardCapacity + ", drainTimeoutMs=" + drainTimeoutMs + "]";
    }

    /** 计数侧默认参数（{@code 8(退役兼容) / 5ms / 200 / 100ms / 2000 / 5s}） */
    public static MergeConfig countDefaults() {
        return new MergeConfig(DEFAULT_SHARD_COUNT, DEFAULT_WINDOW_MS, DEFAULT_MAX_BATCH,
                DEFAULT_WAIT_TIMEOUT_MS, DEFAULT_SHARD_CAPACITY, DEFAULT_DRAIN_TIMEOUT_MS);
    }

    private static void require(boolean condition, String template, Object... args) {
        if (!condition) {
            throw new IllegalArgumentException("[配置错误] " + String.format(template, args));
        }
    }
}
