package com.betterschool.unit.config;

import com.betterschool.config.CountMergeConfig;
import com.betterschool.infrastructure.adapter.merge.MergeConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 「{@code flushThreads <= 池 − 2}」这条约束必须<b>在启动时</b>炸：引擎不认识连接池，
 * 把它放进 {@link MergeConfig} 会变成「引擎校验一个自己用不到的参数」。
 *
 * <p>纯单元测试（{@link MockEnvironment}，不起 Spring、不连库）：这条规则是装配层的，
 * 不该为了它把整个应用上下文拉起来。
 */
class CountMergeConfigTest {

    private final CountMergeConfig config = new CountMergeConfig();

    @Test
    @DisplayName("什么都没配：落 spec §2.4 的计数侧默认值（256 / 5ms / 200 / 100ms / 5000 / 4 / 5s）")
    void fallsBackToSpecDefaults() {
        MergeConfig mergeConfig = config.mergeConfig(new MockEnvironment());

        assertEquals(MergeConfig.countDefaults(), mergeConfig);
        assertEquals(4, mergeConfig.flushThreads());
    }

    @Test
    @DisplayName("配置项逐条生效：merge.count.* 覆盖默认值")
    void readsEveryProperty() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("merge.count.shard-count", "64")
                .withProperty("merge.count.max-flush-delay-millis", "3")
                .withProperty("merge.count.max-batch", "100")
                .withProperty("merge.count.wait-timeout-ms", "80")
                .withProperty("merge.count.shard-capacity", "2000")
                .withProperty("merge.count.flush-threads", "2")
                .withProperty("merge.count.drain-timeout-ms", "3000")
                .withProperty("spring.datasource.hikari.maximum-pool-size", "10");

        MergeConfig mergeConfig = config.mergeConfig(environment);

        assertEquals(64, mergeConfig.shardCount());
        assertEquals(3, mergeConfig.maxFlushDelayMillis());
        assertEquals(100, mergeConfig.maxBatch());
        assertEquals(80, mergeConfig.waitTimeoutMs());
        assertEquals(2000, mergeConfig.shardCapacity());
        assertEquals(2, mergeConfig.flushThreads());
        assertEquals(3000, mergeConfig.drainTimeoutMs());
    }

    @Test
    @DisplayName("flushThreads = 池 − 2：合法（连接池就是真并发上限，flush 不能占满）")
    void acceptsFlushThreadsAtPoolMinusTwo() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.hikari.maximum-pool-size", "10")
                .withProperty("merge.count.flush-threads", "8");

        assertEquals(8, config.mergeConfig(environment).flushThreads());
    }

    @Test
    @DisplayName("flushThreads > 池 − 2：启动即失败（否则 flush 占满连接，普通查询在 connection-timeout 上排队）")
    void rejectsFlushThreadsAbovePoolMinusTwo() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.hikari.maximum-pool-size", "10")
                .withProperty("merge.count.flush-threads", "9");

        assertThrows(IllegalStateException.class, () -> config.mergeConfig(environment));
    }

    @Test
    @DisplayName("参数自身的约束仍在引擎侧：非 2 的幂分片数照旧构造即失败")
    void stillDelegatesEngineLevelValidation() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("merge.count.shard-count", "200");

        assertThrows(IllegalArgumentException.class, () -> config.mergeConfig(environment));
    }
}
