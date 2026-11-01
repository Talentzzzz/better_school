package com.betterschool.integration.errand;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.usecase.moderation.ReviewCase;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一裁决用例 decide()（票 03）集成测试：
 * 通过 → PUBLISHED 对外可见 + 作者通知；拒绝 → REJECTED 终态 + 解冻退款流水 + 通知含理由；
 * 重复裁决幂等（条件翻转 + 流水 uk 防重）；非管理员 403；用例层不校验调用者角色（operatorId 直传）；
 * 帖子人工审核走同一用例无回归。
 */
class ErrandReviewDecideIntegrationTest extends CommunityApiTestBase {

    @Autowired
    private ReviewCase reviewCase;

    // ---------- 通过：PENDING_REVIEW → PUBLISHED ----------

    @Test
    void 裁决通过转PUBLISHED对外可见且作者收通知() {
        Session author = registerAndLogin("13800003001", "发单作者");
        String orderId = createPaidOrder(author);
        Session admin = adminSession();

        JsonNode response = postJson("/api/admin/reviews/decide",
                Map.of("targetType", "ERRAND", "targetId", orderId, "approve", true), admin);
        assertSuccess(response, "裁决通过");
        // 重试场景：重复通过应幂等（PUBLISHED 跳过 + 审核记录 INSERT IGNORE 不重复）
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "ERRAND", "targetId", orderId, "approve", true), admin), "重复通过");

        assertThat(statusOf(orderId)).as("通过应转 PUBLISHED").isEqualTo("PUBLISHED");
        Integer approveRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_review WHERE target_type = 'ERRAND' AND target_id = ? AND action = 'APPROVE'",
                Integer.class, orderId);
        assertThat(approveRows).as("审核记录只插一行").isEqualTo(1);
        assertThat(containsOrder(listPublicOrders(), orderId)).as("对外列表应可见").isTrue();

        List<String> contents = jdbcTemplate.queryForList(
                "SELECT content FROM notification WHERE user_id = ? AND type = 'AUDIT_RESULT'",
                String.class, author.userId());
        assertThat(contents).as("作者应收到通过通知").anyMatch(content -> content.contains("通过审核"));

        assertThat(countWalletTransactions(orderId, "UNFROZEN"))
                .as("通过不触发退款").isZero();
        BigDecimal frozenAmount = jdbcTemplate.queryForObject(
                "SELECT frozen_amount FROM virtual_wallet WHERE user_id = ?", BigDecimal.class, author.userId());
        assertThat(frozenAmount).as("资金仍在冻结中（完成时才划转）").isEqualByComparingTo("10.00");
    }

    // ---------- 拒绝：REJECTED + 解冻退款 + 通知含理由 ----------

    @Test
    void 拒绝无理由被参数拒绝且状态不变() {
        Session author = registerAndLogin("13800003002", "发单作者2");
        String orderId = createPaidOrder(author);
        Session admin = adminSession();

        JsonNode response = postJson("/api/admin/reviews/decide",
                Map.of("targetType", "ERRAND", "targetId", orderId, "approve", false), admin);

        assertThat(response.path("code").asText()).as("拒绝理由必填：%s", response).isEqualTo("400");
        assertThat(statusOf(orderId)).as("裁决未生效").isEqualTo("PENDING_REVIEW");
    }

    @Test
    void 拒绝转REJECTED解冻退款且通知含理由() {
        Session author = registerAndLogin("13800003003", "发单作者3");
        String orderId = createPaidOrder(author);
        String reason = "配图模糊无法辨认需求";
        Session admin = adminSession();

        JsonNode response = postJson("/api/admin/reviews/decide",
                Map.of("targetType", "ERRAND", "targetId", orderId, "approve", false, "reasonCode", "OTHER", "supplement", reason), admin);
        assertSuccess(response, "裁决拒绝");

        assertThat(statusOf(orderId)).as("拒绝应转 REJECTED 终态").isEqualTo("REJECTED");
        assertThat(countWalletTransactions(orderId, "UNFROZEN")).as("应有解冻退款流水").isEqualTo(1);
        BigDecimal frozenAmount = jdbcTemplate.queryForObject(
                "SELECT frozen_amount FROM virtual_wallet WHERE user_id = ?", BigDecimal.class, author.userId());
        assertThat(frozenAmount).as("冻结应清零").isEqualByComparingTo(BigDecimal.ZERO);
        BigDecimal balance = jdbcTemplate.queryForObject(
                "SELECT balance FROM virtual_wallet WHERE user_id = ?", BigDecimal.class, author.userId());
        assertThat(balance).as("冻结金额应全额退回").isEqualByComparingTo("1000.00");

        String reviewReason = jdbcTemplate.queryForObject(
                "SELECT reason FROM content_review WHERE target_type = 'ERRAND' AND target_id = ? AND action = 'REJECT'",
                String.class, orderId);
        assertThat(reviewReason).as("拒绝理由应落审核记录").isEqualTo("其他：" + reason);

        List<String> contents = jdbcTemplate.queryForList(
                "SELECT content FROM notification WHERE user_id = ? AND type = 'AUDIT_RESULT'",
                String.class, author.userId());
        assertThat(contents).as("拒绝通知应含不通过理由").anyMatch(content -> content.contains(reason));
    }

    @Test
    void 重复裁决幂等退款不重复() {
        Session author = registerAndLogin("13800003004", "发单作者4");
        String orderId = createPaidOrder(author);
        Session admin = adminSession();
        Map<String, Object> rejectBody = Map.of("targetType", "ERRAND", "targetId", orderId,
                "approve", false, "reasonCode", "OTHER", "supplement", "描述不清");

        assertSuccess(postJson("/api/admin/reviews/decide", rejectBody, admin), "首次拒绝");
        // 重试场景：订单已 REJECTED，域方法重放退款需求，流水 uk 保证解冻不重复
        assertSuccess(postJson("/api/admin/reviews/decide", rejectBody, admin), "重复拒绝");

        assertThat(statusOf(orderId)).isEqualTo("REJECTED");
        assertThat(countWalletTransactions(orderId, "UNFROZEN")).as("解冻流水不重复").isEqualTo(1);
        Integer reviewRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_review WHERE target_type = 'ERRAND' AND target_id = ? AND action = 'REJECT'",
                Integer.class, orderId);
        assertThat(reviewRows).as("审核记录只插一行").isEqualTo(1);
        BigDecimal balance = jdbcTemplate.queryForObject(
                "SELECT balance FROM virtual_wallet WHERE user_id = ?", BigDecimal.class, author.userId());
        assertThat(balance).as("退款不重复").isEqualByComparingTo("1000.00");

        // 已拒绝后改判通过：被终态闸门拦下，不得发布
        JsonNode approveResponse = postJson("/api/admin/reviews/decide",
                Map.of("targetType", "ERRAND", "targetId", orderId, "approve", true), admin);
        assertThat(approveResponse.path("code").asText()).as("终态不可改判：%s", approveResponse).isEqualTo("500");
        assertThat(statusOf(orderId)).isEqualTo("REJECTED");
    }

    // ---------- 权限与调用者口径 ----------

    @Test
    void 非管理员调用裁决被拒() {
        Session author = registerAndLogin("13800003005", "发单作者5");
        String orderId = createPaidOrder(author);
        Session normalUser = registerAndLogin("13800003006", "普通用户");

        JsonNode response = postJson("/api/admin/reviews/decide",
                Map.of("targetType", "ERRAND", "targetId", orderId, "approve", true), normalUser);
        assertThat(response.path("code").asText()).as("普通用户应 403：%s", response).isEqualTo("403");
        assertThat(statusOf(orderId)).isEqualTo("PENDING_REVIEW");

        ResponseEntity<String> anonymous = exchange(HttpMethod.POST, "/api/admin/reviews/decide",
                Map.of("targetType", "ERRAND", "targetId", orderId, "approve", true), null);
        assertThat(anonymous.getStatusCode().value()).as("未登录应 401").isEqualTo(401);
    }

    @Test
    void 系统账号operatorId直调用例生效() {
        Session author = registerAndLogin("13800003007", "发单作者7");
        String orderId = createPaidOrder(author);
        // operatorId 直传即可走通，用例不校验登录态/角色（HTTP 层拦截角色）
        Session systemAccount = registerAndLogin("13800003008", "系统账号");

        reviewCase.decide("ERRAND", orderId, true, null, null, systemAccount.userId());

        assertThat(statusOf(orderId)).as("系统账号 operatorId 裁决应生效").isEqualTo("PUBLISHED");
        String operatorId = jdbcTemplate.queryForObject(
                "SELECT operator_id FROM content_review WHERE target_type = 'ERRAND' AND target_id = ? AND action = 'APPROVE'",
                String.class, orderId);
        assertThat(operatorId).as("裁决记录应落系统账号 operatorId").isEqualTo(systemAccount.userId());
    }

    // ---------- POST/COMMENT 无回归 ----------

    @Test
    void 帖子人工审核走统一裁决无回归() {
        Session author = registerAndLogin("13800003009", "发帖作者");
        String postId = publishPostPendingReview(author);
        Session admin = adminSession();

        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "POST", "targetId", postId, "approve", true), admin), "帖子裁决通过");

        String status = jdbcTemplate.queryForObject("SELECT state FROM post WHERE post_id = ?", String.class, postId);
        assertThat(status).as("帖子通过应转 NORMAL").isEqualTo("NORMAL");
        JsonNode detail = getJson("/api/community/posts/" + postId, null);
        assertSuccess(detail, "访客看帖");
        assertThat(detail.path("data").path("postId").asText()).isEqualTo(postId);

        // 拒绝理由必填（票 01 新语义，对齐商品/跑腿），理由落审核记录
        String rejectedPostId = publishPostPendingReview(author);
        JsonNode missingReason = postJson("/api/admin/reviews/decide",
                Map.of("targetType", "POST", "targetId", rejectedPostId, "approve", false), admin);
        assertThat(missingReason.path("code").asText()).as("无理由拒绝应被拦截：%s", missingReason).isEqualTo("400");
        String rejectReason = "内容与社区规范不符";
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "POST", "targetId", rejectedPostId, "approve", false, "reasonCode", "OTHER", "supplement", rejectReason), admin),
                "帖子裁决拒绝");
        String rejectedStatus = jdbcTemplate.queryForObject(
                "SELECT state FROM post WHERE post_id = ?", String.class, rejectedPostId);
        assertThat(rejectedStatus).isEqualTo("REJECTED");
        String reviewReason = jdbcTemplate.queryForObject(
                "SELECT reason FROM content_review WHERE target_type = 'POST' AND target_id = ? AND action = 'REJECT'",
                String.class, rejectedPostId);
        assertThat(reviewReason).as("拒绝理由应落审核记录").isEqualTo("其他：" + rejectReason);
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
        Session registered = registerAndLogin("13900003001", "管理员");
        jdbcTemplate.update("UPDATE bs_user SET role = 'ADMIN' WHERE user_id = ?", registered.userId());
        postJson("/api/user/send-code", Map.of("phone", "13900003001"), null);
        String code = redissonClient.<String>getBucket(Constants.VERIFY_CODE_KEY + "13900003001").get();
        JsonNode loginResponse = postJson("/api/user/login", Map.of("phone", "13900003001", "code", code), null);
        assertSuccess(loginResponse, "管理员重新登录");
        return new Session(loginResponse.path("data").path("token").asText(), registered.userId());
    }

    /** 发帖（先审后发：干净内容落库即待审；命中敏感词会直接报错不落库） */
    private String publishPostPendingReview(Session author) {
        JsonNode response = postJson("/api/community/posts",
                Map.of("title", "测试帖子", "content", "正常帖子内容"), author);
        assertSuccess(response, "发帖");
        String postId = response.path("data").asText();
        String status = jdbcTemplate.queryForObject("SELECT state FROM post WHERE post_id = ?", String.class, postId);
        assertThat(status).as("先审后发：发布应进人工待审").isEqualTo("PENDING_REVIEW");
        return postId;
    }

    private String statusOf(String orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM errand_order WHERE errand_order_id = ?", String.class, orderId);
    }

    private int countWalletTransactions(String orderId, String type) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_detail WHERE order_id = ? AND type = ?",
                Integer.class, orderId, type);
        return count == null ? 0 : count;
    }

    private List<String> listPublicOrders() {
        JsonNode response = getJson("/api/errand/orders?page=1&pageSize=10", null);
        assertSuccess(response, "公开订单列表");
        return StreamSupport.stream(response.path("data").path("list").spliterator(), false)
                .map(node -> node.path("orderId").asText())
                .toList();
    }

    private boolean containsOrder(List<String> orderIds, String orderId) {
        return orderIds.contains(orderId);
    }
}
