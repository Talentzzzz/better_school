package com.betterschool.marketing.config;

import com.betterschool.marketing.domain.inventory.adapter.repository.IInventoryRepository;
import com.betterschool.marketing.domain.inventory.service.merge.MergeConfig;
import com.betterschool.marketing.domain.inventory.service.merge.InventoryMerger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 库存合并器的参数装配与「分片数 × 连接池」校验。
 *
 * <p>为什么 {@code shardCount <= 池 − 2} 不在 {@link MergeConfig} 里校验：连接池属于应用装配层，
 * 引擎不认识 Hikari。这里是共享的 {@code hikari.maximum-pool-size} + 虚拟线程，连接池就是真并发上限，
 * flush 占满连接后普通查询会在 {@code connection-timeout} 上排队——所以引擎只校验 2 的幂等自身约束，
 * 并发上限在启动这里卡死。
 *
 * <p>per-shard 常驻线程模型（每分片一条 {@code inventory-merge-flush-*}）下 flush 并发 = shardCount，
 * 不变量「flush 并发 <= 池 − 2」的校验对象从旧的 flushThreads 换成 shardCount（wayfinder charting-log Q7）。
 */
@Slf4j
@Configuration
public class InventoryMergeConfig {

    /** 连接池默认值与 application.yml 的 {@code spring.datasource.hikari.maximum-pool-size} 对齐 */
    private static final int DEFAULT_POOL_SIZE = 10;

    /** 留给普通请求的连接数：flush 再多就只能抢业务查询的连接 */
    private static final int RESERVED_POOL_CONNECTIONS = 2;

    /**
     * 库存侧合并参数。未配置的项落 {@link MergeConfig#inventoryDefaults()}
     * （8 / 5ms / 200 / 100ms / 2000 / 5s）。
     */
    @Bean
    public MergeConfig inventoryMergeConfig(Environment environment) {
        MergeConfig defaults = MergeConfig.inventoryDefaults();
        MergeConfig config = new MergeConfig(
                environment.getProperty("merge.inventory.shard-count", Integer.class, defaults.shardCount()),
                environment.getProperty("merge.inventory.max-flush-delay-millis", Integer.class, defaults.maxFlushDelayMillis()),
                environment.getProperty("merge.inventory.max-batch", Integer.class, defaults.maxBatch()),
                environment.getProperty("merge.inventory.wait-timeout-ms", Integer.class, defaults.waitTimeoutMs()),
                environment.getProperty("merge.inventory.shard-capacity", Integer.class, defaults.shardCapacity()),
                environment.getProperty("merge.inventory.drain-timeout-ms", Long.class, defaults.drainTimeoutMs()));

        int poolSize = environment.getProperty("spring.datasource.hikari.maximum-pool-size",
                Integer.class, DEFAULT_POOL_SIZE);
        int maxShardCount = poolSize - RESERVED_POOL_CONNECTIONS;
        if (config.shardCount() > maxShardCount) {
            throw new IllegalStateException(String.format(
                    "[配置错误] merge.inventory.shard-count(%s) 必须 <= 连接池上限 - %s（当前池 %s，上限 %s）："
                            + "per-shard 常驻线程模型下 flush 并发 = shardCount，flush 占满连接后普通查询"
                            + "会在 connection-timeout 上排队",
                    config.shardCount(), RESERVED_POOL_CONNECTIONS, poolSize, maxShardCount));
        }

        log.info("库存合并器装配：merge.inventory.shard-count={}（= flush 常驻线程数），"
                        + "连接池上限(hikari.maximum-pool-size)={}，校验口径 shard-count <= 池 - {}（上限 {}）",
                config.shardCount(), poolSize, RESERVED_POOL_CONNECTIONS, maxShardCount);
        return config;
    }

    /**
     * 库存合并器实例（domain 层纯 Java 引擎）：落库出口注入领域端口 {@link IInventoryRepository}
     * （真身 {@code InventoryRepository} → {@code InventoryQuotaFlushService}，一组一个事务；
     * 预扣的一组票 06 接线，确认的一组票 07 接线）。{@code close()} 由 {@code @Bean} 的
     * destroyMethod 推断在停机时调用（停收 → 排空 → signal + join）。
     */
    @Bean
    public InventoryMerger inventoryMerger(MergeConfig mergeConfig, IInventoryRepository inventoryRepository) {
        return new InventoryMerger(mergeConfig, inventoryRepository);
    }
}
