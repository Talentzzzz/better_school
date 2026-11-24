package com.betterschool.config;

import com.betterschool.infrastructure.merge.MergeConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 合并引擎的参数装配。
 *
 * <p>本类只负责把 {@code merge.batch.*} 读成一个不可变的 {@link MergeConfig}。
 * {@code MergeEngine} <b>不是 Spring 托管对象</b>——实例数与生命周期归各写入组件，
 * 由写入组件构造时注入本 Bean；一个用途一个引擎，共用这一份参数。
 *
 * <p>per-invId leader 模型下没有常驻 flush 线程：flush 并发 = 并发热点 leader 数，
 * 天然受共享的 {@code hikari.maximum-pool-size} 约束——「shardCount ≤ 池 − 2」的装配校验
 * 随后台线程模型一并退役。{@code merge.batch.shard-count} 键保留一个版本兼容，读进来
 * 只存不用（不再参与路由、不绑定任何线程）。
 */
@Slf4j
@Configuration
public class MergeEngineConfig {

    /**
     * 合并引擎参数。未配置的项落 {@link MergeConfig#defaults()}（5ms 窗口 / 200 批 / 2000 组容量 /
     * 5s 排空）。没有「等待超时」——等待的终点由底层 SQL 的 socketTimeout 保证。
     */
    @Bean
    public MergeConfig mergeConfig(Environment environment) {
        MergeConfig defaults = MergeConfig.defaults();
        MergeConfig config = new MergeConfig(
                environment.getProperty("merge.batch.shard-count", Integer.class, defaults.shardCount()),
                environment.getProperty("merge.batch.max-flush-delay-millis", Integer.class, defaults.maxFlushDelayMillis()),
                environment.getProperty("merge.batch.max-batch", Integer.class, defaults.maxBatch()),
                environment.getProperty("merge.batch.shard-capacity", Integer.class, defaults.shardCapacity()),
                environment.getProperty("merge.batch.drain-timeout-ms", Long.class, defaults.drainTimeoutMs()));

        log.info("合并引擎装配（per-invId leader 模型）：merge.batch.shard-count={}（退役兼容键，读入不用），"
                        + "收集窗口={}ms，flush 并发 = 并发热点 leader 数（受连接池自然约束）",
                config.shardCount(), config.maxFlushDelayMillis());
        return config;
    }
}
