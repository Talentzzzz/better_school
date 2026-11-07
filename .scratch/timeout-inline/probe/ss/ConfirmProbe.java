import org.apache.shardingsphere.infra.hint.HintManager;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Confirmation probe (resets data per variant):
 *   A probe-sharding.yaml  -- exactly the timeout-center shape, no !SINGLE  (expected: unusable)
 *   B probe-star.yaml      -- + !SINGLE { tables: ["*.*.*"] }                (expected: fully usable)
 *   C probe-explicit.yaml  -- + !SINGLE { defaultDataSource, explicit tables } (expected: fully usable)
 *
 * Plus a dedicated HintManager thread-transmission investigation.
 */
public final class ConfirmProbe {

    static final String SCHEMA = "probe";
    static final String RAW_URL = "jdbc:mysql://127.0.0.1:3307/probe?useSSL=false&allowPublicKeyRetrieval=true"
            + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8";

    static DataSource raw;
    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        raw = hikari();
        reset(raw);

        run("A noSingle   probe-sharding.yaml", "probe-sharding.yaml");
        run("B singleStar probe-star.yaml", "probe-star.yaml");
        run("C singleExpl probe-explicit.yaml", "probe-explicit.yaml");

        hintThreads();

        System.out.println();
        System.out.println("=== CONFIRM RESULT: PASS=" + pass + " FAIL=" + fail + " ===");
    }

    static void run(String label, String yaml) throws Exception {
        reset(raw);
        System.out.println();
        System.out.println("################ " + label + " ################");
        Mgr m = new Mgr("jdbc:shardingsphere:classpath:" + yaml);
        try {
            shape(m);
            checks(m);
        } catch (Throwable t) {
            System.out.println("  [FATAL] " + firstLine(t));
        } finally {
            m.close();
        }
    }

    static void shape(Mgr m) {
        try {
            Object ctx = cm(m.real());
            for (Object name : (Collection<?>) ctx.getClass().getMethod("getAllDatabaseNames").invoke(ctx)) {
                Object db = ctx.getClass().getMethod("getDatabase", String.class).invoke(ctx, name);
                if (!(Boolean) db.getClass().getMethod("isComplete").invoke(db)) {
                    continue;
                }
                System.out.println("  [info] logical db '" + name + "' units="
                        + ctx.getClass().getMethod("getStorageUnits", String.class).invoke(ctx, name));
                Object ruleMd = db.getClass().getMethod("getRuleMetaData").invoke(db);
                for (Object rule : (Collection<?>) ruleMd.getClass().getMethod("getRules").invoke(ruleMd)) {
                    String rn = rule.getClass().getSimpleName();
                    System.out.println("  [info]   rule " + rn + " :: " + rule.getClass().getMethod("getConfiguration").invoke(rule));
                    if (rn.contains("Single")) {
                        Map<?, ?> nodes = (Map<?, ?>) rule.getClass().getMethod("getSingleTableDataNodes").invoke(rule);
                        SortedSet<String> keys = new TreeSet<>();
                        for (Object k : nodes.keySet()) keys.add(String.valueOf(k));
                        System.out.println("  [info]   singleTableDataNodes (" + nodes.size() + ") keys=" + keys);
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("  [info] shape failed: " + firstLine(t));
        }
    }

    static void checks(Mgr m) {
        exec(m, "SELECT business", "SELECT name FROM user_account WHERE id=1", (s, rs) -> {
            rs.next();
            check("business SELECT", "alice".equals(rs.getString(1)), "name=" + rs.getString(1));
        });
        exec(m, "INSERT business", "INSERT INTO product_order (order_no,user_id,status,msg) VALUES ('P1',7,'W','x')",
                (s, rs) -> check("business INSERT", s.getUpdateCount() == 1, "n=" + s.getUpdateCount()));
        exec(m, "JOIN business", "SELECT o.id FROM product_order o JOIN user_account u ON u.id=o.user_id", (s, rs) -> {
            int n = 0; while (rs.next()) n++;
            check("business JOIN", n >= 1, "rows=" + n);
        });
        exec(m, "EXPLAIN business", "EXPLAIN SELECT * FROM product_order", (s, rs) -> {
            int n = 0; while (rs.next()) n++;
            check("EXPLAIN business table", n >= 1, "rows=" + n);
        });
        exec(m, "ODKU", "INSERT INTO product_order (order_no,user_id,status,msg) VALUES ('P1',7,'W','y')"
                + " ON DUPLICATE KEY UPDATE msg='y'", (s, rs) ->
                check("ON DUPLICATE KEY UPDATE", s.getUpdateCount() >= 1, "n=" + s.getUpdateCount()));
        exec(m, "INSERT IGNORE", "INSERT IGNORE INTO product_order (order_no,user_id,status,msg) VALUES ('P1',7,'W','z')",
                (s, rs) -> check("INSERT IGNORE", s.getUpdateCount() == 0, "n=" + s.getUpdateCount()));
        exec(m, "subquery", "SELECT COUNT(*) FROM (SELECT id FROM product_order) t", (s, rs) -> {
            rs.next();
            check("subquery over business table", true, "count=" + rs.getInt(1));
        });
        exec(m, "DDL", "CREATE TABLE IF NOT EXISTS zz_tmp (id INT PRIMARY KEY)", (s, rs) -> check("DDL", true, "ok"));
        exec(m, "DDL drop", "DROP TABLE zz_tmp", (s, rs) -> check("DDL drop", true, "ok"));
        exec(m, "sharded broadcast read", "SELECT COUNT(*) FROM timeout_task", (s, rs) -> {
            rs.next();
            check("sharded logical read (broadcast, no hint)", true, "count=" + rs.getInt(1));
        });

        try (Connection c = m.get().getConnection(); Statement s = c.createStatement()) {
            try (HintManager h = HintManager.getInstance()) {
                h.addTableShardingValue("timeout_task", "timeout_task_03");
                s.executeUpdate("INSERT INTO timeout_task (task_type,biz_id,status,retry_count) VALUES ('T','c1','WAITING',0)");
            }
            ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM timeout_task_03");
            rs.next();
            check("hinted write routed to shard 03", rs.getInt(1) == 1, "count=" + rs.getInt(1));
        } catch (Exception e) {
            check("hinted write", false, firstLine(e));
        }

        try (Connection c = m.get().getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO product_order (order_no,user_id,status,msg) VALUES ('T1',1,'W','a')");
                long id1 = one(c, "SELECT CONNECTION_ID()");
                try (HintManager h = HintManager.getInstance()) {
                    h.addTableShardingValue("timeout_task", "timeout_task_05");
                    s.executeUpdate("INSERT INTO timeout_task (task_type,biz_id,status,retry_count) VALUES ('T','t1','WAITING',0)");
                }
                long id2 = one(c, "SELECT CONNECTION_ID()");
                s.executeUpdate("INSERT INTO review_task (task_id) VALUES (5)");
                long id3 = one(c, "SELECT CONNECTION_ID()");
                check("one MySQL session for sharded+unsharded writes", id1 == id2 && id2 == id3, "ids=" + id1 + "," + id2 + "," + id3);
            }
            c.rollback();
            check("one rollback clears all three tables",
                    countRaw("SELECT COUNT(*) FROM product_order WHERE order_no='T1'") == 0
                            && countRaw("SELECT COUNT(*) FROM timeout_task_05") == 0
                            && countRaw("SELECT COUNT(*) FROM review_task") == 0,
                    "po=" + countRaw("SELECT COUNT(*) FROM product_order WHERE order_no='T1'")
                            + " tt=" + countRaw("SELECT COUNT(*) FROM timeout_task_05")
                            + " rt=" + countRaw("SELECT COUNT(*) FROM review_task"));
            c.setAutoCommit(true);
        } catch (Exception e) {
            check("mixed transaction", false, firstLine(e));
        }

        try (Connection c = m.get().getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("UPDATE timeout_task SET retry_count=1 WHERE biz_id IN ('c1','none')");
            check("sharded UPDATE without hint must fail", false, "succeeded");
        } catch (Exception e) {
            check("sharded UPDATE without hint fails fast", true, brief(firstLine(e)));
        }
    }

    // ---------- HintManager thread transmission ----------

    static void hintThreads() {
        System.out.println();
        System.out.println("################ HintManager thread-transmission ################");
        try {
            Field f = HintManager.class.getDeclaredField("HINT_MANAGER_HOLDER");
            f.setAccessible(true);
            Object holder = f.get(null);
            System.out.println("  [info] HINT_MANAGER_HOLDER class = " + holder.getClass().getName());
            for (Class<?> c = holder.getClass(); c != null; c = c.getSuperclass()) {
                if (c.getSimpleName().equals("ThreadLocal")) {
                    System.out.println("  [info] ThreadLocal impl class = " + c.getName());
                    break;
                }
            }
        } catch (Throwable t) {
            System.out.println("  [info] field probe failed: " + firstLine(t));
        }
        try (HintManager h = HintManager.getInstance()) {
            h.addTableShardingValue("timeout_task", "timeout_task_11");
            final String[] childSees = new String[1];
            Thread t = new Thread(() -> childSees[0] = String.valueOf(HintManager.getTableShardingValues("timeout_task")));
            t.start();
            t.join();
            check("plain child Thread inherits the parent hint (TransmittableThreadLocal)",
                    !"[]".equals(childSees[0]), "child tableShardingValues=" + childSees[0]);
        } catch (Exception e) {
            check("thread transmission", false, firstLine(e));
        }

        // thread-pool reuse: does a hint set on a pooled worker survive into the NEXT task on that worker?
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> {
                try (HintManager h = HintManager.getInstance()) {
                    h.addTableShardingValue("timeout_task", "timeout_task_12");
                }
            }).get();
            Boolean leaked = pool.submit(HintManager::isInstantiated).get();
            check("hint does not leak into the next task on a reused pooled thread",
                    !leaked, "next task isInstantiated=" + leaked);
        } catch (Exception e) {
            check("pooled thread reuse", false, firstLine(e));
        } finally {
            pool.shutdownNow();
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
        Object c = f.get(null);
        return (Map<String, DataSource>) c.getClass().getMethod("getDataSourceMap").invoke(c);
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

    static void reset(DataSource ds) throws SQLException {
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
            }
            for (int i = 0; i < 16; i++) {
                s.executeUpdate("DELETE FROM timeout_task_" + String.format("%02d", i));
            }
            s.executeUpdate("DELETE FROM user_account");
            s.executeUpdate("DELETE FROM product_order");
            s.executeUpdate("DELETE FROM review_task");
            s.executeUpdate("INSERT INTO user_account (id,name) VALUES (1,'alice')");
            s.executeUpdate("INSERT INTO product_order (order_no,user_id,status,msg) VALUES ('BASE',1,'WAITING','b')");
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
        throw new IllegalStateException("no contextManager");
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
        return r.toString();
    }

    static String brief(String s) {
        String x = s.replaceAll("\\s+", " ");
        return x.length() > 100 ? x.substring(0, 100) + "..." : x;
    }
}
