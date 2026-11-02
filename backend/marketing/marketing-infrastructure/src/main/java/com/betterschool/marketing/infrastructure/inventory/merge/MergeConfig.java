package com.betterschool.marketing.infrastructure.inventory.merge;

/**
 * 库存侧合并器配置（不可变，构造即校验）。
 *
 * <p><b>这些参数互相约束，配错了不是「慢」而是直接失效</b>，所以校验放在紧凑构造器里：
 * 配置错误必须在启动瞬间炸掉，而不是上线后才发现每一笔预扣都超时。
 *
 * <p>三组硬约束（{@code spec §2.4}，与计数侧同一个口径）：
 * <ol>
 *   <li><b>{@code shardCount} 必须是 2 的幂</b>：路由用 {@code (key ^ (key >>> 32)) & mask}，
 *       非 2 的幂算出的下标会越界；</li>
 *   <li><b>{@code waitTimeoutMs > windowMs}</b>：否则请求必然「先超时、后落库」，每一个预扣都会
 *       变成 {@code TIMEOUT_UNKNOWN}（用户看到「处理中」，主链路还要按可重试抛一次）；</li>
 *   <li><b>{@code shardCapacity >= maxBatch}</b>：否则永远攒不满一批就被背压拒绝。</li>
 * </ol>
 *
 * <p><b>{@code flushThreads} 这里只校验 {@code >= 1}</b>：真正的上限是「连接池 − 2」，而连接池属于
 * 应用装配层（{@code marketing-app} 的 {@code QuotaMergeConfig}），引擎不认识 Hikari。
 *
 * <p><b>与计数侧的差异只有参数值</b>（{@code spec §2.4}）：{@code 64 分片 / 2 线程}（库存热点是
 * 「一次活动 1~3 个 invId」，不需要 256 个缓冲）、{@code shardCapacity 2000}（资金敏感 ⇒ 更早降级为
 * 同步单条，队列别长）。<b>算法与五条不变量一字不差</b>（票 06 §9）。
 *
 * @param shardCount     分片数（= 缓冲区个数），2 的幂；只买「批纯度」，并行度由线程数定
 * @param windowMs       合并窗口：分片首个请求排定 windowMs 后 flush，攒满可提前触发
 * @param maxBatch       单次 flush 最多取走的件数
 * @param waitTimeoutMs  等结果的调用方最长等待（含排队 + 落库），超时返回 {@code TIMEOUT_UNKNOWN}
 * @param shardCapacity  单分片缓冲区上限，满即 {@code BUFFER_FULL}（入口背压 → 调用方同步降级）
 * @param flushThreads   flush 执行池大小，装配层另校验 {@code <= 池 − 2}
 * @param drainTimeoutMs 停机排空（停收 → 排空 → 关池）的等待上限
 */
public record MergeConfig(int shardCount,
                          int windowMs,
                          int maxBatch,
                          int waitTimeoutMs,
                          int shardCapacity,
                          int flushThreads,
                          long drainTimeoutMs) {

    /** 库存侧默认值（spec §2.4：与计数侧同一套窗口 / 批 / 超时，分片与线程更小；未扫参） */
    public static final int DEFAULT_SHARD_COUNT = 64;
    public static final int DEFAULT_WINDOW_MS = 5;
    public static final int DEFAULT_MAX_BATCH = 200;
    public static final int DEFAULT_WAIT_TIMEOUT_MS = 100;
    public static final int DEFAULT_SHARD_CAPACITY = 2000;
    public static final int DEFAULT_FLUSH_THREADS = 2;
    public static final long DEFAULT_DRAIN_TIMEOUT_MS = 5_000;

    public MergeConfig {
        require(shardCount > 0, "merge.inventory.shard-count 必须 > 0（当前 %s）", shardCount);
        require(Integer.bitCount(shardCount) == 1,
                "merge.inventory.shard-count(%s) 必须是 2 的幂：分片路由用 hash & (n-1)，非 2 的幂会算出越界下标",
                shardCount);
        require(windowMs >= 0, "merge.inventory.window-ms 必须 >= 0（当前 %s）", windowMs);
        require(maxBatch > 0, "merge.inventory.max-batch 必须 > 0（当前 %s）", maxBatch);
        require(waitTimeoutMs > windowMs,
                "merge.inventory.wait-timeout-ms(%s) 必须 > merge.inventory.window-ms(%s)，"
                        + "否则每一笔预扣都先超时后落库（= 全部 TIMEOUT_UNKNOWN）",
                waitTimeoutMs, windowMs);
        require(shardCapacity >= maxBatch,
                "merge.inventory.shard-capacity(%s) 必须 >= merge.inventory.max-batch(%s)，否则永远攒不满一批就被拒绝",
                shardCapacity, maxBatch);
        require(flushThreads >= 1, "merge.inventory.flush-threads 必须 >= 1（当前 %s）", flushThreads);
        require(drainTimeoutMs > 0, "merge.inventory.drain-timeout-ms 必须 > 0（当前 %s）", drainTimeoutMs);
    }

    /** 库存侧默认参数（{@code 64 / 5ms / 200 / 100ms / 2000 / 2 线程 / 5s}） */
    public static MergeConfig quotaDefaults() {
        return new MergeConfig(DEFAULT_SHARD_COUNT, DEFAULT_WINDOW_MS, DEFAULT_MAX_BATCH,
                DEFAULT_WAIT_TIMEOUT_MS, DEFAULT_SHARD_CAPACITY, DEFAULT_FLUSH_THREADS,
                DEFAULT_DRAIN_TIMEOUT_MS);
    }

    /** 路由掩码：{@code shardCount} 是 2 的幂，故 {@code n - 1} 即全 1 位 */
    int mask() {
        return shardCount - 1;
    }

    /**
     * 分片路由：同一个 {@code invId} 永远落同一个分片（预扣与确认共用，撞片无害且是顺序的前提）。
     *
     * <p>inv_id 已 UUID 化（uuid-biz-id 票 07），数值低位折叠不再适用：改为对字符串 hashCode
     * 做高低位折叠再取掩码——{@code h ^ (h >>> 16)}，把高位信息带进低位，避免大量 key 只差在
     * 高位时全挤进同一片。折叠是内部实现，不外泄（调用方只给 {@code invId}，不感知分片）。
     *
     * <p>用 {@code &} 而不是 {@code Math.floorMod}：掩码恒正，负 hash 也算不出负下标，且无分支。
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
