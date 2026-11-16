package com.betterschool.config;

import com.betterschool.infrastructure.adapter.merge.MergeConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 计数合并器的参数装配与「分片数 × 连接池」校验（spec §2.4）。
 *
 * <p><b>为什么 {@code shardCount <= 池 − 2} 不在 {@link MergeConfig} 里校验</b>：
 * 连接池大小属于应用装配层，引擎不认识 Hikari。这里是<b>共享的 {@code hikari.maximum-pool-size}
 * + 虚拟线程</b>，连接池就是真并发上限，flush 占满连接后普通查询会在
 * {@code connection-timeout: 3000} 上排队——所以引擎只校验 2 的幂等自身约束，
 * 并发上限在启动这里卡死。
 *
 * <p>per-shard 常驻线程模型（每分片一条 {@code count-merge-flush-*}）下 flush 并发 = shardCount，
 * 不变量「flush 并发 <= 池 − 2」的校验对象从旧的 flushThreads 换成 shardCount。
 *
 * <p>两个数一起打进启动日志：调参时先看这两个数，再看 {@code MergeMetrics} 的
 * {@code flushed ÷ batches}。
 */
@Slf4j
@Configuration
public class CountMergeConfig {

    /** 连接池默认值与 application.yml 的 {@code spring.datasource.hikari.maximum-pool-size} 对齐 */
    private static final int DEFAULT_POOL_SIZE = 10;

    /** 留给普通请求的连接数：flush 再多就只能抢业务查询的连接 */
    private static final int RESERVED_POOL_CONNECTIONS = 2;

    /**
     * 计数侧合并参数。未配置的项落 {@link MergeConfig#countDefaults()}（8 / 5ms / 200 / 100ms /
     * 2000 / 5s）。
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

        int poolSize = environment.getProperty("spring.datasource.hikari.maximum-pool-size",
                Integer.class, DEFAULT_POOL_SIZE);
        int maxShardCount = poolSize - RESERVED_POOL_CONNECTIONS;
        if (config.shardCount() > maxShardCount) {
            throw new IllegalStateException(String.format(
                    "[配置错误] merge.count.shard-count(%s) 必须 <= 连接池上限 - %s（当前池 %s，上限 %s）："
                            + "per-shard 常驻线程模型下 flush 并发 = shardCount，flush 占满连接后普通查询"
                            + "会在 connection-timeout 上排队",
                    config.shardCount(), RESERVED_POOL_CONNECTIONS, poolSize, maxShardCount));
        }

        log.info("计数合并器装配：merge.count.shard-count={}（= flush 常驻线程数），"
                        + "连接池上限(hikari.maximum-pool-size)={}，校验口径 shard-count <= 池 - {}（上限 {}）",
                config.shardCount(), poolSize, RESERVED_POOL_CONNECTIONS, maxShardCount);
        return config;
    }
}
