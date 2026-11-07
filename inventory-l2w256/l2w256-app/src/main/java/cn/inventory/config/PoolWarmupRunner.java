package cn.inventory.config;

import cn.inventory.domain.stock.model.valobj.DeductRequestVO;
import cn.inventory.domain.stock.model.valobj.DeductResultVO;
import cn.inventory.usecase.IStockDeductCase;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 启动预热：把物理连接、驱动类加载、mapper 首次执行、InnoDB 首次写路径全部走完，
 * 避免压测第一笔请求撞上冷启动。
 *
 * <p>两件事，缺一件前几十秒的数据都会失真：
 * <ol>
 *   <li><b>把连接池撑满</b>：Hikari 懒建连，只跑一笔请求只会建 1 条；剩下的会在压测开始后
 *       陆续创建，握手成本全摊在压测窗口里；</li>
 *   <li><b>预热写路径</b>：真实走一次「合并 → 落库」，让语句缓存、服务端预处理、InnoDB 写路径就绪。
 *       每次启动都用全新 bizNo——固定 bizNo 会在第二次启动时命中幂等键，
 *       请求只返回 DUPLICATE，根本没碰到写路径。</li>
 * </ol>
 *
 * <p>预热用独立 SKU 9999，先 reset 再扣，反复重启也不会扣空、不会往流水表堆垃圾。
 */
@Slf4j
@Component
public class PoolWarmupRunner implements ApplicationRunner {

    /** 预热专用 SKU，与业务 SKU（1001 等）隔离 */
    private static final long WARMUP_SKU_ID = 9999L;

    /** 预热库存：远大于预热消耗，写路径必须成功（失败即说明环境有问题） */
    private static final int WARMUP_STOCK = 200_000;

    private final IStockDeductCase stockDeductCase;
    private final HikariDataSource stockDataSource;

    public PoolWarmupRunner(IStockDeductCase stockDeductCase, HikariDataSource stockDataSource) {
        this.stockDeductCase = stockDeductCase;
        this.stockDataSource = stockDataSource;
    }

    @Override
    public void run(ApplicationArguments args) {
        long start = System.currentTimeMillis();
        try {
            stockDeductCase.reset(WARMUP_SKU_ID, WARMUP_STOCK);
            int connections = warmPool();
            warmWritePath();
            log.info("预热完成：建连 {} 条，耗时 {} ms；池状态 active={} idle={}",
                    connections, System.currentTimeMillis() - start,
                    stockDataSource.getHikariPoolMXBean() == null ? -1 : stockDataSource.getHikariPoolMXBean().getActiveConnections(),
                    stockDataSource.getHikariPoolMXBean() == null ? -1 : stockDataSource.getHikariPoolMXBean().getIdleConnections());
        } catch (Exception e) {
            // 预热失败不阻断启动（DB 可能尚未就绪），但必须在日志里显眼
            log.error("预热失败，压测前请手工确认 DB 可用、连接池已撑满: {}", e.getMessage(), e);
        }
    }

    /** 顺序建连并全部持有，最后统一归还：Hikari 只在连接被同时持有时才会真正扩到 maximumPoolSize */
    private int warmPool() {
        int target = stockDataSource.getMaximumPoolSize();
        List<Connection> connections = new ArrayList<>(target);
        try {
            for (int i = 0; i < target; i++) {
                Connection connection = stockDataSource.getConnection();
                connections.add(connection);
                try (Statement statement = connection.createStatement();
                     ResultSet rs = statement.executeQuery("SELECT 1")) {
                    rs.next();
                }
            }
            log.info("连接池 stock-pool 预热完成：physical={}", connections.size());
        } catch (Exception e) {
            log.warn("连接池预热中断（已建连 {} 条）: {}", connections.size(), e.getMessage());
        } finally {
            for (Connection connection : connections) {
                try {
                    connection.close();
                } catch (Exception ignore) {
                    // 归还失败无需处理，Hikari 按 maxLifetime 回收
                }
            }
        }
        return connections.size();
    }

    /** 走一次真实的合并落库：覆盖 mapper 首次编译、事务开启、INSERT IGNORE + UPDATE 两条语句 */
    private void warmWritePath() {
        DeductRequestVO requestVO = new DeductRequestVO(WARMUP_SKU_ID, 1, "warmup-" + UUID.randomUUID());
        DeductResultVO result = stockDeductCase.deduct(requestVO);
        // 合并是异步的：必须等它排空，否则预热只是把请求塞进缓冲区就返回了
        stockDeductCase.awaitIdle();
        log.info("写路径预热 status={}", result.getStatus());
    }
}
