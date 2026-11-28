package com.betterschool.infrastructure.merge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 参数构造即校验（两条负例，与计数侧那份逐条对齐）。
 *
 * <p>硬约束都必须<b>在构造时</b>炸：配错了不是「慢」，而是「队列先满即拒、单批上界取不到」——那种错误在线上
 * 表现为「抽奖一直处理中」，在代码里找不到对应关系。
 *
 * <p>per-invId leader 模型下没有分片路由：{@code shardCount} 是退役兼容参数（保留 yml 键一个
 * 版本），只校验 {@code > 0}，不再要求 2 的幂。{@code maxFlushDelayMillis} 同样已退役（收集窗口
 * 删除，队列非空即发车），只校验 {@code >= 0}。也<b>没有等待超时</b>参数：调用方一直等到件落定，
 * 所以不存在「等待超时 < 收集窗口」这类配错（成立的代价是 jdbcUrl 上必须有 socketTimeout）。
 */
class MergeConfigTest {

    @Test
    @DisplayName("默认值：8(退役兼容) / 5ms / 200 / 2000 / 5s")
    void defaultsMatchSpec() {
        MergeConfig mergeConfig = MergeConfig.defaults();

        assertEquals(8, mergeConfig.shardCount());
        assertEquals(5, mergeConfig.maxFlushDelayMillis(), "退役兼容键的默认值沿用现值：不参与发车判定");
        assertEquals(200, mergeConfig.maxBatch());
        assertEquals(2000, mergeConfig.shardCapacity(), "容量默认 2000：满了就拒收，队列别长");
        assertEquals(5000, mergeConfig.drainTimeoutMs());
    }

    @Test
    @DisplayName("shardCount 是退役兼容参数：不再要求 2 的幂，只要求 > 0")
    void shardCountIsRetiredCompatibilityParameter() {
        // 100（非 2 的幂）现在合法：分片路由已退役
        assertDoesNotThrow(() -> new MergeConfig(100, 5, 200, 2000, 5000));
        // 0 仍然非法：参数还在构造器签名里，不能收垃圾值
        assertThrows(IllegalArgumentException.class,
                () -> new MergeConfig(0, 5, 200, 2000, 5000));
    }

    @Test
    @DisplayName("shardCapacity < maxBatch：构造即失败（队列先满即拒，单批上界永远取不到）")
    void rejectsShardCapacityBelowMaxBatch() {
        assertThrows(IllegalArgumentException.class,
                () -> new MergeConfig(64, 5, 200, 199, 5000));
    }

    @Test
    @DisplayName("drainTimeoutMs 必须 > 0：否则停机时既排不空也等不到残余回填的截止")
    void rejectsNonPositiveDrainTimeout() {
        assertThrows(IllegalArgumentException.class,
                () -> new MergeConfig(64, 5, 200, 2000, 0));
    }
}
