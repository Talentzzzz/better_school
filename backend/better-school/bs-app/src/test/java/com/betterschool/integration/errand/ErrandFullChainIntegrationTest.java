package com.betterschool.integration.errand;

import com.betterschool.infrastructure.mq.OutboxMessageSender;
import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.trigger.listener.ErrandPaidConsumer;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全链路编排汇总（票 07）：
 * 支付 → 消费者通知 → 对外可见 的完整编排；
 * outbox 定时扫描兜底重发未发送消息（测试环境无 broker：重试计数可观察）。
 */
class ErrandFullChainIntegrationTest extends CommunityApiTestBase {

    @Autowired
    private ErrandPaidConsumer errandPaidConsumer;
    @Autowired
    private OutboxMessageSender outboxMessageSender;

    // ---------- 完整编排：支付 → 消费者通知 → 可见 ----------

    @Test
    void 支付链路编排_通知可见() {
        Session author = registerAndLogin("13800007002", "发单作者");
        String orderId = createPaidOrder(author, List.of("http://minio.test/bs-files/uploads/a.png"));

        // 1. 支付落 outbox → 消费者直接驱动 → 作者收到"支付成功"通知
        String payload = pendingPayloadOf(orderId);
        errandPaidConsumer.onMessage(payload);
        assertThat(notificationsOf(author.userId(), "ORDER_EVENT", "支付成功"))
                .as("支付后作者应收到支付成功通知").hasSize(1);

        // 2. 支付即 PUBLISHED + 对外可见
        assertThat(statusOf(orderId)).isEqualTo("PUBLISHED");
        assertThat(listPublicOrderIds()).as("支付后应对外可见").contains(orderId);

        JsonNode detail = getJson("/api/errand/orders/" + orderId, null);
        assertSuccess(detail, "访客看详情");
        assertThat(detail.path("data").path("images").get(0).asText())
                .as("详情应带出配图").isEqualTo("http://minio.test/bs-files/uploads/a.png");

        // 3. 状态流转事件流水（票 05 验收项 2）：创建 → 支付，顺序即 id 递增
        assertThat(eventTypesOf(orderId)).as("事件流水应完整记录这一次编排的每次流转")
                .containsExactly("CREATED", "PAID");
        assertThat(eventFromStateOf(orderId, "CREATED")).as("创建事件无前态").isNull();
        assertThat(eventFromStateOf(orderId, "PAID")).as("支付事件前态取业务行真值").isEqualTo("PENDING_PAYMENT");
        assertThat(eventRowOf(orderId, "PAID"))
                .as("支付事件：PENDING_PAYMENT→PUBLISHED、操作方 PUBLISHER")
                .containsEntry("to_state", "PUBLISHED")
                .containsEntry("actor_role", "PUBLISHER")
                .containsEntry("actor_id", author.userId());
    }

    @Test
    void 取消链路编排_解冻退款() {
        Session author = registerAndLogin("13800007003", "发单作者");
        String orderId = createPaidOrder(author, null);

        // 已支付（PUBLISHED，冻结中）→ 作者取消 → 解冻退款
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/cancel", null, author), "取消订单");

        assertThat(statusOf(orderId)).isEqualTo("CANCELLED");
        assertThat(listPublicOrderIds()).as("已取消订单不对外可见").doesNotContain(orderId);
        assertThat(countWalletTransactions(orderId, "UNFROZEN")).as("取消应解冻退款").isEqualTo(1);
        BigDecimal balance = jdbcTemplate.queryForObject(
                "SELECT balance FROM virtual_wallet WHERE user_id = ?", BigDecimal.class, author.userId());
        assertThat(balance).as("冻结金额应全额退回").isEqualByComparingTo("1000.00");
    }

    // ---------- outbox 定时扫描兜底 ----------

    @Test
    void outbox定时扫描兜底重发未发送消息() {
        // 直插一条 PENDING 消息模拟"事务已提交但即时发送未完成"的遗留消息
        String msgId = UUID.randomUUID().toString();
        // uuid-biz-id 票 05：orderId 载荷为 UUID 字符串
        String payload = "{\"orderId\":\"" + UUID.randomUUID() + "\"}";
        jdbcTemplate.update("INSERT INTO outbox_message (msg_id, destination, payload, state, retry_count, create_time, update_time) "
                + "VALUES (?, ?, ?, 0, 0, NOW(), NOW())", msgId, Constants.ERRAND_PAID_TOPIC, payload);

        outboxMessageSender.scanPending(null);

        // 测试环境无 broker：发送必失败 → 重试计数 +1、保持 PENDING 等下次扫描（消费端按 msgId 幂等兜底）
        awaitTrue("扫描应拾起 PENDING 消息并重试（无 broker 表现为 retry_count 增加）",
                () -> retryCountOf(msgId) >= 1);
        assertThat(statusOfOutbox(msgId)).as("重试未耗尽应保持待发送").isEqualTo(0);
    }

    // ---------- 工具 ----------

    private Map<String, Object> errandBody(String description, List<String> images) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "EXPRESS_PICKUP");
        body.put("description", description);
        body.put("pickupLocation", "菜鸟驿站");
        body.put("deliveryLocation", "6号楼楼下");
        body.put("reward", 10);
        body.put("timeLimit", "HOURS_2");
        if (images != null) {
            body.put("images", images);
        }
        return body;
    }

    private String createPaidOrder(Session author, List<String> images) {
        JsonNode createResponse = postJson("/api/errand/orders", errandBody("帮忙取个快递", images), author);
        assertSuccess(createResponse, "发布跑腿");
        String orderId = createResponse.path("data").asText();
        assertUuidId(orderId);
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/pay", null, author), "支付");
        return orderId;
    }

    private String pendingPayloadOf(String orderId) {
        List<String> payloads = jdbcTemplate.queryForList(
                "SELECT payload FROM outbox_message WHERE destination = ? AND state = 0", String.class,
                Constants.ERRAND_PAID_TOPIC);
        return payloads.stream()
                .filter(p -> readTree(p).path("orderId").asText().equals(orderId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("订单 " + orderId + " 的支付 outbox 消息不存在"));
    }

    private List<String> notificationsOf(String userId, String type, String keyword) {
        return jdbcTemplate.queryForList(
                "SELECT content FROM notification WHERE user_id = ? AND type = ? AND content LIKE ?",
                String.class, userId, type, "%" + keyword + "%");
    }

    private List<String> listPublicOrderIds() {
        JsonNode response = getJson("/api/errand/orders?page=1&pageSize=10", null);
        assertSuccess(response, "公开订单列表");
        return StreamSupport.stream(response.path("data").path("list").spliterator(), false)
                .map(node -> node.path("orderId").asText())
                .toList();
    }

    private int orderCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM errand_order", Integer.class);
        return count == null ? 0 : count;
    }

    private int countWalletTransactions(String orderId, String type) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_detail WHERE order_id = ? AND type = ?",
                Integer.class, orderId, type);
        return count == null ? 0 : count;
    }

    private String statusOf(String orderId) {
        return jdbcTemplate.queryForObject("SELECT state FROM errand_order WHERE errand_order_id = ?", String.class, orderId);
    }

    /** 该订单的事件流水类型序（id 递增即流转顺序） */
    private List<String> eventTypesOf(String orderId) {
        return jdbcTemplate.queryForList(
                "SELECT event_type FROM errand_order_event WHERE order_id = ? ORDER BY id", String.class, orderId);
    }

    /** 某类事件的行（每类事件在一条链路里至多一条；取首条足够） */
    private Map<String, Object> eventRowOf(String orderId, String eventType) {
        return jdbcTemplate.queryForMap(
                "SELECT from_state, to_state, actor_id, actor_role, reason_type, reason "
                        + "FROM errand_order_event WHERE order_id = ? AND event_type = ? ORDER BY id LIMIT 1",
                orderId, eventType);
    }

    /** 事件前态；事件行不存在或该列为 NULL 都返回 null（创建事件即 NULL 前态） */
    private String eventFromStateOf(String orderId, String eventType) {
        List<String> states = jdbcTemplate.queryForList(
                "SELECT from_state FROM errand_order_event WHERE order_id = ? AND event_type = ? ORDER BY id LIMIT 1",
                String.class, orderId, eventType);
        return states.isEmpty() ? null : states.get(0);
    }

    private String eventReasonOf(String orderId, String eventType) {
        List<String> reasons = jdbcTemplate.queryForList(
                "SELECT reason FROM errand_order_event WHERE order_id = ? AND event_type = ? ORDER BY id LIMIT 1",
                String.class, orderId, eventType);
        return reasons.isEmpty() ? null : reasons.get(0);
    }

    private int retryCountOf(String msgId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT retry_count FROM outbox_message WHERE msg_id = ?", Integer.class, msgId);
        return count == null ? -1 : count;
    }

    private int statusOfOutbox(String msgId) {
        Integer state = jdbcTemplate.queryForObject(
                "SELECT state FROM outbox_message WHERE msg_id = ?", Integer.class, msgId);
        return state == null ? -1 : state;
    }

    /** 标记线程异步落库，轮询至条件成立或超时（5s） */
    private void awaitTrue(String what, Supplier<Boolean> condition) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline && !Boolean.TRUE.equals(condition.get())) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        assertThat(condition.get()).as(what).isTrue();
    }

}
