package com.betterschool.infrastructure.merge;

import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * 引擎参数（不可变，构造即校验——配错要在启动瞬间炸掉，而不是上线后才暴露）。
 *
 * <p><b>per-invId leader 模型</b>：引擎没有分片数组、也没有常驻 flush 线程——flush 并发 =
 * 并发热点 leader 数，天然受连接池约束，所以「shardCount ≤ 池 − 2」的装配校验随后台线程模型退役。
 *
 * <p><b>刻意没有「等待超时」参数</b>：调用方一直等到件落定（成功或确定失败）为止，不存在
 * 「结果未知」的中间态。这条选择成立有一个前提——底层 SQL 必须有读超时
 * （Connector/J 的 {@code socketTimeout}，配在 sharding.yaml 的 jdbcUrl 上）。否则一句永远不返回的
 * {@code UPDATE} 会让所有等待者一起挂死，且没有任何信号。
 *
 * <p>本类只校验三条自身约束：{@code shardCount > 0}、{@code shardCapacity >= maxBatch}、
 * {@code drainTimeoutMs > 0}。
 *
 * @param shardCount          【退役兼容参数】历史分片数的 yml 键（{@code merge.batch.shard-count}）
 *                            保留一个版本，读进来只存不用——不再参与路由，也不再绑定任何线程；
 *                            下一版本随 yml 键一并删除
 * @param maxFlushDelayMillis 【退役兼容参数】历史「leader 收集窗口」的 yml 键
 *                            （{@code merge.batch.max-flush-delay-millis}）：保留一个版本，
 *                            <b>读入不参与发车判定</b>。收集窗口已整体删除——队列非空即发车，
 *                            收集窗就是落库耗时本身（决策与结构安全性论证见
 *                            {@code backend/docs/architecture/merge-window-removal-spec.md}）；
 *                            下一版本随 yml 键一并删除
 * @param maxBatch            单次 flush 最多取走的件数（单条多行 SQL 的尺寸上界）
 * @param shardCapacity       单个 invId Group 的缓冲上限，满即拒收（直接 {@code FAILED}，调用方重试）
 * @param drainTimeoutMs      停机排空上限
 */
@EqualsAndHashCode
@ToString
public final class MergeConfig {

    /** 历史默认分片数：仅为 defaults() 数值口径兼容而保留，不再对应任何结构 */
    private static final int DEFAULT_SHARD_COUNT = 8;
    private static final int DEFAULT_WINDOW_MS = 5;
    private static final int DEFAULT_MAX_BATCH = 200;
    private static final int DEFAULT_SHARD_CAPACITY = 2000;
    private static final long DEFAULT_DRAIN_TIMEOUT_MS = 5_000;

    private final int shardCount;
    private final int maxFlushDelayMillis;
    private final int maxBatch;
    private final int shardCapacity;
    private final long drainTimeoutMs;

    public MergeConfig(int shardCount,
                       int maxFlushDelayMillis,
                       int maxBatch,
                       int shardCapacity,
                       long drainTimeoutMs) {
        require(shardCount > 0,
                "merge.batch.shard-count 必须 > 0（当前 %s）", shardCount);
        require(maxFlushDelayMillis >= 0,
                "merge.batch.max-flush-delay-millis 必须 >= 0（当前 %s）", maxFlushDelayMillis);
        require(maxBatch > 0, "merge.batch.max-batch 必须 > 0（当前 %s）", maxBatch);
        require(shardCapacity >= maxBatch,
                "merge.batch.shard-capacity(%s) 必须 >= max-batch(%s)，否则单批上界永远取不到、"
                        + "队列先满即拒", shardCapacity, maxBatch);
        require(drainTimeoutMs > 0, "merge.batch.drain-timeout-ms 必须 > 0（当前 %s）", drainTimeoutMs);

        this.shardCount = shardCount;
        this.maxFlushDelayMillis = maxFlushDelayMillis;
        this.maxBatch = maxBatch;
        this.shardCapacity = shardCapacity;
        this.drainTimeoutMs = drainTimeoutMs;
    }

    /** 默认参数：{@code 8(退役兼容) / 5ms / 200 / 2000 / 5s} */
    public static MergeConfig defaults() {
        return new MergeConfig(DEFAULT_SHARD_COUNT, DEFAULT_WINDOW_MS, DEFAULT_MAX_BATCH,
                DEFAULT_SHARD_CAPACITY, DEFAULT_DRAIN_TIMEOUT_MS);
    }

    /** 【退役兼容】历史分片数：不再参与路由、不再绑定线程，仅为 yml 键一个版本兼容而保留 */
    public int shardCount() {
        return shardCount;
    }

    /** 【退役兼容】历史收集窗口：不参与发车判定（队列非空即发），仅为 yml 键一个版本兼容而保留 */
    public int maxFlushDelayMillis() {
        return maxFlushDelayMillis;
    }

    public int maxBatch() {
        return maxBatch;
    }

    public int shardCapacity() {
        return shardCapacity;
    }

    public long drainTimeoutMs() {
        return drainTimeoutMs;
    }

    private static void require(boolean condition, String template, Object... args) {
        if (!condition) {
            throw new IllegalArgumentException("[配置错误] " + String.format(template, args));
        }
    }
}
