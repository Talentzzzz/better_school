package com.betterschool.marketing.config;

import com.betterschool.marketing.infrastructure.inventory.merge.IQuotaMergeFlushHandler;
import com.betterschool.marketing.infrastructure.inventory.merge.MergeConfig;
import com.betterschool.marketing.infrastructure.inventory.merge.QuotaMergeBuffer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 库存合并器的参数装配与「线程数 × 连接池」校验（spec §2.4）。
 *
 * <p><b>为什么 {@code flushThreads <= 池 − 2} 不在 {@link MergeConfig} 里校验</b>：
 * 连接池大小属于应用装配层，引擎不认识 Hikari。参考工程是只做扣减的独立服务（池 20），
 * 搬成「flush 线程 = 池」在本仓库会出事——这里是<b>共享的 {@code hikari.maximum-pool-size}
 * + 虚拟线程</b>，连接池就是真并发上限，flush 占满连接后普通查询会在
 * {@code connection-timeout: 3000} 上排队。所以引擎只校验 {@code >= 1}，上限在启动这里卡死。
 *
 * <p>两个数一起打进启动日志：调参时先看这两个数，再看 {@code MergeMetrics} 的
 * {@code flushed ÷ batches}。
 *
 * <p><b>库存侧与计数侧的参数差异只有值</b>（{@code 64 / 5ms / 200 / 100ms / 2000 / 2 线程}）：
 * 库存热点是「一次活动 1~3 个 invId」，不需要 256 个缓冲；资金敏感 ⇒ 容量更小、更早降级为同步单条。
 */
@Slf4j
@Configuration
public class QuotaMergeConfig {

    /** 连接池默认值与 application.yml 的 {@code spring.datasource.hikari.maximum-pool-size} 对齐 */
    private static final int DEFAULT_POOL_SIZE = 10;

    /** 留给普通请求的连接数：flush 再多就只能抢业务查询的连接 */
    private static final int RESERVED_POOL_CONNECTIONS = 2;

    /**
     * 库存侧合并参数。未配置的项落 {@link MergeConfig#quotaDefaults()}
     * （64 / 5ms / 200 / 100ms / 2000 / 2 线程 / 5s）。
     */
    @Bean
    public MergeConfig quotaMergeConfig(Environment environment) {
        MergeConfig defaults = MergeConfig.quotaDefaults();
        MergeConfig config = new MergeConfig(
                environment.getProperty("merge.inventory.shard-count", Integer.class, defaults.shardCount()),
                environment.getProperty("merge.inventory.window-ms", Integer.class, defaults.windowMs()),
                environment.getProperty("merge.inventory.max-batch", Integer.class, defaults.maxBatch()),
                environment.getProperty("merge.inventory.wait-timeout-ms", Integer.class, defaults.waitTimeoutMs()),
                environment.getProperty("merge.inventory.shard-capacity", Integer.class, defaults.shardCapacity()),
                environment.getProperty("merge.inventory.flush-threads", Integer.class, defaults.flushThreads()),
                environment.getProperty("merge.inventory.drain-timeout-ms", Long.class, defaults.drainTimeoutMs()));

        int poolSize = environment.getProperty("spring.datasource.hikari.maximum-pool-size",
                Integer.class, DEFAULT_POOL_SIZE);
        int maxFlushThreads = poolSize - RESERVED_POOL_CONNECTIONS;
        if (config.flushThreads() > maxFlushThreads) {
            throw new IllegalStateException(String.format(
                    "[配置错误] merge.inventory.flush-threads(%s) 必须 <= 连接池上限 - %s（当前池 %s，上限 %s）："
                            + "flush 占满连接后普通查询会在 connection-timeout 上排队",
                    config.flushThreads(), RESERVED_POOL_CONNECTIONS, poolSize, maxFlushThreads));
        }

        log.info("库存合并器装配：merge.inventory.flush-threads={}，连接池上限(hikari.maximum-pool-size)={}，"
                        + "校验口径 flush-threads <= 池 - {}（上限 {}）",
                config.flushThreads(), poolSize, RESERVED_POOL_CONNECTIONS, maxFlushThreads);
        return config;
    }

    /**
     * 库存合并器实例：落库出口是 {@code InventoryRepository}（它实现 {@link IQuotaMergeFlushHandler}；
     * 预扣的一组票 06 接线，确认的一组票 07 接线——预扣与确认共用同一实例与分片，
     * 两个组方法各自一个事务）。{@code @PreDestroy} 的优雅停机（停收 → 排空 → 关池）作为 bean 生效。
     */
    @Bean
    public QuotaMergeBuffer quotaMergeBuffer(MergeConfig mergeConfig, IQuotaMergeFlushHandler flushHandler) {
        return new QuotaMergeBuffer(mergeConfig, flushHandler);
    }
}
