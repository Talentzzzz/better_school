import org.apache.shardingsphere.single.datanode.SingleTableDataNodeLoader;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

/** Direct probe of SingleTableDataNodeLoader.loadSchemaTableNames with the very same Hikari datasource. */
public final class LoaderProbe {

    static final String URL = "jdbc:mysql://127.0.0.1:3307/probe?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8";

    public static void main(String[] args) throws Exception {
        DataSource ds = hikari();
        System.out.println("datasource class = " + ds.getClass().getName());
        try (Connection c = ds.getConnection()) {
            System.out.println("connection catalog = " + c.getCatalog());
            System.out.println("connection schema  = " + c.getSchema());
            DatabaseMetaData md = c.getMetaData();
            System.out.println("driver = " + md.getDriverName() + " " + md.getDriverVersion());
            System.out.println("databaseProductName = " + md.getDatabaseProductName());
        }
        Object type = Class.forName("org.apache.shardingsphere.infra.database.DatabaseTypeEngine")
                .getMethod("getStorageType", DataSource.class).invoke(null, ds);
        System.out.println("storage type = " + type + " / " + type.getClass().getName());

        for (String schema : new String[]{"probe", "logic_db", "PROBE"}) {
            Map<String, Collection<String>> got = SingleTableDataNodeLoader.loadSchemaTableNames(
                    schema, (org.apache.shardingsphere.database.connector.core.type.DatabaseType) type, ds, schema, new ArrayList<>());
            System.out.println("loadSchemaTableNames(schema=" + schema + ") -> " + got);
        }

        // The real entry point SingleRule uses: load(String, DatabaseType, Map<String,DataSource>, rules, configuredTables)
        Map<String, DataSource> one = new LinkedHashMap<>();
        one.put("ds0", ds);
        for (String schema : new String[]{"probe", "logic_db", "ds0"}) {
            Map<String, Collection<org.apache.shardingsphere.infra.datanode.DataNode>> loaded = SingleTableDataNodeLoader.load(
                    schema, (org.apache.shardingsphere.database.connector.core.type.DatabaseType) type,
                    one, new ArrayList<>(), new ArrayList<>());
            System.out.println("SingleTableDataNodeLoader.load(" + schema + ") -> size=" + loaded.size() + " " + loaded);
        }

        // Also: what does SchemaMetaDataLoader see when the name is a DATA SOURCE name instead of a schema name?
        Object loader = Class.forName("org.apache.shardingsphere.database.connector.core.metadata.data.loader.type.SchemaMetaDataLoader")
                .getConstructor(Class.forName("org.apache.shardingsphere.database.connector.core.type.DatabaseType"))
                .newInstance(type);
        for (String name : new String[]{"probe", "ds0", "logic_db"}) {
            Object got = loader.getClass()
                    .getMethod("loadSchemaTableNames", String.class, DataSource.class, Collection.class)
                    .invoke(loader, name, ds, new ArrayList<>());
            System.out.println("SchemaMetaDataLoader.loadSchemaTableNames(" + name + ") -> " + got);
        }

        // And a direct SingleRule with a real datasource map:
        Map<String, DataSource> ruleDs = new LinkedHashMap<>();
        ruleDs.put("ds0", ds);
        Class<?> confCls = Class.forName("org.apache.shardingsphere.single.config.SingleRuleConfiguration");
        Object conf = confCls.getConstructor().newInstance();
        Object rule = Class.forName("org.apache.shardingsphere.single.rule.SingleRule")
                .getConstructor(confCls, String.class,
                        Class.forName("org.apache.shardingsphere.database.connector.core.type.DatabaseType"),
                        Map.class, Collection.class)
                .newInstance(conf, "probe", type, ruleDs, new ArrayList<>());
        Object nodes = rule.getClass().getMethod("getSingleTableDataNodes").invoke(rule);
        System.out.println("direct SingleRule('probe') singleTableDataNodes size = " + ((Map<?, ?>) nodes).size());
        int i = 0;
        for (Map.Entry<?, ?> e : ((Map<?, ?>) nodes).entrySet()) {
            if (i++ >= 4) break;
            System.out.println("   " + e.getKey() + " -> " + e.getValue());
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
