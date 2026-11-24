package com.betterschool.config;

import com.betterschool.infrastructure.merge.count.MergeConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 计数合并器的参数装配。
 *
 * <p>per-invId leader 模型下没有常驻 flush 线程：flush 并发 = 并发热点 leader 数，
 * 天然受共享的 {@code hikari.maximum-pool-size} 约束——「shardCount ≤ 池 − 2」的装配校验
 * 随后台线程模型一并退役。{@code merge.count.shard-count} 键保留一个版本兼容，读进来
 * 只存不用（不再参与路由、不绑定任何线程）。
 *
 * <p>调参看 {@code MergeMetrics} 的 {@code flushed ÷ batches}（合并比，期望值 = 到达速率 ×
 * 落库耗时）、{@code activeGroups}（活跃热点组数）与 {@code handovers}（接班次数）。
 * {@code merge.count.max-flush-delay-millis} 已退役：队列非空即发车，没有收集窗口。
 */
@Slf4j
@Configuration
public class CountMergeConfig {

    /**
     * 计数侧合并参数。未配置的项落 {@link MergeConfig#countDefaults()}（200 批 / 100ms 等待 /
     * 2000 组容量 / 5s 排空；窗口参数已退役，默认值仅为 yml 键兼容保留）。
     *
     * <p><b>方法名不能叫 {@code countMergeConfig}</b>：{@code @Configuration} 类本身注册的 bean 名是
     * 类名首字母小写（{@code countMergeConfig}），与同名 {@code @Bean} 方法撞车；Spring Boot 默认
     * 关闭 bean 覆盖，上下文会直接起不来。合并器实例（{@code CountMerger}）是 {@code @Component}，
     * 参数从这里注入。
     */
    @Bean
    public MergeConfig mergeConfig(Environment environment) {
        MergeConfig defaults = MergeConfig.countDefaults();
        MergeConfig config = new MergeConfig(
                environment.getProperty("merge.count.shard-count", Integer.class, defaults.shardCount()),
                environment.getProperty("merge.count.max-flush-delay-millis", Integer.class, defaults.maxFlushDelayMillis()),
                environment.getProperty("merge.count.max-batch", Integer.class, defaults.maxBatch()),
                environment.getProperty("merge.count.wait-timeout-ms", Integer.class, defaults.waitTimeoutMs()),
                environment.getProperty("merge.count.shard-capacity", Integer.class, defaults.shardCapacity()),
                environment.getProperty("merge.count.drain-timeout-ms", Long.class, defaults.drainTimeoutMs()));

        log.info("计数合并器装配（per-invId leader 模型）：merge.count.shard-count={}、"
                        + "merge.count.max-flush-delay-millis={}（均为退役兼容键，读入不用），"
                        + "发车条件 = 队列非空即发（无收集窗口），flush 并发 = 并发热点 leader 数（受连接池自然约束）",
                config.shardCount(), config.maxFlushDelayMillis());
        return config;
    }
}
