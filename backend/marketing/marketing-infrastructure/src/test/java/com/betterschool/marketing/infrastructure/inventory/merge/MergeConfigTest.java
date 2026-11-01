package com.betterschool.marketing.infrastructure.inventory.merge;

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
    @DisplayName("默认值：64 / 5ms / 200 / 100ms / 2000 / 2 线程 / 5s（spec §2.4 库存侧）")
    void defaultsMatchSpec() {
        MergeConfig config = MergeConfig.quotaDefaults();

        assertEquals(64, config.shardCount());
        assertEquals(5, config.windowMs());
        assertEquals(200, config.maxBatch());
        assertEquals(100, config.waitTimeoutMs());
        assertEquals(2000, config.shardCapacity(), "库存资金敏感 ⇒ 容量比计数侧小（更早降级为同步单条）");
        assertEquals(2, config.flushThreads());
        assertEquals(5000, config.drainTimeoutMs());
        assertEquals(63, config.mask());
    }

    @Test
    @DisplayName("shardCount 不是 2 的幂：构造即失败（路由用 & mask，会算出越界下标）")
    void rejectsShardCountNotPowerOfTwo() {
        assertThrows(IllegalArgumentException.class,
                () -> new MergeConfig(100, 5, 200, 100, 2000, 2, 5000));
        assertThrows(IllegalArgumentException.class,
                () -> new MergeConfig(0, 5, 200, 100, 2000, 2, 5000));
    }

    @Test
    @DisplayName("waitTimeoutMs <= windowMs：构造即失败（否则必然先超时后落库）")
    void rejectsWaitTimeoutNotGreaterThanWindow() {
        assertThrows(IllegalArgumentException.class,
                () -> new MergeConfig(64, 100, 200, 100, 2000, 2, 5000));
        assertThrows(IllegalArgumentException.class,
                () -> new MergeConfig(64, 100, 200, 50, 2000, 2, 5000));
    }

    @Test
    @DisplayName("shardCapacity < maxBatch：构造即失败（永远攒不满一批就被拒）")
    void rejectsShardCapacityBelowMaxBatch() {
        assertThrows(IllegalArgumentException.class,
                () -> new MergeConfig(64, 5, 200, 100, 199, 2, 5000));
    }

    @Test
    @DisplayName("flushThreads 只校验 >= 1（上限「池 - 2」属于装配层，引擎不认识连接池）")
    void rejectsOnlyNonPositiveFlushThreads() {
        assertThrows(IllegalArgumentException.class,
                () -> new MergeConfig(64, 5, 200, 100, 2000, 0, 5000));
        // 64 线程在引擎侧合法，装配层才会按连接池拦下
        new MergeConfig(64, 5, 200, 100, 2000, 64, 5000);
    }

    @Test
    @DisplayName("drainTimeoutMs 必须 > 0：否则停机时既排不空也等不到关池")
    void rejectsNonPositiveDrainTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> new MergeConfig(64, 5, 200, 100, 2000, 2, 0));
    }

    @Test
    @DisplayName("路由：同一 key 恒落同一片，且高位参与折叠（雪花 / 分表拼接 ID 不会全挤 0 号片）")
    void foldsHighBitsSoSnowflakeIdsSpreadAcrossShards() {
        MergeConfig config = MergeConfig.quotaDefaults();

        // 低位恒定、高位递增：直接 key & mask 会全落在同一个分片
        Set<Integer> shards = new HashSet<>();
        for (long high = 1; high <= 64; high++) {
            shards.add(config.shardOf((high << 32) | 0xFFL));
        }
        assertEquals(64, shards.size(), "64 个高位不同的 ID 必须散到 64 个分片，而不是挤在 0 号片");

        // 稳定性：预扣与确认给同一个 invId，必须落到同一片（否则单飞失效、确认可能看不到已落账的 waiting）
        long invId = 9_001L;
        int first = config.shardOf(invId);
        for (int i = 0; i < 100; i++) {
            assertEquals(first, config.shardOf(invId));
            assertTrue(first >= 0 && first < config.shardCount());
        }
    }
}
