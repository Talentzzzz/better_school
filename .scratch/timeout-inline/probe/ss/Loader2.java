import org.apache.shardingsphere.infra.datanode.DataNode;
import org.apache.shardingsphere.single.datanode.SingleTableDataNodeLoader;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** Bisect: which argument combination makes SingleTableDataNodeLoader.load return nodes? */
public final class Loader2 {

    static final String URL = "jdbc:mysql://127.0.0.1:3307/probe?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8";

    public static void main(String[] args) throws Exception {
        DataSource ds = hikari();
        Object type = Class.forName("org.apache.shardingsphere.infra.database.DatabaseTypeEngine")
                .getMethod("getStorageType", DataSource.class).invoke(null, ds);
        Map<String, DataSource> dss = new LinkedHashMap<>();
        dss.put("ds0", ds);

        call("probe", type, dss, List.of(), List.of());
        call("probe", type, dss, List.of(), List.of("user_account", "product_order"));
        call("probe", type, dss, List.of(), List.of("*.*.*"));
        call("probe", type, dss, List.of(), List.of("probe.*.*"));
        call("probe", type, dss, List.of(), List.of("probe.*"));
        call("probe", type, dss, List.of(), List.of("*.*"));
    }

    static void call(String db, Object type, Map<String, DataSource> dss, Collection<?> rules, Collection<String> tables) {
        try {
            Map<String, Collection<DataNode>> got = SingleTableDataNodeLoader.load(
                    db, (org.apache.shardingsphere.database.connector.core.type.DatabaseType) type,
                    dss, (Collection) rules, tables);
            System.out.println("tables=" + tables + " -> size=" + got.size()
                    + (got.isEmpty() ? "" : " first=" + got.values().iterator().next().iterator().next()));
        } catch (Throwable t) {
            System.out.println("tables=" + tables + " -> EX " + t);
        }
    }

    static DataSource hikari() throws Exception {
        Class<?> hk = Class.forName("com.zaxxer.hikari.HikariDataSource");
        DataSource ds = (DataSource) hk.getDeclaredConstructor().newInstance();
        hk.getMethod("setJdbcUrl", String.class).invoke(ds, URL);
        hk.getMethod("setUsername", String.class).invoke(ds, "root");
        hk.getMethod("setPassword", String.class).invoke(ds, "");
        hk.getMethod("setDriverClassName", String.class).invoke(ds, "com.mysql.cj.jdbc.Driver");
        hk.getMethod("setMaximumPoolSize", int.class).invoke(ds, 10);
        hk.getMethod("setMinimumIdle", int.class).invoke(ds, 1);
        return ds;
    }
}
