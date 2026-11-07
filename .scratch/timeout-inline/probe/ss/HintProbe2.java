import org.apache.shardingsphere.infra.hint.HintManager;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.*;
import java.util.*;

/**
 * Round-2 decisive probe.
 *
 * Q-A: is the "hinted write" failure in ConfirmProbe reproducible, and what exactly is it?
 *      (ConfirmProbe set the hint AFTER opening the connection; the production pattern
 *       TimeoutTaskRepository.withSlotHint() sets it before -- and before any statement exists.)
 * Q-B: are the 16 physical shard tables visible in the logical metadata at all?
 *
 * Schemas are fresh and per-variant, and each variant gets its own JDBC URL so the driver's
 * DATA_SOURCE_CACHE (keyed by URL) cannot hand back another variant's datasource.
 */
public final class HintProbe2 {

    static final String A_URL = "jdbc:mysql://127.0.0.1:3307/ss_a?useSSL=false&allowPublicKeyRetrieval=true"
            + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8";
    static final String B_URL = "jdbc:mysql://127.0.0.1:3307/ss_b?useSSL=false&allowPublicKeyRetrieval=true"
            + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8";

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        run("A production-shape  (ss_a / probe-r-a.yaml)", A_URL, "probe-r-a.yaml");
        run("B singleStar        (ss_b / probe-r-b.yaml)", B_URL, "probe-r-b.yaml");
        System.out.println();
        System.out.println("=== HINTPROBE2 RESULT: PASS=" + pass + " FAIL=" + fail + " ===");
        for (String url : cacheKeys()) {
            System.out.println("  [info] DATA_SOURCE_CACHE key: " + url);
        }
    }

    static void run(String label, String url, String yaml) throws Exception {
        System.out.println();
        System.out.println("################ " + label + " ################");
        reset(url);
        DataSource m = ss("jdbc:shardingsphere:classpath:" + yaml);
        dumpMeta(m);

        // ---- Q-A1: production pattern. Hint is created BEFORE the connection is opened. ----
        System.out.println("  ---- A1 production pattern: hint -> connection -> statement -> execute ----");
        for (int i = 0; i < 6; i++) {
            final String shard = "timeout_task_0" + i;
            Object n = inHintScope(shard, () -> {
                try (Connection c = m.getConnection(); Statement s = c.createStatement()) {
                    return s.executeUpdate("INSERT INTO timeout_task (task_type,biz_id,status,retry_count)"
                            + " VALUES ('T','a" + 0 + "','WAITING',0)");
                }
            });
            checkHit(url, "A1 hinted write -> " + shard, shard, n);
        }

        // ---- Q-A2: ConfirmProbe pattern. Connection first, hint second, on a POOLED connection. ----
        System.out.println("  ---- A2 ConfirmProbe pattern: connection -> hint -> statement -> execute ----");
        for (int i = 6; i < 12; i++) {
            final String shard = "timeout_task_0" + (i % 10);
            Object n = afterConnection(shard, m);
            checkHit(url, "A2 conn-first hinted write -> " + shard, shard, n);
        }

        // ---- Q-A3: PreparedStatement path (what MyBatis actually uses) ----
        System.out.println("  ---- A3 prepared-statement path ----");
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            final String shard = "timeout_task_1" + i;
            Object n = inHintScope(shard, () -> {
                try (Connection c = m.getConnection();
                     PreparedStatement ps = c.prepareStatement("INSERT INTO timeout_task (task_type,biz_id,status,retry_count) VALUES ('T',?,'WAITING',0)")) {
                    ps.setString(1, "p" + idx);
                    return ps.executeUpdate();
                }
            });
            checkHit(url, "A3 hinted prepared write -> " + shard, shard, n);
        }

        // ---- Q-A4: the fail-fast safety net -- does a no-hint write really get rejected? ----
        System.out.println("  ---- A4 fail-fast safety net ----");
        try (Connection c = m.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO timeout_task (task_type,biz_id,status,retry_count) VALUES ('T','nohint','WAITING',0)");
            check("A4 no-hint sharded write is rejected", false, "it succeeded -- no fail-fast");
        } catch (Throwable t) {
            check("A4 no-hint sharded write is rejected", true, brief(firstLine(t)));
        }
        System.out.println("  [info] total rows in the 16 shards now = " + totalRows(url));
    }

    @FunctionalInterface interface SqlCall { Object run() throws Exception; }

    static Object inHintScope(String shard, SqlCall call) {
        try (HintManager h = HintManager.getInstance()) {
            h.addTableShardingValue("timeout_task", shard);
            return call.run();
        } catch (Throwable t) {
            return t;
        }
    }

    static Object afterConnection(String shard, DataSource m) {
        try (Connection c = m.getConnection()) {
            try (HintManager h = HintManager.getInstance()) {
                h.addTableShardingValue("timeout_task", shard);
                try (Statement s = c.createStatement()) {
                    return s.executeUpdate("INSERT INTO timeout_task (task_type,biz_id,status,retry_count)"
                            + " VALUES ('T','c" + shard + "','WAITING',0)");
                }
            }
        } catch (Throwable t) {
            return t;
        }
    }

    static void checkHit(String url, String label, String shard, Object result) {
        if (result instanceof Throwable) {
            check(label, false, brief(firstLine((Throwable) result)));
            return;
        }
        int got = rawCount(url, "SELECT COUNT(*) FROM " + shard);
        check(label, got > 0, "updateCount=" + result + " rowsInShard=" + got);
    }

    // ---------- metadata ----------

    static void dumpMeta(DataSource m) {
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
                System.out.println("  [info] logical db '" + dbName + "' -> schema tables(" + names.size() + ")");
                System.out.println("  [info]   " + names);
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
        DataSource ds = cacheMap().get(url);
        if (ds == null) throw new IllegalStateException("not cached: " + url);
        return ds;
    }

    @SuppressWarnings("unchecked")
    static Map<String, DataSource> cacheMap() throws Exception {
        Field f = Class.forName("org.apache.shardingsphere.driver.ShardingSphereDriver").getDeclaredField("DATA_SOURCE_CACHE");
        f.setAccessible(true);
        Object cache = f.get(null);
        java.lang.reflect.Method getMap = cache.getClass().getMethod("getDataSourceMap");
        getMap.setAccessible(true);
        return (Map<String, DataSource>) getMap.invoke(cache);
    }

    static List<String> cacheKeys() {
        try {
            return new ArrayList<>(cacheMap().keySet());
        } catch (Exception e) {
            return List.of("<cache read failed: " + e + ">");
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

    static void reset(String url) throws Exception {
        try (Connection c = DriverManager.getConnection(url, "root", "");
             Statement s = c.createStatement()) {
            s.executeUpdate("CREATE TABLE IF NOT EXISTS user_account (id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(50))");
            s.executeUpdate("CREATE TABLE IF NOT EXISTS product_order (id BIGINT PRIMARY KEY AUTO_INCREMENT, order_no VARCHAR(40) UNIQUE, user_id BIGINT, status VARCHAR(20), msg VARCHAR(50))");
            for (int i = 0; i < 16; i++) {
                s.executeUpdate("CREATE TABLE IF NOT EXISTS timeout_task_" + String.format("%02d", i)
                        + " (id BIGINT PRIMARY KEY AUTO_INCREMENT, task_type VARCHAR(40), biz_id VARCHAR(64),"
                        + " action_time DATETIME, status VARCHAR(20), retry_count INT DEFAULT 0, next_fire_time DATETIME,"
                        + " created_at DATETIME, updated_at DATETIME, UNIQUE KEY uk_type_biz (task_type, biz_id))");
                s.executeUpdate("DELETE FROM timeout_task_" + String.format("%02d", i));
            }
            s.executeUpdate("DELETE FROM user_account");
            s.executeUpdate("INSERT INTO user_account (id,name) VALUES (1,'alice')");
        }
    }

    static int totalRows(String url) {
        int total = 0;
        for (int i = 0; i < 16; i++) {
            total += Math.max(0, rawCount(url, "SELECT COUNT(*) FROM timeout_task_" + String.format("%02d", i)));
        }
        return total;
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
        return x.length() > 130 ? x.substring(0, 130) + "..." : x;
    }
}
