import org.apache.shardingsphere.driver.api.ShardingSphereDataSourceFactory;
import org.apache.shardingsphere.infra.config.rule.RuleConfiguration;
import org.apache.shardingsphere.infra.hint.HintManager;
import org.apache.shardingsphere.sharding.api.config.ShardingRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.strategy.sharding.HintShardingStrategyConfiguration;

import javax.sql.DataSource;
import java.lang.reflect.Method;
import java.sql.*;
import java.util.*;

/**
 * Offline probe: real ShardingSphere 5.5.3 jars + real MySQL.
 * One datasource = one schema (probe) holding 16 timeout shards AND 4 unconfigured business tables.
 * ASCII-only on purpose (javac/PowerShell codepage pain).
 */
public final class SsProbe {

    static final String HOST = "127.0.0.1";
    static final int PORT = 3307;
    static final String DB = "probe";
    static final String JDBC = "jdbc:mysql://" + HOST + ":" + PORT + "/" + DB
            + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8";
    static final String USER = "root";
    static final String PASS = "";

    static DataSource inner;
    static DataSource ss;
    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        createSchema();
        inner = hikari(10, 1);
        seed(inner);

        ShardingRuleConfiguration sharding = new ShardingRuleConfiguration();
        ShardingTableRuleConfiguration table =
                new ShardingTableRuleConfiguration("timeout_task", DB + "." + nodes());
        table.setTableShardingStrategy(new HintShardingStrategyConfiguration("t-hint"));
        sharding.getTables().add(table);
        sharding.getShardingAlgorithms().put("t-hint", hintAlgorithm());

        long t0 = System.currentTimeMillis();
        ss = ShardingSphereDataSourceFactory.createDataSource(
                DB, map("ds0", inner), (Collection<RuleConfiguration>) (Collection<?>) List.of(sharding), new Properties());
        System.out.println("[info] ShardingSphereDataSource init took " + (System.currentTimeMillis() - t0) + "ms");

        section("A. LOADED RULE SHAPE (is there an implicit SingleRule? what defaultDataSource?)");
        SingleRuleView.dump(ss);
        metadataTableView(ss);

        section("B. UNCONFIGURED TABLES: transparent pass-through or not");
        q1_unconfiguredTables();
        q2_sameDatasourceUsage();

        section("C. TRANSACTION: sharded + unsharded in one tx, one connection?");
        q3_transaction();

        section("D. RUNTIME RISK: parse failures, metadata connections, HintManager, JDBC feature matrix");
        q4_runtimeRisk();

        section("E. SEPARATE ShardingSphereDataSource bean vs business datasource: shared transaction?");
        q6_separateDataSource();

        section("F. POOL FOOTPRINT");
        poolFootprint();

        System.out.println();
        System.out.println("=== PROBE RESULT: PASS=" + pass + " FAIL=" + fail + " ===");
    }

    // ------------------------- Q1 / Q2 -------------------------

    static void q1_unconfiguredTables() {
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
        exec("DELETE", "DELETE FROM product_order WHERE order_no = 'PO-2'", (s, rs) -> {
            check("unconfigured DELETE passthrough (0 rows, no error)", s.getUpdateCount() == 0, "updateCount=" + s.getUpdateCount());
        });
        exec("COUNT", "SELECT COUNT(*) FROM review_task", (s, rs) -> {
            rs.next();
            check("unconfigured full-table COUNT passthrough", rs.getInt(1) == 0, "count=" + rs.getInt(1));
        });
        exec("JOIN", "SELECT o.id FROM product_order o JOIN user_account u ON u.id = o.user_id", (s, rs) -> {
            int n = 0; while (rs.next()) n++;
            check("unconfigured JOIN passthrough", n >= 1, "rows=" + n);
        });
        exec("DDL-CREATE", "CREATE TABLE IF NOT EXISTS probe_new_table (id BIGINT PRIMARY KEY AUTO_INCREMENT, v VARCHAR(20))", (s, rs) -> {
            check("unconfigured DDL CREATE passthrough", true, "ok");
        });
        exec("DDL-DROP", "DROP TABLE probe_new_table", (s, rs) -> {
            check("unconfigured DDL DROP passthrough", true, "ok");
        });
        exec("schema-qualified", "SELECT id FROM " + DB + ".user_account", (s, rs) -> {
            int n = 0; while (rs.next()) n++;
            check("explicit schema.table form passthrough", n == 1, "rows=" + n);
        });
        exec("physical shard name", "SELECT COUNT(*) FROM timeout_task_01", (s, rs) -> {
            rs.next();
            check("naming a physical shard directly still works", true, "count=" + rs.getInt(1));
        });
        exec("unknown table", "SELECT id FROM no_such_table_xyz", (s, rs) -> {
            check("truly unknown table -> error propagated", false, "no error?");
        });
    }

    static void q2_sameDatasourceUsage() {
        try (Connection c = ss.getConnection()) {
            try (Statement s = c.createStatement()) {
                try (HintManager h = HintManager.getInstance()) {
                    h.addTableShardingValue("timeout_task", 3);
                    s.executeUpdate("INSERT INTO timeout_task (task_type, biz_id, status, retry_count) VALUES ('T', 'b1', 'WAITING', 0)");
                }
                ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM timeout_task_03");
                rs.next();
                check("hint works: row went to timeout_task_03", rs.getInt(1) == 1, "count=" + rs.getInt(1));
                rs = s.executeQuery("SELECT COUNT(*) FROM user_account");
                rs.next();
                check("unconfigured table fine on same connection", rs.getInt(1) == 1, "count=" + rs.getInt(1));
            }
        } catch (SQLException e) {
            check("hint + unconfigured table mixed", false, firstLine(e));
        }
        try (Connection c = ss.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO timeout_task (task_type, biz_id, status, retry_count) VALUES ('T', 'b2', 'WAITING', 0)");
            check("sharded WRITE without hint -> should fail fast", false, "it succeeded?!");
        } catch (Exception e) {
            check("sharded WRITE without hint -> fails fast", true, firstLine(e));
        }
        try (Connection c = ss.getConnection(); Statement s = c.createStatement()) {
            ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM timeout_task");
            rs.next();
            check("sharded READ without hint -> 16-table broadcast", rs.getInt(1) >= 1, "count=" + rs.getInt(1) + " (00..15 combined)");
        } catch (Exception e) {
            check("sharded READ without hint", false, firstLine(e));
        }
    }

    // ------------------------- Q3 transaction -------------------------

    static void q3_transaction() {
        try (Connection c = ss.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO product_order (order_no, user_id, status, msg) VALUES ('TX-1', 1, 'WAITING', 'a')");
                try (HintManager h = HintManager.getInstance()) {
                    h.addTableShardingValue("timeout_task", 5);
                    s.executeUpdate("INSERT INTO timeout_task (task_type, biz_id, status, retry_count) VALUES ('T', 'tx1', 'WAITING', 0)");
                }
            }
            c.rollback();
            check("after rollback: unsharded row gone (tx really hits MySQL)",
                    countInner("SELECT COUNT(*) FROM product_order WHERE order_no='TX-1'") == 0,
                    "rows=" + countInner("SELECT COUNT(*) FROM product_order WHERE order_no='TX-1'"));
            check("after rollback: sharded row gone",
                    countInner("SELECT COUNT(*) FROM timeout_task_05") == 0,
                    "rows=" + countInner("SELECT COUNT(*) FROM timeout_task_05"));
            c.setAutoCommit(true);
        } catch (SQLException e) {
            check("same-tx rollback (sharded + unsharded)", false, firstLine(e));
        }

        try (Connection c = ss.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO product_order (order_no, user_id, status, msg) VALUES ('TX-2', 1, 'WAITING', 'a')");
                try (HintManager h = HintManager.getInstance()) {
                    h.addTableShardingValue("timeout_task", 6);
                    s.executeUpdate("INSERT INTO timeout_task (task_type, biz_id, status, retry_count) VALUES ('T', 'tx2', 'WAITING', 0)");
                }
            }
            c.commit();
            check("after commit: unsharded row visible",
                    countInner("SELECT COUNT(*) FROM product_order WHERE order_no='TX-2'") == 1,
                    "rows=" + countInner("SELECT COUNT(*) FROM product_order WHERE order_no='TX-2'"));
            check("after commit: sharded row visible",
                    countInner("SELECT COUNT(*) FROM timeout_task_06") == 1,
                    "rows=" + countInner("SELECT COUNT(*) FROM timeout_task_06"));
        } catch (SQLException e) {
            check("same-tx commit (sharded + unsharded)", false, firstLine(e));
        }

        try (Connection c = ss.getConnection()) {
            c.setAutoCommit(false);
            Object mgr = c.getClass().getMethod("getDatabaseConnectionManager").invoke(c);
            Class<?> modeCls = Class.forName("org.apache.shardingsphere.infra.executor.sql.execute.engine.ConnectionMode");
            Object mode = Enum.valueOf((Class<Enum>) modeCls, "MEMORY_STRICTLY");
            Method getConns = mgr.getClass().getMethod("getConnections", String.class, String.class, int.class, int.class, modeCls);
            List<?> c1 = (List<?>) getConns.invoke(mgr, "ds0", DB, 1, 1, mode);
            List<?> c2 = (List<?>) getConns.invoke(mgr, "ds0", DB, 1, 1, mode);
            check("physical connection reused inside a tx (same object)",
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
                    h.addTableShardingValue("timeout_task", 2);
                    s.executeUpdate("INSERT INTO timeout_task (task_type, biz_id, status, retry_count) VALUES ('T','m2','WAITING',0)");
                }
                try (HintManager h = HintManager.getInstance()) {
                    h.addTableShardingValue("timeout_task", 4);
                    s.executeUpdate("INSERT INTO timeout_task (task_type, biz_id, status, retry_count) VALUES ('T','m4','WAITING',0)");
                }
                s.executeUpdate("INSERT INTO product_order (order_no, user_id, status, msg) VALUES ('TX-5',1,'W','a')");
            }
            c.rollback();
            boolean clean = countInner("SELECT COUNT(*) FROM timeout_task_02") == 0
                    && countInner("SELECT COUNT(*) FROM timeout_task_04") == 0
                    && countInner("SELECT COUNT(*) FROM product_order WHERE order_no='TX-5'") == 0;
            check("one tx across TWO shards + one business table: single rollback clears all", clean,
                    "t02=" + countInner("SELECT COUNT(*) FROM timeout_task_02")
                            + " t04=" + countInner("SELECT COUNT(*) FROM timeout_task_04")
                            + " po=" + countInner("SELECT COUNT(*) FROM product_order WHERE order_no='TX-5'"));
            c.setAutoCommit(true);
        } catch (Exception e) {
            check("one tx across two shards", false, firstLine(e));
        }

        try (Connection c = ss.getConnection()) {
            c.setAutoCommit(false);
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO product_order (order_no, user_id, status, msg) VALUES ('TX-3', 1, 'W','a')");
                Savepoint sp = c.setSavepoint("sp1");
                s.executeUpdate("INSERT INTO product_order (order_no, user_id, status, msg) VALUES ('TX-4', 1, 'W','a')");
                c.rollback(sp);
            }
            c.commit();
            check("savepoint supported", countInner("SELECT COUNT(*) FROM product_order WHERE order_no IN ('TX-3','TX-4')") == 1,
                    "rows=" + countInner("SELECT COUNT(*) FROM product_order WHERE order_no IN ('TX-3','TX-4')"));
        } catch (Exception e) {
            check("savepoint supported", false, firstLine(e));
        }

        try (Connection c = ss.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
            check("setTransactionIsolation works", c.getTransactionIsolation() == Connection.TRANSACTION_READ_COMMITTED,
                    "isolation=" + c.getTransactionIsolation());
            c.setAutoCommit(false);
            c.setReadOnly(true);
            check("setReadOnly works", c.isReadOnly(), "readOnly=" + c.isReadOnly());
            c.rollback();
            c.setReadOnly(false);
            c.setAutoCommit(true);
        } catch (Exception e) {
            check("isolation/readOnly pass-through", false, firstLine(e));
        }
    }

    // ------------------------- Q4 -------------------------

    static void q4_runtimeRisk() throws Exception {
        String[] weird = {
                "SELECT 1",
                "SELECT @@version",
                "SHOW TABLES",
                "SHOW DATABASES",
                "SHOW CREATE TABLE product_order",
                "SHOW INDEX FROM product_order",
                "DESC product_order",
                "EXPLAIN SELECT * FROM product_order",
                "SET SESSION autocommit = 1",
                "SET NAMES utf8mb4",
                "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = '" + DB + "' LIMIT 3",
                "SELECT DATABASE()",
                "/* comment */ SELECT id FROM product_order",
                "SELECT id, (SELECT COUNT(*) FROM timeout_task_00) AS c FROM product_order",
                "SELECT COUNT(*) FROM (SELECT id FROM product_order) t",
                "SELECT 1 UNION SELECT 2",
                "SELECT SLEEP(0)",
                "SELECT * FROM product_order FOR UPDATE",
                "TRUNCATE TABLE review_task",
                "INSERT INTO product_order (order_no, user_id, status, msg) VALUES ('OK-1',1,'W','w') ON DUPLICATE KEY UPDATE msg = 'dup'",
                "INSERT IGNORE INTO product_order (order_no, user_id, status, msg) VALUES ('OK-1',1,'W','w2')",
                "REPLACE INTO review_task (id, task_id) VALUES (999, 1)",
                "SELECT id FROM product_order WHERE order_no IN ('OK-1','BASE-1') ORDER BY id LIMIT 1",
                "UPDATE product_order SET msg = CASE order_no WHEN 'OK-1' THEN 'c1' ELSE msg END WHERE order_no IN ('OK-1')",
                "SELECT LAST_INSERT_ID()",
                "CALL not_existing_proc()",
                "SELECT id FROM product_order p, user_account u WHERE p.user_id = u.id",
                "INSERT INTO product_order (order_no, user_id, status, msg) SELECT 'SEL-1', 1, 'W', 's' FROM DUAL",
                "WITH c AS (SELECT 1 AS x) SELECT x FROM c",
        };
        for (String sql : weird) {
            try (Connection c = ss.getConnection(); Statement s = c.createStatement()) {
                boolean hasRs = s.execute(sql);
                if (hasRs) {
                    ResultSet rs = s.getResultSet();
                    int n = 0;
                    while (rs.next() && n < 3) n++;
                    check("SQL ok: " + brief(sql), true, "rows=" + n);
                } else {
                    check("SQL ok: " + brief(sql), true, "updateCount=" + s.getUpdateCount());
                }
            } catch (Exception e) {
                check("SQL ok: " + brief(sql), false, firstLine(e));
            }
        }

        try (Connection c = ss.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO product_order (order_no, user_id, status, msg) VALUES (?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, "GK-1"); ps.setLong(2, 1); ps.setString(3, "W"); ps.setString(4, "g");
                ps.executeUpdate();
                ResultSet ks = ps.getGeneratedKeys();
                boolean ok = ks.next();
                check("unconfigured table useGeneratedKeys", ok, "key=" + (ok ? ks.getLong(1) : "none"));
            }
            long shardKey;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO timeout_task (task_type, biz_id, status, retry_count) VALUES (?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                try (HintManager h = HintManager.getInstance()) {
                    h.addTableShardingValue("timeout_task", 7);
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
            check("LAST_INSERT_ID() == just-inserted sharded row id", last == shardKey, "lastId=" + last + " shardKey=" + shardKey);
        } catch (Exception e) {
            check("useGeneratedKeys / LAST_INSERT_ID", false, firstLine(e));
        }

        try {
            try (Connection c = ss.getConnection(); PreparedStatement ps = c.prepareStatement(
                    "UPDATE timeout_task SET retry_count = retry_count + 1, status = CASE biz_id WHEN 'a' THEN 'RETRYING' WHEN 'b' THEN 'SUCCESS' ELSE status END WHERE biz_id IN ('a','b') AND status IN ('WAITING','RETRYING')")) {
                try (HintManager h = HintManager.getInstance()) {
                    h.addTableShardingValue("timeout_task", 9);
                    int n = ps.executeUpdate();
                    check("sharded batch CASE UPDATE with hint", true, "updateCount=" + n);
                }
            }
            try (Connection c = ss.getConnection(); Statement s = c.createStatement()) {
                int n = s.executeUpdate("UPDATE timeout_task SET retry_count = retry_count + 1 WHERE biz_id IN ('a','b')");
                check("sharded batch UPDATE WITHOUT hint -> should fail", false, "it succeeded, updateCount=" + n);
            } catch (Exception e) {
                check("sharded batch UPDATE WITHOUT hint -> fails", true, firstLine(e));
            }
        } catch (Exception e) {
            check("batch UPDATE", false, firstLine(e));
        }

        try (HintManager h = HintManager.getInstance()) {
            h.addTableShardingValue("timeout_task", 11);
            final boolean[] seen = new boolean[1];
            final boolean[] empty = new boolean[1];
            Thread t = new Thread(() -> {
                seen[0] = HintManager.isInstantiated();
                empty[0] = HintManager.getTableShardingValues("timeout_task").isEmpty();
            });
            t.start(); t.join();
            check("HintManager is ThreadLocal (not visible on child thread)", !seen[0] && empty[0],
                    "child isInstantiated=" + seen[0] + " valuesEmpty=" + empty[0]);
        } catch (Exception e) {
            check("HintManager ThreadLocal", false, firstLine(e));
        }

        try (HintManager h = HintManager.getInstance()) {
            h.addTableShardingValue("timeout_task", 11);
        }
        check("after HintManager.close() nothing leaks on this thread", !HintManager.isInstantiated(),
                "isInstantiated=" + HintManager.isInstantiated());

        try (Connection c = ss.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM timeout_task WHERE biz_id = 'zzz'");
            check("sharded DELETE without hint -> should fail", false, "it succeeded");
        } catch (Exception e) {
            check("sharded DELETE without hint -> fails", true, firstLine(e));
        }
    }

    // ------------------------- Q6 -------------------------

    static void q6_separateDataSource() throws Exception {
        DataSource bizPool = hikari(10, 1);
        DataSource ssPool = hikari(10, 1);
        ShardingRuleConfiguration sharding = new ShardingRuleConfiguration();
        ShardingTableRuleConfiguration table = new ShardingTableRuleConfiguration("timeout_task", DB + "." + nodes());
        table.setTableShardingStrategy(new HintShardingStrategyConfiguration("t-hint"));
        sharding.getTables().add(table);
        sharding.getShardingAlgorithms().put("t-hint", hintAlgorithm());
        DataSource ss2 = ShardingSphereDataSourceFactory.createDataSource(
                DB, map("ds0", ssPool), (Collection<RuleConfiguration>) (Collection<?>) List.of(sharding), new Properties());

        Connection biz = bizPool.getConnection();
        biz.setAutoCommit(false);
        try (Statement s = biz.createStatement()) {
            s.executeUpdate("INSERT INTO product_order (order_no, user_id, status, msg) VALUES ('SEP-1', 1, 'W', 's')");
        }
        try (Connection tc = ss2.getConnection(); Statement s = tc.createStatement()) {
            try (HintManager h = HintManager.getInstance()) {
                h.addTableShardingValue("timeout_task", 12);
                s.executeUpdate("INSERT INTO timeout_task (task_type, biz_id, status, retry_count) VALUES ('T','sep1','WAITING',0)");
            }
        }
        biz.rollback();
        biz.setAutoCommit(true);
        biz.close();

        check("separate DS: business row rolled back",
                countInner("SELECT COUNT(*) FROM product_order WHERE order_no='SEP-1'") == 0,
                "rows=" + countInner("SELECT COUNT(*) FROM product_order WHERE order_no='SEP-1'"));
        check("separate DS: timeout row did NOT roll back (no shared tx)",
                countInner("SELECT COUNT(*) FROM timeout_task_12") == 1,
                "rows=" + countInner("SELECT COUNT(*) FROM timeout_task_12"));
    }

    static void poolFootprint() {
        try {
            System.out.println("  [info] inner Hikari(ds0) status: " + callPoolStatus(inner));
            try (Connection c = inner.getConnection(); Statement s = c.createStatement();
                 ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE DB = '" + DB + "'")) {
                rs.next();
                System.out.println("  [info] ProcessList(DB=" + DB + ") = " + rs.getInt(1));
            }
        } catch (Throwable t) {
            System.out.println("  [info] pool status probe failed: " + firstLine(t));
        }
    }

    static String callPoolStatus(DataSource ds) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (String m : new String[]{"getMaximumPoolSize", "getMinimumIdle", "getActiveConnections", "getIdleConnections", "getTotalConnections", "getThreadsAwaitingConnection"}) {
            try {
                Object v = ds.getClass().getMethod(m).invoke(ds);
                sb.append(m).append('=').append(v).append(' ');
            } catch (Throwable ignore) {
            }
        }
        return sb.toString();
    }

    static void metadataTableView(DataSource ds) {
        try {
            Object ctx = contextManagerOf(ds);
            System.out.println("  [info] all database names = " + ctx.getClass().getMethod("getAllDatabaseNames").invoke(ctx));
            Object db = ctx.getClass().getMethod("getDatabase", String.class).invoke(ctx, DB);
            System.out.println("  [info] database name = " + db.getClass().getMethod("getName").invoke(db));
            System.out.println("  [info] isComplete = " + db.getClass().getMethod("isComplete").invoke(db));
            System.out.println("  [info] containsDataSource = " + db.getClass().getMethod("containsDataSource").invoke(db));
            System.out.println("  [info] storageUnits = " + ctx.getClass().getMethod("getStorageUnits", String.class).invoke(ctx, DB));
            Collection<?> schemas = (Collection<?>) db.getClass().getMethod("getAllSchemas").invoke(db);
            System.out.println("  [info] metadata schemas = " + schemas);
            for (Object schema : schemas) {
                Object tables = schema.getClass().getMethod("getTables").invoke(schema);
                TreeSet<String> names = new TreeSet<>();
                for (Object k : ((Map<?, ?>) tables).keySet()) names.add(String.valueOf(k));
                System.out.println("  [info] schema " + schema.getClass().getMethod("getName").invoke(schema)
                        + " cached " + names.size() + " tables: " + names);
            }
            Object ruleMd = db.getClass().getMethod("getRuleMetaData").invoke(db);
            Map<?, ?> rules = (Map<?, ?>) ruleMd.getClass().getMethod("getRules").invoke(ruleMd);
            System.out.println("  [info] loaded rules = " + rules.size() + " -> " + rules.keySet());
        } catch (Throwable t) {
            System.out.println("  [info] metadata probe failed: " + firstLine(t));
        }
    }

    static Object contextManagerOf(DataSource ds) throws Exception {
        Class<?> c = ds.getClass();
        while (c != null) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField("contextManager");
                f.setAccessible(true);
                return f.get(ds);
            } catch (NoSuchFieldException e) {
                c = c.getSuperclass();
            }
        }
        throw new IllegalStateException("contextManager field not found on " + ds.getClass());
    }

    // ------------------------- infra -------------------------

    static DataSource hikari(int maxPool, int minIdle) throws Exception {
        Class<?> hk = Class.forName("com.zaxxer.hikari.HikariDataSource");
        DataSource ds = (DataSource) hk.getDeclaredConstructor().newInstance();
        hk.getMethod("setJdbcUrl", String.class).invoke(ds, JDBC);
        hk.getMethod("setUsername", String.class).invoke(ds, USER);
        hk.getMethod("setPassword", String.class).invoke(ds, PASS);
        hk.getMethod("setDriverClassName", String.class).invoke(ds, "com.mysql.cj.jdbc.Driver");
        hk.getMethod("setMaximumPoolSize", int.class).invoke(ds, maxPool);
        hk.getMethod("setMinimumIdle", int.class).invoke(ds, minIdle);
        hk.getMethod("setConnectionTimeout", long.class).invoke(ds, 3000L);
        return ds;
    }

    static org.apache.shardingsphere.infra.algorithm.core.config.AlgorithmConfiguration hintAlgorithm() {
        org.apache.shardingsphere.infra.algorithm.core.config.AlgorithmConfiguration cfg =
                new org.apache.shardingsphere.infra.algorithm.core.config.AlgorithmConfiguration("CLASS_BASED", new Properties());
        cfg.getProps().setProperty("strategy", "HINT");
        cfg.getProps().setProperty("algorithmClassName",
                "org.apache.shardingsphere.sharding.algorithm.sharding.hint.HintInlineShardingAlgorithm");
        return cfg;
    }

    static String nodes() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            if (i > 0) sb.append(',');
            sb.append(DB).append(String.format(".timeout_task_%02d", i));
        }
        return sb.toString();
    }

    static Map<String, DataSource> map(String k, DataSource v) {
        Map<String, DataSource> m = new LinkedHashMap<>();
        m.put(k, v);
        return m;
    }

    static void createSchema() throws SQLException {
        try (Connection c = DriverManager.getConnection(
                "jdbc:mysql://" + HOST + ":" + PORT + "/?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai", USER, PASS);
             Statement s = c.createStatement()) {
            s.executeUpdate("CREATE DATABASE IF NOT EXISTS " + DB + " DEFAULT CHARACTER SET utf8mb4");
        }
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
            s.executeUpdate("INSERT INTO user_account (id, name) VALUES (1, 'alice')");
            s.executeUpdate("INSERT INTO product_order (order_no, user_id, status, msg) VALUES ('BASE-1', 1, 'WAITING', 'b')");
        }
    }

    static int countInner(String sql) {
        try (Connection c = inner.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        } catch (SQLException e) {
            return -999;
        }
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

    static final class SingleRuleView {
        static void dump(DataSource ds) {
            try {
                Object ctx = contextManagerOf(ds);
                Object db = ctx.getClass().getMethod("getDatabase", String.class).invoke(ctx, DB);
                Object ruleMd = db.getClass().getMethod("getRuleMetaData").invoke(db);
                Map<?, ?> rules = (Map<?, ?>) ruleMd.getClass().getMethod("getRules").invoke(ruleMd);
                System.out.println("  [info] loaded rules = " + rules.size() + " -> " + rules.keySet());
                for (Map.Entry<?, ?> e : rules.entrySet()) {
                    System.out.println("  [info] rule: " + e.getKey());
                    if (String.valueOf(e.getKey()).contains("Single")) {
                        Object rule = e.getValue();
                        Object conf = rule.getClass().getMethod("getConfiguration").invoke(rule);
                        Object defDs = conf.getClass().getMethod("getDefaultDataSource").invoke(conf);
                        Object tables = conf.getClass().getMethod("getTables").invoke(conf);
                        Object nodeMap = rule.getClass().getMethod("getSingleTableDataNodes").invoke(rule);
                        Object dsn = rule.getClass().getMethod("getDataSourceNames").invoke(rule);
                        System.out.println("  [info]   configuration.getDefaultDataSource() (Optional) = " + defDs);
                        System.out.println("  [info]   configuration.getTables() = " + tables);
                        System.out.println("  [info]   rule.getDataSourceNames() = " + dsn);
                        System.out.println("  [info]   rule.getSingleTableDataNodes() size = " + ((Map<?, ?>) nodeMap).size());
                        int i = 0;
                        for (Map.Entry<?, ?> n : ((Map<?, ?>) nodeMap).entrySet()) {
                            if (i++ >= 8) { System.out.println("  [info]     ... total " + ((Map<?, ?>) nodeMap).size()); break; }
                            System.out.println("  [info]     " + n.getKey() + " -> " + n.getValue());
                        }
                    }
                }
            } catch (Throwable t) {
                System.out.println("  [info] SingleRule reflection probe failed: " + firstLine(t));
            }
        }
    }
}
