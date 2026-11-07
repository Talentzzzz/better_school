import org.apache.shardingsphere.infra.hint.HintManager;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.*;
import java.util.*;

/**
 * FINAL probe: run the whole question list against real ShardingSphere 5.5.3 + real MySQL 8 for
 * several YAML shapes, so the !SINGLE question is answered by measurement rather than by recall.
 *
 *   A  no !SINGLE                       (== what backend/timeout-center/sharding.yaml ships today)
 *   B  !SINGLE { defaultDataSource }    (declared, but no tables list)
 *   C  !SINGLE { tables: ["*.*.*"] }    (declared with wildcard)
 *   D  !SINGLE { defaultDataSource, tables: ["*.*.*"] }
 */
public final class FinalProbe {

    static final String SCHEMA = "probe";
    static final String RAW_URL = "jdbc:mysql://127.0.0.1:3307/probe?useSSL=false&allowPublicKeyRetrieval=true"
            + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8";

    static DataSource raw;
    static int pass = 0, fail = 0;
    static String current;

    public static void main(String[] args) throws Exception {
        raw = hikari();
        seed(raw);
        System.out.println("MySQL schema " + SCHEMA + " tables = "
                + countRaw("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='" + SCHEMA + "'"));

        run("A", "noSingle", "probe-named.yaml");
        run("B", "singleDefaultDsOnly", "probe-single.yaml");
        run("C", "singleTablesStar", "probe-single-star.yaml");
        run("D", "singleDefaultDsAndTablesStar", "probe-single-star-def.yaml");

        System.out.println();
        System.out.println("=== FINAL RESULT: PASS=" + pass + " FAIL=" + fail + " ===");
    }

    static void run(String tag, String label, String yaml) throws Exception {
        current = tag + "/" + label;
        System.out.println();
        System.out.println("################ " + current + "  (" + yaml + ") ################");
        Mgr m = new Mgr("jdbc:shardingsphere:classpath:" + yaml);
        try {
            shape(m);
            q1(m);
            q2(m);
            q3(m);
            q4(m);
            q5(m);
        } catch (Throwable t) {
            System.out.println("  [FATAL] " + firstLine(t));
        } finally {
            m.close();
        }
    }

    // ---------- shape ----------

    static void shape(Mgr m) {
        System.out.println("  ---- single-table rule shape ----");
        try {
            Object ctx = cm(m.real());
            for (Object name : (Collection<?>) ctx.getClass().getMethod("getAllDatabaseNames").invoke(ctx)) {
                Object db = ctx.getClass().getMethod("getDatabase", String.class).invoke(ctx, name);
                if (!(Boolean) db.getClass().getMethod("isComplete").invoke(db)) {
                    continue;
                }
                System.out.println("  [info] logical database '" + name + "' storageUnits = "
                        + ctx.getClass().getMethod("getStorageUnits", String.class).invoke(ctx, name));
                Object ruleMd = db.getClass().getMethod("getRuleMetaData").invoke(db);
                for (Object rule : (Collection<?>) ruleMd.getClass().getMethod("getRules").invoke(ruleMd)) {
                    String rn = rule.getClass().getSimpleName();
                    Object conf = rule.getClass().getMethod("getConfiguration").invoke(rule);
                    System.out.println("  [info] rule " + rn + " :: " + conf);
                    if (rn.contains("Single")) {
                        Object nodes = rule.getClass().getMethod("getSingleTableDataNodes").invoke(rule);
                        Map<?, ?> nm = (Map<?, ?>) nodes;
                        System.out.println("  [info]   singleTableDataNodes size = " + nm.size());
                        SortedSet<String> keys = new TreeSet<>();
                        for (Object k : nm.keySet()) keys.add(String.valueOf(k));
                        System.out.println("  [info]   keys = " + keys);
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("  [info] shape failed: " + firstLine(t));
        }
    }

    // ---------- Q1: unconfigured tables ----------

    static void q1(Mgr m) {
        System.out.println("  ---- Q1 unconfigured business tables ----");
        exec(m, "select user_account", "SELECT id,name FROM user_account WHERE id=1", (s, rs) -> {
            rs.next();
            check("unconfigured SELECT", "alice".equals(rs.getString(2)), "name=" + rs.getString(2));
        });
        exec(m, "insert product_order", "INSERT INTO product_order (order_no,user_id,status,msg) VALUES ('PO-1',7,'WAITING','x')",
                (s, rs) -> check("unconfigured INSERT", s.getUpdateCount() == 1, "updateCount=" + s.getUpdateCount()));
        exec(m, "update product_order", "UPDATE product_order SET msg='z' WHERE order_no='PO-1'",
                (s, rs) -> check("unconfigured UPDATE", s.getUpdateCount() == 1, "updateCount=" + s.getUpdateCount()));
        exec(m, "delete product_order", "DELETE FROM product_order WHERE order_no='NOPE'",
                (s, rs) -> check("unconfigured DELETE", s.getUpdateCount() == 0, "updateCount=" + s.getUpdateCount()));
        exec(m, "join", "SELECT o.id FROM product_order o JOIN user_account u ON u.id=o.user_id", (s, rs) -> {
            int n = 0; while (rs.next()) n++;
            check("unconfigured JOIN", n >= 1, "rows=" + n);
        });
        exec(m, "count review_task", "SELECT COUNT(*) FROM review_task", (s, rs) -> {
            rs.next();
            check("unconfigured COUNT", true, "count=" + rs.getInt(1));
        });
        exec(m, "schema.table form", "SELECT id FROM " + SCHEMA + ".user_account", (s, rs) -> {
            int n = 0; while (rs.next()) n++;
            check("schema-qualified form", n == 1, "rows=" + n);
        });
        exec(m, "DDL create", "CREATE TABLE IF NOT EXISTS probe_tmp (id BIGINT PRIMARY KEY AUTO_INCREMENT, v VARCHAR(10))",
                (s, rs) -> check("DDL CREATE", true, "ok"));
        exec(m, "DDL drop", "DROP TABLE probe_tmp", (s, rs) -> check("DDL DROP", true, "ok"));
        exec(m, "unknown table", "SELECT id FROM nope_xyz", (s, rs) -> check("unknown table errors", false, "no error raised"));
    }

    // ---------- Q2: same datasource: sharded logical + unconfigured ----------

    static void q2(Mgr m) {
        System.out.println("  ---- Q2 sharded logical table + unconfigured table on one datasource ----");
        try (Connection c = m.get().getConnection(); Statement s = c.createStatement()) {
            try (HintManager h = HintManager.getInstance()) {
                h.addTableShardingValue("timeout_task", "timeout_task_03");
                s.executeUpdate("INSERT INTO timeout_task (task_type,biz_id,status,retry_count) VALUES ('T','q2a','WAITING',0)");
            }
            ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM timeout_task_03");
            rs.next();
            check("hint write routed to timeout_task_03", rs.getInt(1) == 1, "count=" + rs.getInt(1));
            rs = s.executeQuery("SELECT COUNT(*) FROM user_account");
            rs.next();
            check("same connection: unconfigured table works", rs.getInt(1) == 1, "count=" + rs.getInt(1));
        } catch (Exception e) {
            check("hint + unconfigured mixed", false, firstLine(e));
        }
        try (Connection c = m.get().getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO timeout_task (task_type,biz_id,status,retry_count) VALUES ('T','q2b','WAITING',0)");
            check("sharded WRITE without hint fails fast", false, "it succeeded");
        } catch (Exception e) {
            check("sharded WRITE without hint fails fast", true, brief(firstLine(e)));
        }
        try (Connection c = m.get().getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM timeout_task");
            rs.next();
            check("sharded READ without hint broadcasts", rs.getInt(1) >= 1, "count=" + rs.getInt(1));
        } catch (Exception e) {
            check("sharded READ without hint", false, firstLine(e));
        }
    }

    // ---------- Q3: transaction ----------

    static void q3(Mgr m) {
        System.out.println("  ---- Q3 transaction (sharded + unsharded in one local tx) ----");
        try (Connection c = m.get().getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO product_order (order_no,user_id,status,msg) VALUES ('TX-1',1,'W','a')");
                long id1 = one(c, "SELECT CONNECTION_ID()");
                try (HintManager h = HintManager.getInstance()) {
                    h.addTableShardingValue("timeout_task", "timeout_task_05");
                    s.executeUpdate("INSERT INTO timeout_task (task_type,biz_id,status,retry_count) VALUES ('T','tx1','WAITING',0)");
                }
                long id2 = one(c, "SELECT CONNECTION_ID()");
                s.executeUpdate("INSERT INTO review_task (task_id) VALUES (1)");
                long id3 = one(c, "SELECT CONNECTION_ID()");
                check("one MySQL session id for unsharded+sharded+unsharded writes",
                        id1 == id2 && id2 == id3, "ids=" + id1 + "," + id2 + "," + id3);
            }
            c.rollback();
            check("single rollback clears unsharded row",
                    countRaw("SELECT COUNT(*) FROM product_order WHERE order_no='TX-1'") == 0,
                    "rows=" + countRaw("SELECT COUNT(*) FROM product_order WHERE order_no='TX-1'"));
            check("single rollback clears sharded row",
                    countRaw("SELECT COUNT(*) FROM timeout_task_05") == 0,
                    "rows=" + countRaw("SELECT COUNT(*) FROM timeout_task_05"));
            check("single rollback clears second unsharded row",
                    countRaw("SELECT COUNT(*) FROM review_task") == 0,
                    "rows=" + countRaw("SELECT COUNT(*) FROM review_task"));
            c.setAutoCommit(true);
        } catch (Exception e) {
            check("mixed transaction rollback", false, firstLine(e));
        }

        try (Connection c = m.get().getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO product_order (order_no,user_id,status,msg) VALUES ('TX-2',1,'W','a')");
                try (HintManager h = HintManager.getInstance()) {
                    h.addTableShardingValue("timeout_task", "timeout_task_06");
                    s.executeUpdate("INSERT INTO timeout_task (task_type,biz_id,status,retry_count) VALUES ('T','tx2','WAITING',0)");
                }
            }
            c.commit();
            check("commit persists both",
                    countRaw("SELECT COUNT(*) FROM product_order WHERE order_no='TX-2'") == 1
                            && countRaw("SELECT COUNT(*) FROM timeout_task_06") == 1,
                    "po=" + countRaw("SELECT COUNT(*) FROM product_order WHERE order_no='TX-2'")
                            + " tt=" + countRaw("SELECT COUNT(*) FROM timeout_task_06"));
        } catch (Exception e) {
            check("mixed transaction commit", false, firstLine(e));
        }

        try (Connection c = m.get().getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                try (HintManager h = HintManager.getInstance()) {
                    h.addTableShardingValue("timeout_task", "timeout_task_02");
                    s.executeUpdate("INSERT INTO timeout_task (task_type,biz_id,status,retry_count) VALUES ('T','m2','WAITING',0)");
                }
                try (HintManager h = HintManager.getInstance()) {
                    h.addTableShardingValue("timeout_task", "timeout_task_04");
                    s.executeUpdate("INSERT INTO timeout_task (task_type,biz_id,status,retry_count) VALUES ('T','m4','WAITING',0)");
                }
                s.executeUpdate("INSERT INTO product_order (order_no,user_id,status,msg) VALUES ('TX-5',1,'W','a')");
            }
            c.rollback();
            boolean clean = countRaw("SELECT COUNT(*) FROM timeout_task_02") == 0
                    && countRaw("SELECT COUNT(*) FROM timeout_task_04") == 0
                    && countRaw("SELECT COUNT(*) FROM product_order WHERE order_no='TX-5'") == 0;
            check("one tx across two shards + business table: single rollback clears all", clean,
                    "t02=" + countRaw("SELECT COUNT(*) FROM timeout_task_02")
                            + " t04=" + countRaw("SELECT COUNT(*) FROM timeout_task_04")
                            + " po=" + countRaw("SELECT COUNT(*) FROM product_order WHERE order_no='TX-5'"));
            c.setAutoCommit(true);
        } catch (Exception e) {
            check("tx across two shards", false, firstLine(e));
        }

        try (Connection c = m.get().getConnection()) {
            Object mgr = c.getClass().getMethod("getDatabaseConnectionManager").invoke(c);
            Class<?> modeCls = Class.forName("org.apache.shardingsphere.infra.executor.sql.execute.engine.ConnectionMode");
            Object mode = Enum.valueOf((Class<Enum>) modeCls, "MEMORY_STRICTLY");
            java.lang.reflect.Method gc = mgr.getClass()
                    .getMethod("getConnections", String.class, String.class, int.class, int.class, modeCls);
            c.setAutoCommit(false);
            List<?> a = (List<?>) gc.invoke(mgr, "ds0", SCHEMA, 1, 1, mode);
            List<?> b = (List<?>) gc.invoke(mgr, "ds0", SCHEMA, 1, 1, mode);
            check("physical connection cached+reused inside tx",
                    a.size() == 1 && b.size() == 1 && a.get(0) == b.get(0),
                    "a=" + a.size() + " b=" + b.size() + " same=" + (a.size() == 1 && b.size() == 1 && a.get(0) == b.get(0)));
            c.rollback();
        } catch (Throwable e) {
            check("physical connection reuse", false, firstLine(e));
        }

        try (Connection c = m.get().getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO product_order (order_no,user_id,status,msg) VALUES ('TX-3',1,'W','a')");
                Savepoint sp = c.setSavepoint("sp1");
                s.executeUpdate("INSERT INTO product_order (order_no,user_id,status,msg) VALUES ('TX-4',1,'W','a')");
                c.rollback(sp);
            }
            c.commit();
            check("savepoint works",
                    countRaw("SELECT COUNT(*) FROM product_order WHERE order_no IN ('TX-3','TX-4')") == 1,
                    "rows=" + countRaw("SELECT COUNT(*) FROM product_order WHERE order_no IN ('TX-3','TX-4')"));
        } catch (Exception e) {
            check("savepoint", false, firstLine(e));
        }
    }

    // ---------- Q4: JDBC feature matrix ----------

    static void q4(Mgr m) {
        System.out.println("  ---- Q4 JDBC / SQL feature matrix (unconfigured table) ----");
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
                "REPLACE INTO review_task (id,task_id) VALUES (999,1)",
                "SELECT id FROM product_order WHERE order_no IN ('OK-1','BASE-1') ORDER BY id LIMIT 1",
                "UPDATE product_order SET msg=CASE order_no WHEN 'OK-1' THEN 'c1' ELSE msg END WHERE order_no IN ('OK-1')",
                "SELECT LAST_INSERT_ID()",
                "TRUNCATE TABLE review_task",
                "SELECT id FROM product_order p, user_account u WHERE p.user_id=u.id",
                "INSERT INTO product_order (order_no,user_id,status,msg) SELECT 'SEL-1',1,'W','s'",
                "WITH c AS (SELECT 1 AS x) SELECT x FROM c",
                "SELECT SLEEP(0)",
                "SELECT * FROM product_order LIMIT 1 OFFSET 0",
                "SELECT COUNT(*) OVER () FROM product_order",
                "SELECT JSON_OBJECT('a',1)",
                "SELECT DATE_FORMAT(NOW(), '%Y-%m-%d')",
                "SELECT GROUP_CONCAT(order_no) FROM product_order",
                "SELECT CAST(1 AS CHAR)",
                "START TRANSACTION",
                "COMMIT",
                "ROLLBACK",
        };
        for (String sql : sqls) {
            try (Connection c = m.get().getConnection(); Statement s = c.createStatement()) {
                boolean hasRs = s.execute(sql);
                if (hasRs) {
                    ResultSet rs = s.getResultSet();
                    int n = 0;
                    while (rs.next() && n < 4) n++;
                    check("SQL ok: " + brief(sql), true, "rows=" + n);
                } else {
                    check("SQL ok: " + brief(sql), true, "updateCount=" + s.getUpdateCount());
                }
            } catch (Exception e) {
                check("SQL ok: " + brief(sql), false, brief(firstLine(e)));
            }
        }

        try (Connection c = m.get().getConnection()) {
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
            check("LAST_INSERT_ID() equals sharded insert id", last == shardKey, "last=" + last + " shard=" + shardKey);
        } catch (Exception e) {
            check("generated keys", false, firstLine(e));
        }

        try (Connection c = m.get().getConnection(); Statement s = c.createStatement()) {
            try (HintManager h = HintManager.getInstance()) {
                h.addTableShardingValue("timeout_task", "timeout_task_09");
                int n = s.executeUpdate("UPDATE timeout_task SET retry_count=retry_count+1,"
                        + " status=CASE biz_id WHEN 'a' THEN 'RETRYING' WHEN 'b' THEN 'SUCCESS' ELSE status END"
                        + " WHERE biz_id IN ('a','b') AND status IN ('WAITING','RETRYING')");
                check("sharded batch CASE UPDATE with hint", true, "updateCount=" + n);
            }
        } catch (Exception e) {
            check("sharded batch CASE UPDATE", false, firstLine(e));
        }
        try (Connection c = m.get().getConnection(); Statement s = c.createStatement()) {
            int n = s.executeUpdate("UPDATE timeout_task SET retry_count=retry_count+1 WHERE biz_id IN ('a','b')");
            check("sharded batch UPDATE without hint fails", false, "succeeded n=" + n);
        } catch (Exception e) {
            check("sharded batch UPDATE without hint fails", true, brief(firstLine(e)));
        }

        try (HintManager h = HintManager.getInstance()) {
            h.addTableShardingValue("timeout_task", "timeout_task_11");
            final boolean[] child = new boolean[1];
            Thread t = new Thread(() -> child[0] = HintManager.isInstantiated());
            t.start(); t.join();
            check("HintManager is ThreadLocal", !child[0], "child isInstantiated=" + child[0]);
        } catch (Exception e) {
            check("HintManager ThreadLocal", false, firstLine(e));
        }
        check("HintManager cleaned after close", !HintManager.isInstantiated(), "isInstantiated=" + HintManager.isInstantiated());
    }

    // ---------- Q5: pool footprint ----------

    static void q5(Mgr m) {
        System.out.println("  ---- Q5 pool footprint / metadata connections ----");
        try {
            Object pool = null;
            Object ctx = cm(m.real());
            Object units = ctx.getClass().getMethod("getStorageUnits", String.class)
                    .invoke(ctx, databaseNameOf(ctx));
            for (Object u : ((Map<?, ?>) units).values()) {
                Object ds = u.getClass().getMethod("getDataSource").invoke(u);
                pool = ds;
                System.out.println("  [info] storage unit datasource = " + ds.getClass().getName());
            }
            if (pool != null) {
                StringBuilder sb = new StringBuilder();
                for (String g : new String[]{"getMaximumPoolSize", "getMinimumIdle", "getActiveConnections",
                        "getIdleConnections", "getTotalConnections", "getThreadsAwaitingConnection"}) {
                    try {
                        sb.append(g).append('=').append(pool.getClass().getMethod(g).invoke(pool)).append(' ');
                    } catch (Throwable ignore) {
                    }
                }
                System.out.println("  [info] inner Hikari: " + sb);
            }
        } catch (Throwable t) {
            System.out.println("  [info] pool probe failed: " + firstLine(t));
        }
        try (Connection c = raw.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE DB='" + SCHEMA + "'")) {
            rs.next();
            System.out.println("  [info] MySQL PROCESSLIST rows for DB=" + SCHEMA + ": " + rs.getInt(1));
        } catch (Throwable t) {
            System.out.println("  [info] processlist failed: " + firstLine(t));
        }
    }

    // ---------- infra ----------

    static String databaseNameOf(Object ctx) throws Exception {
        Collection<?> names = (Collection<?>) ctx.getClass().getMethod("getAllDatabaseNames").invoke(ctx);
        for (Object n : names) {
            if (SCHEMA.equalsIgnoreCase(String.valueOf(n))) {
                return String.valueOf(n);
            }
        }
        throw new IllegalStateException("no database " + SCHEMA + " in " + names);
    }

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
        return (Map<String, DataSource>) f.get(null).getClass().getMethod("getDataSourceMap").invoke(f.get(null));
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
        return r.toString();
    }

    static String brief(String s) {
        String x = s.replaceAll("\\s+", " ");
        return x.length() > 110 ? x.substring(0, 110) + "..." : x;
    }
}
