package com.betterschool.config;

import com.betterschool.infrastructure.merge.MergeConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 合并引擎的参数装配与「分片数 × 连接池」校验。
 *
 * <p>本类只负责把 {@code merge.batch.*} 读成一个不可变的 {@link MergeConfig}，
 * 并在启动瞬间卡住它与连接池的矛盾。{@code MergeEngine} <b>不是 Spring 托管对象</b>——它带一组常驻线程，
 * 实例数与生命周期归各写入组件，由写入组件构造时注入本 Bean；一个用途一个引擎，共用这一份参数。
 *
 * <p><b>口径</b>：{@code shardCount} 是<b>单引擎</b>的 flush 线程数。引擎实例变多时，
 * 总 flush 并发 = 引擎数 × shardCount，本校验只保证单引擎不越界，实例数由装配方自己数着加。
 *
 * <p>为什么 {@code shardCount <= 池 − 2} 不在 {@link MergeConfig} 里校验：连接池属于应用装配层，
 * 引擎不认识 Hikari。这里是共享的 {@code hikari.maximum-pool-size} + 虚拟线程，连接池就是真并发上限，
 * flush 占满连接后普通查询会在 {@code connection-timeout} 上排队——所以引擎只校验 2 的幂等自身约束，
 * 并发上限在启动这里卡死。
 *
 * <p>per-shard 常驻线程模型（每分片一条 {@code merge-flush-*}）下 flush 并发 = shardCount，
 * 不变量「flush 并发 <= 池 − 2」的校验对象就是 shardCount。
 */
@Slf4j
@Configuration
public class MergeEngineConfig {

    /** 连接池默认值与 application.yml 的 {@code spring.datasource.hikari.maximum-pool-size} 对齐 */
    private static final int DEFAULT_POOL_SIZE = 10;

    /** 留给普通请求的连接数：flush 再多就只能抢业务查询的连接 */
    private static final int RESERVED_POOL_CONNECTIONS = 2;

    /**
     * 合并引擎参数。未配置的项落 {@link MergeConfig#defaults()}（8 / 5ms / 200 / 100ms / 2000 / 5s）。
     */
    @Bean
    public MergeConfig mergeConfig(Environment environment) {
        MergeConfig defaults = MergeConfig.defaults();
        MergeConfig config = new MergeConfig(
                environment.getProperty("merge.batch.shard-count", Integer.class, defaults.shardCount()),
                environment.getProperty("merge.batch.max-flush-delay-millis", Integer.class, defaults.maxFlushDelayMillis()),
                environment.getProperty("merge.batch.max-batch", Integer.class, defaults.maxBatch()),
                environment.getProperty("merge.batch.wait-timeout-ms", Integer.class, defaults.waitTimeoutMs()),
                environment.getProperty("merge.batch.shard-capacity", Integer.class, defaults.shardCapacity()),
                environment.getProperty("merge.batch.drain-timeout-ms", Long.class, defaults.drainTimeoutMs()));

        int poolSize = environment.getProperty("spring.datasource.hikari.maximum-pool-size",
                Integer.class, DEFAULT_POOL_SIZE);
        int maxShardCount = poolSize - RESERVED_POOL_CONNECTIONS;
        if (config.shardCount() > maxShardCount) {
            throw new IllegalStateException(String.format(
                    "[配置错误] merge.batch.shard-count(%s) 必须 <= 连接池上限 - %s（当前池 %s，上限 %s）："
                            + "per-shard 常驻线程模型下 flush 并发 = shardCount，flush 占满连接后普通查询"
                            + "会在 connection-timeout 上排队",
                    config.shardCount(), RESERVED_POOL_CONNECTIONS, poolSize, maxShardCount));
        }

        log.info("合并引擎装配：merge.batch.shard-count={}（= 单引擎 flush 常驻线程数），"
                        + "连接池上限(hikari.maximum-pool-size)={}，校验口径 shard-count <= 池 - {}（上限 {}）",
                config.shardCount(), poolSize, RESERVED_POOL_CONNECTIONS, maxShardCount);
        return config;
    }
}
