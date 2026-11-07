import org.apache.shardingsphere.infra.hint.HintManager;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.*;
import java.util.*;

/**
 * Probe #3: same driver/YAML shape as the real timeout-center sharding.yaml, minus the custom
 * algorithm class. Two variants differ ONLY in one line: presence of `databaseName: probe`.
 *
 *   (1) probe-sharding.yaml  -- no databaseName (== exactly what timeout-center ships)
 *   (2) probe-named.yaml     -- databaseName: probe
 *
 * This isolates what decides whether the 30 unconfigured business tables are routable.
 */
public final class NamedProbe {

    static final String HOST = "127.0.0.1";
    static final int PORT = 3307;
    static final String SCHEMA = "probe";
    static final String RAW_URL = "jdbc:mysql://" + HOST + ":" + PORT + "/" + SCHEMA
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8";

    static DataSource raw;
    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        raw = hikari();
        seed(raw);
        System.out.println("MySQL schema " + SCHEMA + " has "
                + countRaw("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='" + SCHEMA + "'") + " tables");

        run("A. NO databaseName  (exactly backend/timeout-center/sharding.yaml shape)", "probe-sharding.yaml");
        run("B. WITH databaseName: probe", "probe-named.yaml");

        System.out.println();
        System.out.println("=== NAMED PROBE RESULT: PASS=" + pass + " FAIL=" + fail + " ===");
    }

    static void run(String title, String yaml) throws Exception {
        System.out.println();
        System.out.println("================ " + title + " ================");
        DataSourceManager ds = new DataSourceManager("jdbc:shardingsphere:classpath:" + yaml);
        try {
            section("metadata shape");
            shape(ds);
            section("SQL against unconfigured business tables");
            unconfigured(ds);
            section("mixed sharded + unsharded transaction");
            tx(ds);
            section("feature matrix");
            features(ds);
        } finally {
            ds.close();
        }
    }

    // ---------- metadata ----------

    static void shape(DataSourceManager ds) {
        try {
            Object ctx = contextManagerOf(ds.real());
            System.out.println("  [info] allDatabaseNames = " + ctx.getClass().getMethod("getAllDatabaseNames").invoke(ctx));
            System.out.println("  [info] preSelectedDatabaseName = "
                    + ctx.getClass().getMethod("getPreSelectedDatabaseName").invoke(ctx));
            for (Object name : (Collection<?>) ctx.getClass().getMethod("getAllDatabaseNames").invoke(ctx)) {
                Object db = ctx.getClass().getMethod("getDatabase", String.class).invoke(ctx, name);
                System.out.println("  [info] database '" + name + "' isComplete="
                        + db.getClass().getMethod("isComplete").invoke(db) + " containsDataSource="
                        + db.getClass().getMethod("containsDataSource").invoke(db));
                for (Object schema : (Collection<?>) db.getClass().getMethod("getAllSchemas").invoke(db)) {
                    System.out.println("  [info]   schema '" + schema.getClass().getMethod("getName").invoke(schema) + "'");
                    for (String m : new String[]{"getAllTables", "getAllViews"}) {
                        try {
                            Object got = schema.getClass().getMethod(m).invoke(schema);
                            TreeSet<String> names = new TreeSet<>();
                            if (got instanceof Map) {
                                for (Object k : ((Map<?, ?>) got).keySet()) names.add(String.valueOf(k));
                            }
                            System.out.println("  [info]     " + m + " (" + names.size() + "): " + names);
                        } catch (NoSuchMethodException ignore) {
                        }
                    }
                }
                Object ruleMd = db.getClass().getMethod("getRuleMetaData").invoke(db);
                Object rulesObj = ruleMd.getClass().getMethod("getRules").invoke(ruleMd);
                for (Object rule : (Collection<?>) rulesObj) {
                    String rn = rule.getClass().getSimpleName();
                    System.out.println("  [info]   rule: " + rn);
                    if (rn.contains("Single")) {
                        Object conf = rule.getClass().getMethod("getConfiguration").invoke(rule);
                        System.out.println("  [info]     defaultDataSource = "
                                + conf.getClass().getMethod("getDefaultDataSource").invoke(conf)
                                + ", tables = " + conf.getClass().getMethod("getTables").invoke(conf));
                        Object nodeMap = rule.getClass().getMethod("getSingleTableDataNodes").invoke(rule);
                        System.out.println("  [info]     singleTableDataNodes (" + ((Map<?, ?>) nodeMap).size() + "):");
                        int i = 0;
                        for (Map.Entry<?, ?> e : ((Map<?, ?>) nodeMap).entrySet()) {
                            if (i++ >= 6) { System.out.println("  [info]       ... (" + ((Map<?, ?>) nodeMap).size() + " total)"); break; }
                            System.out.println("  [info]       " + e.getKey() + " -> " + e.getValue());
                        }
                    } else if (rn.contains("Sharding")) {
                        Object conf = rule.getClass().getMethod("getConfiguration").invoke(rule);
                        System.out.println("  [info]     logicTableNames = "
                                + conf.getClass().getMethod("getLogicTableNames").invoke(conf));
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("  [info] shape probe failed: " + firstLine(t));
        }
    }

    // ---------- checks ----------

    static void unconfigured(DataSourceManager ds) {
        exec(ds, "SELECT unconfigured", "SELECT id, name FROM user_account WHERE id = 1", (s, rs) -> {
            rs.next();
            check("unconfigured SELECT passthrough", "alice".equals(rs.getString(2)), "name=" + rs.getString(2));
        });
        exec(ds, "INSERT unconfigured", "INSERT INTO product_order (order_no,user_id,status,msg) VALUES ('PO-1',7,'WAITING','x')",
                (s, rs) -> check("unconfigured INSERT passthrough", s.getUpdateCount() == 1, "updateCount=" + s.getUpdateCount()));
        exec(ds, "UPDATE unconfigured", "UPDATE product_order SET msg='z' WHERE order_no='PO-1'",
                (s, rs) -> check("unconfigured UPDATE passthrough", s.getUpdateCount() == 1, "updateCount=" + s.getUpdateCount()));
        exec(ds, "JOIN unconfigured", "SELECT o.id FROM product_order o JOIN user_account u ON u.id=o.user_id", (s, rs) -> {
            int n = 0; while (rs.next()) n++;
            check("unconfigured JOIN passthrough", n >= 1, "rows=" + n);
        });
        exec(ds, "COUNT unconfigured", "SELECT COUNT(*) FROM review_task", (s, rs) -> {
            rs.next();
            check("unconfigured COUNT passthrough", true, "count=" + rs.getInt(1));
        });
        exec(ds, "schema-qualified schema name", "SELECT id FROM " + SCHEMA + ".user_account", (s, rs) -> {
            int n = 0; while (rs.next()) n++;
            check("schema-qualified (" + SCHEMA + ".) unconfigured table", n == 1, "rows=" + n);
        });
        exec(ds, "schema-qualified logic_db", "SELECT id FROM logic_db.user_account", (s, rs) -> {
            int n = 0; while (rs.next()) n++;
            check("schema-qualified (logic_db.) unconfigured table", n == 1, "rows=" + n);
        });
        exec(ds, "unconfigured DDL", "CREATE TABLE IF NOT EXISTS probe_tmp (id BIGINT PRIMARY KEY AUTO_INCREMENT, v VARCHAR(10))",
                (s, rs) -> check("unconfigured DDL CREATE passthrough", true, "ok"));
        exec(ds, "unconfigured DDL drop", "DROP TABLE probe_tmp", (s, rs) ->
                check("unconfigured DDL DROP passthrough", true, "ok"));
        exec(ds, "physical shard", "SELECT COUNT(*) FROM timeout_task_01", (s, rs) -> {
            rs.next();
            check("naming a physical shard directly", true, "count=" + rs.getInt(1));
        });
        exec(ds, "sharded read logical", "SELECT COUNT(*) FROM timeout_task", (s, rs) -> {
            rs.next();
            check("logical shard table readable without hint (broadcast)", true, "count=" + rs.getInt(1));
        });
    }

    static void tx(DataSourceManager ds) {
        try (Connection c = ds.get().getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO product_order (order_no,user_id,status,msg) VALUES ('TX-1',1,'W','a')");
                try (HintManager h = HintManager.getInstance()) {
                    h.addTableShardingValue("timeout_task", "timeout_task_05");
                    s.executeUpdate("INSERT INTO timeout_task (task_type,biz_id,status,retry_count) VALUES ('T','tx1','WAITING',0)");
                }
                long id1 = one(c, "SELECT CONNECTION_ID()");
                s.executeUpdate("INSERT INTO user_account (id,name) VALUES (999,'zz')");
                long id2 = one(c, "SELECT CONNECTION_ID()");
                check("same MySQL session across sharded + unsharded writes", id1 == id2, "id1=" + id1 + " id2=" + id2);
            }
            c.rollback();
            check("one rollback clears unsharded row",
                    countRaw("SELECT COUNT(*) FROM product_order WHERE order_no='TX-1'") == 0,
                    "rows=" + countRaw("SELECT COUNT(*) FROM product_order WHERE order_no='TX-1'"));
            check("one rollback clears sharded row",
                    countRaw("SELECT COUNT(*) FROM timeout_task_05") == 0,
                    "rows=" + countRaw("SELECT COUNT(*) FROM timeout_task_05"));
            c.setAutoCommit(true);
        } catch (Exception e) {
            check("mixed tx", false, firstLine(e));
        }

        try (Connection c = ds.get().getConnection()) {
            c.setAutoCommit(false);
            Object mgr = c.getClass().getMethod("getDatabaseConnectionManager").invoke(c);
            Class<?> modeCls = Class.forName("org.apache.shardingsphere.infra.executor.sql.execute.engine.ConnectionMode");
            Object mode = Enum.valueOf((Class<Enum>) modeCls, "MEMORY_STRICTLY");
            java.lang.reflect.Method getConns = mgr.getClass()
                    .getMethod("getConnections", String.class, String.class, int.class, int.class, modeCls);
            List<?> c1 = (List<?>) getConns.invoke(mgr, "ds0", SCHEMA, 1, 1, mode);
            List<?> c2 = (List<?>) getConns.invoke(mgr, "ds0", SCHEMA, 1, 1, mode);
            check("physical connection reused inside tx",
                    c1.size() == 1 && c2.size() == 1 && c1.get(0) == c2.get(0),
                    "size1=" + c1.size() + " size2=" + c2.size() + " same="
                            + (c1.size() == 1 && c2.size() == 1 && c1.get(0) == c2.get(0)));
            c.rollback();
        } catch (Throwable e) {
            check("physical connection reuse", false, firstLine(e));
        }
    }

    static void features(DataSourceManager ds) {
        String[] sqls = {
                "SELECT 1",
                "SHOW TABLES",
                "SHOW CREATE TABLE product_order",
                "DESC product_order",
                "EXPLAIN SELECT * FROM product_order",
                "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='probe' LIMIT 3",
                "SELECT DATABASE()",
                "SELECT COUNT(*) FROM (SELECT id FROM product_order) t",
                "SELECT * FROM product_order FOR UPDATE",
                "INSERT INTO product_order (order_no,user_id,status,msg) VALUES ('OK-1',1,'W','w') ON DUPLICATE KEY UPDATE msg='dup'",
                "INSERT IGNORE INTO product_order (order_no,user_id,status,msg) VALUES ('OK-1',1,'W','w2')",
                "UPDATE product_order SET msg = CASE order_no WHEN 'OK-1' THEN 'c1' ELSE msg END WHERE order_no IN ('OK-1')",
                "SELECT LAST_INSERT_ID()",
                "TRUNCATE TABLE review_task",
        };
        for (String sql : sqls) {
            try (Connection c = ds.get().getConnection(); Statement s = c.createStatement()) {
                boolean hasRs = s.execute(sql);
                if (hasRs) {
                    ResultSet rs = s.getResultSet();
                    int n = 0;
                    while (rs.next() && n < 5) n++;
                    check("SQL ok: " + brief(sql), true, "rows=" + n);
                } else {
                    check("SQL ok: " + brief(sql), true, "updateCount=" + s.getUpdateCount());
                }
            } catch (Exception e) {
                check("SQL ok: " + brief(sql), false, firstLine(e));
            }
        }
        try (Connection c = ds.get().getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO product_order (order_no,user_id,status,msg) VALUES (?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, "GK-1"); ps.setLong(2, 1); ps.setString(3, "W"); ps.setString(4, "g");
                ps.executeUpdate();
                ResultSet ks = ps.getGeneratedKeys();
                boolean ok = ks.next();
                check("unconfigured useGeneratedKeys", ok, "key=" + (ok ? ks.getLong(1) : "none"));
            }
            long shardKey;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO timeout_task (task_type,biz_id,status,retry_count) VALUES (?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                try (HintManager h = HintManager.getInstance()) {
                    h.addTableShardingValue("timeout_task", "timeout_task_07");
                    ps.setString(1, "T"); ps.setString(2, "gk"); ps.setString(3, "WAITING"); ps.setInt(4, 0);
                    ps.executeUpdate();
                }
                ResultSet ks = ps.getGeneratedKeys();
                boolean ok = ks.next();
                shardKey = ok ? ks.getLong(1) : -1;
                check("sharded useGeneratedKeys", ok, "key=" + shardKey);
            }
            long last = one(c, "SELECT LAST_INSERT_ID()");
            check("LAST_INSERT_ID() == sharded insert id", last == shardKey, "last=" + last + " shard=" + shardKey);
        } catch (Exception e) {
            check("generated keys", false, firstLine(e));
        }
    }

    // ---------- infra ----------

    static final class DataSourceManager implements AutoCloseable {
        private final String url;
        private DataSource cached;
        DataSourceManager(String url) { this.url = url; }
        DataSource get() throws SQLException {
            if (cached == null) {
                try (Connection c = DriverManager.getConnection(url)) { /* force DS creation + metadata */ }
                try {
                    cached = cache().get(url);
                } catch (Exception e) {
                    throw new SQLException("cache lookup failed", e);
                }
                if (cached == null) throw new SQLException("no cached ShardingSphereDataSource for " + url);
            }
            return cached;
        }
        DataSource real() throws SQLException { get(); return cached; }
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

    static Object contextManagerOf(DataSource ds) throws Exception {
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

    static void exec(DataSourceManager ds, String tag, String sql, Sink sink) {
        try (Connection conn = ds.get().getConnection(); Statement s = conn.createStatement()) {
            boolean hasRs = s.execute(sql);
            sink.accept(s, hasRs ? s.getResultSet() : null);
        } catch (Exception e) {
            check(tag, false, firstLine(e));
        }
    }

    static void section(String t) {
        System.out.println("  ---- " + t + " ----");
    }

    static void check(String what, boolean ok, String detail) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "  [PASS] " : "  [FAIL] ") + what + "  ::  " + detail);
    }

    static String firstLine(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        String m = r.toString();
        return m.length() > 300 ? m.substring(0, 300) + "..." : m;
    }

    static String brief(String sql) {
        String s = sql.replaceAll("\\s+", " ");
        return s.length() > 58 ? s.substring(0, 58) + "..." : s;
    }
}
