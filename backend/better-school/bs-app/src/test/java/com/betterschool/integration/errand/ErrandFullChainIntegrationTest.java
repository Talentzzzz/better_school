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
 * 初筛命中改文案重发成功；支付 → 消费者通知 → 裁决 → 对外可见/退款 的完整编排（通过/拒绝双链）；
 * outbox 定时扫描兜底重发未发送消息（测试环境无 broker：重试计数可观察）。
 */
class ErrandFullChainIntegrationTest extends CommunityApiTestBase {

    @Autowired
    private ErrandPaidConsumer errandPaidConsumer;
    @Autowired
    private OutboxMessageSender outboxMessageSender;

    // ---------- 初筛命中 → 改文案重发 ----------

    @Test
    void 初筛命中改文案重发成功() {
        Session user = registerAndLogin("13800007001", "发单用户");

        JsonNode rejected = postJson("/api/errand/orders", errandBody("帮我代考一下", null), user);
        assertThat(rejected.path("code").asText()).as("初筛命中应失败：%s", rejected).isEqualTo("500");
        assertThat(orderCount()).as("命中订单不应落库").isZero();

        JsonNode resent = postJson("/api/errand/orders", errandBody("帮我取个快递", null), user);
        assertSuccess(resent, "改文案重发");
        long orderId = resent.path("data").asLong();
        assertThat(statusOf(orderId)).as("改文案后应正常创建待支付").isEqualTo("PENDING_PAYMENT");
    }

    // ---------- 完整编排：支付 → 消费者通知 → 裁决 ----------

    @Test
    void 通过链路编排_支付通知裁决可见() {
        Session author = registerAndLogin("13800007002", "发单作者");
        long orderId = createPaidOrder(author, List.of("http://minio.test/bs-files/uploads/a.png"));
        Session admin = adminSession();

        // 1. 支付落 outbox → 消费者直接驱动 → 作者收到"进入人工审核"通知
        String payload = pendingPayloadOf(orderId);
        errandPaidConsumer.onMessage(payload);
        assertThat(notificationsOf(author.userId(), "ORDER_EVENT", "人工审核"))
                .as("支付后作者应收到进入人工审核通知").hasSize(1);

        // 2. 管理员裁决通过 → PUBLISHED + 对外可见 + 作者通知
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "ERRAND", "targetId", orderId, "approve", true), admin), "裁决通过");

        assertThat(statusOf(orderId)).isEqualTo("PUBLISHED");
        assertThat(listPublicOrderIds()).as("通过后应对外可见").contains(orderId);
        assertThat(notificationsOf(author.userId(), "AUDIT_RESULT", "通过审核"))
                .as("作者应收到通过通知").hasSize(1);

        JsonNode detail = getJson("/api/errand/orders/" + orderId, null);
        assertSuccess(detail, "访客看详情");
        assertThat(detail.path("data").path("images").get(0).asText())
                .as("详情应带出配图").isEqualTo("http://minio.test/bs-files/uploads/a.png");

        // 3. 状态流转事件流水（票 05 验收项 2）：创建 → 支付 → 裁决通过，顺序即 id 递增
        assertThat(eventTypesOf(orderId)).as("事件流水应完整记录这一次编排的每次流转")
                .containsExactly("CREATED", "PAID", "REVIEW_APPROVED");
        assertThat(eventFromStateOf(orderId, "CREATED")).as("创建事件无前态").isNull();
        assertThat(eventFromStateOf(orderId, "PAID")).as("支付事件前态取业务行真值").isEqualTo("PENDING_PAYMENT");
        assertThat(eventRowOf(orderId, "PAID"))
                .as("支付事件：PENDING_PAYMENT→PENDING_REVIEW、操作方 PUBLISHER")
                .containsEntry("to_state", "PENDING_REVIEW")
                .containsEntry("actor_role", "PUBLISHER")
                .containsEntry("actor_id", author.userId());
        assertThat(eventRowOf(orderId, "REVIEW_APPROVED"))
                .as("裁决事件：PENDING_REVIEW→PUBLISHED、操作方 ADMIN")
                .containsEntry("from_state", "PENDING_REVIEW")
                .containsEntry("to_state", "PUBLISHED")
                .containsEntry("actor_role", "ADMIN");
    }

    @Test
    void 拒绝链路编排_支付通知裁决退款理由() {
        Session author = registerAndLogin("13800007003", "发单作者");
        long orderId = createPaidOrder(author, null);
        Session admin = adminSession();

        errandPaidConsumer.onMessage(pendingPayloadOf(orderId));
        String reason = "配图模糊无法辨认需求";
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "ERRAND", "targetId", orderId, "approve", false, "reasonCode", "OTHER", "supplement", reason), admin),
                "裁决拒绝");

        assertThat(statusOf(orderId)).isEqualTo("REJECTED");
        assertThat(listPublicOrderIds()).as("被拒订单不对外可见").doesNotContain(orderId);

        assertThat(countWalletTransactions(orderId, "UNFROZEN")).as("拒绝应解冻退款").isEqualTo(1);
        BigDecimal balance = jdbcTemplate.queryForObject(
                "SELECT balance FROM virtual_wallet WHERE user_id = ?", BigDecimal.class, author.userId());
        assertThat(balance).as("冻结金额应全额退回").isEqualByComparingTo("1000.00");

        List<String> notices = notificationsOf(author.userId(), "AUDIT_RESULT", reason);
        assertThat(notices).as("拒绝通知应含理由").hasSize(1);
        JsonNode detail = getJson("/api/errand/orders/" + orderId, author);
        assertThat(detail.path("data").path("rejectReason").asText()).as("作者详情应带出理由").isEqualTo("其他：" + reason);

        // 状态流转事件流水（票 05 验收项 2）：拒绝事件落 REJECTED，且拒绝理由进事件的 reason 列
        assertThat(eventTypesOf(orderId)).as("创建 → 支付 → 审核驳回").containsExactly("CREATED", "PAID", "REVIEW_REJECTED");
        assertThat(eventRowOf(orderId, "REVIEW_REJECTED"))
                .as("驳回事件：PENDING_REVIEW→REJECTED、操作方 ADMIN、原因类型 REVIEW_REJECT")
                .containsEntry("from_state", "PENDING_REVIEW")
                .containsEntry("to_state", "REJECTED")
                .containsEntry("actor_role", "ADMIN")
                .containsEntry("reason_type", "REVIEW_REJECT");
        assertThat(eventReasonOf(orderId, "REVIEW_REJECTED"))
                .as("事件 reason 列应落拒绝理由（全站统一格式「枚举文案：补充说明」，原先该列恒 null）")
                .isEqualTo("其他：" + reason);
    }

    // ---------- outbox 定时扫描兜底 ----------

    @Test
    void outbox定时扫描兜底重发未发送消息() {
        // 直插一条 PENDING 消息模拟"事务已提交但即时发送未完成"的遗留消息
        String msgId = UUID.randomUUID().toString();
        String payload = "{\"orderId\":1}";
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

    private long createPaidOrder(Session author, List<String> images) {
        JsonNode createResponse = postJson("/api/errand/orders", errandBody("帮忙取个快递", images), author);
        assertSuccess(createResponse, "发布跑腿");
        long orderId = createResponse.path("data").asLong();
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/pay", null, author), "支付");
        return orderId;
    }

    private String pendingPayloadOf(long orderId) {
        List<String> payloads = jdbcTemplate.queryForList(
                "SELECT payload FROM outbox_message WHERE destination = ? AND state = 0", String.class,
                Constants.ERRAND_PAID_TOPIC);
        return payloads.stream()
                .filter(p -> readTree(p).path("orderId").asLong() == orderId)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("订单 " + orderId + " 的支付 outbox 消息不存在"));
    }

    private List<String> notificationsOf(String userId, String type, String keyword) {
        return jdbcTemplate.queryForList(
                "SELECT content FROM notification WHERE user_id = ? AND type = ? AND content LIKE ?",
                String.class, userId, type, "%" + keyword + "%");
    }

    private List<Long> listPublicOrderIds() {
        JsonNode response = getJson("/api/errand/orders?page=1&pageSize=10", null);
        assertSuccess(response, "公开订单列表");
        return StreamSupport.stream(response.path("data").path("list").spliterator(), false)
                .map(node -> node.path("orderId").asLong())
                .toList();
    }

    private int orderCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM errand_order", Integer.class);
        return count == null ? 0 : count;
    }

    private int countWalletTransactions(long orderId, String type) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_detail WHERE order_id = ? AND type = ?",
                Integer.class, orderId, type);
        return count == null ? 0 : count;
    }

    private String statusOf(long orderId) {
        return jdbcTemplate.queryForObject("SELECT state FROM errand_order WHERE errand_order_id = ?", String.class, orderId);
    }

    /** 该订单的事件流水类型序（id 递增即流转顺序） */
    private List<String> eventTypesOf(long orderId) {
        return jdbcTemplate.queryForList(
                "SELECT event_type FROM errand_order_event WHERE order_id = ? ORDER BY id", String.class, orderId);
    }

    /** 某类事件的行（每类事件在一条链路里至多一条；取首条足够） */
    private Map<String, Object> eventRowOf(long orderId, String eventType) {
        return jdbcTemplate.queryForMap(
                "SELECT from_state, to_state, actor_id, actor_role, reason_type, reason "
                        + "FROM errand_order_event WHERE order_id = ? AND event_type = ? ORDER BY id LIMIT 1",
                orderId, eventType);
    }

    /** 事件前态；事件行不存在或该列为 NULL 都返回 null（创建事件即 NULL 前态） */
    private String eventFromStateOf(long orderId, String eventType) {
        List<String> states = jdbcTemplate.queryForList(
                "SELECT from_state FROM errand_order_event WHERE order_id = ? AND event_type = ? ORDER BY id LIMIT 1",
                String.class, orderId, eventType);
        return states.isEmpty() ? null : states.get(0);
    }

    private String eventReasonOf(long orderId, String eventType) {
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

    /** 管理员登录态：注册（USER）→ 改库提权 → 重新验证码登录拿含 ADMIN 角色的 JWT */
    private Session adminSession() {
        Session registered = registerAndLogin("13900007001", "管理员");
        jdbcTemplate.update("UPDATE bs_user SET role = 'ADMIN' WHERE user_id = ?", registered.userId());
        postJson("/api/user/send-code", Map.of("phone", "13900007001"), null);
        String code = redissonClient.<String>getBucket(Constants.VERIFY_CODE_KEY + "13900007001").get();
        JsonNode loginResponse = postJson("/api/user/login", Map.of("phone", "13900007001", "code", code), null);
        assertSuccess(loginResponse, "管理员重新登录");
        return new Session(loginResponse.path("data").path("token").asText(), registered.userId());
    }
}
