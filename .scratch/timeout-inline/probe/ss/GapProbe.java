import org.apache.shardingsphere.infra.hint.HintManager;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.sql.*;
import java.util.*;

/**
 * Gap check for the exact configuration the fix would ship:
 *   repo shape (NO databaseName) + !SINGLE { tables: ["*.*.*"] }
 *
 * If this passes, the fix is a pure YAML addition with no other change required.
 */
public final class GapProbe {

    static final String URL = "jdbc:mysql://127.0.0.1:3307/ss_g?useSSL=false&allowPublicKeyRetrieval=true"
            + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8";

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        reset();
        DataSource m = ss("jdbc:shardingsphere:classpath:probe-r-g.yaml");

        System.out.println("################ repo shape (no databaseName) + !SINGLE [\"*.*.*\"] ################");
        dump(m);

        int ok = 0;
        for (String t : List.of("user_account", "product_order", "review_task", "errand_order")) {
            try (Connection c = m.getConnection(); Statement s = c.createStatement()) {
                s.executeQuery("SELECT COUNT(*) FROM " + t);
                ok++;
            } catch (Throwable ignored) {
            }
        }
        check("unconfigured business tables transparent (4/4)", ok == 4, "usable=" + ok + "/4");

        int hit = 0;
        for (int i = 0; i < 6; i++) {
            final String shard = "timeout_task_0" + i;
            try (HintManager h = HintManager.getInstance()) {
                h.addTableShardingValue("timeout_task", shard);
                try (Connection c = m.getConnection(); Statement s = c.createStatement()) {
                    s.executeUpdate("INSERT INTO timeout_task (task_type,biz_id,status,retry_count)"
                            + " VALUES ('T','g" + i + "','W',0)");
                }
            }
            if (count("SELECT COUNT(*) FROM " + shard) == 1) hit++;
        }
        check("hint-routed writes land on the hinted shard (6/6)", hit == 6, "hit=" + hit + "/6");

        try (Connection c = m.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("INSERT INTO timeout_task (task_type,biz_id,status,retry_count) VALUES ('T','nh','W',0)");
            check("no-hint sharded write rejected", false, "it succeeded");
        } catch (Throwable t) {
            check("no-hint sharded write rejected", true, brief(t));
        }

        // the mixed local transaction
        final String tx = "timeout_task_08";
        try (Connection c = m.getConnection()) {
            c.setAutoCommit(false);
            long id1, id2, id3;
            try (Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO product_order (order_no,user_id,status,msg) VALUES ('TX9',1,'W','a')");
                id1 = one(c, "SELECT CONNECTION_ID()");
                try (HintManager h = HintManager.getInstance()) {
                    h.addTableShardingValue("timeout_task", tx);
                    s.executeUpdate("INSERT INTO timeout_task (task_type,biz_id,status,retry_count) VALUES ('T','tx9','W',0)");
                }
                id2 = one(c, "SELECT CONNECTION_ID()");
                s.executeUpdate("INSERT INTO review_task (task_id) VALUES (5)");
                id3 = one(c, "SELECT CONNECTION_ID()");
            }
            check("one MySQL session for sharded+unsharded", id1 == id2 && id2 == id3, "ids=" + id1 + "," + id2 + "," + id3);
            c.rollback();
            check("one rollback clears all three",
                    count("SELECT COUNT(*) FROM product_order WHERE order_no='TX9'") == 0
                            && count("SELECT COUNT(*) FROM " + tx) == 0
                            && count("SELECT COUNT(*) FROM review_task") == 0,
                    "po=" + count("SELECT COUNT(*) FROM product_order WHERE order_no='TX9'")
                            + " tt=" + count("SELECT COUNT(*) FROM " + tx)
                            + " rt=" + count("SELECT COUNT(*) FROM review_task"));
            c.setAutoCommit(true);
        } catch (Throwable t) {
            check("mixed local transaction", false, brief(t));
        }

        // MyBatis-shaped prepared statement with generated keys, hinted
        try (Connection c = m.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO timeout_task (task_type,biz_id,status,retry_count) VALUES ('T',?,'W',0)",
                     Statement.RETURN_GENERATED_KEYS)) {
            try (HintManager h = HintManager.getInstance()) {
                h.addTableShardingValue("timeout_task", "timeout_task_10");
                ps.setString(1, "gk");
                ps.executeUpdate();
            }
            try (ResultSet rs = ps.getGeneratedKeys()) {
                rs.next();
                check("hinted prepared insert + generated keys", rs.getLong(1) > 0, "key=" + rs.getLong(1));
            }
        } catch (Throwable t) {
            check("hinted prepared insert + generated keys", false, brief(t));
        }

        System.out.println();
        System.out.println("=== GAP RESULT: PASS=" + pass + " FAIL=" + fail + " ===");
    }

    static void dump(DataSource m) {
        try {
            Class<?> dc = Class.forName("org.apache.shardingsphere.driver.jdbc.core.datasource.ShardingSphereDataSource");
            Class<?> cc = Class.forName("org.apache.shardingsphere.infra.metadata.ShardingSphereMetaData");
            Field f = dc.getDeclaredField("contextManager");
            f.setAccessible(true);
            Object ctx = f.get(m);
            for (Object dbName : (Collection<?>) ctx.getClass().getMethod("getAllDatabaseNames").invoke(ctx)) {
                Object db = ctx.getClass().getMethod("getDatabase", String.class).invoke(ctx, dbName);
                if (!(Boolean) db.getClass().getMethod("isComplete").invoke(db)) continue;
                Object schema = db.getClass().getMethod("getSchema", String.class).invoke(db, String.valueOf(dbName));
                SortedSet<String> names = new TreeSet<>();
                for (Object t : (Collection<?>) schema.getClass().getMethod("getAllTables").invoke(schema)) {
                    names.add(String.valueOf(t.getClass().getMethod("getName").invoke(t)));
                }
                System.out.println("  [info] logical db '" + dbName + "' visible tables(" + names.size() + "): " + names);
                Object ruleMd = db.getClass().getMethod("getRuleMetaData").invoke(db);
                for (Object rule : (Collection<?>) ruleMd.getClass().getMethod("getRules").invoke(ruleMd)) {
                    if (rule.getClass().getSimpleName().contains("Single")) {
                        Map<?, ?> nodes = (Map<?, ?>) rule.getClass().getMethod("getSingleTableDataNodes").invoke(rule);
                        System.out.println("  [info] singleTableDataNodes (" + nodes.size() + ") keys=" + new TreeSet<>(nodes.keySet()));
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("  [info] dump failed: " + t);
        }
    }

    static void reset() throws Exception {
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

    @SuppressWarnings("unchecked")
    static DataSource ss(String url) throws Exception {
        try (Connection c = DriverManager.getConnection(url)) { }
        Field f = Class.forName("org.apache.shardingsphere.driver.ShardingSphereDriver").getDeclaredField("DATA_SOURCE_CACHE");
        f.setAccessible(true);
        Object cache = f.get(null);
        Method getMap = cache.getClass().getMethod("getDataSourceMap");
        getMap.setAccessible(true);
        DataSource ds = ((Map<String, DataSource>) getMap.invoke(cache)).get(url);
        if (ds == null) throw new IllegalStateException("not cached: " + url);
        return ds;
    }

    static void check(String what, boolean ok, String detail) {
        if (ok) pass++; else fail++;
        System.out.println((ok ? "  [PASS] " : "  [FAIL] ") + what + "  ::  " + detail);
    }

    static String brief(Throwable t) {
        Throwable r = t;
        while (r.getCause() != null && r.getCause() != r) r = r.getCause();
        String s = r.toString().replaceAll("\\s+", " ");
        return s.length() > 110 ? s.substring(0, 110) + "..." : s;
    }
}
