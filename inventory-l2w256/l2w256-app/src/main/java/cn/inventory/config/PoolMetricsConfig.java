package cn.inventory.config;

import cn.inventory.domain.stock.service.merge.IStockMergeService;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 连接池与积压指标注册（只观测，不改任何运行行为）
 *
 * <p>为什么自己注册：Spring Boot 内置的 {@code jdbc.connections.*} 只暴露 active/idle/max，
 * <b>拿不到「有多少线程正在等连接」</b>——而「P99 飙升但 DB 很闲」的第一嫌疑就是池排队。
 * Hikari 的 {@link HikariPoolMXBean#getThreadsAwaitingConnection()} 正好是它。
 *
 * <p>指标名（名字里带池名/含义，curl 一眼能读，不用记 tag）：
 * <pre>
 *   stock.pool.active    在用连接
 *   stock.pool.idle      空闲连接
 *   stock.pool.waiting   ★ 正在排队等连接的线程数（持续 &gt; 0 = 池是瓶颈）
 *   stock.merge.pending  ★ 合并器当前积压（持续上涨 = 消费跟不上到达）
 * </pre>
 *
 * <p>Hikari 的池是懒创建的，注册时 {@code getHikariPoolMXBean()} 可能还是 null，
 * 所以 Gauge 里做空值保护、在采集时才取值（Gauge 的函数每次抓取都会调用）。
 */
@Configuration
public class PoolMetricsConfig {

    @Bean
    public MeterBinder stockPoolMetrics(HikariDataSource stockDataSource, IStockMergeService stockMergeService) {
        return registry -> {
            Gauge.builder("stock.pool.active", stockDataSource, PoolMetricsConfig::activeConnections).register(registry);
            Gauge.builder("stock.pool.idle", stockDataSource, PoolMetricsConfig::idleConnections).register(registry);
            Gauge.builder("stock.pool.waiting", stockDataSource, PoolMetricsConfig::waitingThreads).register(registry);
            Gauge.builder("stock.merge.pending", stockMergeService, service -> service.pendingCount()).register(registry);
        };
    }

    private static double activeConnections(HikariDataSource dataSource) {
        HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
        return pool == null ? 0 : pool.getActiveConnections();
    }

    private static double idleConnections(HikariDataSource dataSource) {
        HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
        return pool == null ? 0 : pool.getIdleConnections();
    }

    private static double waitingThreads(HikariDataSource dataSource) {
        HikariPoolMXBean pool = dataSource.getHikariPoolMXBean();
        return pool == null ? 0 : pool.getThreadsAwaitingConnection();
    }
}
