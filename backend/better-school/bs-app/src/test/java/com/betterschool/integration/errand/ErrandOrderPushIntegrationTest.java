package com.betterschool.integration.errand;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跑腿订单状态推送事件集成测试（ORDER 帧）：每个状态迁移点成功后写一条 bs-order-push outbox
 * （消费侧经 WS 推给双方在线者，此处断言 outbox 外部可观察状态 + 载荷形状）。
 * 抢单失败/幂等跳过（重复支付重放等）不产生多余推送事件
 */
class ErrandOrderPushIntegrationTest extends CommunityApiTestBase {

    @Test
    void 状态迁移逐点写ORDER推送事件() {
        Session author = registerAndLogin("13800008001", "发单作者");
        Session taker = registerAndLogin("13800008002", "接单同学");

        // 创建 + 支付：PENDING_PAYMENT（草稿提交路径）与 PUBLISHED（支付成功）各一次迁移
        JsonNode createResponse = postJson("/api/errand/orders", body(), author);
        assertSuccess(createResponse, "发布跑腿");
        String orderId = createResponse.path("data").asText();
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/pay", null, author), "支付");

        List<String> payloads = orderPushPayloads();
        assertThat(statusesOf(payloads, orderId))
                .as("创建进待支付 + 支付成功应各写一条推送事件")
                .containsExactly("PENDING_PAYMENT", "PUBLISHED");

        // 抢单：PUBLISHED → LOCKED
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/grab", null, taker), "抢单");
        // 确认接单：LOCKED → CONFIRMED；取货：→ PICKED_UP；送达：→ DELIVERED；确认完成：→ COMPLETED
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/accept", null, taker), "确认接单");
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/pickup", null, taker), "取货");
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/deliver", null, taker), "送达");
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/confirm", null, author), "确认完成");

        assertThat(statusesOf(orderPushPayloads(), orderId))
                .as("完整链路应按迁移序逐点写推送事件")
                .containsExactly("PENDING_PAYMENT", "PUBLISHED", "LOCKED", "CONFIRMED", "PICKED_UP",
                        "DELIVERED", "COMPLETED");
    }

    @Test
    void 支付重放不产生多余推送事件() {
        Session author = registerAndLogin("13800008003", "发单作者");
        JsonNode createResponse = postJson("/api/errand/orders", body(), author);
        assertSuccess(createResponse, "发布跑腿");
        String orderId = createResponse.path("data").asText();
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/pay", null, author), "支付");
        // 幂等重放（已 PUBLISHED 视为支付成功直接返回）
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/pay", null, author), "重复支付");

        assertThat(statusesOf(orderPushPayloads(), orderId))
                .as("重放不写第二条推送事件")
                .containsExactly("PENDING_PAYMENT", "PUBLISHED");
    }

    // ---------- 工具 ----------

    private java.util.Map<String, Object> body() {
        java.util.Map<String, Object> map = new java.util.HashMap<>();
        map.put("title", "帮忙取个快递");
        map.put("type", "EXPRESS_PICKUP");
        map.put("description", "帮忙取个快递");
        map.put("pickupLocation", "菜鸟驿站");
        map.put("deliveryLocation", "6号楼楼下");
        map.put("reward", 10);
        map.put("deadline", java.time.LocalDateTime.now().plusHours(2).toString());
        return map;
    }

    private List<String> orderPushPayloads() {
        return jdbcTemplate.queryForList(
                "SELECT payload FROM outbox_message WHERE destination = ? ORDER BY id", String.class,
                Constants.ORDER_PUSH_TOPIC);
    }

    private List<String> statusesOf(List<String> payloads, String orderId) {
        return payloads.stream()
                .map(this::readTree)
                .filter(node -> node.path("orderId").asText().equals(orderId))
                .map(node -> node.path("status").asText())
                .toList();
    }
}
