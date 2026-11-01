package com.betterschool.integration.errand;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.trigger.job.ErrandTimeoutScanJob;
import com.betterschool.trigger.listener.ErrandPaidConsumer;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 支付消息消费者 + 24h 待审超时提醒（票 04）集成测试：
 * 消费 bs-errand-paid → 作者收到一条"进入人工审核"通知（重复消费 dedup_key 幂等只一条）；
 * 待审超 24h 扫描 → 管理员/审核员各收一条提醒（重复扫描不重复提醒），提醒不改订单状态；
 * 未超时/非待审订单不提醒。
 */
class ErrandPaidNotifyIntegrationTest extends CommunityApiTestBase {

    @Autowired
    private ErrandPaidConsumer errandPaidConsumer;
    @Autowired
    private ErrandTimeoutScanJob errandTimeoutScanJob;

    // ---------- 支付消费者：作者"进入人工审核"通知 ----------

    @Test
    void 消费支付消息作者收到进入待审通知且重复消费只一条() {
        Session author = registerAndLogin("13800004001", "发单作者");
        String orderId = createPaidOrder(author);

        List<String> payloads = jdbcTemplate.queryForList(
                "SELECT payload FROM outbox_message WHERE destination = ? AND state = 0",
                String.class, Constants.ERRAND_PAID_TOPIC);
        assertThat(payloads).as("支付应落 bs-errand-paid outbox").hasSize(1);
        assertThat(readTree(payloads.get(0)).path("orderId").asText()).as("载荷 orderId 为 UUID 字符串（uuid-biz-id 票 05）").isEqualTo(orderId);

        // 消费两次（broker 重投场景）：dedup_key 幂等，作者只一条通知
        errandPaidConsumer.onMessage(payloads.get(0));
        errandPaidConsumer.onMessage(payloads.get(0));

        Integer noticeRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE user_id = ? AND type = 'ORDER_EVENT' "
                        + "AND content LIKE '%人工审核%' AND ref_id = ?",
                Integer.class, author.userId(), orderId);
        assertThat(noticeRows).as("重复消费只落一条进入待审通知").isEqualTo(1);
    }

    @Test
    void 消费不存在的订单id静默忽略() {
        errandPaidConsumer.onMessage("{\"orderId\":\"" + java.util.UUID.randomUUID() + "\"}");
        Integer noticeRows = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification", Integer.class);
        assertThat(noticeRows).as("订单不存在不应落任何通知").isZero();
    }

    // ---------- 24h 待审超时提醒 ----------

    @Test
    void 待审超24h扫描提醒管理员与审核员且重复扫描不重复提醒() {
        registerAndLogin("13800004002", "发单作者");
        Session admin = registerAndLogin("13800004003", "管理员");
        jdbcTemplate.update("UPDATE bs_user SET role = 'ADMIN' WHERE user_id = ?", admin.userId());
        Session auditor = registerAndLogin("13800004004", "审核员");
        jdbcTemplate.update("UPDATE bs_user SET role = 'AUDITOR' WHERE user_id = ?", auditor.userId());
        Session normalUser = registerAndLogin("13800004005", "普通用户");

        String overdueOrderId = insertOrderWithAge("超时待审单", "PENDING_REVIEW", 25);
        String freshOrderId = insertOrderWithAge("刚待审单", "PENDING_REVIEW", 1);

        errandTimeoutScanJob.scan(null);
        errandTimeoutScanJob.scan(null);

        assertThat(countReviewReminders(admin.userId(), overdueOrderId))
                .as("管理员应收提醒且重复扫描只一条").isEqualTo(1);
        assertThat(countReviewReminders(auditor.userId(), overdueOrderId))
                .as("审核员应收提醒且重复扫描只一条").isEqualTo(1);
        assertThat(countReviewReminders(normalUser.userId(), overdueOrderId))
                .as("普通用户不应收到提醒").isZero();
        assertThat(countReviewReminders(admin.userId(), freshOrderId))
                .as("未超 24h 的待审单不提醒").isZero();

        String status = jdbcTemplate.queryForObject(
                "SELECT state FROM errand_order WHERE errand_order_id = ?", String.class, overdueOrderId);
        assertThat(status).as("提醒不改变订单状态，绝不自动裁决").isEqualTo("PENDING_REVIEW");
    }

    @Test
    void 非待审状态的过期订单不提醒() {
        registerAndLogin("13800004006", "发单作者");
        Session admin = registerAndLogin("13800004007", "管理员");
        jdbcTemplate.update("UPDATE bs_user SET role = 'ADMIN' WHERE user_id = ?", admin.userId());

        insertOrderWithAge("已发布旧单", "PUBLISHED", 25);

        errandTimeoutScanJob.scan(null);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM notification", Integer.class))
                .as("PUBLISHED 订单不受 24h 待审提醒影响（走订单超时逻辑）").isZero();
    }

    // ---------- 工具 ----------

    private String createPaidOrder(Session author) {
        JsonNode createResponse = postJson("/api/errand/orders", Map.ofEntries(
                Map.entry("type", "EXPRESS_PICKUP"),
                Map.entry("description", "帮忙取个快递"),
                Map.entry("pickupLocation", "菜鸟驿站"),
                Map.entry("deliveryLocation", "6号楼楼下"),
                Map.entry("reward", 10),
                Map.entry("timeLimit", "HOURS_2")), author);
        assertSuccess(createResponse, "发布跑腿");
        String orderId = createResponse.path("data").asText();
        assertUuidId(orderId);
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/pay", null, author), "支付");
        return orderId;
    }

    /** 直插订单（create_time/update_time 回拨 hoursAgo 小时）；业务 ID 为 UUID（uuid-biz-id 票 05）。
     *  时间回拨在 Java 侧算好当普通 ? 传：`INTERVAL ?` 占位符会被 ShardingSphere 的 SQL 解析弄丢
     *  （内嵌化票 02 起 datasource 坐 SS 驱动），生产代码无此形态，夹具避让即可 */
    private String insertOrderWithAge(String description, String status, int hoursAgo) {
        String orderId = java.util.UUID.randomUUID().toString();
        LocalDateTime aged = LocalDateTime.now().minusHours(hoursAgo).truncatedTo(ChronoUnit.SECONDS);
        jdbcTemplate.update("INSERT INTO errand_order (errand_order_id, publisher_id, type, description, pickup_location, "
                        + "delivery_location, reward, time_limit_hours, state, create_time, update_time) "
                        + "VALUES (?, 1, 'EXPRESS_PICKUP', ?, '驿站', '6号楼', 10.00, 2, ?, ?, ?)",
                orderId, description, status, aged, aged);
        return orderId;
    }

    private int countReviewReminders(String userId, String orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE user_id = ? AND type = 'ORDER_EVENT' "
                        + "AND content LIKE '%24 小时%' AND ref_id = ?",
                Integer.class, userId, orderId);
        return count == null ? 0 : count;
    }
}
