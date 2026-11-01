package com.betterschool.marketing.unit.config;

import com.betterschool.marketing.config.QuotaMergeConfig;
import com.betterschool.marketing.infrastructure.inventory.merge.MergeConfig;
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
 * 不该为了它把整个应用上下文拉起来。用例与计数侧那份（{@code CountMergeConfigTest}）同形。
 */
class QuotaMergeConfigTest {

    private final QuotaMergeConfig config = new QuotaMergeConfig();

    @Test
    @DisplayName("什么都没配：落 spec §2.4 的库存侧默认值（64 / 5ms / 200 / 100ms / 2000 / 2 / 5s）")
    void fallsBackToSpecDefaults() {
        MergeConfig mergeConfig = config.quotaMergeConfig(new MockEnvironment());

        assertEquals(MergeConfig.quotaDefaults(), mergeConfig);
        assertEquals(2, mergeConfig.flushThreads());
    }

    @Test
    @DisplayName("配置项逐条生效：merge.inventory.* 覆盖默认值")
    void readsEveryProperty() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("merge.inventory.shard-count", "256")
                .withProperty("merge.inventory.window-ms", "3")
                .withProperty("merge.inventory.max-batch", "100")
                .withProperty("merge.inventory.wait-timeout-ms", "80")
                .withProperty("merge.inventory.shard-capacity", "5000")
                .withProperty("merge.inventory.flush-threads", "4")
                .withProperty("merge.inventory.drain-timeout-ms", "3000")
                .withProperty("spring.datasource.hikari.maximum-pool-size", "10");

        MergeConfig mergeConfig = config.quotaMergeConfig(environment);

        assertEquals(256, mergeConfig.shardCount());
        assertEquals(3, mergeConfig.windowMs());
        assertEquals(100, mergeConfig.maxBatch());
        assertEquals(80, mergeConfig.waitTimeoutMs());
        assertEquals(5000, mergeConfig.shardCapacity());
        assertEquals(4, mergeConfig.flushThreads());
        assertEquals(3000, mergeConfig.drainTimeoutMs());
    }

    @Test
    @DisplayName("flushThreads = 池 − 2：合法（连接池就是真并发上限，flush 不能占满）")
    void acceptsFlushThreadsAtPoolMinusTwo() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.hikari.maximum-pool-size", "10")
                .withProperty("merge.inventory.flush-threads", "8");

        assertEquals(8, config.quotaMergeConfig(environment).flushThreads());
    }

    @Test
    @DisplayName("flushThreads > 池 − 2：启动即失败（否则 flush 占满连接，预扣在 connection-timeout 上排队）")
    void rejectsFlushThreadsAbovePoolMinusTwo() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.hikari.maximum-pool-size", "10")
                .withProperty("merge.inventory.flush-threads", "9");

        assertThrows(IllegalStateException.class, () -> config.quotaMergeConfig(environment));
    }

    @Test
    @DisplayName("参数自身的约束仍在引擎侧：非 2 的幂分片数照旧构造即失败")
    void stillDelegatesEngineLevelValidation() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("merge.inventory.shard-count", "100");

        assertThrows(IllegalArgumentException.class, () -> config.quotaMergeConfig(environment));
    }
}
