import org.apache.shardingsphere.infra.hint.HintManager;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.*;
import java.util.*;

/**
 * Chase the one anomaly from ConfirmProbe2 variant D:
 *   "one rollback clears all three tables :: po=0 tt=1 rt=0"
 * i.e. a row inserted into the sharded table inside a transaction survived the rollback,
 * while the two unsharded rows did not.
 *
 * Everything is printed: per-shard counts at each step, and the MySQL connection id seen
 * before and after the sharded write.
 */
public final class RollbackProbe {

    static final String URL = "jdbc:mysql://127.0.0.1:3307/ss_d?useSSL=false&allowPublicKeyRetrieval=true"
            + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8";

    public static void main(String[] args) throws Exception {
        clear();
        DataSource m = ss("jdbc:shardingsphere:classpath:probe-r-d.yaml");
        System.out.println("  [info] per-shard counts BEFORE = " + shardCounts());

        // ---- repeat the exact transaction block ----
        try (Connection c = m.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO product_order (order_no,user_id,status,msg) VALUES ('TX1',1,'W','a')");
                long id1 = one(c, "SELECT CONNECTION_ID()");
                try (HintManager h = HintManager.getInstance()) {
                    h.addTableShardingValue("timeout_task", "timeout_task_05");
                    System.out.println("  [info] inside hint scope, values="
                            + HintManager.getTableShardingValues("timeout_task"));
                    int n = s.executeUpdate("INSERT INTO timeout_task (task_type,biz_id,status,retry_count)"
                            + " VALUES ('T','tx1','W',0)");
                    System.out.println("  [info] sharded insert updateCount=" + n);
                }
                long id2 = one(c, "SELECT CONNECTION_ID()");
                s.executeUpdate("INSERT INTO review_task (task_id) VALUES (5)");
                long id3 = one(c, "SELECT CONNECTION_ID()");
                System.out.println("  [info] session ids = " + id1 + "," + id2 + "," + id3);
            }
            System.out.println("  [info] per-shard counts BEFORE ROLLBACK (from a DIFFERENT connection, so invisible if truly uncommitted) = "
                    + shardCounts());
            c.rollback();
            c.setAutoCommit(true);
        }

        System.out.println("  [info] per-shard counts AFTER ROLLBACK = " + shardCounts());
        System.out.println("  [info] business rows: product_order(TX1)=" + count("SELECT COUNT(*) FROM product_order WHERE order_no='TX1'")
                + " review_task=" + count("SELECT COUNT(*) FROM review_task"));

        // ---- same thing but WITHOUT a hint on the sharded write, to isolate the variable ----
        clear();
        System.out.println();
        System.out.println("  ---- control: hinted sharded insert, autocommit OFF, explicit ROLLBACK ----");
        try (Connection c = m.getConnection()) {
            c.setAutoCommit(false);
            try (HintManager h = HintManager.getInstance()) {
                h.addTableShardingValue("timeout_task", "timeout_task_05");
                try (Statement s = c.createStatement()) {
                    s.executeUpdate("INSERT INTO timeout_task (task_type,biz_id,status,retry_count) VALUES ('T','ctl','W',0)");
                }
            }
            c.rollback();
            c.setAutoCommit(true);
        }
        System.out.println("  [info] per-shard counts after control rollback = " + shardCounts());

        // ---- control 2: does Hikari hand the SAME backend session back after rollback? ----
        System.out.println();
        System.out.println("  ---- control 2: session identity across two Hikari borrows ----");
        long a;
        try (Connection c = m.getConnection(); Statement s = c.createStatement()) {
            a = one(c, "SELECT CONNECTION_ID()");
        }
        long b;
        try (Connection c = m.getConnection(); Statement s = c.createStatement()) {
            b = one(c, "SELECT CONNECTION_ID()");
        }
        System.out.println("  [info] two sequential Hikari borrows -> session " + a + " then " + b
                + (a == b ? " (SAME physical session)" : " (different physical session)"));
    }

    static String shardCounts() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            int n = count("SELECT COUNT(*) FROM timeout_task_" + String.format("%02d", i));
            if (n != 0) {
                sb.append("timeout_task_").append(String.format("%02d", i)).append("=").append(n).append(" ");
            }
        }
        return sb.length() == 0 ? "<all empty>" : sb.toString().trim();
    }

    static void clear() throws Exception {
        try (Connection c = DriverManager.getConnection(URL, "root", ""); Statement s = c.createStatement()) {
            for (int i = 0; i < 16; i++) {
                s.executeUpdate("DELETE FROM timeout_task_" + String.format("%02d", i));
            }
            s.executeUpdate("DELETE FROM product_order");
            s.executeUpdate("DELETE FROM review_task");
        }
    }

    static int count(String sql) {
        try (Connection c = DriverManager.getConnection(URL, "root", "");
             Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            return -999;
        }
    }

    static long one(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static DataSource ss(String url) throws Exception {
        try (Connection c = DriverManager.getConnection(url)) { }
        Field f = Class.forName("org.apache.shardingsphere.driver.ShardingSphereDriver").getDeclaredField("DATA_SOURCE_CACHE");
        f.setAccessible(true);
        Object cache = f.get(null);
        Method getMap = cache.getClass().getMethod("getDataSourceMap");
        getMap.setAccessible(true);
        Map<String, DataSource> map = (Map<String, DataSource>) getMap.invoke(cache);
        DataSource ds = map.get(url);
        if (ds == null) throw new IllegalStateException("not cached: " + url);
        return ds;
    }
}
