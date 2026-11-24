package com.betterschool.unit.config;

import com.betterschool.config.CountMergeConfig;
import com.betterschool.infrastructure.merge.count.MergeConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * 装配层只负责把 {@code merge.count.*} 读成不可变的 {@link MergeConfig}。
 *
 * <p>per-invId leader 模型下没有常驻 flush 线程：flush 并发 = 并发热点 leader 数，天然受连接池
 * 约束——「shardCount ≤ 池 − 2」的装配校验随后台线程模型一并退役。{@code shard-count} 键保留
 * 一个版本兼容，读进来只存不用（不再参与路由）。
 *
 * <p>纯单元测试（{@link MockEnvironment}，不起 Spring、不连库）。
 */
class CountMergeConfigTest {

    private final CountMergeConfig config = new CountMergeConfig();

    @Test
    @DisplayName("什么都没配：落计数侧默认值（5ms 窗口 / 200 批 / 100ms 等待 / 2000 容量 / 5s 排空）")
    void fallsBackToEngineDefaults() {
        MergeConfig mergeConfig = config.mergeConfig(new MockEnvironment());

        assertEquals(MergeConfig.countDefaults(), mergeConfig);
        assertEquals(8, mergeConfig.shardCount(), "退役兼容参数的默认值保持不变");
    }

    @Test
    @DisplayName("配置项逐条生效：merge.count.* 覆盖默认值（shard-count 读入不用，任意值都合法）")
    void readsEveryProperty() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("merge.count.shard-count", "4")
                .withProperty("merge.count.max-flush-delay-millis", "3")
                .withProperty("merge.count.max-batch", "100")
                .withProperty("merge.count.wait-timeout-ms", "80")
                .withProperty("merge.count.shard-capacity", "2000")
                .withProperty("merge.count.drain-timeout-ms", "3000");

        MergeConfig mergeConfig = config.mergeConfig(environment);

        assertEquals(4, mergeConfig.shardCount());
        assertEquals(3, mergeConfig.maxFlushDelayMillis());
        assertEquals(100, mergeConfig.maxBatch());
        assertEquals(80, mergeConfig.waitTimeoutMs());
        assertEquals(2000, mergeConfig.shardCapacity());
        assertEquals(3000, mergeConfig.drainTimeoutMs());
    }

    @Test
    @DisplayName("shard-count 任意值都不再触发装配校验（分片路由已退役）")
    void acceptsAnyShardCountWithoutPoolValidation() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("merge.count.shard-count", "200")
                .withProperty("spring.datasource.hikari.maximum-pool-size", "10");

        assertDoesNotThrow(() -> config.mergeConfig(environment));
    }
}
