package com.betterschool.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ShardingSphere 路由自检（启动期，票 01 的两条上线阻断建议）：
 * 主 datasource 已换成 {@code jdbc:shardingsphere:}（超时中心内嵌化票 02），最大的两个坑都在
 * sharding.yaml 配置漂移上、且都是<b>启动成功、第一条业务 SQL 才炸</b>的静默形态：
 * <ol>
 *   <li>缺 {@code !SINGLE tables: ["*.*.*"]} → 逻辑库只暴露 timeout_task 一张表，
 *       30 张业务表全部 TableNotFoundException（整库锁死）；</li>
 *   <li>{@code !SHARDING} 规则没生效 / 物理分表被当成单表 → 逻辑库暴露 timeout_task_NN 物理名，
 *       业务按逻辑表写的 SQL 与 hint 路由对不上。</li>
 * </ol>
 * 这里在启动期把两件事都验掉：逻辑库必须能看到 timeout_task 与几张代表性业务表，
 * 且绝不能看到 timeout_task_NN 物理名。验不过直接启动失败——配置错就该死在启动期。
 */
@Slf4j
@Configuration
public class ShardingSelfCheck {

    /** 代表性业务表：覆盖社区/跑腿/商品/审核四条主链路（有任意一张不可见即整库锁死形态） */
    private static final List<String> MUST_BE_VISIBLE =
            List.of("timeout_task", "bs_user", "post", "errand_order", "product_order", "review_task");

    @Bean
    public ApplicationRunner shardingRouteSelfCheck(DataSource dataSource) {
        return (ApplicationArguments args) -> {
            Set<String> visibleTables = new HashSet<>();
            // 必须走 JDBC DatabaseMetaData（SS 的逻辑元数据）：SHOW TABLES 会被驱动原样透传给 MySQL，
            // 返回的是物理表名（46 张 = 30 业务 + 16 分表），逻辑表 timeout_task 反而看不到——用它断言会误判
            try (Connection connection = dataSource.getConnection();
                 ResultSet resultSet = connection.getMetaData().getTables(null, null, "%", new String[]{"TABLE"})) {
                while (resultSet.next()) {
                    visibleTables.add(resultSet.getString("TABLE_NAME"));
                }
            }
            List<String> missing = MUST_BE_VISIBLE.stream()
                    .filter(table -> !visibleTables.contains(table))
                    .toList();
            if (!missing.isEmpty()) {
                throw new IllegalStateException("ShardingSphere 逻辑库缺表 " + missing
                        + "（可见表 " + visibleTables.size() + " 张）——大概率是 sharding.yaml 缺 "
                        + "!SINGLE tables: [\"*.*.*\"]：缺它时未配置分片的业务表全部不可见（票 01）");
            }
            List<String> physical = visibleTables.stream()
                    .filter(name -> name.matches("timeout_task_\\d{2}"))
                    .toList();
            if (!physical.isEmpty()) {
                throw new IllegalStateException("ShardingSphere 逻辑库暴露了物理分表 " + physical
                        + "——!SHARDING 规则没生效，或物理表被 !SINGLE 吃成单表（票 01）");
            }
            log.info("ShardingSphere 路由自检通过：逻辑库可见 {} 张表，timeout_task 与业务表齐全，无物理分表泄漏",
                    visibleTables.size());
        };
    }
}
