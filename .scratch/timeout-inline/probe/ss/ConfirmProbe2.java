import org.apache.shardingsphere.infra.hint.HintManager;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Final consolidated confirmation for ticket 01. Threads the needle that broke ConfirmProbe:
 *   - one FRESH MySQL schema per variant (ss_c / ss_d / ss_e / ss_f)
 *   - one JDBC URL per variant, so the driver's DATA_SOURCE_CACHE (keyed by URL) cannot
 *     hand back a datasource built from a different variant's YAML
 *   - assertions only through the LOGICAL table (or raw JDBC); physical shard tables are never
 *     addressed directly, because in a sharded logical table that is exactly what a sharded
 *     datasource is supposed to refuse.
 *
 * C: repo shape verbatim            -- !SHARDING only, no !SINGLE, no databaseName
 * D: repo shape + the proposed fix  -- !SINGLE { tables: ["*.*.*"] }
 * E: !SINGLE { defaultDataSource }  -- no tables
 * F: !SINGLE { defaultDataSource, per-table "<schema>.<table>" lines }
 */
public final class ConfirmProbe2 {

    static int pass = 0, fail = 0;

    static final class Variant {

        private final String label;
        private final String schema;
        private final String yaml;

        Variant(String label, String schema, String yaml) {
            this.label = label;
            this.schema = schema;
            this.yaml = yaml;
        }

        public String label() {
            return label;
        }

        public String schema() {
            return schema;
        }

        public String yaml() {
            return yaml;
        }
    }

    static final List<Variant> VARIANTS = List.of(
            new Variant("C repo-shape-verbatim   (no !SINGLE)", "ss_c", "probe-r-c.yaml"),
            new Variant("D repo-shape+!SINGLE *  (proposed fix)", "ss_d", "probe-r-d.yaml"),
            new Variant("E !SINGLE defaultDs-only (no tables)", "ss_e", "probe-r-e.yaml"),
            new Variant("F !SINGLE per-table lines", "ss_f", "probe-r-f.yaml"));

    public static void main(String[] args) throws Exception {
        for (Variant v : VARIANTS) {
            run(v);
        }
        System.out.println();
        System.out.println("=== CONFIRMPROBE2 RESULT: PASS=" + pass + " FAIL=" + fail + " ===");
        System.out.println("  [info] DATA_SOURCE_CACHE holds " + cacheKeys().size() + " entries (one per YAML):");
        for (String k : cacheKeys()) {
            System.out.println("  [info]   " + k);
        }
    }

    static void run(Variant v) throws Exception {
        String url = "jdbc:mysql://127.0.0.1:3307/" + v.schema()
                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8";
        System.out.println();
        System.out.println("################ " + v.label() + " ################");
        reset(url);
        DataSource m = ss("jdbc:shardingsphere:classpath:" + v.yaml());
        boolean transparent = dumpShape(m);

        // Q1: are unconfigured business tables usable?
        int ok = 0;
        for (String t : List.of("user_account", "product_order", "review_task", "errand_order")) {
            try (Connection c = m.getConnection(); Statement s = c.createStatement()) {
                s.executeQuery("SELECT COUNT(*) FROM " + t);
                ok++;
            } catch (Throwable ignored) {
            }
        }
        System.out.println("  [info] Q1 unconfigured business tables usable: " + ok + "/4"
                + "  (transparent=" + transparent + ")");

        // Q2: sharded logical table still routes by hint (the production pattern)
        int hit = 0;
        for (int i = 0; i < 6; i++) {
            final String shard = "timeout_task_0" + i;
            if (hintedInsert(m, shard, "q2-" + i) && rawCount(url, "SELECT COUNT(*) FROM " + shard) == 1) {
                hit++;
            }
        }
        check("Q2 hint-routed writes land on the hinted physical shard (6/6)", hit == 6, "hit=" + hit + "/6");

        // Q2b: no-hint write must be rejected (fail-fast safety net)
        try (Connection c = m.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO timeout_task (task_type,biz_id,status,retry_count) VALUES ('T','nohint','W',0)");
            check("Q2b unwritable without hint (fail-fast)", false, "it succeeded");
        } catch (Throwable t) {
            check("Q2b unwritable without hint (fail-fast)", true, brief(firstLine(t)));
        }

        // Q3: same local transaction across sharded + unsharded + sharded.
        // Shard 06 is deliberately outside the 00..05 range used by the Q2 loop, and the target
        // shard is asserted empty up front, so "the rollback left a row" cannot be confused with
        // a row the Q2 loop committed.
        final String txShard = "timeout_task_06";
        check("Q3 target shard " + txShard + " is empty before the transaction",
                rawCount(url, "SELECT COUNT(*) FROM " + txShard) == 0,
                "rows=" + rawCount(url, "SELECT COUNT(*) FROM " + txShard));
        try (Connection c = m.getConnection()) {
            c.setAutoCommit(false);
            long id1, id2, id3;
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO product_order (order_no,user_id,status,msg) VALUES ('TX1',1,'W','a')");
                id1 = one(c, "SELECT CONNECTION_ID()");
                try (HintManager h = HintManager.getInstance()) {
                    h.addTableShardingValue("timeout_task", txShard);
                    s.executeUpdate("INSERT INTO timeout_task (task_type,biz_id,status,retry_count) VALUES ('T','tx1','W',0)");
                }
                id2 = one(c, "SELECT CONNECTION_ID()");
                s.executeUpdate("INSERT INTO review_task (task_id) VALUES (5)");
                id3 = one(c, "SELECT CONNECTION_ID()");
            }
            check("Q3 one MySQL session for sharded+unsharded writes", id1 == id2 && id2 == id3, "ids=" + id1 + "," + id2 + "," + id3);
            check("Q3 uncommitted sharded row is invisible to other sessions",
                    rawCount(url, "SELECT COUNT(*) FROM " + txShard) == 0,
                    "rowsFromAnotherConnection=" + rawCount(url, "SELECT COUNT(*) FROM " + txShard));
            c.rollback();
            check("Q3 one rollback clears all three tables",
                    rawCount(url, "SELECT COUNT(*) FROM product_order WHERE order_no='TX1'") == 0
                            && rawCount(url, "SELECT COUNT(*) FROM " + txShard) == 0
                            && rawCount(url, "SELECT COUNT(*) FROM review_task") == 0,
                    "po=" + rawCount(url, "SELECT COUNT(*) FROM product_order WHERE order_no='TX1'")
                            + " tt=" + rawCount(url, "SELECT COUNT(*) FROM " + txShard)
                            + " rt=" + rawCount(url, "SELECT COUNT(*) FROM review_task"));
            c.setAutoCommit(true);
        } catch (Throwable t) {
            check("Q3 mixed transaction", false, firstLine(t));
        }

        // Q4: the JDBC feature matrix the ticket asks about
        matrix(m, url);

        // Q5: pool footprint -- storage-unit Hikari settings + MySQL connection count
        poolFootprint(url);
    }

    static void matrix(DataSource m, String url) {
        exec(m, "Q4 SELECT LAST_INSERT_ID()", "SELECT LAST_INSERT_ID()");
        exec(m, "Q4 ODKU", "INSERT INTO product_order (order_no,user_id,status,msg) VALUES ('M1',1,'W','x')"
                + " ON DUPLICATE KEY UPDATE msg='y'");
        exec(m, "Q4 INSERT IGNORE", "INSERT IGNORE INTO product_order (order_no,user_id,status,msg) VALUES ('M1',1,'W','z')");
        exec(m, "Q4 multi-value IN batch UPDATE", "UPDATE product_order SET msg='b' WHERE order_no IN ('M1','TX1')");
        exec(m, "Q4 EXPLAIN", "EXPLAIN SELECT * FROM product_order");
        exec(m, "Q4 subquery", "SELECT COUNT(*) FROM (SELECT id FROM product_order) t");
        exec(m, "Q4 JOIN", "SELECT o.id FROM product_order o JOIN user_account u ON u.id=o.user_id");
        exec(m, "Q4 FOR UPDATE", "SELECT * FROM product_order FOR UPDATE");
        exec(m, "Q4 TRUNCATE", "TRUNCATE TABLE review_task");
        exec(m, "Q4 information_schema", "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA='" + schemaOf(url) + "'");
        exec(m, "Q4 SHOW TABLES", "SHOW TABLES");
        exec(m, "Q4 SHOW CREATE TABLE", "SHOW CREATE TABLE product_order");

        // useGeneratedKeys via getGeneratedKeys()
        try (Connection c = m.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO product_order (order_no,user_id,status,msg) VALUES (?,1,'W','g')",
                     Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "GK1");
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                check("Q4 unconfigured-table generated keys", rs.getLong(1) > 0, "key=" + rs.getLong(1));
            }
        } catch (Throwable t) {
            check("Q4 unconfigured-table generated keys", false, firstLine(t));
        }
        try (Connection c = m.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO timeout_task (task_type,biz_id,status,retry_count) VALUES ('T',?,'W',0)",
                     Statement.RETURN_GENERATED_KEYS)) {
            try (HintManager h = HintManager.getInstance()) {
                h.addTableShardingValue("timeout_task", "timeout_task_07");
                ps.setString(1, "gk-shard");
                ps.executeUpdate();
            }
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                check("Q4 sharded-table generated keys", rs.getLong(1) > 0, "key=" + rs.getLong(1));
            }
        } catch (Throwable t) {
            check("Q4 sharded-table generated keys", false, firstLine(t));
        }
    }

    static void poolFootprint(String url) {
        try (Connection c = DriverManager.getConnection(url, "root", "");
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM information_schema.PROCESSLIST WHERE DB='" + schemaOf(url) + "'")) {
            rs.next();
            System.out.println("  [info] Q5 MySQL PROCESSLIST rows for " + schemaOf(url) + " = " + rs.getInt(1));
        } catch (Throwable t) {
            System.out.println("  [info] Q5 processlist failed: " + firstLine(t));
        }
    }

    // ---------- helpers ----------

    static boolean hintedInsert(DataSource m, String shard, String biz) {
        try (HintManager h = HintManager.getInstance()) {
            h.addTableShardingValue("timeout_task", shard);
            try (Connection c = m.getConnection(); Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO timeout_task (task_type,biz_id,status,retry_count)"
                        + " VALUES ('T','" + biz + "','WAITING',0)");
                return true;
            }
        } catch (Throwable t) {
            return false;
        }
    }

    static boolean dumpShape(DataSource m) {
        boolean transparent = false;
        try {
            Object ctx = cm(m);
            for (Object dbName : (Collection<?>) ctx.getClass().getMethod("getAllDatabaseNames").invoke(ctx)) {
                Object db = ctx.getClass().getMethod("getDatabase", String.class).invoke(ctx, dbName);
                if (!(Boolean) db.getClass().getMethod("isComplete").invoke(db)) continue;
                Object schema = db.getClass().getMethod("getSchema", String.class).invoke(db, String.valueOf(dbName));
                SortedSet<String> names = new TreeSet<>();
                for (Object t : (Collection<?>) schema.getClass().getMethod("getAllTables").invoke(schema)) {
                    names.add(String.valueOf(t.getClass().getMethod("getName").invoke(t)));
                }
                System.out.println("  [info] logical db '" + dbName + "' visible tables(" + names.size() + "): " + names);
                transparent = names.size() > 1;
                Object ruleMd = db.getClass().getMethod("getRuleMetaData").invoke(db);
                for (Object rule : (Collection<?>) ruleMd.getClass().getMethod("getRules").invoke(ruleMd)) {
                    if (rule.getClass().getSimpleName().contains("Single")) {
                        Map<?, ?> nodes = (Map<?, ?>) rule.getClass().getMethod("getSingleTableDataNodes").invoke(rule);
                        System.out.println("  [info] singleTableDataNodes (" + nodes.size() + ") keys="
                                + new TreeSet<>(nodes.keySet()));
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("  [info] shape dump failed: " + firstLine(t));
        }
        return transparent;
    }

    static void exec(DataSource m, String tag, String sql) {
        try (Connection c = m.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
            check(tag, true, "ok");
        } catch (Throwable t) {
            check(tag, false, brief(firstLine(t)));
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
        DataSource ds = cacheMap().get(url);
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

    static List<String> cacheKeys() {
        try {
            return new ArrayList<>(cacheMap().keySet());
        } catch (Exception e) {
            return List.of("<failed: " + e + ">");
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

    static String schemaOf(String url) {
        String s = url.substring(url.indexOf("3307/") + 5);
        return s.substring(0, s.indexOf('?'));
    }

    static void reset(String url) throws Exception {
        try (Connection c = DriverManager.getConnection(url, "root", ""); Statement s = c.createStatement()) {
            for (int i = 0; i < 16; i++) {
                s.executeUpdate("DELETE FROM timeout_task_" + String.format("%02d", i));
            }
            s.executeUpdate("DELETE FROM product_order");
            s.executeUpdate("DELETE FROM review_task");
            s.executeUpdate("DELETE FROM user_account");
            s.executeUpdate("INSERT INTO user_account (id,name) VALUES (1,'alice')");
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
        return x.length() > 110 ? x.substring(0, 110) + "..." : x;
    }
}
