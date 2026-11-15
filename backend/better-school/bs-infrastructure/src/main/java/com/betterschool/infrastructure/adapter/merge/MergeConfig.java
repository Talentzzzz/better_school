package com.betterschool.infrastructure.adapter.merge;

import java.util.Objects;

/**
 * 合并器配置（不可变，构造即校验）。
 *
 * <p><b>这些参数互相约束，配错了不是「慢」而是直接失效</b>，所以校验放在紧凑构造器里：
 * 配置错误必须在启动瞬间炸掉，而不是上线后才发现每个请求都超时。
 *
 * <p>三组硬约束（{@code spec §2.4}）：
 * <ol>
 *   <li><b>{@code shardCount} 必须是 2 的幂</b>：路由用 {@code (h ^ (h >>> 16)) & mask}（h = 业务 ID
 *       字符串的 hashCode），非 2 的幂算出的下标会越界；</li>
 *   <li><b>{@code waitTimeoutMs > maxFlushDelayMillis}</b>：否则请求必然「先超时、后落库」，{@code timedOut}
 *       失去观测意义，调用方还会重试一次；</li>
 *   <li><b>{@code shardCapacity >= maxBatch}</b>：否则永远攒不满一批就被背压拒绝。</li>
 * </ol>
 *
 * <p><b>{@code flushThreads} 这里只校验 {@code >= 1}</b>：真正的上限是「连接池 − 2」，而连接池大小
 * 属于应用装配层（{@code bs-app} 的 {@code CountMergeConfig}），引擎不认识 Hikari。
 *
 * <p><b>{@code shardCapacity} 只当「降级触发点 + 内存上界」</b>，不做扩容：满了就让调用方走
 * 同步降级（组大小 1 调同一个落库方法），合并器自己永不扩容、永不丢弃已入队件。
 *
 * @param shardCount     分片数（= 缓冲区个数），2 的幂；建议 ≈ 峰值并发热点数，只买「批纯度」
 * @param maxFlushDelayMillis       合并窗口：分片首个请求排定 maxFlushDelayMillis 后 flush，攒满可提前触发
 * @param maxBatch       单次 flush 最多取走的件数
 * @param waitTimeoutMs  等结果的调用方最长等待（含排队 + 落库），超时返回 {@code TIMEOUT}
 * @param shardCapacity  单分片缓冲区上限，满即 {@code BUFFER_FULL}（入口背压）
 * @param flushThreads   flush 执行池大小，装配层另校验 {@code <= 池 − 2}
 * @param drainTimeoutMs 停机排空（停收 → 排空 → 关池）的等待上限
 */
public final class MergeConfig {

    private final int shardCount;
    private final int maxFlushDelayMillis;
    private final int maxBatch;
    private final int waitTimeoutMs;
    private final int shardCapacity;
    private final int flushThreads;
    private final long drainTimeoutMs;

    /** 计数侧默认值（spec §2.4：一套参数，未扫参） */
    public static final int DEFAULT_SHARD_COUNT = 256;
    public static final int DEFAULT_WINDOW_MS = 5;
    public static final int DEFAULT_MAX_BATCH = 200;
    public static final int DEFAULT_WAIT_TIMEOUT_MS = 100;
    public static final int DEFAULT_SHARD_CAPACITY = 5000;
    public static final int DEFAULT_FLUSH_THREADS = 4;
    public static final long DEFAULT_DRAIN_TIMEOUT_MS = 5_000;

    /**
     * 构造即校验（原 record 紧凑构造器的校验逻辑原样保留）：配置错误必须在启动瞬间炸掉。
     */
    public MergeConfig(int shardCount,
                       int maxFlushDelayMillis,
                       int maxBatch,
                       int waitTimeoutMs,
                       int shardCapacity,
                       int flushThreads,
                       long drainTimeoutMs) {
        require(shardCount > 0, "merge.count.shard-count 必须 > 0（当前 %s）", shardCount);
        require(Integer.bitCount(shardCount) == 1,
                "merge.count.shard-count(%s) 必须是 2 的幂：分片路由用 hash & (n-1)，非 2 的幂会算出越界下标", shardCount);
        require(maxFlushDelayMillis >= 0, "merge.count.max-flush-delay-millis 必须 >= 0（当前 %s）", maxFlushDelayMillis);
        require(maxBatch > 0, "merge.count.max-batch 必须 > 0（当前 %s）", maxBatch);
        require(waitTimeoutMs > maxFlushDelayMillis,
                "merge.count.wait-timeout-ms(%s) 必须 > merge.count.max-flush-delay-millis(%s)，否则请求必然先超时后落库",
                waitTimeoutMs, maxFlushDelayMillis);
        require(shardCapacity >= maxBatch,
                "merge.count.shard-capacity(%s) 必须 >= merge.count.max-batch(%s)，否则永远攒不满一批就被拒绝",
                shardCapacity, maxBatch);
        require(flushThreads >= 1, "merge.count.flush-threads 必须 >= 1（当前 %s）", flushThreads);
        require(drainTimeoutMs > 0, "merge.count.drain-timeout-ms 必须 > 0（当前 %s）", drainTimeoutMs);
        this.shardCount = shardCount;
        this.maxFlushDelayMillis = maxFlushDelayMillis;
        this.maxBatch = maxBatch;
        this.waitTimeoutMs = waitTimeoutMs;
        this.shardCapacity = shardCapacity;
        this.flushThreads = flushThreads;
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

    public int flushThreads() {
        return flushThreads;
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
                && flushThreads == other.flushThreads
                && drainTimeoutMs == other.drainTimeoutMs;
    }

    @Override
    public int hashCode() {
        return Objects.hash(shardCount, maxFlushDelayMillis, maxBatch, waitTimeoutMs, shardCapacity, flushThreads, drainTimeoutMs);
    }

    @Override
    public String toString() {
        return "MergeConfig[shardCount=" + shardCount + ", maxFlushDelayMillis=" + maxFlushDelayMillis + ", maxBatch=" + maxBatch
                + ", waitTimeoutMs=" + waitTimeoutMs + ", shardCapacity=" + shardCapacity
                + ", flushThreads=" + flushThreads + ", drainTimeoutMs=" + drainTimeoutMs + "]";
    }

    /** 计数侧默认参数（{@code 256 / 5ms / 200 / 100ms / 5000 / 4 线程 / 5s}） */
    public static MergeConfig countDefaults() {
        return new MergeConfig(DEFAULT_SHARD_COUNT, DEFAULT_WINDOW_MS, DEFAULT_MAX_BATCH,
                DEFAULT_WAIT_TIMEOUT_MS, DEFAULT_SHARD_CAPACITY, DEFAULT_FLUSH_THREADS,
                DEFAULT_DRAIN_TIMEOUT_MS);
    }

    /** 路由掩码：{@code shardCount} 是 2 的幂，故 {@code n - 1} 即全 1 位 */
    int mask() {
        return shardCount - 1;
    }

    /**
     * 分片路由：同一条业务行永远落同一个分片。
     *
     * <p><b>键是业务 ID 字符串</b>（uuid-biz-id 起帖/评是 UUID，商品/跑腿过渡期是数值串）：
     * 先取 {@link String#hashCode()}，再做高位折叠 {@code h ^ (h >>> 16)} 后看低位。
     * 折叠是内部实现，不外泄（调用方只给行主键，不感知分片）。
     *
     * <p><b>为什么这里可以用哈希、而分组键不行</b>：分片只是「放哪个缓冲队列」，哈希碰撞让两行落
     * 同一分片无害（同分片内还会按<b>原值</b>二次分组）；分组要求「同一行」严格相等，所以
     * {@link CountMerger} 的分组键用字符串原值，绝不拿哈希当身份。
     *
     * <p>用 {@code &} 而不是 {@code Math.floorMod}：掩码恒正，负哈希也算不出负下标，且无分支。
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
