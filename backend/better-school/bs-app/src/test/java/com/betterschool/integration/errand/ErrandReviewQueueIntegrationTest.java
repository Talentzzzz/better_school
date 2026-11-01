package com.betterschool.integration.errand;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 审核队列跑腿素材（票 06）集成测试：
 * ERRAND 条目带出配图 images；列表按进入待审时间（update_time）排序；
 * 裁决后队列刷新（待审单移出）；审核详情同样带配图。
 */
class ErrandReviewQueueIntegrationTest extends CommunityApiTestBase {

    // ---------- 队列带配图 ----------

    @Test
    void 审核队列带出跑腿配图且按进入待审时间排序() {
        Session author = registerAndLogin("13800004001", "发单作者");
        String firstPaidId = createPaidOrder(author, null);
        String secondPaidId = createPaidOrder(author, List.of(
                "http://minio.test/bs-files/uploads/a.jpg", "http://minio.test/bs-files/uploads/b.jpg"));
        Session admin = adminSession();

        JsonNode response = getJson("/api/moderation/queue?targetType=ERRAND&page=1&pageSize=10", admin);
        assertSuccess(response, "审核队列");
        List<JsonNode> items = StreamSupport.stream(response.path("data").path("list").spliterator(), false)
                .toList();
        assertThat(items).as("两张待审单都应在队列：%s", response).hasSize(2);
        assertThat(items.get(0).path("targetId").asText())
                .as("先支付的先进待审，排在前").isEqualTo(firstPaidId);
        assertThat(items.get(1).path("targetId").asText()).isEqualTo(secondPaidId);

        JsonNode withoutImages = items.get(0);
        assertThat(toStringList(withoutImages.path("images"))).as("无配图为空列表").isEmpty();

        JsonNode withImages = items.get(1);
        List<String> images = toStringList(withImages.path("images"));
        assertThat(images).as("队列条目应带出配图").containsExactly(
                "http://minio.test/bs-files/uploads/a.jpg", "http://minio.test/bs-files/uploads/b.jpg");
        assertThat(withImages.path("content").asText()).isEqualTo("帮忙取个快递");
        assertThat(withImages.path("reward").asDouble()).isEqualTo(10.0);
        assertThat(withImages.path("status").asText()).isEqualTo("PENDING_REVIEW");

        // 详情接口同样带出配图
        JsonNode detail = getJson("/api/moderation/queue/ERRAND/" + secondPaidId, admin);
        assertSuccess(detail, "审核详情");
        assertThat(toStringList(detail.path("data").path("images"))).containsExactly(
                "http://minio.test/bs-files/uploads/a.jpg", "http://minio.test/bs-files/uploads/b.jpg");
    }

    // ---------- 裁决后队列刷新 ----------

    @Test
    void 裁决后订单移出待审队列() {
        Session author = registerAndLogin("13800004002", "发单作者2");
        String rejectedId = createPaidOrder(author, List.of("http://minio.test/bs-files/uploads/c.jpg"));
        String approvedId = createPaidOrder(author, null);
        Session admin = adminSession();

        // 遗留审核入口已收口（票 09）：裁决前先领单
        claimTaskOf(admin, "ERRAND", rejectedId);
        claimTaskOf(admin, "ERRAND", approvedId);
        assertSuccess(postJson("/api/moderation/review",
                Map.of("targetType", "ERRAND", "targetId", rejectedId, "action", "REJECT", "reasonCode", "OTHER", "supplement", "配图与描述不符"),
                admin), "裁决拒绝");
        assertSuccess(postJson("/api/moderation/review",
                Map.of("targetType", "ERRAND", "targetId", approvedId, "action", "APPROVE"),
                admin), "裁决通过");

        JsonNode response = getJson("/api/moderation/queue?targetType=ERRAND&page=1&pageSize=10", admin);
        assertSuccess(response, "审核队列");
        List<String> remainIds = StreamSupport.stream(response.path("data").path("list").spliterator(), false)
                .map(node -> node.path("targetId").asText())
                .toList();
        assertThat(remainIds).as("拒绝与通过的订单都应移出待审队列")
                .doesNotContain(rejectedId, approvedId);
        assertThat(response.path("data").path("total").asLong()).as("队列计数同步刷新").isZero();
        assertThat(statusOf(approvedId)).as("通过应转 PUBLISHED").isEqualTo("PUBLISHED");
        assertThat(statusOf(rejectedId)).as("拒绝应转 REJECTED").isEqualTo("REJECTED");
    }

    private String statusOf(String orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM errand_order WHERE errand_order_id = ?", String.class, orderId);
    }

    // ---------- 工具 ----------

    private String createPaidOrder(Session author, List<String> images) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "EXPRESS_PICKUP");
        body.put("description", "帮忙取个快递");
        body.put("pickupLocation", "菜鸟驿站");
        body.put("deliveryLocation", "6号楼楼下");
        body.put("reward", 10);
        body.put("timeLimit", "HOURS_2");
        if (images != null) {
            body.put("images", images);
        }
        JsonNode createResponse = postJson("/api/errand/orders", body, author);
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

    private List<String> toStringList(JsonNode arrayNode) {
        return StreamSupport.stream(arrayNode.spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }
}
