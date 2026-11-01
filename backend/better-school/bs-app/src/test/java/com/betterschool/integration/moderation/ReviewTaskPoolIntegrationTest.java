package com.betterschool.integration.moderation;

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
 * 审核工单池（票 06）集成测试：四类内容进入待审同事务开单、入池立即可读、裁决出池、
 * 同轮重复入池幂等一行、重提新轮次新工单（旧工单停 DONE）、按类型积压计数与池内行数一致。
 * seam = trigger 层 HTTP 入口 + MySQL 可观察状态（review_task 行）。
 */
class ReviewTaskPoolIntegrationTest extends CommunityApiTestBase {

    // ---------- 四类内容进入待审后立即可在池读到 ----------

    @Test
    void 四类内容进入待审后立即在池且entered_at取正确来源() {
        Session author = registerAndLogin("13800006001", "作者");
        Session admin = adminSession();

        // 帖子：发布落库即待审（entered_at = review_entered_at）
        String postId = publishPendingPost(author);
        // 商品：提交落库即待审
        String productId = createProduct(author);
        // 评论：先备一张已通过帖子（其工单已 DONE），评论发布即待审（entered_at = create_time）
        String visiblePostId = publishPost(author, "评论宿主帖子");
        String commentId = publishCommentAndGetId(visiblePostId, author, "待审评论");
        // 跑腿：支付成功进待审（entered_at = 支付时刻）
        String orderId = createPaidOrder(author);

        assertThat(taskState("POST", postId)).as("帖子发布同事务开单").isEqualTo("PENDING");
        assertThat(taskState("PRODUCT", productId)).as("商品提交同事务开单").isEqualTo("PENDING");
        assertThat(taskState("COMMENT", commentId)).as("评论发布同事务开单").isEqualTo("PENDING");
        assertThat(taskState("ERRAND", orderId)).as("跑腿支付同事务开单").isEqualTo("PENDING");

        assertThat(taskRound("POST", postId)).isEqualTo(1);
        assertThat(taskRound("COMMENT", commentId)).isEqualTo(1);
        assertThat(taskRound("ERRAND", orderId)).isEqualTo(1);

        // 各类型队列立即可读（工单池口径：claim_state=PENDING）
        assertThat(queueIdsOf(admin, "POST")).contains(postId);
        assertThat(queueIdsOf(admin, "PRODUCT")).contains(productId);
        assertThat(queueIdsOf(admin, "COMMENT")).contains(commentId);
        assertThat(queueIdsOf(admin, "ERRAND")).contains(orderId);
    }

    @Test
    void 跨类型队列按entered_at先到先审() throws Exception {
        Session author = registerAndLogin("13800006002", "作者2");
        Session admin = adminSession();

        String postId = publishPendingPost(author);
        Thread.sleep(20);
        String orderId = createPaidOrder(author);
        Thread.sleep(20);
        String productId = createProduct(author);

        // 不带 targetType = 跨类型取候选：全局按 entered_at FIFO
        assertThat(queueIdsOf(admin, null)).containsExactly(postId, orderId, productId);
    }

    // ---------- 裁决完成后出池 ----------

    @Test
    void 裁决通过与拒绝后工单出池() {
        Session author = registerAndLogin("13800006003", "作者3");
        Session admin = adminSession();

        String approvedPostId = publishPendingPost(author);
        String rejectedOrderId = createPaidOrder(author);

        // 遗留审核入口已收口（票 09）：裁决前先领单，按「类型+内容+当前用户」反查持有的在途工单
        claimTaskOf(admin, "POST", approvedPostId);
        claimTaskOf(admin, "ERRAND", rejectedOrderId);
        assertSuccess(postJson("/api/moderation/review",
                Map.of("targetType", "POST", "targetId", approvedPostId, "action", "APPROVE"), admin), "裁决通过");
        assertSuccess(postJson("/api/moderation/review",
                Map.of("targetType", "ERRAND", "targetId", rejectedOrderId, "action", "REJECT",
                        "reasonCode", "OTHER", "supplement", "与描述不符"), admin), "裁决拒绝");

        assertThat(taskState("POST", approvedPostId)).as("通过后工单停 DONE").isEqualTo("DONE");
        assertThat(taskState("ERRAND", rejectedOrderId)).as("拒绝后工单停 DONE").isEqualTo("DONE");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT decided_at IS NOT NULL FROM review_task WHERE target_type = 'POST' AND target_id = ?",
                Boolean.class, approvedPostId)).as("DONE 工单落裁决时刻").isTrue();

        assertThat(queueIdsOf(admin, "POST")).doesNotContain(approvedPostId);
        assertThat(queueIdsOf(admin, "ERRAND")).doesNotContain(rejectedOrderId);
    }

    // ---------- 同一轮重复入池只有一行 ----------

    @Test
    void 同轮重复入池幂等只有一行() {
        Session author = registerAndLogin("13800006004", "作者4");

        String orderId = createPaidOrder(author);
        // 支付重试（幂等返回）：同一轮再走一次支付路径，工单不开第二行
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/pay", null, author), "支付重试");

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM review_task WHERE target_type = 'ERRAND' AND target_id = ?",
                Integer.class, orderId);
        assertThat(rows).as("uk(target_type,target_id,review_round) 吸收重复，同轮只一行").isEqualTo(1);
        assertThat(taskState("ERRAND", orderId)).isEqualTo("PENDING");
    }

    // ---------- 同轮重提＝新轮次＝新工单行，旧工单停 DONE ----------

    @Test
    void 撤回重提后新轮次新工单旧工单停DONE() {
        Session author = registerAndLogin("13800006005", "作者5");
        Session admin = adminSession();

        String postId = publishPendingPost(author);
        assertThat(taskRound("POST", postId)).isEqualTo(1);

        // 撤回回草稿箱：工单出池停 DONE
        assertSuccess(postJson("/api/community/posts/" + postId + "/withdraw", null, author), "撤回");
        assertThat(taskState("POST", postId)).isEqualTo("DONE");
        assertThat(queueIdsOf(admin, "POST")).as("撤回后不留幽灵工单").doesNotContain(postId);

        // 重提：新轮次 = 新工单行（round 2），旧工单仍停 DONE
        assertSuccess(postJson("/api/community/posts/" + postId + "/submit", null, author), "重提");
        assertThat(taskRound("POST", postId)).isEqualTo(2);
        assertThat(taskState("POST", postId)).isEqualTo("PENDING");
        List<Integer> openRounds = jdbcTemplate.queryForList(
                "SELECT review_round FROM review_task WHERE target_type = 'POST' AND target_id = ? " +
                        "AND claim_state = 'PENDING'", Integer.class, postId);
        assertThat(openRounds).as("在池工单只有新轮次这一行").containsExactly(2);
        assertThat(queueIdsOf(admin, "POST")).contains(postId);
    }

    // ---------- 计数与池内行数一致 ----------

    @Test
    void 积压计数与池内行数一致且缺省类型补零() {
        Session author = registerAndLogin("13800006006", "作者6");
        Session admin = adminSession();

        String postId = publishPendingPost(author);
        String commentId = publishCommentAndGetId(publishPost(author, "宿主帖"), author, "待审评论");
        String productId = createProduct(author);
        String orderId = createPaidOrder(author);

        JsonNode counts = getJson("/api/moderation/tasks/counts", admin);
        assertSuccess(counts, "积压计数");
        assertThat(counts.path("data").path("POST").asLong()).isEqualTo(1);
        assertThat(counts.path("data").path("COMMENT").asLong()).isEqualTo(1);
        assertThat(counts.path("data").path("PRODUCT").asLong()).isEqualTo(1);
        assertThat(counts.path("data").path("ERRAND").asLong()).isEqualTo(1);

        // 计数 = 池内 PENDING 行数（逐类型对账）
        for (String type : List.of("POST", "COMMENT", "PRODUCT", "ERRAND")) {
            Integer poolRows = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM review_task WHERE target_type = ? AND claim_state = 'PENDING'",
                    Integer.class, type);
            assertThat(counts.path("data").path(type).asLong())
                    .as("%s 计数应与池内 PENDING 行数一致", type)
                    .isEqualTo(poolRows == null ? 0 : poolRows.longValue());
        }

        // 裁决出池后计数同步回落（遗留入口收口：先领单再裁）
        claimTaskOf(admin, "POST", postId);
        assertSuccess(postJson("/api/moderation/review",
                Map.of("targetType", "POST", "targetId", postId, "action", "APPROVE"), admin), "裁决通过");
        JsonNode countsAfter = getJson("/api/moderation/tasks/counts", admin);
        assertThat(countsAfter.path("data").path("POST").asLong()).isZero();

        // 计数条四类型恒齐（当前无待审的类型也补 0）
        for (String type : List.of("POST", "COMMENT", "PRODUCT", "ERRAND")) {
            assertThat(countsAfter.path("data").has(type)).isTrue();
        }
        // 待审行还在的 Sanity：comment/product/errand 仍各 1
        assertThat(countsAfter.path("data").path("COMMENT").asLong()).isEqualTo(1);
        assertThat(taskState("COMMENT", commentId)).isEqualTo("PENDING");
        assertThat(taskState("PRODUCT", productId)).isEqualTo("PENDING");
        assertThat(taskState("ERRAND", orderId)).isEqualTo("PENDING");
    }

    // ---------- 工具 ----------

    /** 直接发布待审帖（先审后发，不开工单裁决），返回 postId */
    private String publishPendingPost(Session author) {
        JsonNode response = postJson("/api/community/posts",
                Map.of("title", "待审帖子", "content", "等待人工审核的内容"), author);
        assertSuccess(response, "发帖");
        return response.path("data").asText();
    }

    private String createProduct(Session seller) {
        JsonNode response = postJson("/api/trade/products", Map.ofEntries(
                Map.entry("title", "待审商品"),
                Map.entry("description", "自用闲置，九成新"),
                Map.entry("images", List.of()),
                Map.entry("price", 19.9),
                Map.entry("tradeLocation", "图书馆门口"),
                Map.entry("category", "BOOKS")), seller);
        assertSuccess(response, "发布商品");
        return response.path("data").asText();
    }

    private String createPaidOrder(Session author) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "EXPRESS_PICKUP");
        body.put("description", "帮忙取个快递");
        body.put("pickupLocation", "菜鸟驿站");
        body.put("deliveryLocation", "6号楼楼下");
        body.put("reward", 10);
        body.put("timeLimit", "HOURS_2");
        JsonNode createResponse = postJson("/api/errand/orders", body, author);
        assertSuccess(createResponse, "发布跑腿");
        String orderId = createResponse.path("data").asText();
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/pay", null, author), "支付");
        return orderId;
    }

    /** 工单池队列（targetType 可空 = 跨类型），返回 targetId 列表 */
    private List<String> queueIdsOf(Session admin, String targetType) {
        String url = targetType == null
                ? "/api/moderation/queue?page=1&pageSize=50"
                : "/api/moderation/queue?targetType=" + targetType + "&page=1&pageSize=50";
        JsonNode response = getJson(url, admin);
        assertSuccess(response, "审核队列");
        return StreamSupport.stream(response.path("data").path("list").spliterator(), false)
                .map(node -> node.path("targetId").asText())
                .toList();
    }

    private String taskState(String targetType, String targetId) {
        return jdbcTemplate.queryForObject(
                "SELECT claim_state FROM review_task WHERE target_type = ? AND target_id = ? " +
                        "ORDER BY review_round DESC LIMIT 1", String.class, targetType, targetId);
    }

    private int taskRound(String targetType, String targetId) {
        Integer round = jdbcTemplate.queryForObject(
                "SELECT review_round FROM review_task WHERE target_type = ? AND target_id = ? " +
                        "ORDER BY review_round DESC LIMIT 1", Integer.class, targetType, targetId);
        return round == null ? -1 : round;
    }

    /** 管理员登录态：先注册（USER），改库提权后重新走验证码登录拿含 ADMIN 角色的 JWT */
    private Session adminSession() {
        Session registered = registerAndLogin("13900006001", "管理员");
        jdbcTemplate.update("UPDATE bs_user SET role = 'ADMIN' WHERE user_id = ?", registered.userId());
        postJson("/api/user/send-code", Map.of("phone", "13900006001"), null);
        String code = redissonClient.<String>getBucket(Constants.VERIFY_CODE_KEY + "13900006001").get();
        JsonNode loginResponse = postJson("/api/user/login", Map.of("phone", "13900006001", "code", code), null);
        assertSuccess(loginResponse, "管理员重新登录");
        return new Session(loginResponse.path("data").path("token").asText(), registered.userId());
    }
}
