package com.betterschool.integration.errand;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 拒绝理由可见性（票 05）：GET /api/errand/orders/{id} 作者 + REJECTED → rejectReason（从 content_review.reason 读）；
 * 他人不带、非 REJECTED 不带。
 */
class ErrandRejectReasonIntegrationTest extends CommunityApiTestBase {

    @Test
    void 作者看被拒订单带拒绝理由() {
        Session author = registerAndLogin("13800004001", "被拒作者");
        String orderId = createPaidOrder(author);
        String reason = "配图与描述不符";
        Session admin = adminSession();
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "ERRAND", "targetId", orderId, "approve", false, "reasonCode", "OTHER", "supplement", reason), admin),
                "裁决拒绝");

        JsonNode detail = getJson("/api/errand/orders/" + orderId, author);
        assertSuccess(detail, "作者看被拒订单");
        assertThat(detail.path("data").path("status").asText()).isEqualTo("REJECTED");
        assertThat(detail.path("data").path("rejectReason").asText())
                .as("作者应看到具体不通过理由").isEqualTo("其他：" + reason);
    }

    @Test
    void 他人看被拒订单不带拒绝理由() {
        Session author = registerAndLogin("13800004002", "被拒作者2");
        String orderId = createPaidOrder(author);
        Session admin = adminSession();
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "ERRAND", "targetId", orderId, "approve", false, "reasonCode", "OTHER", "supplement", "描述不清"), admin),
                "裁决拒绝");

        Session other = registerAndLogin("13800004003", "无关用户");
        JsonNode detail = getJson("/api/errand/orders/" + orderId, other);
        assertSuccess(detail, "他人看被拒订单");
        assertThat(detail.path("data").path("rejectReason").isMissingNode())
                .as("非作者不应带出拒绝理由").isTrue();
    }

    @Test
    void 非REJECTED状态不带拒绝理由() {
        Session author = registerAndLogin("13800004004", "待审作者");
        String orderId = createPaidOrder(author);

        JsonNode detail = getJson("/api/errand/orders/" + orderId, author);
        assertSuccess(detail, "作者看待审订单");
        assertThat(detail.path("data").path("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(detail.path("data").path("rejectReason").isMissingNode())
                .as("非 REJECTED 不带拒绝理由").isTrue();
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

    /** 管理员登录态：先注册（USER），改库提权后重新走验证码登录拿含 ADMIN 角色的 JWT */
    private Session adminSession() {
        Session registered = registerAndLogin("13900004001", "管理员");
        jdbcTemplate.update("UPDATE bs_user SET role = 'ADMIN' WHERE user_id = ?", registered.userId());
        postJson("/api/user/send-code", Map.of("phone", "13900004001"), null);
        String code = redissonClient.<String>getBucket(Constants.VERIFY_CODE_KEY + "13900004001").get();
        JsonNode loginResponse = postJson("/api/user/login", Map.of("phone", "13900004001", "code", code), null);
        assertSuccess(loginResponse, "管理员重新登录");
        return new Session(loginResponse.path("data").path("token").asText(), registered.userId());
    }
}
