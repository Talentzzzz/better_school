import org.apache.shardingsphere.infra.hint.HintManager;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.*;
import java.util.*;

/**
 * Probe #4: isolate WHY the single-table rule sees no tables.
 * Variants differ only in YAML shape:
 *   A probe-named.yaml     -- databaseName: probe, Hikari wrapped by dataSourceClassName
 *   B probe-rawpool.yaml   -- databaseName: probe, raw HikariCP props (no dataSourceClassName)
 *   C probe-single.yaml    -- A + explicit !SINGLE { defaultDataSource: ds0 }
 */
public final class VariantProbe {

    static final String SCHEMA = "probe";
    static final String RAW_URL = "jdbc:mysql://127.0.0.1:3307/probe?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8";

    static DataSource raw;
    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        raw = hikari();
        seed(raw);
        System.out.println("MySQL " + SCHEMA + " table count = "
                + countRaw("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='" + SCHEMA + "'"));

        run("A", "probe-named.yaml");
        run("B", "probe-rawpool.yaml");
        run("C", "probe-single.yaml");

        System.out.println();
        System.out.println("=== VARIANT RESULT: PASS=" + pass + " FAIL=" + fail + " ===");
    }

    static void run(String tag, String yaml) throws Exception {
        System.out.println();
        System.out.println("################ VARIANT " + tag + " : " + yaml + " ################");
        Mgr m = new Mgr("jdbc:shardingsphere:classpath:" + yaml);
        try {
            shape(m);
            routing(m);
            tx(m);
        } finally {
            m.close();
        }
    }

    static void shape(Mgr m) {
        try {
            Object ctx = cm(m.real());
            System.out.println("  [info] allDatabaseNames = " + ctx.getClass().getMethod("getAllDatabaseNames").invoke(ctx));
            for (Object name : (Collection<?>) ctx.getClass().getMethod("getAllDatabaseNames").invoke(ctx)) {
                Object db = ctx.getClass().getMethod("getDatabase", String.class).invoke(ctx, name);
                boolean complete = (Boolean) db.getClass().getMethod("isComplete").invoke(db);
                boolean hasDs = (Boolean) db.getClass().getMethod("containsDataSource").invoke(db);
                if (!complete && !hasDs) {
                    continue;
                }
                System.out.println("  [info] database '" + name + "' isComplete=" + complete + " containsDataSource=" + hasDs);
                System.out.println("  [info]   storageUnits = "
                        + ctx.getClass().getMethod("getStorageUnits", String.class).invoke(ctx, name));
                for (Object schema : (Collection<?>) db.getClass().getMethod("getAllSchemas").invoke(db)) {
                    Object tables = schema.getClass().getMethod("getAllTables").invoke(schema);
                    SortedSet<String> names = new TreeSet<>();
                    if (tables instanceof Map) {
                        for (Object k : ((Map<?, ?>) tables).keySet()) names.add(String.valueOf(k));
                    }
                    System.out.println("  [info]   schema '" + schema.getClass().getMethod("getName").invoke(schema)
                            + "' tables (" + names.size() + "): " + names);
                }
                for (Object rule : (Collection<?>) db.getClass().getMethod("getRuleMetaData").invoke(db)
                        .getClass().getMethod("getRules")
                        .invoke(db.getClass().getMethod("getRuleMetaData").invoke(db))) {
                    String rn = rule.getClass().getSimpleName();
                    System.out.println("  [info]   rule " + rn);
                    try {
                        Object conf = rule.getClass().getMethod("getConfiguration").invoke(rule);
                        if (rn.contains("Single")) {
                            System.out.println("  [info]     defaultDataSource = "
                                    + conf.getClass().getMethod("getDefaultDataSource").invoke(conf)
                                    + " ; tables = " + conf.getClass().getMethod("getTables").invoke(conf));
                            Object nodeMap = rule.getClass().getMethod("getSingleTableDataNodes").invoke(rule);
                            System.out.println("  [info]     singleTableDataNodes size = " + ((Map<?, ?>) nodeMap).size());
                            int i = 0;
                            for (Map.Entry<?, ?> e : ((Map<?, ?>) nodeMap).entrySet()) {
                                if (i++ >= 5) { System.out.println("  [info]       ..."); break; }
                                System.out.println("  [info]       " + e.getKey() + " -> " + e.getValue());
                            }
                        }
                    } catch (NoSuchMethodException ignore) {
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("  [info] shape failed: " + firstLine(t));
        }
    }

    static void routing(Mgr m) {
        exec(m, "SELECT unconfigured", "SELECT id,name FROM user_account WHERE id=1", (s, rs) -> {
            rs.next();
            check("select user_account", "alice".equals(rs.getString(2)), "name=" + rs.getString(2));
        });
        exec(m, "INSERT product_order", "INSERT INTO product_order (order_no,user_id,status,msg) VALUES ('PO-1',7,'WAITING','x')",
                (s, rs) -> check("insert product_order", s.getUpdateCount() == 1, "updateCount=" + s.getUpdateCount()));
        exec(m, "UPDATE product_order", "UPDATE product_order SET msg='z' WHERE order_no='PO-1'",
                (s, rs) -> check("update product_order", s.getUpdateCount() == 1, "updateCount=" + s.getUpdateCount()));
        exec(m, "JOIN", "SELECT o.id FROM product_order o JOIN user_account u ON u.id=o.user_id", (s, rs) -> {
            int n = 0; while (rs.next()) n++;
            check("join business tables", n >= 1, "rows=" + n);
        });
        exec(m, "DDL", "CREATE TABLE IF NOT EXISTS probe_tmp (id BIGINT PRIMARY KEY AUTO_INCREMENT, v VARCHAR(10))",
                (s, rs) -> check("DDL create", true, "ok"));
        exec(m, "DDL drop", "DROP TABLE probe_tmp", (s, rs) -> check("DDL drop", true, "ok"));
        try (Connection c = m.get().getConnection(); Statement s = c.createStatement()) {
            try (HintManager h = HintManager.getInstance()) {
                h.addTableShardingValue("timeout_task", "timeout_task_03");
                s.executeUpdate("INSERT INTO timeout_task (task_type,biz_id,status,retry_count) VALUES ('T','h1','WAITING',0)");
            }
            ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM timeout_task_03");
            rs.next();
            check("hint routed write to timeout_task_03", rs.getInt(1) == 1, "count=" + rs.getInt(1));
        } catch (Exception e) {
            check("hint routed write", false, firstLine(e));
        }
    }

    static void tx(Mgr m) {
        try (Connection c = m.get().getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO product_order (order_no,user_id,status,msg) VALUES ('TX-1',1,'W','a')");
                try (HintManager h = HintManager.getInstance()) {
                    h.addTableShardingValue("timeout_task", "timeout_task_05");
                    s.executeUpdate("INSERT INTO timeout_task (task_type,biz_id,status,retry_count) VALUES ('T','tx1','WAITING',0)");
                }
                long a = one(c, "SELECT CONNECTION_ID()");
                s.executeUpdate("INSERT INTO user_account (id,name) VALUES (999,'z')");
                long b = one(c, "SELECT CONNECTION_ID()");
                check("same MySQL session id across sharded+unsharded writes", a == b, "a=" + a + " b=" + b);
            }
            c.rollback();
            check("rollback cleared unsharded row", countRaw("SELECT COUNT(*) FROM product_order WHERE order_no='TX-1'") == 0,
                    "rows=" + countRaw("SELECT COUNT(*) FROM product_order WHERE order_no='TX-1'"));
            check("rollback cleared sharded row", countRaw("SELECT COUNT(*) FROM timeout_task_05") == 0,
                    "rows=" + countRaw("SELECT COUNT(*) FROM timeout_task_05"));
            c.setAutoCommit(true);
        } catch (Exception e) {
            check("mixed tx", false, firstLine(e));
        }
    }

    // ---------- infra ----------

    static final class Mgr implements AutoCloseable {
        private final String url;
        private DataSource cached;
        Mgr(String url) { this.url = url; }
        DataSource get() throws SQLException {
            if (cached == null) {
                try (Connection c = DriverManager.getConnection(url)) { }
                try {
                    cached = cache().get(url);
                } catch (Exception e) {
                    throw new SQLException("cache lookup failed", e);
                }
                if (cached == null) throw new SQLException("not cached: " + url);
            }
            return cached;
        }
        DataSource real() throws SQLException { return get(); }
        public void close() { }
    }

    static Map<String, DataSource> cache() throws Exception {
        Field f = Class.forName("org.apache.shardingsphere.driver.ShardingSphereDriver").getDeclaredField("DATA_SOURCE_CACHE");
        f.setAccessible(true);
        Object cache = f.get(null);
        return (Map<String, DataSource>) cache.getClass().getMethod("getDataSourceMap").invoke(cache);
    }

    static DataSource hikari() throws Exception {
        Class<?> hk = Class.forName("com.zaxxer.hikari.HikariDataSource");
        DataSource ds = (DataSource) hk.getDeclaredConstructor().newInstance();
        hk.getMethod("setJdbcUrl", String.class).invoke(ds, RAW_URL);
        hk.getMethod("setUsername", String.class).invoke(ds, "root");
        hk.getMethod("setPassword", String.class).invoke(ds, "");
        hk.getMethod("setDriverClassName", String.class).invoke(ds, "com.mysql.cj.jdbc.Driver");
        hk.getMethod("setMaximumPoolSize", int.class).invoke(ds, 10);
        hk.getMethod("setMinimumIdle", int.class).invoke(ds, 1);
        hk.getMethod("setConnectionTimeout", long.class).invoke(ds, 3000L);
        return ds;
    }

    static void seed(DataSource ds) throws SQLException {
        try (Connection c = ds.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS user_account (id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(50))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS product_order (id BIGINT PRIMARY KEY AUTO_INCREMENT, order_no VARCHAR(40) UNIQUE, user_id BIGINT, status VARCHAR(20), msg VARCHAR(50))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS review_task (id BIGINT PRIMARY KEY AUTO_INCREMENT, task_id BIGINT)");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS errand_order (id BIGINT PRIMARY KEY AUTO_INCREMENT, lock_seq INT)");
            for (int i = 0; i < 16; i++) {
                s.executeUpdate("CREATE TABLE IF NOT EXISTS timeout_task_" + String.format("%02d", i)
                        + " (id BIGINT PRIMARY KEY AUTO_INCREMENT, task_type VARCHAR(40), biz_id VARCHAR(64),"
                        + " action_time DATETIME, status VARCHAR(20), retry_count INT DEFAULT 0, next_fire_time DATETIME,"
                        + " created_at DATETIME, updated_at DATETIME, UNIQUE KEY uk_type_biz (task_type, biz_id))");
                s.executeUpdate("DELETE FROM timeout_task_" + String.format("%02d", i));
            }
            s.executeUpdate("DELETE FROM user_account");
            s.executeUpdate("DELETE FROM product_order");
            s.executeUpdate("DELETE FROM review_task");
            s.executeUpdate("INSERT INTO user_account (id,name) VALUES (1,'alice')");
            s.executeUpdate("INSERT INTO product_order (order_no,user_id,status,msg) VALUES ('BASE-1',1,'WAITING','b')");
        }
    }

    static long one(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static int countRaw(String sql) {
        try (Connection c = raw.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            return -999;
        }
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
        throw new IllegalStateException("no contextManager on " + ds.getClass());
    }

    interface Sink { void accept(Statement s, ResultSet rs) throws Exception; }

    static void exec(Mgr m, String tag, String sql, Sink sink) {
        try (Connection conn = m.get().getConnection(); Statement s = conn.createStatement()) {
            boolean hasRs = s.execute(sql);
            sink.accept(s, hasRs ? s.getResultSet() : null);
        } catch (Exception e) {
            check(tag, false, firstLine(e));
        }
    }

    static void check(String what, boolean ok, String detail) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "  [PASS] " : "  [FAIL] ") + what + "  ::  " + detail);
    }

    static String firstLine(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        String s = r.toString();
        return s.length() > 260 ? s.substring(0, 260) + "..." : s;
    }
}
