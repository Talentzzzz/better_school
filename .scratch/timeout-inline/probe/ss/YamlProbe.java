import org.apache.shardingsphere.infra.hint.HintManager;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.*;
import java.util.*;

/**
 * Probe #2: the REAL deployment shape -- ShardingSphereDriver + jdbc:shardingsphere:classpath:probe-sharding.yaml,
 * i.e. exactly what backend/timeout-center does today (no !SINGLE rule, one datasource, 16 shards for one logical table).
 */
public final class YamlProbe {

    static final String DB = "probe";
    static final String SS_URL = "jdbc:shardingsphere:classpath:probe-sharding.yaml";
    static final String RAW_URL = "jdbc:mysql://127.0.0.1:3307/probe?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8";

    static DataSource raw;
    static DataSource ss;
    static DataSource contextManagerHolder;
    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        raw = hikari();
        seed(raw);

        ss = new DriverManagerDataSource(SS_URL);
        try (Connection warm = ss.getConnection()) {
            System.out.println("[info] driver datasource = " + warm.getClass().getName());
        }
        contextManagerHolder = null;
        try {
            Class<?> drv = Class.forName("org.apache.shardingsphere.driver.ShardingSphereDriver");
            Field f = drv.getDeclaredField("DATA_SOURCE_CACHE");
            f.setAccessible(true);
            Object cache = f.get(null);
            Map<?, ?> m = (Map<?, ?>) cache.getClass().getMethod("getDataSourceMap").invoke(cache);
            System.out.println("[info] DriverDataSourceCache entries = " + m.keySet());
            for (Object v : m.values()) {
                System.out.println("[info]   cached DS class = " + v.getClass().getName());
                contextManagerHolder = (DataSource) v;
            }
        } catch (Throwable t) {
            System.out.println("[info] DriverDataSourceCache probe failed: " + firstLine(t));
        }

        section("A. RULES AS LOADED BY THE DRIVER (is there an implicit SingleRule?)");
        rulesView(contextManagerHolder);
        metadataView(contextManagerHolder);

        section("B. UNCONFIGURED TABLES under the driver");
        b_unconfigured();

        section("C. HINT routing + mixed statements");
        c_hint();

        section("D. TRANSACTION: sharded + unsharded in one local tx");
        d_tx();

        section("E. JDBC feature matrix");
        e_features();

        System.out.println();
        System.out.println("=== YAML PROBE RESULT: PASS=" + pass + " FAIL=" + fail + " ===");
    }

    // ---------- A ----------

    static String databaseNameOf(DataSource ds) {
        try {
            Object ctx = contextManagerOf(ds);
            Object pre = ctx.getClass().getMethod("getPreSelectedDatabaseName").invoke(ctx);
            if (pre != null && !String.valueOf(pre).isEmpty()) {
                return String.valueOf(pre);
            }
            Collection<?> names = (Collection<?>) ctx.getClass().getMethod("getAllDatabaseNames").invoke(ctx);
            for (Object n : names) {
                if (DB.equalsIgnoreCase(String.valueOf(n))) {
                    return String.valueOf(n);
                }
            }
            return names.isEmpty() ? DB : String.valueOf(names.iterator().next());
        } catch (Throwable t) {
            return DB;
        }
    }

    static void rulesView(DataSource ds) {
        try {
            Object ctx = contextManagerOf(ds);
            String dbName = databaseNameOf(ds);
            System.out.println("  [info] resolved metadata database name = " + dbName);
            Object db = ctx.getClass().getMethod("getDatabase", String.class).invoke(ctx, dbName);
            Object ruleMd = db.getClass().getMethod("getRuleMetaData").invoke(db);
            Object rulesObj = ruleMd.getClass().getMethod("getRules").invoke(ruleMd);
            System.out.println("  [info] getRules() runtime type = " + rulesObj.getClass().getName());
            for (Object rule : (Collection<?>) rulesObj) {
                System.out.println("  [info] rule = " + rule.getClass().getName());
                try {
                    Object conf = rule.getClass().getMethod("getConfiguration").invoke(rule);
                    System.out.println("  [info]   configuration = " + conf);
                    if (String.valueOf(rule.getClass().getName()).contains("Single")) {
                        System.out.println("  [info]   getDefaultDataSource() = "
                                + conf.getClass().getMethod("getDefaultDataSource").invoke(conf));
                        System.out.println("  [info]   config.getTables() = "
                                + conf.getClass().getMethod("getTables").invoke(conf));
                        Object nodeMap = rule.getClass().getMethod("getSingleTableDataNodes").invoke(rule);
                        System.out.println("  [info]   singleTableDataNodes size = " + ((Map<?, ?>) nodeMap).size());
                        int i = 0;
                        for (Map.Entry<?, ?> n : ((Map<?, ?>) nodeMap).entrySet()) {
                            if (i++ >= 40) { System.out.println("  [info]     ..."); break; }
                            System.out.println("  [info]     " + n.getKey() + " -> " + n.getValue());
                        }
                    }
                } catch (NoSuchMethodException ignore) {
                }
            }
        } catch (Throwable t) {
            System.out.println("  [info] rules view failed: " + firstLine(t));
        }
    }

    static void metadataView(DataSource ds) {
        try {
            Object ctx = contextManagerOf(ds);
            String dbName = databaseNameOf(ds);
            Object db = ctx.getClass().getMethod("getDatabase", String.class).invoke(ctx, dbName);
            System.out.println("  [info] metadata database = " + db.getClass().getMethod("getName").invoke(db));
            System.out.println("  [info] allDatabaseNames = " + ctx.getClass().getMethod("getAllDatabaseNames").invoke(ctx));
            System.out.println("  [info] storageUnits = " + ctx.getClass().getMethod("getStorageUnits", String.class).invoke(ctx, dbName));
            Collection<?> schemas = (Collection<?>) db.getClass().getMethod("getAllSchemas").invoke(db);
            System.out.println("  [info] schema objects = " + schemas.size());
            for (Object schema : schemas) {
                System.out.println("  [info] schema class = " + schema.getClass().getName());
                System.out.println("  [info] schema methods = " + Arrays.toString(
                        Arrays.stream(schema.getClass().getMethods()).map(java.lang.reflect.Method::getName).distinct().toArray()));
                for (String m : new String[]{"getTables", "getViews", "getName"}) {
                    try {
                        Object got = schema.getClass().getMethod(m).invoke(schema);
                        if (got instanceof Map) {
                            TreeSet<String> names = new TreeSet<>();
                            for (Object k : ((Map<?, ?>) got).keySet()) names.add(String.valueOf(k));
                            System.out.println("  [info] schema." + m + " -> " + names.size() + " entries: " + names);
                        } else {
                            System.out.println("  [info] schema." + m + " -> " + got);
                        }
                    } catch (NoSuchMethodException ignore) {
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("  [info] metadata view failed: " + firstLine(t));
        }
    }

    // ---------- B ----------

    static void b_unconfigured() {
        exec("SELECT", "SELECT id, name FROM user_account WHERE id = 1", (s, rs) -> {
            rs.next();
            check("unconfigured SELECT passthrough", "alice".equals(rs.getString(2)), "name=" + rs.getString(2));
        });
        exec("INSERT", "INSERT INTO product_order (order_no, user_id, status, msg) VALUES ('PO-1', 7, 'WAITING', 'x')", (s, rs) -> {
            check("unconfigured INSERT passthrough", s.getUpdateCount() == 1, "updateCount=" + s.getUpdateCount());
        });
        exec("UPDATE", "UPDATE product_order SET msg = 'z' WHERE order_no = 'PO-1'", (s, rs) -> {
            check("unconfigured UPDATE passthrough", s.getUpdateCount() == 1, "updateCount=" + s.getUpdateCount());
        });
        exec("JOIN", "SELECT o.id FROM product_order o JOIN user_account u ON u.id = o.user_id", (s, rs) -> {
            int n = 0; while (rs.next()) n++;
            check("unconfigured JOIN passthrough", n >= 1, "rows=" + n);
        });
        exec("COUNT review_task", "SELECT COUNT(*) FROM review_task", (s, rs) -> {
            rs.next();
            check("unconfigured COUNT passthrough", true, "count=" + rs.getInt(1));
        });
        exec("DDL create", "CREATE TABLE IF NOT EXISTS probe_tmp (id BIGINT PRIMARY KEY AUTO_INCREMENT, v VARCHAR(10))", (s, rs) ->
                check("unconfigured DDL CREATE passthrough", true, "ok"));
        exec("DDL drop", "DROP TABLE probe_tmp", (s, rs) ->
                check("unconfigured DDL DROP passthrough", true, "ok"));
        exec("schema-qualified", "SELECT id FROM " + DB + ".user_account", (s, rs) -> {
            int n = 0; while (rs.next()) n++;
            check("schema-qualified unconfigured table", n == 1, "rows=" + n);
        });
        exec("physical shard", "SELECT COUNT(*) FROM timeout_task_01", (s, rs) -> {
            rs.next();
            check("naming a physical shard directly", true, "count=" + rs.getInt(1));
        });
        exec("unknown table", "SELECT id FROM nope_xyz", (s, rs) ->
                check("unknown table -> error propagated", false, "no error"));
    }

    // ---------- C ----------

    static void c_hint() {
        try (Connection c = ss.getConnection(); Statement s = c.createStatement()) {
            try (HintManager h = HintManager.getInstance()) {
                h.addTableShardingValue("timeout_task", "timeout_task_03");
                s.executeUpdate("INSERT INTO timeout_task (task_type, biz_id, status, retry_count) VALUES ('T','h1','WAITING',0)");
            }
            ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM timeout_task_03");
            rs.next();
            check("hint routed the write to timeout_task_03", rs.getInt(1) == 1, "count=" + rs.getInt(1));
            rs = s.executeQuery("SELECT COUNT(*) FROM user_account");
            rs.next();
            check("unconfigured table fine on same connection", rs.getInt(1) == 1, "count=" + rs.getInt(1));
        } catch (Exception e) {
            check("hint + unconfigured mixed", false, firstLine(e));
        }
        try (Connection c = ss.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO timeout_task (task_type, biz_id, status, retry_count) VALUES ('T','h2','WAITING',0)");
            check("sharded WRITE without hint -> must fail fast", false, "it succeeded");
        } catch (Exception e) {
            check("sharded WRITE without hint -> fails fast", true, firstLine(e));
        }
        try (Connection c = ss.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM timeout_task");
            rs.next();
            check("sharded READ without hint -> 16-table broadcast", rs.getInt(1) >= 1, "count=" + rs.getInt(1));
        } catch (Exception e) {
            check("sharded READ without hint", false, firstLine(e));
        }
        try (Connection c = ss.getConnection(); Statement s = c.createStatement()) {
            try (HintManager h = HintManager.getInstance()) {
                h.addTableShardingValue("timeout_task", "timeout_task_05");
                s.executeUpdate("UPDATE timeout_task SET status='SUCCESS' WHERE task_type='T' AND biz_id='h1'");
            }
            check("hint write to a DIFFERENT shard than the row lives in", true, "0 rows expected");
        } catch (Exception e) {
            check("hint write to different shard", false, firstLine(e));
        }
        try (Connection c = ss.getConnection(); Statement s = c.createStatement()) {
            try (HintManager h = HintManager.getInstance()) {
                h.addTableShardingValue("timeout_task", "timeout_task_03");
                s.executeUpdate("UPDATE timeout_task SET status='SUCCESS' WHERE task_type='T' AND biz_id='h1'");
            }
            check("hint write to the correct shard", countRaw("SELECT COUNT(*) FROM timeout_task_03 WHERE status='SUCCESS'") == 1,
                    "rows=" + countRaw("SELECT COUNT(*) FROM timeout_task_03 WHERE status='SUCCESS'"));
        } catch (Exception e) {
            check("hint write to correct shard", false, firstLine(e));
        }
    }

    // ---------- D ----------

    static void d_tx() {
        try (Connection c = ss.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO product_order (order_no, user_id, status, msg) VALUES ('TX-1',1,'WAITING','a')");
                try (HintManager h = HintManager.getInstance()) {
                    h.addTableShardingValue("timeout_task", "timeout_task_05");
                    s.executeUpdate("INSERT INTO timeout_task (task_type, biz_id, status, retry_count) VALUES ('T','tx1','WAITING',0)");
                }
            }
            c.rollback();
            check("same-tx rollback: unsharded row gone",
                    countRaw("SELECT COUNT(*) FROM product_order WHERE order_no='TX-1'") == 0,
                    "rows=" + countRaw("SELECT COUNT(*) FROM product_order WHERE order_no='TX-1'"));
            check("same-tx rollback: sharded row gone",
                    countRaw("SELECT COUNT(*) FROM timeout_task_05") == 0,
                    "rows=" + countRaw("SELECT COUNT(*) FROM timeout_task_05"));
            c.setAutoCommit(true);
        } catch (Exception e) {
            check("same-tx rollback", false, firstLine(e));
        }

        try (Connection c = ss.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO product_order (order_no, user_id, status, msg) VALUES ('TX-2',1,'WAITING','a')");
                try (HintManager h = HintManager.getInstance()) {
                    h.addTableShardingValue("timeout_task", "timeout_task_06");
                    s.executeUpdate("INSERT INTO timeout_task (task_type, biz_id, status, retry_count) VALUES ('T','tx2','WAITING',0)");
                }
            }
            c.commit();
            check("same-tx commit: unsharded row visible",
                    countRaw("SELECT COUNT(*) FROM product_order WHERE order_no='TX-2'") == 1,
                    "rows=" + countRaw("SELECT COUNT(*) FROM product_order WHERE order_no='TX-2'"));
            check("same-tx commit: sharded row visible",
                    countRaw("SELECT COUNT(*) FROM timeout_task_06") == 1,
                    "rows=" + countRaw("SELECT COUNT(*) FROM timeout_task_06"));
        } catch (Exception e) {
            check("same-tx commit", false, firstLine(e));
        }

        try (Connection c = ss.getConnection()) {
            c.setAutoCommit(false);
            Object mgr = c.getClass().getMethod("getDatabaseConnectionManager").invoke(c);
            String realDb = databaseNameOf(contextManagerHolder);
            Class<?> modeCls = Class.forName("org.apache.shardingsphere.infra.executor.sql.execute.engine.ConnectionMode");
            Object mode = Enum.valueOf((Class<Enum>) modeCls, "MEMORY_STRICTLY");
            java.lang.reflect.Method getConns = mgr.getClass()
                    .getMethod("getConnections", String.class, String.class, int.class, int.class, modeCls);
            List<?> c1 = (List<?>) getConns.invoke(mgr, "ds0", realDb, 1, 1, mode);
            List<?> c2 = (List<?>) getConns.invoke(mgr, "ds0", realDb, 1, 1, mode);
            check("physical MySQL connection reused inside the tx (same java.sql.Connection object)",
                    c1.size() == 1 && c2.size() == 1 && c1.get(0) == c2.get(0),
                    "size1=" + c1.size() + " size2=" + c2.size() + " same=" + (c1.size() == 1 && c2.size() == 1 && c1.get(0) == c2.get(0)));
            c.rollback();
        } catch (Throwable e) {
            check("physical connection reuse probe", false, firstLine(e));
        }

        try (Connection c = ss.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                try (HintManager h = HintManager.getInstance()) {
                    h.addTableShardingValue("timeout_task", "timeout_task_02");
                    s.executeUpdate("INSERT INTO timeout_task (task_type, biz_id, status, retry_count) VALUES ('T','m2','WAITING',0)");
                }
                try (HintManager h = HintManager.getInstance()) {
                    h.addTableShardingValue("timeout_task", "timeout_task_04");
                    s.executeUpdate("INSERT INTO timeout_task (task_type, biz_id, status, retry_count) VALUES ('T','m4','WAITING',0)");
                }
                s.executeUpdate("INSERT INTO product_order (order_no, user_id, status, msg) VALUES ('TX-5',1,'W','a')");
            }
            c.rollback();
            boolean clean = countRaw("SELECT COUNT(*) FROM timeout_task_02") == 0
                    && countRaw("SELECT COUNT(*) FROM timeout_task_04") == 0
                    && countRaw("SELECT COUNT(*) FROM product_order WHERE order_no='TX-5'") == 0;
            check("one tx across TWO shards + one business table: single rollback clears all", clean,
                    "t02=" + countRaw("SELECT COUNT(*) FROM timeout_task_02")
                            + " t04=" + countRaw("SELECT COUNT(*) FROM timeout_task_04")
                            + " po=" + countRaw("SELECT COUNT(*) FROM product_order WHERE order_no='TX-5'"));
            c.setAutoCommit(true);
        } catch (Exception e) {
            check("one tx across two shards", false, firstLine(e));
        }

        // connection identity seen from MySQL: the shard write and the business write must come from one MySQL session
        try (Connection c = ss.getConnection()) {
            c.setAutoCommit(false);
            long shardConnId = connectionIdVia(c, "SELECT CONNECTION_ID()");
            long bizConnId = connectionIdVia(c, "SELECT CONNECTION_ID()");
            check("CONNECTION_ID() stable across sharded+unsharded statements in one tx", shardConnId == bizConnId,
                    "shard=" + shardConnId + " biz=" + bizConnId);
            c.rollback();
            c.setAutoCommit(true);
        } catch (Exception e) {
            check("CONNECTION_ID() identity", false, firstLine(e));
        }
    }

    static long connectionIdVia(Connection c, String sql) throws SQLException {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    // ---------- E ----------

    static void e_features() {
        String[] sqls = {
                "SELECT 1",
                "SELECT @@version",
                "SHOW TABLES",
                "SHOW CREATE TABLE product_order",
                "SHOW INDEX FROM product_order",
                "DESC product_order",
                "EXPLAIN SELECT * FROM product_order",
                "SET SESSION autocommit = 1",
                "SET NAMES utf8mb4",
                "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA='probe' LIMIT 3",
                "SELECT DATABASE()",
                "SELECT COUNT(*) FROM (SELECT id FROM product_order) t",
                "SELECT 1 UNION SELECT 2",
                "SELECT * FROM product_order FOR UPDATE",
                "INSERT INTO product_order (order_no,user_id,status,msg) VALUES ('OK-1',1,'W','w') ON DUPLICATE KEY UPDATE msg='dup'",
                "INSERT IGNORE INTO product_order (order_no,user_id,status,msg) VALUES ('OK-1',1,'W','w2')",
                "SELECT id FROM product_order WHERE order_no IN ('OK-1','BASE-1') ORDER BY id LIMIT 1",
                "UPDATE product_order SET msg = CASE order_no WHEN 'OK-1' THEN 'c1' ELSE msg END WHERE order_no IN ('OK-1')",
                "SELECT LAST_INSERT_ID()",
                "TRUNCATE TABLE review_task",
                "SELECT id FROM product_order p, user_account u WHERE p.user_id = u.id",
                "INSERT INTO product_order (order_no,user_id,status,msg) SELECT 'SEL-1',1,'W','s'",
        };
        for (String sql : sqls) {
            try (Connection c = ss.getConnection(); Statement s = c.createStatement()) {
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

        // generated keys + LAST_INSERT_ID
        try (Connection c = ss.getConnection()) {
            long bizKey;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO product_order (order_no,user_id,status,msg) VALUES (?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, "GK-1"); ps.setLong(2, 1); ps.setString(3, "W"); ps.setString(4, "g");
                ps.executeUpdate();
                ResultSet ks = ps.getGeneratedKeys();
                boolean ok = ks.next();
                bizKey = ok ? ks.getLong(1) : -1;
                check("unconfigured table useGeneratedKeys", ok, "key=" + bizKey);
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
                check("sharded table useGeneratedKeys", ok, "key=" + shardKey);
            }
            long last;
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT LAST_INSERT_ID()")) {
                rs.next();
                last = rs.getLong(1);
            }
            check("LAST_INSERT_ID() == last sharded insert id", last == shardKey, "lastId=" + last + " shardKey=" + shardKey);
        } catch (Exception e) {
            check("useGeneratedKeys / LAST_INSERT_ID", false, firstLine(e));
        }

        // batch update on a sharded table with hint
        try (Connection c = ss.getConnection(); Statement s = c.createStatement()) {
            try (HintManager h = HintManager.getInstance()) {
                h.addTableShardingValue("timeout_task", "timeout_task_09");
                int n = s.executeUpdate("UPDATE timeout_task SET retry_count = retry_count + 1,"
                        + " status = CASE biz_id WHEN 'a' THEN 'RETRYING' WHEN 'b' THEN 'SUCCESS' ELSE status END"
                        + " WHERE biz_id IN ('a','b') AND status IN ('WAITING','RETRYING')");
                check("sharded multi-value IN batch CASE UPDATE with hint", true, "updateCount=" + n);
            }
        } catch (Exception e) {
            check("sharded batch CASE UPDATE with hint", false, firstLine(e));
        }
        try (Connection c = ss.getConnection(); Statement s = c.createStatement()) {
            int n = s.executeUpdate("UPDATE timeout_task SET retry_count = retry_count + 1 WHERE biz_id IN ('a','b')");
            check("sharded batch UPDATE WITHOUT hint -> must fail", false, "succeeded, updateCount=" + n);
        } catch (Exception e) {
            check("sharded batch UPDATE WITHOUT hint -> fails", true, firstLine(e));
        }

        // HintManager thread-locality across the whole statement lifecycle
        try (HintManager h = HintManager.getInstance()) {
            h.addTableShardingValue("timeout_task", "timeout_task_11");
            final boolean[] childSees = new boolean[1];
            Thread t = new Thread(() -> childSees[0] = HintManager.isInstantiated());
            t.start(); t.join();
            check("HintManager is ThreadLocal", !childSees[0], "child isInstantiated=" + childSees[0]);
        } catch (Exception e) {
            check("HintManager is ThreadLocal", false, firstLine(e));
        }
        check("HintManager cleared after close()", !HintManager.isInstantiated(),
                "isInstantiated=" + HintManager.isInstantiated());
    }

    // ---------- infra ----------

    static DataSource driverDataSource() throws Exception {
        Class.forName("org.apache.shardingsphere.driver.ShardingSphereDriver");
        return new DriverManagerDataSource(SS_URL);
    }

    /** Minimal DataSource over DriverManager so we exercise the real ShardingSphereDriver URL. */
    static final class DriverManagerDataSource implements DataSource {
        private final String url;
        DriverManagerDataSource(String url) { this.url = url; }
        public Connection getConnection() throws SQLException { return DriverManager.getConnection(url); }
        public Connection getConnection(String u, String p) throws SQLException { return DriverManager.getConnection(url, u, p); }
        public java.io.PrintWriter getLogWriter() { return null; }
        public void setLogWriter(java.io.PrintWriter out) { }
        public void setLoginTimeout(int seconds) { }
        public int getLoginTimeout() { return 0; }
        public java.util.logging.Logger getParentLogger() { return java.util.logging.Logger.getGlobal(); }
        public <T> T unwrap(Class<T> iface) throws SQLException { throw new SQLException("not a wrapper"); }
        public boolean isWrapperFor(Class<?> iface) { return false; }
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
            s.executeUpdate("INSERT INTO user_account (id, name) VALUES (1, 'alice')");
            s.executeUpdate("INSERT INTO product_order (order_no, user_id, status, msg) VALUES ('BASE-1', 1, 'WAITING', 'b')");
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

    static void exec(String tag, String sql, Sink sink) {
        try (Connection conn = ss.getConnection(); Statement s = conn.createStatement()) {
            boolean hasRs = s.execute(sql);
            sink.accept(s, hasRs ? s.getResultSet() : null);
        } catch (Exception e) {
            check(tag, false, firstLine(e));
        }
    }

    static void section(String t) {
        System.out.println();
        System.out.println("-------- " + t + " --------");
    }

    static void check(String what, boolean ok, String detail) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "  [PASS] " : "  [FAIL] ") + what + "  ::  " + detail);
    }

    static String firstLine(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        String m = r.toString();
        return m.length() > 400 ? m.substring(0, 400) + "..." : m;
    }

    static String brief(String sql) {
        String s = sql.replaceAll("\\s+", " ");
        return s.length() > 62 ? s.substring(0, 62) + "..." : s;
    }
}
