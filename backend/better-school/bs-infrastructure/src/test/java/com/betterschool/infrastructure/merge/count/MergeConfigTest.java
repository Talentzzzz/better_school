package com.betterschool.infrastructure.merge.count;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 参数构造即校验（与通用引擎那份逐条对齐）。
 *
 * <p>硬约束都必须<b>在构造时</b>炸：配错了不是「慢」，而是「每个请求都会撞超时」或
 * 「永远攒不满一批」——那种错误在压测里表现为数字难看，在代码里找不到对应关系。
 *
 * <p>per-invId leader 模型下没有分片路由：{@code shardCount} 是退役兼容参数（保留 yml 键一个
 * 版本），只校验 {@code > 0}，不再要求 2 的幂。
 */
class MergeConfigTest {

    @Test
    @DisplayName("默认值：8(退役兼容) / 5ms / 200 / 100ms / 2000 / 5s")
    void defaultsMatchSpec() {
        MergeConfig config = MergeConfig.countDefaults();

        assertEquals(8, config.shardCount());
        assertEquals(5, config.maxFlushDelayMillis(), "退役兼容键的默认值沿用现值：不参与发车判定");
        assertEquals(200, config.maxBatch());
        assertEquals(100, config.waitTimeoutMs());
        assertEquals(2000, config.shardCapacity());
        assertEquals(5000, config.drainTimeoutMs());
    }

    @Test
    @DisplayName("shardCount 是退役兼容参数：不再要求 2 的幂，只要求 > 0")
    void shardCountIsRetiredCompatibilityParameter() {
        // 200（非 2 的幂）现在合法：分片路由已退役
        assertDoesNotThrow(() -> new MergeConfig(200, 5, 200, 100, 5000, 5000));
        // 0 仍然非法：参数还在构造器签名里，不能收垃圾值
        assertThrows(IllegalArgumentException.class,
                () -> new MergeConfig(0, 5, 200, 100, 5000, 5000));
    }

    @Test
    @DisplayName("waitTimeoutMs <= 0：构造即失败（等终态没有上限等于没有超时出口）")
    void rejectsNonPositiveWaitTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> new MergeConfig(8, 5, 200, 0, 5000, 5000));
        assertThrows(IllegalArgumentException.class,
                () -> new MergeConfig(8, 5, 200, -1, 5000, 5000));
    }

    @Test
    @DisplayName("等待预算与退役的窗口键正交：窗口给多大都不再牵制 waitTimeoutMs")
    void waitTimeoutIsOrthogonalToRetiredWindow() {
        // 窗口键（第 2 参）远大于等待预算也合法：窗口已不参与发车判定
        assertDoesNotThrow(() -> new MergeConfig(8, 5_000, 200, 100, 5000, 5000));
    }

    @Test
    @DisplayName("shardCapacity < maxBatch：构造即失败（永远攒不满一批就被拒）")
    void rejectsShardCapacityBelowMaxBatch() {
        assertThrows(IllegalArgumentException.class,
                () -> new MergeConfig(8, 5, 200, 100, 199, 5000));
    }

    @Test
    @DisplayName("drainTimeoutMs 必须 > 0：否则停机时既排不空也等不到残余回填的截止")
    void rejectsNonPositiveDrainTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> new MergeConfig(8, 5, 200, 100, 5000, 0));
    }
}
