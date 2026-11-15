package com.betterschool.infrastructure.adapter.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A7：参数构造即校验（{@code MergeConfigTest}）。
 *
 * <p>三条硬约束都必须<b>在构造时</b>炸：配错了不是「慢」，而是「每个请求都先超时后落库」
 * 或「永远攒不满一批」——那种错误在压测里表现为数字难看，在代码里找不到对应关系。
 */
class MergeConfigTest {

    @Test
    @DisplayName("默认值：256 / 5ms / 200 / 100ms / 5000 / 4 线程 / 5s（spec §2.4 计数侧）")
    void defaultsMatchSpec() {
        MergeConfig config = MergeConfig.countDefaults();

        assertEquals(256, config.shardCount());
        assertEquals(5, config.maxFlushDelayMillis());
        assertEquals(200, config.maxBatch());
        assertEquals(100, config.waitTimeoutMs());
        assertEquals(5000, config.shardCapacity());
        assertEquals(4, config.flushThreads());
        assertEquals(5000, config.drainTimeoutMs());
        assertEquals(255, config.mask());
    }

    @Test
    @DisplayName("shardCount 不是 2 的幂：构造即失败（路由用 & mask，会算出越界下标）")
    void rejectsShardCountNotPowerOfTwo() {
        assertThrows(IllegalArgumentException.class,
                () -> new MergeConfig(200, 5, 200, 100, 5000, 4, 5000));
        assertThrows(IllegalArgumentException.class,
                () -> new MergeConfig(0, 5, 200, 100, 5000, 4, 5000));
    }

    @Test
    @DisplayName("waitTimeoutMs <= maxFlushDelayMillis：构造即失败（否则必然先超时后落库）")
    void rejectsWaitTimeoutNotGreaterThanWindow() {
        assertThrows(IllegalArgumentException.class,
                () -> new MergeConfig(256, 100, 200, 100, 5000, 4, 5000));
        assertThrows(IllegalArgumentException.class,
                () -> new MergeConfig(256, 100, 200, 50, 5000, 4, 5000));
    }

    @Test
    @DisplayName("shardCapacity < maxBatch：构造即失败（永远攒不满一批就被拒）")
    void rejectsShardCapacityBelowMaxBatch() {
        assertThrows(IllegalArgumentException.class,
                () -> new MergeConfig(256, 5, 200, 100, 199, 4, 5000));
    }

    @Test
    @DisplayName("flushThreads 只校验 >= 1（上限「池 - 2」属于装配层，引擎不认识连接池）")
    void rejectsOnlyNonPositiveFlushThreads() {
        assertThrows(IllegalArgumentException.class,
                () -> new MergeConfig(256, 5, 200, 100, 5000, 0, 5000));
        // 64 线程在引擎侧合法，装配层才会按连接池拦下
        new MergeConfig(256, 5, 200, 100, 5000, 64, 5000);
    }

    @Test
    @DisplayName("drainTimeoutMs 必须 > 0：否则停机时既排不空也等不到关池")
    void rejectsNonPositiveDrainTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> new MergeConfig(256, 5, 200, 100, 5000, 4, 0));
    }

    @Test
    @DisplayName("路由：同一 key 恒落同一片，且高位参与折叠（UUID 高低位都参与，不会全挤 0 号片）")
    void foldsHighBitsSoKeysSpreadAcrossShards() {
        MergeConfig config = MergeConfig.countDefaults();

        // 同前缀、尾部递增：直接 key & mask（只看低位）会全落在同一个分片
        Set<Integer> shards = new HashSet<>();
        for (int tail = 1; tail <= 64; tail++) {
            shards.add(config.shardOf(String.format("9f8e7d6c-5b4a-3210-ffff-00000000%04x", tail)));
        }
        assertEquals(64, shards.size(), "64 个尾部不同的 ID 必须散到 64 个分片，而不是挤在 0 号片");

        // 稳定性：同一个 key 反复路由结果不变（否则同一行会被两个分片各自合并，行锁重新排队）
        String postId = "8c1f0e2a-6d4b-4b7e-9a3c-5f2b1d0c9e77";
        int first = config.shardOf(postId);
        for (int i = 0; i < 100; i++) {
            assertEquals(first, config.shardOf(postId));
            assertTrue(first >= 0 && first < config.shardCount());
        }
    }
}
