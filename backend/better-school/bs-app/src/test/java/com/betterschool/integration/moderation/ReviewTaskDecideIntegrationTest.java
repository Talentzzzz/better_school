package com.betterschool.integration.moderation;

import com.betterschool.domain.timeout.model.entity.TimeoutTaskEntity;
import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.integration.support.TimeoutCallbackFixture;
import com.betterschool.types.common.Constants;
import com.betterschool.usecase.timeout.TimeoutTaskCallbackDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 裁决强绑定 + 遗留入口收口（票 09）集成测试：裁决只能由持有人对自己手上的工单做出，
 * 「业务行已切 + 裁决已记 + 工单已关」三者原子；已 DONE 的重放整体幂等成功零副作用；
 * 遗留 POST /api/moderation/review 收口为「反查我持有的在途工单 → 裁决」，无绕过路径。
 * seam = trigger 层 HTTP 入口 + MySQL 可观察状态（review_task / content_review / 业务行 / 钱包）。
 */
@DisplayName("票 09：裁决强绑定 + 遗留入口收口")
class ReviewTaskDecideIntegrationTest extends CommunityApiTestBase {

    @Autowired
    private TimeoutTaskCallbackDispatcher timeoutTaskCallback;

    // ---------- 未领单裁决被拒：403 且业务行零改动 ----------

    @Test
    void 未领单裁决被拒且业务行零改动() {
        Session author = registerAndLogin("13800009001", "作者1");
        long orderId = createPaidOrder(author);
        Session auditor = auditorSession("13900009001", "13900009001");

        long taskId = latestTaskIdOf("ERRAND", String.valueOf(orderId));
        assertThat(taskState(taskId)).as("前置：工单在池").isEqualTo("PENDING");

        // 未领单直接对工单裁决：403，业务行/审核记录/工单全零改动
        JsonNode decideResponse = postJson("/api/moderation/tasks/" + taskId + "/decide",
                Map.of("action", "APPROVE"), auditor);
        assertThat(decideResponse.path("code").asText()).as("未领单裁决应被拒：%s", decideResponse).isEqualTo("403");

        // 遗留入口未领单直接裁：403「请先领单」，同样零改动
        JsonNode legacyResponse = postJson("/api/moderation/review",
                Map.of("targetType", "ERRAND", "targetId", orderId, "action", "APPROVE"), auditor);
        assertThat(legacyResponse.path("code").asText())
                .as("遗留入口未领单应被拒：%s", legacyResponse).isEqualTo("403");

        assertThat(orderState(orderId)).as("业务行零改动").isEqualTo("PENDING_REVIEW");
        assertThat(taskState(taskId)).as("工单仍应在池").isEqualTo("PENDING");
        assertThat(reviewRowCount("ERRAND", String.valueOf(orderId))).as("审核记录零改动").isZero();
    }

    // ---------- 正常领单后裁决四类型各一次 ----------

    @Test
    void 领单后裁决四类型各一次结论与现状一致() {
        Session postAuthor = registerAndLogin("13800009002", "帖作者");
        Session commentAuthor = registerAndLogin("13800009003", "评作者");
        Session auditor = auditorSession("13900009002", "13900009002");

        // 帖（通过）/ 评（通过，宿主帖先经统一裁决可见、评作者≠帖作者以触发互动通知）/ 商（拒绝）/ 跑腿（拒绝）
        String postId = publishPendingPost(postAuthor);
        String hostPostId = publishPost(postAuthor, "评论宿主帖子");
        String commentId = publishCommentAndGetId(hostPostId, commentAuthor, "待审评论");
        long productId = createProduct(postAuthor);
        long orderId = createPaidOrder(postAuthor);

        // POST 通过：业务行 NORMAL + 审核记录 + 工单 DONE 同事务
        long postTaskId = claimTaskOf(auditor, "POST", postId);
        assertSuccess(postJson("/api/moderation/tasks/" + postTaskId + "/decide",
                Map.of("action", "APPROVE"), auditor), "帖子裁决");
        assertThat(postState(postId)).isEqualTo("NORMAL");
        assertTaskDone(postTaskId, auditor);
        assertThat(reviewRowCount("POST", postId)).isEqualTo(1);

        // COMMENT 通过：评论 NORMAL + 通过才发的互动通知（帖被评通知帖主）；帖主与评作者不同人
        long commentTaskId = claimTaskOf(auditor, "COMMENT", commentId);
        assertSuccess(postJson("/api/moderation/tasks/" + commentTaskId + "/decide",
                Map.of("action", "APPROVE"), auditor), "评论裁决");
        assertThat(commentStatusOf(commentId)).isEqualTo("NORMAL");
        assertTaskDone(commentTaskId, auditor);
        List<String> interactions = jdbcTemplate.queryForList(
                "SELECT content FROM notification WHERE user_id = ? AND scope = 'INTERACTION_COMMENT'",
                String.class, postAuthor.userId());
        assertThat(interactions).as("评论通过才发互动通知").anyMatch(content -> content.contains("新评论"));

        // PRODUCT 拒绝：REJECTED + 拒绝理由落枚举格式（无资金环节）
        long productTaskId = claimTaskOf(auditor, "PRODUCT", String.valueOf(productId));
        assertSuccess(postJson("/api/moderation/tasks/" + productTaskId + "/decide",
                Map.of("action", "REJECT", "reasonCode", "OTHER", "supplement", "图片与实物不符"), auditor), "商品裁决");
        assertThat(productStatus(productId)).isEqualTo("REJECTED");
        assertTaskDone(productTaskId, auditor);
        assertThat(rejectReasonOf("PRODUCT", String.valueOf(productId))).isEqualTo("其他：图片与实物不符");

        // ERRAND 拒绝：REJECTED + 解冻退款（跨域资金动作）
        long errandTaskId = claimTaskOf(auditor, "ERRAND", String.valueOf(orderId));
        assertSuccess(postJson("/api/moderation/tasks/" + errandTaskId + "/decide",
                Map.of("action", "REJECT", "reasonCode", "OTHER", "supplement", "描述不清"), auditor), "跑腿裁决");
        assertThat(orderState(orderId)).isEqualTo("REJECTED");
        assertTaskDone(errandTaskId, auditor);
        assertThat(countWalletTransactions(orderId, "UNFROZEN")).as("跑腿拒绝解冻退款").isEqualTo(1);
        BigDecimal frozen = jdbcTemplate.queryForObject(
                "SELECT frozen_amount FROM virtual_wallet WHERE user_id = ?", BigDecimal.class, postAuthor.userId());
        assertThat(frozen).as("冻结清零").isEqualByComparingTo(BigDecimal.ZERO);
    }

    // ---------- 重放幂等：已 DONE 的工单再裁一次整体幂等成功 ----------

    @Test
    void 已DONE工单重放裁决幂等零副作用() {
        Session author = registerAndLogin("13800009004", "作者4");
        long orderId = createPaidOrder(author);
        Session auditor = auditorSession("13900009003", "13900009003");

        long taskId = claimTaskOf(auditor, "ERRAND", String.valueOf(orderId));
        Map<String, Object> rejectBody = Map.of("action", "REJECT",
                "reasonCode", "OTHER", "supplement", "描述不清");
        assertSuccess(postJson("/api/moderation/tasks/" + taskId + "/decide", rejectBody, auditor), "首次拒绝");
        LocalDateTime decidedAt = decidedAtOf(taskId);

        // 重试语义：同一张已 DONE 工单按同一动作再裁一次 → 整体幂等成功，业务行不再翻转、退款不重复、记录不重复
        assertSuccess(postJson("/api/moderation/tasks/" + taskId + "/decide", rejectBody, auditor), "重放拒绝");

        assertThat(orderState(orderId)).as("业务行不再翻转").isEqualTo("REJECTED");
        assertThat(countWalletTransactions(orderId, "UNFROZEN")).as("退款不重复").isEqualTo(1);
        BigDecimal balance = jdbcTemplate.queryForObject(
                "SELECT balance FROM virtual_wallet WHERE user_id = ?", BigDecimal.class, author.userId());
        assertThat(balance).as("资金不再变动").isEqualByComparingTo("1000.00");
        assertThat(reviewRowCount("ERRAND", String.valueOf(orderId))).as("审核记录不重复").isEqualTo(1);
        assertThat(decidedAtOf(taskId)).as("裁决时刻不变").isEqualTo(decidedAt);

        // 相反动作（已拒再改判通过）不是重放：既有内核终态闸门拦下，零副作用
        JsonNode conflicting = postJson("/api/moderation/tasks/" + taskId + "/decide",
                Map.of("action", "APPROVE"), auditor);
        assertThat(conflicting.path("code").asText()).as("终态不可改判：%s", conflicting).isEqualTo("500");
        assertThat(orderState(orderId)).isEqualTo("REJECTED");
    }

    // ---------- 非持有人 / 被回收后裁决被拒 ----------

    @Test
    void 非持有人与被回收后裁决被拒且业务行零改动() {
        Session author = registerAndLogin("13800009005", "作者5");
        long orderId = createPaidOrder(author);
        Session auditorA = auditorSession("13900009004", "13900009004");
        Session auditorB = auditorSession("13900009005", "13900009005");

        long taskId = claimTaskOf(auditorA, "ERRAND", String.valueOf(orderId));

        // 非持有人裁决：403，工单租约不动、业务行零改动
        JsonNode wrongDecider = postJson("/api/moderation/tasks/" + taskId + "/decide",
                Map.of("action", "APPROVE"), auditorB);
        assertThat(wrongDecider.path("code").asText()).as("非持有人裁决应被拒：%s", wrongDecider).isEqualTo("403");
        assertThat(orderState(orderId)).isEqualTo("PENDING_REVIEW");
        assertThat(taskState(taskId)).isEqualTo("CLAIMED");
        assertThat(claimByOf(taskId)).isEqualTo(auditorA.userId());

        // 租约到点回收（超时中心回调，真实形状）：持有人清空
        assertCallbackSuccess(callback(leaseTask(taskId, 1)));
        assertThat(taskState(taskId)).isEqualTo("PENDING");
        assertThat(claimByOf(taskId)).isNull();

        // 被回收后原持有人再裁：403「已被回收」，业务行零改动、可重新领单
        JsonNode recycled = postJson("/api/moderation/tasks/" + taskId + "/decide",
                Map.of("action", "APPROVE"), auditorA);
        assertThat(recycled.path("code").asText()).as("被回收后裁决应被拒：%s", recycled).isEqualTo("403");
        assertThat(orderState(orderId)).as("业务行零改动").isEqualTo("PENDING_REVIEW");
        assertThat(reviewRowCount("ERRAND", String.valueOf(orderId))).isZero();
        assertThat(claimTaskOf(auditorB, "ERRAND", String.valueOf(orderId))).as("回池后可被他人领走").isPositive();
    }

    // ---------- 遗留入口收口：反查我持有的在途工单 → 裁决 ----------

    @Test
    void 遗留审核入口收口为反查持有人在途工单() {
        Session author = registerAndLogin("13800009006", "作者6");
        String postId = publishPendingPost(author);
        Session auditorA = auditorSession("13900009006", "13900009006");
        Session auditorB = auditorSession("13900009007", "13900009007");

        // 领单后遗留入口可裁：按「类型 + 内容 id + 当前登录用户」反查持有的在途工单，命中转强绑定裁决
        long taskId = claimTaskOf(auditorA, "POST", postId);
        assertSuccess(postJson("/api/moderation/review",
                Map.of("targetType", "POST", "targetId", postId, "action", "APPROVE"), auditorA), "遗留入口裁决");
        assertThat(postState(postId)).isEqualTo("NORMAL");
        assertTaskDone(taskId, auditorA);

        // 他人持有：反查不到（claim_by=别人）→ 403，杜绝代裁
        String postId2 = publishPendingPost(author);
        long taskId2 = claimTaskOf(auditorB, "POST", postId2);
        JsonNode hijack = postJson("/api/moderation/review",
                Map.of("targetType", "POST", "targetId", postId2, "action", "APPROVE"), auditorA);
        assertThat(hijack.path("code").asText()).as("他人持有的工单反查不到：%s", hijack).isEqualTo("403");
        assertThat(postState(postId2)).isEqualTo("PENDING_REVIEW");
        assertThat(taskState(taskId2)).isEqualTo("CLAIMED");

        // 已裁决后遗留入口重放：反查不到在途工单 → 拒绝且零副作用（强绑定走 /tasks/{id}/decide 才有重放幂等）
        JsonNode replay = postJson("/api/moderation/review",
                Map.of("targetType", "POST", "targetId", postId, "action", "APPROVE"), auditorA);
        assertThat(replay.path("code").asText()).as("已裁决内容遗留入口不再命中：%s", replay).isEqualTo("403");
        assertThat(postState(postId)).isEqualTo("NORMAL");
        assertThat(reviewRowCount("POST", postId)).isEqualTo(1);
    }

    // ---------- 用例夹具 ----------

    private String publishPendingPost(Session author) {
        JsonNode response = postJson("/api/community/posts",
                Map.of("title", "待审帖子", "content", "等待人工审核的内容"), author);
        assertSuccess(response, "发帖");
        return response.path("data").asText();
    }

    private long createProduct(Session seller) {
        JsonNode response = postJson("/api/trade/products", Map.ofEntries(
                Map.entry("title", "待审商品"),
                Map.entry("description", "自用闲置，九成新"),
                Map.entry("images", List.of()),
                Map.entry("price", 19.9),
                Map.entry("tradeLocation", "图书馆门口"),
                Map.entry("category", "BOOKS")), seller);
        assertSuccess(response, "发布商品");
        return response.path("data").asLong();
    }

    private long createPaidOrder(Session author) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "EXPRESS_PICKUP");
        body.put("description", "帮忙取个快递");
        body.put("pickupLocation", "菜鸟驿站");
        body.put("deliveryLocation", "6号楼楼下");
        body.put("reward", 10);
        body.put("timeLimit", "HOURS_2");
        JsonNode createResponse = postJson("/api/errand/orders", body, author);
        assertSuccess(createResponse, "发布跑腿");
        long orderId = createResponse.path("data").asLong();
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/pay", null, author), "支付");
        return orderId;
    }

    /** 审核员登录态：注册 → 改库提权 AUDITOR → 重新走验证码登录拿含新角色的 JWT */
    private Session auditorSession(String phone, String nickname) {
        Session registered = registerAndLogin(phone, nickname);
        jdbcTemplate.update("UPDATE bs_user SET role = 'AUDITOR' WHERE user_id = ?", registered.userId());
        postJson("/api/user/send-code", Map.of("phone", phone), null);
        String code = redissonClient.<String>getBucket(Constants.VERIFY_CODE_KEY + phone).get();
        JsonNode loginResponse = postJson("/api/user/login", Map.of("phone", phone, "code", code), null);
        assertSuccess(loginResponse, "审核员重新登录");
        return new Session(loginResponse.path("data").path("token").asText(), registered.userId());
    }

    // ---------- 回调工具（租约回收，真实形状同票 08） ----------

    private static TimeoutTaskEntity leaseTask(long taskId, int claimSeq) {
        return TimeoutCallbackFixture.dueTask(Constants.REVIEW_LEASE_TIMEOUT, taskId + ":" + claimSeq, 5);
    }

    private Set<String> callback(TimeoutTaskEntity... tasks) {
        return TimeoutCallbackFixture.callback(timeoutTaskCallback, tasks);
    }

    private static void assertCallbackSuccess(Set<String> failures) {
        TimeoutCallbackFixture.assertCallbackSuccess(failures);
    }

    // ---------- 可观察状态 ----------

    private long latestTaskIdOf(String targetType, String targetId) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM review_task WHERE target_type = ? AND target_id = ? ORDER BY review_round DESC LIMIT 1",
                Long.class, targetType, targetId);
    }

    private String taskState(long taskId) {
        return jdbcTemplate.queryForObject("SELECT claim_state FROM review_task WHERE id = ?", String.class, taskId);
    }

    private String claimByOf(long taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT claim_by FROM review_task WHERE id = ?", String.class, taskId);
    }

    private LocalDateTime decidedAtOf(long taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT decided_at FROM review_task WHERE id = ?", LocalDateTime.class, taskId);
    }

    /** 工单裁决收口断言：DONE + 裁决时刻已写 + 持有人快照保留 */
    private void assertTaskDone(long taskId, Session auditor) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT claim_state, claim_by, decided_at FROM review_task WHERE id = ?", taskId);
        assertThat(row.get("claim_state")).as("工单应停 DONE").isEqualTo("DONE");
        assertThat((String) row.get("claim_by")).as("持有人快照保留").isEqualTo(auditor.userId());
        assertThat(row.get("decided_at")).as("裁决时刻已写").isNotNull();
    }

    private int reviewRowCount(String targetType, String targetId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_review WHERE target_type = ? AND target_id = ?",
                Integer.class, targetType, targetId);
        return count == null ? 0 : count;
    }

    private String rejectReasonOf(String targetType, String targetId) {
        return jdbcTemplate.queryForObject(
                "SELECT reason FROM content_review WHERE target_type = ? AND target_id = ? AND action = 'REJECT'",
                String.class, targetType, targetId);
    }

    private String postState(String postId) {
        return jdbcTemplate.queryForObject("SELECT state FROM post WHERE post_id = ?", String.class, postId);
    }

    private String productStatus(long productId) {
        return jdbcTemplate.queryForObject("SELECT state FROM product WHERE product_id = ?", String.class, productId);
    }

    private String orderState(long orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM errand_order WHERE errand_order_id = ?", String.class, orderId);
    }

    private int countWalletTransactions(long orderId, String type) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_detail WHERE order_id = ? AND type = ?",
                Integer.class, orderId, type);
        return count == null ? 0 : count;
    }
}
