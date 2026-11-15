package cn.inventory.domain.stock.model.valobj;

import lombok.Getter;
import lombok.ToString;

/**
 * 合并扣减器配置（不可变，构造即校验）
 *
 * <p><b>这些参数互相有约束，配错了不是「慢」而是直接失效</b>，所以校验放在构造器里：
 * 配置错误必须在启动瞬间炸掉，而不是压测跑到一半才发现每个请求都超时。
 *
 * <p>参数之间的三组硬约束（都有实测依据）：
 * <ol>
 *   <li><b>分片数必须是 2 的幂</b>：路由用 {@code skuId & mask}，非 2 的幂会算出越界下标；</li>
 *   <li><b>flushThreads 必须等于连接池大小</b>：大于池 → 线程抢连接排队（256 线程抢 20 连接时
 *       多热点直接掉到 7.4k）；小于池 → 连接闲置，天花板 = 线程数 ÷ 单次 flush 耗时
 *       （8 线程实测卡在 12.4k，正好 100% 跑满）。这条在启动层配合池大小一起校验；</li>
 *   <li><b>waitTimeoutMs 必须大于 maxFlushDelayMillis</b>：否则请求必然「先超时、后落库」，
 *       TIMEOUT 计数失去意义，用户还会重复下单。</li>
 * </ol>
 */
@Getter
@ToString
public class MergeConfigVO {

    /** 分片数（= 缓冲区个数），必须 2 的幂；建议 ≈ 峰值并发热点数 */
    private final int shardCount;

    /** 合并窗口：分片首个请求排定 maxFlushDelayMillis 后 flush，攒满可提前触发 */
    private final int maxFlushDelayMillis;

    /** 单次 flush 最多取走的请求数 */
    private final int maxBatch;

    /** 调用方最长等待（含排队 + 落库），超时返回「结果未知」 */
    private final int waitTimeoutMs;

    /** 单分片缓冲区上限，满了直接拒绝（入口背压） */
    private final int shardCapacity;

    /** flush 执行池大小，必须 = 连接池大小 */
    private final int flushThreads;

    /** 售罄短路：整批都不够时一次 SELECT 判定，不做二分拆批 */
    private final boolean soldoutShortcut;

    /** 停机 / 重置前排空在途请求的等待上限 */
    private final long drainTimeoutMs;

    public MergeConfigVO(int shardCount,
                         int maxFlushDelayMillis,
                         int maxBatch,
                         int waitTimeoutMs,
                         int shardCapacity,
                         int flushThreads,
                         boolean soldoutShortcut,
                         long drainTimeoutMs) {
        require(shardCount > 0, "merge.shard-count 必须 > 0（当前 %s）", shardCount);
        require(Integer.bitCount(shardCount) == 1,
                "merge.shard-count(%s) 必须是 2 的幂：分片路由用 skuId & (n-1)，非 2 的幂会算出越界下标", shardCount);
        require(maxFlushDelayMillis >= 0, "merge.max-flush-delay-millis 必须 >= 0（当前 %s）", maxFlushDelayMillis);
        require(maxBatch > 0, "merge.max-batch 必须 > 0（当前 %s）", maxBatch);
        require(waitTimeoutMs > maxFlushDelayMillis,
                "merge.wait-timeout-ms(%s) 必须 > merge.max-flush-delay-millis(%s)，否则请求必然先超时后落库", waitTimeoutMs, maxFlushDelayMillis);
        require(shardCapacity >= maxBatch,
                "merge.shard-capacity(%s) 必须 >= merge.max-batch(%s)，否则永远攒不满一批就被拒绝", shardCapacity, maxBatch);
        require(flushThreads > 0, "merge.flush-threads 必须 > 0（当前 %s）", flushThreads);
        require(drainTimeoutMs > 0, "merge.drain-timeout-ms 必须 > 0（当前 %s）", drainTimeoutMs);

        this.shardCount = shardCount;
        this.maxFlushDelayMillis = maxFlushDelayMillis;
        this.maxBatch = maxBatch;
        this.waitTimeoutMs = waitTimeoutMs;
        this.shardCapacity = shardCapacity;
        this.flushThreads = flushThreads;
        this.soldoutShortcut = soldoutShortcut;
        this.drainTimeoutMs = drainTimeoutMs;
    }

    /** 路由掩码：{@code skuId & mask} 即分片下标（因为 shardCount 是 2 的幂） */
    public int mask() {
        return shardCount - 1;
    }

    /**
     * 路由：同一个 SKU 永远落同一个分片。
     *
     * <p>用 {@code &} 而不是 {@code Math.floorMod}：skuId 非负时两者等价，
     * 而 {@code &} 是两条指令、无分支；负 skuId 也不会算出负下标（mask 恒正）。
     */
    public int shardOf(long skuId) {
        return (int) (skuId & mask());
    }

    private static void require(boolean condition, String template, Object... args) {
        if (!condition) {
            throw new IllegalArgumentException("[配置错误] " + String.format(template, args));
        }
    }
}
