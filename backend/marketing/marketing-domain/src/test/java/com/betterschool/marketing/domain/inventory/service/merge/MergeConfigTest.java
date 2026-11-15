package com.betterschool.marketing.domain.inventory.service.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A7：参数构造即校验（库存侧那一份，四条负例与计数侧逐条对齐）。
 *
 * <p>三条硬约束都必须<b>在构造时</b>炸：配错了不是「慢」，而是「每一笔预扣都先超时后落库
 * （= 全部 {@code TIMEOUT_UNKNOWN}，用户看到「处理中」）」或「永远攒不满一批」——那种错误在线上表现为
 * 「抽奖一直处理中」，在代码里找不到对应关系。
 */
class MergeConfigTest {

    @Test
    @DisplayName("默认值：8 / 5ms / 200 / 100ms / 2000 / 5s（per-shard 线程模型下 flush 并发 = shardCount，默认取「池 − 2」下限）")
    void defaultsMatchSpec() {
        MergeConfig config = MergeConfig.quotaDefaults();

        assertEquals(8, config.shardCount());
        assertEquals(5, config.maxFlushDelayMillis());
        assertEquals(200, config.maxBatch());
        assertEquals(100, config.waitTimeoutMs());
        assertEquals(2000, config.shardCapacity(), "库存资金敏感 ⇒ 容量比计数侧小（更早降级为同步单条）");
        assertEquals(5000, config.drainTimeoutMs());
        assertEquals(7, config.mask());
    }

    @Test
    @DisplayName("shardCount 不是 2 的幂：构造即失败（路由用 & mask，会算出越界下标）")
    void rejectsShardCountNotPowerOfTwo() {
        assertThrows(IllegalArgumentException.class,
                () -> new MergeConfig(100, 5, 200, 100, 2000, 5000));
        assertThrows(IllegalArgumentException.class,
                () -> new MergeConfig(0, 5, 200, 100, 2000, 5000));
    }

    @Test
    @DisplayName("waitTimeoutMs <= maxFlushDelayMillis：构造即失败（否则必然先超时后落库）")
    void rejectsWaitTimeoutNotGreaterThanWindow() {
        assertThrows(IllegalArgumentException.class,
                () -> new MergeConfig(64, 100, 200, 100, 2000, 5000));
        assertThrows(IllegalArgumentException.class,
                () -> new MergeConfig(64, 100, 200, 50, 2000, 5000));
    }

    @Test
    @DisplayName("shardCapacity < maxBatch：构造即失败（永远攒不满一批就被拒）")
    void rejectsShardCapacityBelowMaxBatch() {
        assertThrows(IllegalArgumentException.class,
                () -> new MergeConfig(64, 5, 200, 100, 199, 5000));
    }

    @Test
    @DisplayName("drainTimeoutMs 必须 > 0：否则停机时既排不空也等不到线程退出")
    void rejectsNonPositiveDrainTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> new MergeConfig(64, 5, 200, 100, 2000, 0));
    }

    @Test
    @DisplayName("路由：同一 key 恒落同一片，且不同 invId 散开多片（hashCode 折叠后不会全挤 0 号片）")
    void spreadsDistinctKeysAcrossShards() {
        MergeConfig config = MergeConfig.quotaDefaults();

        // uuid-biz-id 票 07：inv_id 已 UUID 字符串化，路由输入从 long 改字符串 hashCode 折叠。
        // 64 个互异 key 至少散进一半以上分片（hashCode 均匀性下 64 个全同片的概率可忽略），
        // 关键反例是「全挤在 0 号片」。
        // 注意：key 不能只差末尾几位十进制数字——31 进制 hash 对 2 的幂模数只有低位数字有效权重，
        // 那种 key 折叠后只会落进极少数片，是 key 造得退化，不是路由的锅。
        // 高低位两段都带序号，模拟真实 UUID 的熵分布。
        Set<Integer> shards = new HashSet<>();
        for (int i = 0; i < 64; i++) {
            shards.add(config.shardOf("%08x-0000-4000-8000-%012x".formatted(i, i)));
        }
        assertTrue(shards.size() >= 4,
                "64 个不同的 invId 至少要散进一半分片（默认 8 片），实际 " + shards.size());

        // 稳定性：预扣与确认给同一个 invId，必须落到同一片（否则单飞失效、确认可能看不到已落账的 waiting）
        String invId = "00000000-0000-4000-8000-000000009001";
        int first = config.shardOf(invId);
        for (int i = 0; i < 100; i++) {
            assertEquals(first, config.shardOf(invId));
            assertTrue(first >= 0 && first < config.shardCount());
        }
    }
}
