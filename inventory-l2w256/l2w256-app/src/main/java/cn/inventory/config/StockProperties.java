package cn.inventory.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 库存服务配置（数据源 / 连接池 / 合并参数），全部可用环境变量覆盖
 *
 * <p>用 {@code @ConfigurationProperties} 而不是散落的 {@code @Value}：合并参数有 8 个，
 * 又互相有约束，必须能整体交给 {@code MergeConfigVO} 做一次性校验。
 */
@Data
@Component
@ConfigurationProperties(prefix = "stock")
public class StockProperties {

    private Datasource datasource = new Datasource();

    private Pool pool = new Pool();

    private Merge merge = new Merge();

    @Data
    public static class Datasource {

        private String host = "127.0.0.1";

        private String port = "3306";

        private String username = "root";

        private String password = "root123";

        private String database = "inventory_demo";

        /**
         * JDBC URL。几个参数是「压测/生产有效性」的前提，改之前先看注释：
         * <ul>
         *   <li><b>socketTimeout=60000</b>：必须远大于锁等待超时。否则排队等锁的请求会先被驱动
         *       按 socket 超时掐断，拿到的是通信异常而不是 1205，锁竞争就观测不到了；</li>
         *   <li><b>sslMode=DISABLED</b>：内网不做 TLS 握手，避免每连接握手开销干扰吞吐对比
         *       （公网/跨机房必须改回 REQUIRED）；</li>
         *   <li><b>allowPublicKeyRetrieval=true</b>：MySQL 9.x 已移除 mysql_native_password，
         *       账号默认 caching_sha2_password，非 TLS 首次认证需要取服务端公钥；</li>
         *   <li><b>rewriteBatchedStatements=true</b>：把批量写流水重写成多值 INSERT，
         *       200 行流水一次往返；</li>
         *   <li><b>useLocalSessionState=true</b>：驱动不再每次执行前回查 autocommit/隔离级别。</li>
         * </ul>
         */
        public String buildJdbcUrl() {
            return "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useUnicode=true&characterEncoding=UTF-8"
                    + "&useServerPrepStmts=true&cachePrepStmts=true"
                    + "&prepStmtCacheSize=250&prepStmtCacheSqlLimit=2048"
                    + "&rewriteBatchedStatements=true"
                    + "&connectTimeout=3000&socketTimeout=60000"
                    + "&useLocalSessionState=true&allowPublicKeyRetrieval=true"
                    + "&sslMode=DISABLED&tcpKeepAlive=true";
        }
    }

    @Data
    public static class Pool {

        /**
         * 池大小。它不是「越大越快」：双 1 刷盘下，并发提交越多、单事务越慢。
         * 合并后每次事务碰不相交的行，20 条并发才有正收益——扩容只能靠合并，不能靠加连接。
         */
        private int maximumPoolSize = 20;

        private int minimumIdle = 20;

        /**
         * 取连接的最长等待。必须远小于合并等待上限（100ms）：两个数量级的差距意味着
         * 「池排队」只会表现为罕见的尖刺，而不是全体请求排队 30 秒。
         */
        private long connectionTimeoutMs = 2_000L;

        /**
         * 启动时连不上库怎么办：{@code >0} 立刻失败（默认，别让带病的实例接流量）；
         * {@code -1} 允许启动并持续重试（DB 尚未就绪、或依赖 k8s 探针兜底时用）。
         */
        private long initializationFailTimeout = 1L;
    }

    @Data
    public static class Merge {

        /** 分片数，必须 2 的幂；建议 ≈ 峰值并发热点数 */
        private int shardCount = 256;

        /** 合并窗口（毫秒）：分片首个请求排定 windowMs 后 flush */
        private int windowMs = 5;

        /** 单次 flush 最多取走的请求数 */
        private int maxBatch = 200;

        /** 调用方最长等待；超时返回「处理中」（结果未知），不是失败 */
        private int waitTimeoutMs = 100;

        /** 单分片缓冲区上限：256 × 10000 ≈ 20MB 引用 */
        private int shardCapacity = 10_000;

        /** flush 执行池大小，必须等于连接池大小（启动时强制校验） */
        private int flushThreads = 20;

        /** 售罄短路：整批都不够时一次 SELECT 判定，不做二分拆批。生产必开 */
        private boolean soldoutShortcut = true;

        /** 停机 / 复位前排空在途请求的等待上限 */
        private long drainTimeoutMs = 5_000L;
    }
}
