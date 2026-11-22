package com.betterschool.unit.config;

import com.betterschool.config.MergeEngineConfig;
import com.betterschool.infrastructure.merge.MergeConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 「{@code shardCount <= 池 − 2}」这条约束必须<b>在启动时</b>炸：引擎不认识连接池，
 * 把它放进 {@link MergeConfig} 会变成「引擎校验一个自己用不到的参数」。
 * per-shard 常驻线程模型下 flush 并发 = shardCount。
 *
 * <p>纯单元测试（{@link MockEnvironment}，不起 Spring、不连库）：这条规则是装配层的，
 * 不该为了它把整个应用上下文拉起来。用例与计数侧那份（{@code CountMergeConfigTest}）同形。
 */
class MergeEngineConfigTest {

    private final MergeEngineConfig config = new MergeEngineConfig();

    @Test
    @DisplayName("什么都没配：落默认值（8 / 5ms / 200 / 100ms / 2000 / 5s）")
    void fallsBackToSpecDefaults() {
        MergeConfig mergeConfig = config.mergeConfig(new MockEnvironment());

        assertEquals(MergeConfig.defaults(), mergeConfig);
        assertEquals(8, mergeConfig.shardCount());
    }

    @Test
    @DisplayName("配置项逐条生效：merge.batch.* 覆盖默认值")
    void readsEveryProperty() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("merge.batch.shard-count", "256")
                .withProperty("merge.batch.max-flush-delay-millis", "3")
                .withProperty("merge.batch.max-batch", "100")
                .withProperty("merge.batch.wait-timeout-ms", "80")
                .withProperty("merge.batch.shard-capacity", "5000")
                .withProperty("merge.batch.drain-timeout-ms", "3000")
                .withProperty("spring.datasource.hikari.maximum-pool-size", "512");

        MergeConfig mergeConfig = config.mergeConfig(environment);

        assertEquals(256, mergeConfig.shardCount());
        assertEquals(3, mergeConfig.maxFlushDelayMillis());
        assertEquals(100, mergeConfig.maxBatch());
        assertEquals(80, mergeConfig.waitTimeoutMs());
        assertEquals(5000, mergeConfig.shardCapacity());
        assertEquals(3000, mergeConfig.drainTimeoutMs());
    }

    @Test
    @DisplayName("shardCount = 池 − 2：合法（连接池就是真并发上限，flush 不能占满）")
    void acceptsShardCountAtPoolMinusTwo() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.hikari.maximum-pool-size", "10")
                .withProperty("merge.batch.shard-count", "8");

        assertEquals(8, config.mergeConfig(environment).shardCount());
    }

    @Test
    @DisplayName("shardCount > 池 − 2：启动即失败（per-shard 线程下 flush 并发 = shardCount，"
            + "占满连接则业务查询在 connection-timeout 上排队）")
    void rejectsShardCountAbovePoolMinusTwo() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.hikari.maximum-pool-size", "10")
                .withProperty("merge.batch.shard-count", "16");

        assertThrows(IllegalStateException.class, () -> config.mergeConfig(environment));
    }

    @Test
    @DisplayName("参数自身的约束仍在引擎侧：非 2 的幂分片数照旧构造即失败")
    void stillDelegatesEngineLevelValidation() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("merge.batch.shard-count", "100");

        assertThrows(IllegalArgumentException.class, () -> config.mergeConfig(environment));
    }
}
