import org.apache.shardingsphere.infra.hint.HintManager;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Round-2 verification of two claims that matter for the shot-call:
 *
 * 1. HintManager thread transmission. The arch doc says "hint and the mapper call must be on the
 *    same thread". ConfirmProbe reported that a plain `new Thread` DOES inherit the parent hint.
 *    Verify exactly which thread-creation paths inherit and which do not.
 *
 * 2. The repo's real sharding.yaml shape (no !SINGLE) on a faithful replica -- how many of the
 *    unconfigured business tables die, and does the logical table still work.
 */
public final class VerifyProbe {

    static final String T_URL = "jdbc:mysql://127.0.0.1:3307/ss_t?useSSL=false&allowPublicKeyRetrieval=true"
            + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8";

    static int pass = 0, fail = 0;

    public static void main(String[] args) throws Exception {
        reset(T_URL);
        DataSource raw = hikari(T_URL);

        hintTransmission();

        DataSource m = ss("jdbc:shardingsphere:classpath:probe-r-t.yaml");
        System.out.println();
        System.out.println("################ repo-shape replica (ss_t / probe-r-t.yaml, NO !SINGLE) ################");
        dumpMeta(m);

        System.out.println("  ---- unconfigured business tables ----");
        int dead = 0;
        List<String> business = List.of("user_account", "product_order", "review_task", "errand_order",
                "bs_user", "bs_course", "bs_student", "bs_teacher", "bs_class", "bs_grade",
                "bs_homework", "bs_exam", "bs_notice", "bs_message", "bs_file", "bs_log",
                "bs_tag", "bs_comment", "bs_like", "bs_follow");
        for (String t : business) {
            try (Connection c = m.getConnection(); Statement s = c.createStatement()) {
                s.executeQuery("SELECT COUNT(*) FROM " + t);
            } catch (Throwable e) {
                dead++;
            }
        }
        check("all " + business.size() + " unconfigured business tables unreachable",
                dead == business.size(), "dead=" + dead + "/" + business.size());

        System.out.println("  ---- logical sharded table still works ----");
        try (HintManager h = HintManager.getInstance()) {
            h.addTableShardingValue("timeout_task", "timeout_task_03");
            try (Connection c = m.getConnection(); Statement s = c.createStatement()) {
                int n = s.executeUpdate("INSERT INTO timeout_task (task_type,biz_id,status,retry_count)"
                        + " VALUES ('T','v1','WAITING',0)");
                check("hinted write via logical table (no !SINGLE config)", n == 1, "updateCount=" + n);
            }
        } catch (Throwable t) {
            check("hinted write via logical table (no !SINGLE config)", false, firstLine(t));
        }
        check("row physically in timeout_task_03",
                rawCount(T_URL, "SELECT COUNT(*) FROM timeout_task_03") == 1,
                "raw=" + rawCount(T_URL, "SELECT COUNT(*) FROM timeout_task_03"));

        System.out.println();
        System.out.println("=== VERIFY RESULT: PASS=" + pass + " FAIL=" + fail + " ===");
        raw.getClass().getMethod("close").invoke(raw);
    }

    // ---------- HintManager thread transmission ----------

    static void hintTransmission() {
        System.out.println();
        System.out.println("################ HintManager thread transmission ################");
        printHolderClass();

        // 1. plain new Thread created inside the hint scope
        try (HintManager h = HintManager.getInstance()) {
            h.addTableShardingValue("timeout_task", "timeout_task_11");
            String[] seen = new String[1];
            Thread t = new Thread(() -> seen[0] = String.valueOf(HintManager.getTableShardingValues("timeout_task")));
            t.start();
            t.join();
            check("plain new Thread (created in scope) sees parent hint",
                    !"[]".equals(seen[0]), "child sees " + seen[0]);
        } catch (Exception e) {
            check("plain new Thread (created in scope) sees parent hint", false, firstLine(e));
        }

        // 2. plain new Thread created BEFORE the hint, started inside the scope
        try {
            String[] seen = new String[1];
            Thread t = new Thread(() -> seen[0] = String.valueOf(HintManager.getTableShardingValues("timeout_task")));
            try (HintManager h = HintManager.getInstance()) {
                h.addTableShardingValue("timeout_task", "timeout_task_13");
                t.start();
                t.join();
            }
            check("plain new Thread (created BEFORE scope) sees parent hint",
                    !"[]".equals(seen[0]), "child sees " + seen[0]);
        } catch (Exception e) {
            check("plain new Thread (created BEFORE scope) sees parent hint", false, firstLine(e));
        }

        // 3. pooled worker thread
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            try (HintManager h = HintManager.getInstance()) {
                h.addTableShardingValue("timeout_task", "timeout_task_15");
                String seen = pool.submit(() -> String.valueOf(HintManager.getTableShardingValues("timeout_task"))).get();
                check("pooled worker sees parent hint", !"[]".equals(seen), "worker sees " + seen);
            }
            // 4. does it survive into the NEXT task on that reused worker?
            Boolean leaked = pool.submit(HintManager::isInstantiated).get();
            check("hint does not survive into next task on reused worker",
                    !leaked, "next task isInstantiated=" + leaked);
        } catch (Exception e) {
            check("pooled worker", false, firstLine(e));
        } finally {
            pool.shutdownNow();
        }

        // 5. after close(), a child thread must not see anything
        try (HintManager h = HintManager.getInstance()) {
            h.addTableShardingValue("timeout_task", "timeout_task_09");
        }
        String[] after = new String[1];
        try {
            Thread t = new Thread(() -> after[0] = String.valueOf(HintManager.getTableShardingValues("timeout_task")));
            t.start();
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        check("no hint transmitted to a thread created after close()",
                "[]".equals(after[0]), "child sees " + after[0]);
    }

    static void printHolderClass() {
        try {
            Field f = HintManager.class.getDeclaredField("HINT_MANAGER_HOLDER");
            f.setAccessible(true);
            Object holder = f.get(null);
            System.out.println("  [info] HINT_MANAGER_HOLDER = " + holder.getClass().getName());
        } catch (Throwable t) {
            System.out.println("  [info] holder probe failed: " + firstLine(t));
        }
    }

    // ---------- infra ----------

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
                System.out.println("  [info] logical db '" + dbName + "' -> visible tables(" + names.size() + "): " + names);
            }
        } catch (Throwable t) {
            System.out.println("  [info] meta dump failed: " + firstLine(t));
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
        java.lang.reflect.Method getMap = cache.getClass().getMethod("getDataSourceMap");
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

    static void reset(String url) throws Exception {
        try (Connection c = DriverManager.getConnection(url, "root", ""); Statement s = c.createStatement()) {
            for (String t : new String[]{"user_account", "product_order", "review_task", "errand_order",
                    "bs_user", "bs_course", "bs_student", "bs_teacher", "bs_class", "bs_grade",
                    "bs_homework", "bs_exam", "bs_notice", "bs_message", "bs_file", "bs_log",
                    "bs_tag", "bs_comment", "bs_like", "bs_follow"}) {
                s.executeUpdate("CREATE TABLE IF NOT EXISTS " + t + " (id BIGINT PRIMARY KEY AUTO_INCREMENT)");
            }
            for (int i = 0; i < 16; i++) {
                s.executeUpdate("CREATE TABLE IF NOT EXISTS timeout_task_" + String.format("%02d", i)
                        + " (id BIGINT PRIMARY KEY AUTO_INCREMENT, task_type VARCHAR(40), biz_id VARCHAR(64),"
                        + " action_time DATETIME, status VARCHAR(20), retry_count INT DEFAULT 0, next_fire_time DATETIME,"
                        + " created_at DATETIME, updated_at DATETIME, UNIQUE KEY uk_type_biz (task_type, biz_id))");
                s.executeUpdate("DELETE FROM timeout_task_" + String.format("%02d", i));
            }
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
}
