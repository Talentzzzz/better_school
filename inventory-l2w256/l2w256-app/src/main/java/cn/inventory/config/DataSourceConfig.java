package cn.inventory.config;

import cn.inventory.domain.stock.model.valobj.MergeConfigVO;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import javax.sql.DataSource;

/**
 * 数据源 / MyBatis / 合并参数的装配
 *
 * <p>只有一个数据源：本方案是「合并扣减」单一形态，不像对照工程那样需要 L0/L1 两个池做 A/B。
 * 少一个池就少一套 SQL 命名空间、少一套事务模板、少一半误配可能。
 *
 * <p>池的会话参数只有一条：{@code innodb_lock_wait_timeout = 2}（快速失败）。
 * 隔离级别<b>不动</b>，保持 MySQL 默认 REPEATABLE-READ——改 RC 能减少间隙锁，
 * 但那是另一个变量，混进来就说不清收益是谁带来的。
 */
@Configuration
@MapperScan(basePackages = "cn.inventory.infrastructure.dao")
public class DataSourceConfig {

    @Bean(name = "stockDataSource", destroyMethod = "close")
    public HikariDataSource stockDataSource(StockProperties properties) {
        StockProperties.Datasource datasource = properties.getDatasource();
        StockProperties.Pool pool = properties.getPool();

        HikariDataSource dataSource = new HikariDataSource();
        dataSource.setJdbcUrl(datasource.buildJdbcUrl());
        dataSource.setUsername(datasource.getUsername());
        dataSource.setPassword(datasource.getPassword());
        dataSource.setMaximumPoolSize(pool.getMaximumPoolSize());
        dataSource.setMinimumIdle(pool.getMinimumIdle());
        dataSource.setPoolName("stock-pool");
        // 显式声明，不依赖 Hikari 默认值随版本漂移
        dataSource.setConnectionTimeout(pool.getConnectionTimeoutMs());
        dataSource.setInitializationFailTimeout(pool.getInitializationFailTimeout());
        dataSource.setValidationTimeout(3_000L);
        dataSource.setMaxLifetime(30 * 60 * 1000L);
        // 会话级参数：只压锁等待超时，隔离级别保持 RR
        dataSource.setConnectionInitSql("SET SESSION innodb_lock_wait_timeout = 2");
        return dataSource;
    }

    @Bean
    public SqlSessionFactory sqlSessionFactory(DataSource stockDataSource) throws Exception {
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(stockDataSource);
        factoryBean.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources("classpath*:mybatis/mapper/*.xml"));
        return factoryBean.getObject();
    }

    @Bean
    public SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
        return new SqlSessionTemplate(sqlSessionFactory);
    }

    /**
     * 合并参数：构造即校验，参数自相矛盾时应用直接起不来，而不是压测跑到一半才发现每个请求都超时。
     *
     * <p>额外在这里做一条「跨层约束」：flush 线程数必须等于连接池大小。
     * 大于是线程抢连接（256 线程抢 20 连接时多热点掉到 7.4k）；
     * 小于是连接闲置（8 线程的天花板正好卡在 12.4k）。这条只能在这里校验，因为域层看不到池。
     */
    @Bean
    public MergeConfigVO mergeConfigVO(StockProperties properties) {
        StockProperties.Merge merge = properties.getMerge();
        int poolSize = properties.getPool().getMaximumPoolSize();
        if (merge.getFlushThreads() != poolSize) {
            throw new IllegalStateException(String.format(
                    "[配置错误] merge.flush-threads(%d) 必须等于 pool.maximum-pool-size(%d)："
                            + "大于池会让 flush 线程抢连接排队，小于池会让连接闲置、吞吐封顶在「线程数 ÷ 单次 flush 耗时」",
                    merge.getFlushThreads(), poolSize));
        }
        return new MergeConfigVO(
                merge.getShardCount(),
                merge.getWindowMs(),
                merge.getMaxBatch(),
                merge.getWaitTimeoutMs(),
                merge.getShardCapacity(),
                merge.getFlushThreads(),
                merge.isSoldoutShortcut(),
                merge.getDrainTimeoutMs());
    }
}
