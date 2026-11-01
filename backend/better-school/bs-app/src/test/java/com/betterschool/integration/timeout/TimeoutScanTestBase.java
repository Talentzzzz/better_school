package com.betterschool.integration.timeout;

import com.betterschool.domain.timeout.adapter.repository.ITimeoutRepository;
import com.betterschool.domain.timeout.model.entity.TimeoutTaskEntity;
import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.integration.support.TimeoutTaskCallbackProbe;
import com.betterschool.infrastructure.sharding.TimeoutTaskShardSlots;
import com.betterschool.types.timeout.TimeoutConstants;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 超时扫描类用例的公共夹具（票 04）：注册到期任务、裸 MySQL 读物理分表的行、按槽位造 bizId。
 * <p>
 * 物理断言必须**绕过 ShardingSphere**（物理分表不在逻辑元数据里，经 SS 连接点名 timeout_task_NN 是
 * TableNotFoundException，票 01/02 已定位）——裸连地址复用 {@code timeout-it.raw-mysql-url}。
 */
abstract class TimeoutScanTestBase extends CommunityApiTestBase {

    static final String TASK_TYPE = "PRODUCT_ORDER_PAY_TIMEOUT";
    /** 槽位总数：认领条件的上界，用例按它造满 16 张表 */
    static final int TOTAL_SLOTS = TimeoutConstants.TOTAL_SLOTS;

    @Autowired
    protected ITimeoutRepository timeoutRepository;

    /** 回调 seam 探针：@Primary 顶掉生产的进程内分发器，默认原样委派（见探针类注释） */
    @Autowired
    protected TimeoutTaskCallbackProbe callbackProbe;

    @Value("${timeout-it.raw-mysql-url}")
    private String rawMysqlUrl;
    @Value("${spring.datasource.username}")
    private String rawDbUser;
    @Value("${spring.datasource.password}")
    private String rawDbPassword;

    /**
     * 探针是 {@code @Primary} 单例、Spring context 又是跨测试类缓存的：桩住的模式（按失败回报 / 并发闩）
     * 不会随用例结束消失，会漏给**下一个共用 context 的测试类**（类执行顺序由 surefire 定，不由用例定）。
     * 所以进出都摆回默认（委派真分发器）并清空观测——{@code @AfterEach} 是真正的兜底：
     * 用例断言失败时后面的代码不跑，只有它一定跑得到。
     */
    @BeforeEach
    @AfterEach
    void resetCallbackProbe() {
        callbackProbe.reset();
    }

    // ---------- 夹具 ----------

    /** 注册一行「已到点」的任务（actionTime = {@code minutesFromNow} 分钟前/后，next_fire_time 同值） */
    protected void registerDue(String taskType, String bizId, int minutesFromNow) {
        LocalDateTime actionTime = LocalDateTime.now().plusMinutes(minutesFromNow).truncatedTo(ChronoUnit.SECONDS);
        assertThat(timeoutRepository.register(
                TimeoutTaskEntity.newWaiting(taskType, bizId, actionTime, LocalDateTime.now())))
                .as("注册超时任务 %s/%s 失败", taskType, bizId)
                .isTrue();
    }

    protected void registerDue(String bizId, int minutesFromNow) {
        registerDue(TASK_TYPE, bizId, minutesFromNow);
    }

    /** 某一行的槽位（物理分表的编号就是它） */
    protected int slotOf(String bizId) {
        return TimeoutTaskShardSlots.slotOf(bizId);
    }

    /**
     * 造一个落在指定槽位上的 bizId：槽位是 {@code floorMod(hash(bizId), 16)}，所以要试几个后缀。
     * 认领条件的用例靠它精确摆行——摆错槽位会让断言验的是别的东西。
     */
    protected String bizIdForSlot(String prefix, int slot) {
        for (int i = 0; i < 10_000; i++) {
            String bizId = prefix + i;
            if (slotOf(bizId) == slot) {
                return bizId;
            }
        }
        throw new IllegalStateException("找不到落在槽位 " + slot + " 的 bizId：" + prefix);
    }

    /** 造 {@code count} 个都落在指定槽位上的 bizId（槽位分布均匀，扫几轮前缀就够） */
    protected List<String> bizIdsForSlot(String prefix, int slot, int count) {
        List<String> bizIds = new ArrayList<>(count);
        for (int i = 0; bizIds.size() < count; i++) {
            String bizId = prefix + i;
            if (slotOf(bizId) == slot) {
                bizIds.add(bizId);
            }
        }
        return bizIds;
    }

    // ---------- 物理分表直查（绕过 SS） ----------

    /** 裸 MySQL 读这一行的物理列（列名小写；不存在直接断言失败——用例的前置条件该在这里钉死） */
    protected Map<String, Object> physicalRow(String taskType, String bizId) {
        String table = TimeoutTaskShardSlots.tableNameOf(slotOf(bizId));
        try (Connection connection = DriverManager.getConnection(rawMysqlUrl, rawDbUser, rawDbPassword);
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM " + table + " WHERE task_type = ? AND biz_id = ?")) {
            statement.setString(1, taskType);
            statement.setString(2, bizId);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next())
                        .as("物理表 %s 里应有 %s/%s 这一行", table, taskType, bizId)
                        .isTrue();
                ResultSetMetaData meta = resultSet.getMetaData();
                Map<String, Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.put(meta.getColumnLabel(i).toLowerCase(Locale.ROOT), resultSet.getObject(i));
                }
                return row;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("裸连点查物理分表失败：" + table, e);
        }
    }

    protected Map<String, Object> physicalRow(String bizId) {
        return physicalRow(TASK_TYPE, bizId);
    }

    /** 裸 MySQL 改物理列（只给「摆 retry_count / 直接置态」这类前置条件用，别拿它当被测行为） */
    protected int physicalUpdate(String sql, Object... args) {
        try (Connection connection = DriverManager.getConnection(rawMysqlUrl, rawDbUser, rawDbPassword);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("裸连改物理分表失败：" + sql, e);
        }
    }

    /** 摆一行的 retry_count（已失败次数）：等它自然失败 16 次要 10 小时，死信用例直接摆第 15 次 */
    protected void setRetryCount(String taskType, String bizId, int retryCount) {
        String table = TimeoutTaskShardSlots.tableNameOf(slotOf(bizId));
        int updated = physicalUpdate("UPDATE " + table + " SET retry_count = ? WHERE task_type = ? AND biz_id = ?",
                retryCount, taskType, bizId);
        assertThat(updated).as("摆 retry_count 失败：%s/%s", taskType, bizId).isEqualTo(1);
    }

    /**
     * 物理列里的时刻 → {@link LocalDateTime}。裸连（{@link DriverManager}，非 ShardingSphere）由 MySQL
     * Connector/J 直接返回 {@code LocalDateTime}；这里兼容 {@link Timestamp} / {@link OffsetDateTime}，
     * 免得换个驱动版本就把断言炸在强转上。
     */
    protected static LocalDateTime dateTime(Object columnValue) {
        if (columnValue instanceof LocalDateTime localDateTime) {
            return localDateTime;
        }
        if (columnValue instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (columnValue instanceof OffsetDateTime offsetDateTime) {
            return offsetDateTime.toLocalDateTime();
        }
        throw new IllegalArgumentException("不是可识别的时刻列：" + columnValue);
    }
}
