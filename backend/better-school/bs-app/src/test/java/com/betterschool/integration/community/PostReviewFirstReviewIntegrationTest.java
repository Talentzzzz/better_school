package com.betterschool.integration.community;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 帖子审核新语义（票 01）：先审后发——同步初筛命中报错不落库；干净帖子落库即 PENDING_REVIEW 进统一人工裁决；
 * 通过才对外可见并落搜索同步消息；拒绝为终态、理由必填、不进搜索；审核记录按目标+动作+轮次幂等
 * （POST 轮次取业务表 post.review_round）。
 */
@DisplayName("帖子先审后发：初筛报错 + 落库即待审 + 统一裁决 + 轮次幂等")
class PostReviewFirstReviewIntegrationTest extends CommunityApiTestBase {

    /** schema.sql 种子敏感词 */
    private static final String SENSITIVE_CONTENT = "帮我作弊一下";

    @Test
    @DisplayName("发帖命中敏感词：报错提示修改，内容不落库")
    void publish_hitSensitiveWord_errorAndNotSaved() {
        Session author = registerAndLogin("13800001001", "发帖人");

        JsonNode response = postJson("/api/community/posts",
                Map.of("title", "求助帖", "content", SENSITIVE_CONTENT), author);

        assertThat(response.path("code").asText()).as("初筛命中应报错：%s", response).isEqualTo("500");
        assertThat(response.path("info").asText()).as("报错应引导修改").contains("内容包含违规词汇，请修改后重试");
        Integer postRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM post WHERE user_id = ?", Integer.class, author.userId());
        assertThat(postRows).as("命中帖不应落库").isZero();
    }

    @Test
    @DisplayName("发布落库即待审：轮次 1，作者可见自己的待审帖，他人与访客不可见，进审核队列")
    void publish_cleanPost_pendingReview_visibleOnlyToAuthor() {
        Session author = registerAndLogin("13800001002", "发帖人");
        Session other = registerAndLogin("13800001003", "路人");
        String postId = publishPendingPost(author);

        assertThat(postRoundOf(postId)).as("直接发布轮次应为 1").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT review_entered_at FROM post WHERE post_id = ?", Timestamp.class, postId))
                .as("进入待审锚点应在发布时落库").isNotNull();

        // 作者可见自己的待审帖
        JsonNode ownerDetail = getJson("/api/community/posts/" + postId, author);
        assertSuccess(ownerDetail, "作者看自己的待审帖");
        JsonNode mine = getJson("/api/community/posts/mine?page=1&pageSize=10", author);
        assertThat(postIdsOf(mine)).contains(postId);

        // 他人与访客不可见：详情 404，feed 不出
        assertThat(getJson("/api/community/posts/" + postId, other).path("code").asText())
                .as("他人看待审帖应 404").isEqualTo("404");
        assertThat(getJson("/api/community/posts/" + postId, null).path("code").asText())
                .as("访客看待审帖应 404").isEqualTo("404");
        assertThat(feedPostIds()).as("待审帖不应出现在 feed").doesNotContain(postId);

        // 上搜索同步消息：待审帖不进索引（发布不落同步消息）
        assertThat(searchSyncIdsOf(postId)).as("待审帖不应落搜索同步消息").isEmpty();

        // 审核队列带出（轮次取业务表 post.review_round，裁决落审核记录时按同一口径，见通过/拒绝用例）
        JsonNode queue = getJson("/api/moderation/queue?targetType=POST&page=1&pageSize=10", adminSession());
        assertSuccess(queue, "帖子审核队列");
        assertThat(queueItems(queue).stream().map(node -> node.path("targetId").asText()))
                .as("待审帖应进队列").contains(postId);
    }

    @Test
    @DisplayName("裁决通过：转 NORMAL 对外可见，落搜索同步消息，作者收通知，队列移出")
    void decide_approve_visibleAndSearchSynced() {
        Session author = registerAndLogin("13800001004", "发帖人");
        String postId = publishPendingPost(author);
        Session admin = adminSession();

        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "POST", "targetId", postId, "approve", true), admin), "帖子裁决通过");

        assertThat(postStatusOf(postId)).isEqualTo("NORMAL");
        assertSuccess(getJson("/api/community/posts/" + postId, null), "访客看通过的帖");
        assertThat(feedPostIds()).as("通过的帖应出现在 feed").contains(postId);

        List<String> syncPayloads = jdbcTemplate.queryForList(
                "SELECT payload FROM outbox_message WHERE destination = ?", String.class, Constants.SEARCH_SYNC_TOPIC);
        assertThat(syncPayloads.stream().map(this::readTree)
                .anyMatch(node -> "post".equals(node.path("type").asText())
                        && postId.equals(node.path("id").asText())))
                .as("通过应落搜索同步消息（消费方按 NORMAL upsert）").isTrue();

        List<String> contents = jdbcTemplate.queryForList(
                "SELECT content FROM notification WHERE user_id = ? AND type = 'AUDIT_RESULT'",
                String.class, author.userId());
        assertThat(contents).as("作者应收到通过通知").anyMatch(content -> content.contains("通过审核"));

        JsonNode queueAfter = getJson("/api/moderation/queue?targetType=POST&page=1&pageSize=10", admin);
        assertThat(queueItems(queueAfter).stream().map(node -> node.path("targetId").asText()))
                .as("已裁决帖应移出队列").doesNotContain(postId);

        Integer approveRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_review WHERE target_type = 'POST' AND target_id = ? AND action = 'APPROVE'",
                Integer.class, postId);
        assertThat(approveRows).as("审核记录只插一行").isEqualTo(1);
        Integer approveRound = jdbcTemplate.queryForObject(
                "SELECT review_round FROM content_review WHERE target_type = 'POST' AND target_id = ? AND action = 'APPROVE'",
                Integer.class, postId);
        assertThat(approveRound).as("审核记录轮次应取业务表轮次").isEqualTo(1);
    }

    @Test
    @DisplayName("裁决拒绝：理由必填，REJECTED 终态仅作者可见，落删除口径同步消息，通知含理由")
    void decide_reject_requiresReason_terminalNotSearchable() {
        Session author = registerAndLogin("13800001005", "发帖人");
        Session other = registerAndLogin("13800001006", "路人");
        String postId = publishPendingPost(author);
        Session admin = adminSession();

        JsonNode missingReason = postJson("/api/admin/reviews/decide",
                Map.of("targetType", "POST", "targetId", postId, "approve", false), admin);
        assertThat(missingReason.path("code").asText()).as("拒绝理由必填：%s", missingReason).isEqualTo("400");
        assertThat(postStatusOf(postId)).as("裁决未生效").isEqualTo("PENDING_REVIEW");

        String reason = "标题与内容不符";
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "POST", "targetId", postId, "approve", false, "reasonCode", "OTHER", "supplement", reason), admin), "帖子裁决拒绝");

        assertThat(postStatusOf(postId)).as("拒绝应为终态").isEqualTo("REJECTED");
        String reviewReason = jdbcTemplate.queryForObject(
                "SELECT reason FROM content_review WHERE target_type = 'POST' AND target_id = ? AND action = 'REJECT'",
                String.class, postId);
        assertThat(reviewReason).as("拒绝理由落审核记录").isEqualTo("其他：" + reason);

        // 作者可见被拒帖，他人与访客不可见，feed 不出
        assertSuccess(getJson("/api/community/posts/" + postId, author), "作者看被拒帖");
        assertThat(getJson("/api/community/posts/" + postId, other).path("code").asText())
                .as("他人看被拒帖应 404").isEqualTo("404");
        assertThat(feedPostIds()).as("被拒帖不应出现在 feed").doesNotContain(postId);

        List<String> syncPayloads = jdbcTemplate.queryForList(
                "SELECT payload FROM outbox_message WHERE destination = ?", String.class, Constants.SEARCH_SYNC_TOPIC);
        assertThat(syncPayloads.stream().map(this::readTree)
                .anyMatch(node -> "post".equals(node.path("type").asText())
                        && postId.equals(node.path("id").asText())))
                .as("拒绝应落同步消息（消费方按非 NORMAL 删文档，被拒帖不进搜索）").isTrue();

        List<String> contents = jdbcTemplate.queryForList(
                "SELECT content FROM notification WHERE user_id = ? AND type = 'AUDIT_RESULT'",
                String.class, author.userId());
        assertThat(contents).as("拒绝通知应含理由").anyMatch(content -> content.contains(reason));
    }

    @Test
    @DisplayName("同轮重复裁决幂等：重复通过/重复拒绝各只留一条审核记录，通过后不可改判拒绝")
    void decide_repeat_sameRoundIdempotent() {
        Session author = registerAndLogin("13800001007", "发帖人");
        String approvedPostId = publishPendingPost(author);
        String rejectedPostId = publishPendingPost(author);
        Session admin = adminSession();

        // 重复通过：终态幂等静默成功，审核记录不重复
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "POST", "targetId", approvedPostId, "approve", true), admin), "首次通过");
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "POST", "targetId", approvedPostId, "approve", true), admin), "重复通过");
        assertThat(countReviews(approvedPostId, "APPROVE")).isEqualTo(1);
        assertThat(postStatusOf(approvedPostId)).isEqualTo("NORMAL");

        // 已 NORMAL 改判拒绝：被状态闸门拦下，不得对外不可见
        JsonNode flipReject = postJson("/api/admin/reviews/decide",
                Map.of("targetType", "POST", "targetId", approvedPostId, "approve", false, "reasonCode", "OTHER", "supplement", "改判"), admin);
        assertThat(flipReject.path("code").asText()).as("通过后不可改判拒绝：%s", flipReject).isEqualTo("500");
        assertThat(postStatusOf(approvedPostId)).isEqualTo("NORMAL");

        // 重复拒绝：同样幂等，一条 REJECT 记录
        String reason = "内容不合规";
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "POST", "targetId", rejectedPostId, "approve", false, "reasonCode", "OTHER", "supplement", reason), admin), "首次拒绝");
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "POST", "targetId", rejectedPostId, "approve", false, "reasonCode", "OTHER", "supplement", reason), admin), "重复拒绝");
        assertThat(countReviews(rejectedPostId, "REJECT")).isEqualTo(1);
        assertThat(postStatusOf(rejectedPostId)).isEqualTo("REJECTED");
    }

    // ---------- 工具 ----------

    /** 干净内容发帖（先审后发：落库即待审），返回 postId */
    private String publishPendingPost(Session author) {
        JsonNode response = postJson("/api/community/posts",
                Map.of("title", "测试帖子", "content", "正常帖子内容，等待人工审核"), author);
        assertSuccess(response, "发帖");
        String postId = response.path("data").asText();
        assertThat(postStatusOf(postId)).as("先审后发：落库即待审").isEqualTo("PENDING_REVIEW");
        return postId;
    }

    private Session adminSession() {
        Session registered = registerAndLogin("13900001001", "管理员");
        jdbcTemplate.update("UPDATE bs_user SET role = 'ADMIN' WHERE user_id = ?", registered.userId());
        postJson("/api/user/send-code", Map.of("phone", "13900001001"), null);
        String code = redissonClient.<String>getBucket(Constants.VERIFY_CODE_KEY + "13900001001").get();
        JsonNode loginResponse = postJson("/api/user/login", Map.of("phone", "13900001001", "code", code), null);
        assertSuccess(loginResponse, "管理员重新登录");
        return new Session(loginResponse.path("data").path("token").asText(), registered.userId());
    }

    private String postStatusOf(String postId) {
        return jdbcTemplate.queryForObject("SELECT state FROM post WHERE post_id = ?", String.class, postId);
    }

    private int postRoundOf(String postId) {
        Integer round = jdbcTemplate.queryForObject(
                "SELECT review_round FROM post WHERE post_id = ?", Integer.class, postId);
        assertThat(round).as("帖子 %s 的审核轮次", postId).isNotNull();
        return round;
    }

    private int countReviews(String postId, String action) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_review WHERE target_type = 'POST' AND target_id = ? AND action = ?",
                Integer.class, postId, action);
        return count == null ? 0 : count;
    }

    private List<String> feedPostIds() {
        JsonNode response = getJson("/api/community/posts?page=1&pageSize=50", null);
        assertSuccess(response, "feed 列表");
        return postIdsOf(response);
    }

    private List<String> postIdsOf(JsonNode response) {
        return StreamSupport.stream(response.path("data").path("list").spliterator(), false)
                .map(node -> node.path("postId").asText()).toList();
    }

    private List<JsonNode> queueItems(JsonNode queueResponse) {
        return StreamSupport.stream(queueResponse.path("data").path("list").spliterator(), false).toList();
    }

    private List<String> searchSyncIdsOf(String postId) {
        return jdbcTemplate.queryForList(
                        "SELECT payload FROM outbox_message WHERE destination = ?", String.class, Constants.SEARCH_SYNC_TOPIC)
                .stream().map(this::readTree)
                .filter(node -> "post".equals(node.path("type").asText()) && postId.equals(node.path("id").asText()))
                .map(node -> node.path("id").asText()).toList();
    }
}
