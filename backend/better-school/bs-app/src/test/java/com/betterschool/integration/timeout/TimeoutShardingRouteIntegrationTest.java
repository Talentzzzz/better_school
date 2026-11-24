package com.betterschool.integration.timeout;

import com.betterschool.infrastructure.timeout.TimeoutTaskEntity;
import com.betterschool.infrastructure.timeout.TimeoutTaskShardSlots;
import com.betterschool.infrastructure.timeout.TimeoutTaskStore;
import com.betterschool.integration.support.PostApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 超时中心内嵌化票 02 验收：按 (task_type, biz_id) 注册的超时行，实际落库的物理分表
 * 必须就是槽位公式（{@link TimeoutTaskShardSlots#slotOf}，输入 = biz_id）算出的那张——
 * 「写入按自然键、扫描按槽位」两个方向共用同一个编号空间，这条断了扫描就漏行。
 * <p>
 * 物理断言<b>必须绕过 ShardingSphere</b>：物理分表不在逻辑元数据里，经 SS 连接点名
 * timeout_task_NN 是 TableNotFoundException（票 01 定位过的探针坑）。所以用裸 MySQL 连接
 * 直查 better_school_test 的 16 张分表：槽位那张恰 1 行、其余 15 张全 0。
 * 裸连地址从 application-test.yml 的 {@code timeout-it.raw-mysql-url} 注入（与 sharding-test.yaml
 * 的 jdbcUrl 同源；测试代码不另写地址，见 backend/CLAUDE.md §10）。
 */
class TimeoutShardingRouteIntegrationTest extends PostApiTestBase {

    @Value("${timeout-it.raw-mysql-url}")
    private String rawMysqlUrl;

    @Value("${spring.datasource.username}")
    private String rawUser;

    @Value("${spring.datasource.password}")
    private String rawPassword;

    @Autowired
    private TimeoutTaskStore timeoutTaskStore;

    @Test
    void slotComputedFromKeyMatchesPhysicalTable() throws Exception {
        String taskType = "IT_ROUTE_TIMEOUT";
        String bizId = "it-route-" + UUID.randomUUID();
        int slot = TimeoutTaskShardSlots.slotOf(bizId);
        String expectedTable = TimeoutTaskShardSlots.tableNameOf(slot);

        try {
            // 注册（事务内直插的仓储原语，票 03 接管注册语义）：WAITING、next_fire_time = action_time
            //（时刻一律数据库时钟，Java 只传时长）
            TimeoutTaskEntity taskEntity = TimeoutTaskEntity.newWaiting(taskType, bizId, 3600L);
            assertThat(timeoutTaskStore.insertIfAbsent(taskEntity))
                    .as("新键插入应成功").isTrue();
            assertThat(timeoutTaskStore.findByKey(taskType, bizId))
                    .as("按键读回（hint 同槽位）应命中").isNotNull();

            // 验收断言：槽位 = 实际落库的物理表——槽位那张恰 1 行，其余 15 张全 0
            for (int g = 0; g < 16; g++) {
                long rows = countOnPhysicalTable(g, taskType, bizId);
                if (g == slot) {
                    assertThat(rows).as("行应落在槽位 %d 的物理表 %s", g, expectedTable).isEqualTo(1);
                } else {
                    assertThat(rows).as("非槽位分表 %02d 不应出现该行", g).isZero();
                }
            }

            // 同键重注册 = 幂等（uk_type_biz 撞键翻返回值，不抛异常）
            assertThat(timeoutTaskStore.insertIfAbsent(taskEntity))
                    .as("同键二次插入应返回 false（已存在）").isFalse();

            // 业务取消（软置 CANCELED，只打未终态行）→ 终态复活（改期 + 计数清零）
            assertThat(timeoutTaskStore.cancelActive(taskType, bizId)).isTrue();
            assertThat(timeoutTaskStore.findByKey(taskType, bizId).getStatus().name()).isEqualTo("CANCELED");
            assertThat(timeoutTaskStore.cancelActive(taskType, bizId))
                    .as("终态行再取消应无副作用").isFalse();

            // 复活传时长 2h：action_time/next_fire_time 由 SQL 侧 TIMESTAMPADD 按数据库时钟刷新
            //（DB 时钟 ≈ 本机时钟，±5s 容差给两条时钟读数留余量）
            TimeoutTaskEntity reviveEntity = TimeoutTaskEntity.newWaiting(taskType, bizId, 7200L);
            assertThat(timeoutTaskStore.reviveTerminal(reviveEntity))
                    .as("终态行应被复活").isTrue();
            TimeoutTaskEntity afterReviveEntity = timeoutTaskStore.findByKey(taskType, bizId);
            assertThat(afterReviveEntity.getStatus().name()).isEqualTo("WAITING");
            assertThat(afterReviveEntity.getNextFireTime())
                    .as("复活刷新 next_fire_time = 落库时刻 + 2h")
                    .isBetween(LocalDateTime.now().plusHours(2).minusSeconds(5),
                            LocalDateTime.now().plusHours(2).plusSeconds(5));
            assertThat(afterReviveEntity.getRetryCount()).isZero();
        } finally {
            // 不留痕：物理表直删（测试行带唯一 bizId，不影响他用例）
            try (Connection connection = DriverManager.getConnection(rawMysqlUrl, rawUser, rawPassword)) {
                for (int g = 0; g < 16; g++) {
                    try (PreparedStatement statement = connection.prepareStatement(
                            "DELETE FROM timeout_task_" + String.format("%02d", g)
                                    + " WHERE task_type = ? AND biz_id = ?")) {
                        statement.setString(1, taskType);
                        statement.setString(2, bizId);
                        statement.executeUpdate();
                    }
                }
            }
        }
    }

    /** 裸 MySQL 连接点查物理分表（绕过 SS；物理名经 SS 连接查不到，见类注释） */
    private long countOnPhysicalTable(int slot, String taskType, String bizId) throws Exception {
        try (Connection connection = DriverManager.getConnection(rawMysqlUrl, rawUser, rawPassword);
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM timeout_task_"
                     + String.format("%02d", slot) + " WHERE task_type = ? AND biz_id = ?")) {
            statement.setString(1, taskType);
            statement.setString(2, bizId);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getLong(1);
            }
        }
    }
}
