import org.apache.shardingsphere.infra.hint.HintManager;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.*;
import java.util.*;

/**
 * Round-2 clean probe for the ConfirmProbe anomaly.
 *
 * Each variant uses its OWN MySQL schema AND its own JDBC URL, so the driver's
 * DATA_SOURCE_CACHE (keyed by URL) can never hand back a datasource built from
 * another variant's YAML -- that was the suspected cross-variant contamination.
 *
 * Variant A: probe-r-a.yaml  = production shape (no !SINGLE, no databaseName) on schema ss_a
 * Variant B: probe-r-b.yaml  = !SINGLE tables ["*.*.*"] + !SHARDING        on schema ss_b
 */
public final class HintWriteProbe {

    static final String A_URL = "jdbc:mysql://127.0.0.1:3307/ss_a?useSSL=false&allowPublicKeyRetrieval=true"
            + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8";
    static final String B_URL = "jdbc:mysql://127.0.0.1:3307/ss_b?useSSL=false&allowPublicKeyRetrieval=true"
            + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8";

    static DataSource raw;
    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        seed(A_URL);
        seed(B_URL);
        run("A production-shape (ss_a / probe-r-a.yaml)", A_URL, "probe-r-a.yaml", true);
        run("B singleStar      (ss_b / probe-r-b.yaml)", B_URL, "probe-r-b.yaml", false);
        System.out.println();
        System.out.println("=== HINTWRITE RESULT: PASS=" + pass + " FAIL=" + fail + " ===");
    }

    static void run(String label, String url, String yaml, boolean hintAllowed) throws Exception {
        System.out.println();
        System.out.println("################ " + label + " ################");
        raw = hikari(url);
        clearRows(raw);
        DataSource m = ss("jdbc:shardingsphere:classpath:" + yaml);
        dumpMetadata(m);

        // 1. the exact failing operation from ConfirmProbe, with full context
        try (Connection c = m.getConnection(); Statement s = c.createStatement()) {
            try (HintManager h = HintManager.getInstance()) {
                h.addTableShardingValue("timeout_task", "timeout_task_03");
                System.out.println("  [info] hint see =" + HintManager.getTableShardingValues("timeout_task")
                        + " exhausted=" + HintManager.isInstantiated());
                int n = s.executeUpdate("INSERT INTO timeout_task (task_type,biz_id,status,retry_count)"
                        + " VALUES ('T','c1','WAITING',0)");
                check("hinted write via logical table", n == 1, "updateCount=" + n);
            }
        } catch (Throwable t) {
            check("hinted write via logical table", false, firstLine(t));
        }
        check("physical row landed in timeout_task_03", rawCount(url, "SELECT COUNT(*) FROM timeout_task_03") == 1,
                "raw count=" + rawCount(url, "SELECT COUNT(*) FROM timeout_task_03"));

        // 2. hinted read of the physical table (was the surviving read path)
        try (Connection c = m.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM timeout_task_03");
            rs.next();
            check("read physical table timeout_task_03", rs.getInt(1) == 1, "count=" + rs.getInt(1));
        } catch (Throwable t) {
            check("read physical table timeout_task_03", false, firstLine(t));
        }

        // 3. hinted write through the logical table, repeated 5x on fresh connections
        for (int i = 0; i < 5; i++) {
            final int round = i;
            try (Connection c = m.getConnection(); Statement s = c.createStatement()) {
                try (HintManager h = HintManager.getInstance()) {
                    h.addTableShardingValue("timeout_task", "timeout_task_0" + (round % 10));
                }
                int n = s.executeUpdate("INSERT INTO timeout_task (task_type,biz_id,status,retry_count)"
                        + " VALUES ('T','r" + round + "','WAITING',0)");
                check("hinted write round " + round + " -> timeout_task_0" + (round % 10), n == 1, "updateCount=" + n);
            } catch (Throwable t) {
                check("hinted write round " + round + " -> timeout_task_0" + (round % 10), false, firstLine(t));
            }
        }

        // 4. hinted read through the logical table
        try (Connection c = m.getConnection(); Statement s = c.createStatement()) {
            try (HintManager h = HintManager.getInstance()) {
                h.addTableShardingValue("timeout_task", "timeout_task_00");
            }
            ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM timeout_task");
            rs.next();
            check("hinted read logical table -> shard 00", rs.getInt(1) >= 1, "count=" + rs.getInt(1));
        } catch (Throwable t) {
            check("hinted read logical table -> shard 00", false, firstLine(t));
        }

        // 5. broadcast read without hint (all 16 shards)
        try (Connection c = m.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM timeout_task");
            rs.next();
            check("unhinted broadcast read logical table", rs.getInt(1) >= 1, "count=" + rs.getInt(1));
        } catch (Throwable t) {
            check("unhinted broadcast read logical table", false, firstLine(t));
        }

        // 6. unhinted write: production expects fail-fast
        try (Connection c = m.getConnection(); Statement s = c.createStatement()) {
            int n = s.executeUpdate("INSERT INTO timeout_task (task_type,biz_id,status,retry_count)"
                    + " VALUES ('T','nohint','WAITING',0)");
            check("unhinted write fails fast", !hintAllowed, "updateCount=" + n + " (production expects an exception)");
        } catch (Throwable t) {
            check("unhinted write fails fast", true, brief(firstLine(t)));
        }

        // 7. business tables (never sharded)
        try (Connection c = m.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM user_account");
            rs.next();
            check("business table user_account reachable", rs.getInt(1) == 1, "count=" + rs.getInt(1));
        } catch (Throwable t) {
            check("business table user_account reachable", false, firstLine(t));
        }
    }

    // ---------- diagnostics ----------

    static void dumpMetadata(DataSource m) {
        try {
            Object ctx = cm(m);
            for (Object dbName : (Collection<?>) ctx.getClass().getMethod("getAllDatabaseNames").invoke(ctx)) {
                Object db = ctx.getClass().getMethod("getDatabase", String.class).invoke(ctx, dbName);
                if (!(Boolean) db.getClass().getMethod("isComplete").invoke(db)) continue;
                Object schema = db.getClass().getMethod("getSchema", String.class).invoke(db, String.valueOf(dbName));
                Set<String> tables = new TreeSet<>((Collection<String>) schema.getClass()
                        .getMethod("getAllTables").invoke(schema));
                System.out.println("  [info] logical db '" + dbName + "' tables(" + tables.size() + ")=" + tables);
                Object ruleMd = db.getClass().getMethod("getRuleMetaData").invoke(db);
                for (Object rule : (Collection<?>) ruleMd.getClass().getMethod("getRules").invoke(ruleMd)) {
                    if (rule.getClass().getSimpleName().contains("Single")) {
                        Map<?, ?> nodes = (Map<?, ?>) rule.getClass().getMethod("getSingleTableDataNodes").invoke(rule);
                        System.out.println("  [info]   singleTableDataNodes (" + nodes.size() + ") keys="
                                + new TreeSet<>(nodes.keySet()));
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("  [info] meta dump failed: " + firstLine(t));
        }
    }

    // ---------- infra ----------

    static DataSource ss(String url) throws Exception {
        try (Connection c = DriverManager.getConnection(url)) { }
        DataSource ds = (DataSource) cacheMap().get(url);
        if (ds == null) throw new IllegalStateException("not cached: " + url);
        return ds;
    }

    @SuppressWarnings("unchecked")
    static Map<String, DataSource> cacheMap() throws Exception {
        Field f = Class.forName("org.apache.shardingsphere.driver.ShardingSphereDriver").getDeclaredField("DATA_SOURCE_CACHE");
        f.setAccessible(true);
        Object cache = f.get(null);
        Method getMap = cache.getClass().getMethod("getDataSourceMap");
        getMap.setAccessible(true);
        return (Map<String, DataSource>) getMap.invoke(cache);
    }

    static Object cm(DataSource ds) throws Exception {
        Class<?> c = ds.getClass();
        while (c != null) {
            try {
                Field f = c.getDeclaredField("contextManager");
                f.setAccessible(true);
                return f.get(ds);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new IllegalStateException("no contextManager");
    }

    static void seed(String url) throws Exception {
        DataSource ds = hikari(url);
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS user_account (id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(50))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS product_order (id BIGINT PRIMARY KEY AUTO_INCREMENT, order_no VARCHAR(40) UNIQUE, user_id BIGINT, status VARCHAR(20), msg VARCHAR(50))");
            for (int i = 0; i < 16; i++) {
                s.executeUpdate("CREATE TABLE IF NOT EXISTS timeout_task_" + String.format("%02d", i)
                        + " (id BIGINT PRIMARY KEY AUTO_INCREMENT, task_type VARCHAR(40), biz_id VARCHAR(64),"
                        + " action_time DATETIME, status VARCHAR(20), retry_count INT DEFAULT 0, next_fire_time DATETIME,"
                        + " created_at DATETIME, updated_at DATETIME, UNIQUE KEY uk_type_biz (task_type, biz_id))");
            }
            clearRows(c);
        }
        close(ds);
    }

    static void clearRows(DataSource ds) throws SQLException {
        try (Connection c = ds.getConnection()) {
            clearRows(c);
        }
    }

    static void clearRows(Connection c) throws SQLException {
        try (Statement s = c.createStatement()) {
            for (int i = 0; i < 16; i++) {
                s.executeUpdate("DELETE FROM timeout_task_" + String.format("%02d", i));
            }
            s.executeUpdate("DELETE FROM user_account");
            s.executeUpdate("INSERT INTO user_account (id,name) VALUES (1,'alice')");
        }
    }

    static DataSource hikari(String url) throws Exception {
        Class<?> hk = Class.forName("com.zaxxer.hikari.HikariDataSource");
        DataSource ds = (DataSource) hk.getDeclaredConstructor().newInstance();
        hk.getMethod("setJdbcUrl", String.class).invoke(ds, url);
        hk.getMethod("setUsername", String.class).invoke(ds, "root");
        hk.getMethod("setPassword", String.class).invoke(ds, "");
        hk.getMethod("setDriverClassName", String.class).invoke(ds, "com.mysql.cj.jdbc.Driver");
        hk.getMethod("setMaximumPoolSize", int.class).invoke(ds, 10);
        hk.getMethod("setMinimumIdle", int.class).invoke(ds, 1);
        hk.getMethod("setConnectionTimeout", long.class).invoke(ds, 3000L);
        return ds;
    }

    static void close(DataSource ds) {
        try {
            ds.getClass().getMethod("close").invoke(ds);
        } catch (Exception ignored) {
        }
    }

    static int rawCount(String url, String sql) {
        try (Connection c = DriverManager.getConnection(url, "root", "");
             Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            return -999;
        }
    }

    static void check(String what, boolean ok, String detail) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "  [PASS] " : "  [FAIL] ") + what + "  ::  " + detail);
    }

    static String firstLine(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        return r.toString();
    }

    static String brief(String s) {
        String x = s.replaceAll("\\s+", " ");
        return x.length() > 120 ? x.substring(0, 120) + "..." : x;
    }
}
