package com.betterschool.integration.community;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评论审核人工化（issue 03 / ADR-0004）：发布同步初筛命中报错不落库；通过初筛落库即待审、作者可见他人不可见；
 * MQ 消费者降级为"通知审核员有评论待审"（不再裁决）；人工裁决通过才计数/对外可见/发互动通知，拒绝为终态、理由必填、
 * 作者本人可见并带理由。countComment 仅在人工通过时 +1。
 */
@DisplayName("评论审核人工化：同步初筛报错 + 落库即待审 + 人工裁决计数/通知 + 拒绝理由必填可见")
class CommentReviewHumanizedIntegrationTest extends CommunityApiTestBase {

    private static final String SENSITIVE_CONTENT = "帮我作弊一下";

    @Test
    @DisplayName("初筛命中敏感词：报错引导修改，评论不落库、不产生审核 outbox 消息")
    void publish_hitSensitiveWord_errorAndNotSaved() {
        Session owner = registerAndLogin("13800000001", "帖主");
        Session commenter = registerAndLogin("13800000002", "评论人");
        String postId = publishPost(owner, "正常帖子内容，欢迎讨论");

        JsonNode response = publishComment(postId, commenter, SENSITIVE_CONTENT);

        assertThat(response.path("code").asText()).as("初筛命中应报错：%s", response).isEqualTo("500");
        assertThat(response.path("info").asText()).as("报错应引导修改").contains("内容包含违规词汇，请修改后重试");
        assertThat(commentRowCountOf(postId)).as("命中评论不应落库").isZero();
        Integer outboxRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_message WHERE destination = ? AND state = 0",
                Integer.class, Constants.COMMENT_REVIEW_TOPIC);
        assertThat(outboxRows).as("初筛命中不产生审核消息").isZero();
        assertThat(listCommentsAsGuest(postId, 1, 10).path("total").asLong()).isZero();
    }

    @Test
    @DisplayName("过初筛落库即待审：作者可见、他人与访客不可见，同事务落审核 outbox 消息")
    void publish_clean_pendingReview_visibleOnlyToAuthor() {
        Session owner = registerAndLogin("13800000001", "帖主");
        Session commenter = registerAndLogin("13800000002", "评论人");
        String postId = publishPost(owner, "正常帖子内容，欢迎讨论");

        String commentId = publishCommentAndGetId(postId, commenter, "第一楼");

        assertThat(commentStatusOf(commentId)).isEqualTo("PENDING_REVIEW");
        // 同事务落一条待发审核消息（MQ 消费方据此通知审核员）
        Integer outboxRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_message WHERE destination = ? AND state = 0",
                Integer.class, Constants.COMMENT_REVIEW_TOPIC);
        assertThat(outboxRows).isEqualTo(1);

        // 作者本人可见自己的待审根评论（默认档 = id 序；最热档只收已过审根评论、不自见，见 CommentFeedIndexIntegrationTest）
        JsonNode authorView = getJson("/api/community/posts/" + postId + "/comments?page=1&pageSize=10&sort=new", commenter);
        assertThat(commentIdsOf(authorView.path("data"))).containsExactly(commentId);

        // 他人与访客不可见：total 0
        assertThat(listCommentsAsGuest(postId, 1, 10).path("total").asLong()).isZero();
        JsonNode otherView = getJson("/api/community/posts/" + postId + "/comments?page=1&pageSize=10", owner);
        assertThat(otherView.path("data").path("list").size()).isZero();
    }

    @Test
    @DisplayName("MQ 消费者降级：不裁决，只给审核员发待审提醒；重复消费不重复提醒")
    void consumer_onlyNotifiesReviewers_doesNotAdjudicate() {
        Session owner = registerAndLogin("13800000001", "帖主");
        Session commenter = registerAndLogin("13800000002", "评论人");
        Session admin = admin();
        String postId = publishPost(owner, "正常帖子内容，欢迎讨论");
        String commentId = publishCommentAndGetId(postId, commenter, "第一楼");

        driveCommentConsumer(commentId);
        driveCommentConsumer(commentId); // 重复消费不重复提醒

        assertThat(commentStatusOf(commentId)).as("消费方不得改状态").isEqualTo("PENDING_REVIEW");
        assertThat(notificationCountOf(admin.userId(), "AUDIT_PENDING")).as("审核员收一条待审提醒").isEqualTo(1);
        assertThat(notificationCountOf(commenter.userId(), "AUDIT_PENDING")).isZero();
    }

    @Test
    @DisplayName("人工裁决通过（根评论）：NORMAL、他人可见、评论总数 +1、缓存命中路径读到新计数、帖主收帖被评")
    void decide_approve_rootComment_countsAndNotifies() {
        Session owner = registerAndLogin("13800000001", "帖主");
        Session commenter = registerAndLogin("13800000002", "评论人");
        Session admin = admin();
        String postId = publishPost(owner, "正常帖子内容，欢迎讨论");
        String commentId = publishCommentAndGetId(postId, commenter, "第一楼");

        // 预热详情缓存（票 05 后计数不进缓存、审核计数动作也不再失效它），通过后缓存命中路径必须读到新计数
        getJson("/api/community/posts/" + postId, null);
        assertThat(detailCacheBucket(postId).isExists()).as("预热后详情缓存应存在").isTrue();

        approve(admin, commentId);

        assertThat(commentStatusOf(commentId)).isEqualTo("NORMAL");
        assertThat(listCommentsAsGuest(postId, 1, 10).path("total").asLong()).isEqualTo(1);
        assertThat(postCommentCountOf(postId)).isEqualTo(1);
        assertThat(getJson("/api/community/posts/" + postId, null).path("data").path("commentCount").asLong())
                .as("缓存命中路径的计数每次回库拼装，恒等于库值（票 05 / C5）").isEqualTo(1);

        // 帖被评 → 通知帖主；评论作者自评自身的不发
        assertThat(notificationCountOf(owner.userId(), "POST_COMMENTED")).isEqualTo(1);
        assertThat(notificationCountOf(commenter.userId(), "POST_COMMENTED")).isZero();
    }

    @Test
    @DisplayName("人工裁决通过（子回复）：评被回通知 parent 作者、帖被评通知帖主，自回不发")
    void decide_approve_reply_sendsRepliedNotification() {
        Session owner = registerAndLogin("13800000001", "帖主");
        Session bob = registerAndLogin("13800000002", "小北");
        Session carl = registerAndLogin("13800000003", "小南");
        Session admin = admin();
        String postId = publishPost(owner, "正常帖子内容，欢迎讨论");
        String rootId = publishCommentAndGetId(postId, bob, "第一楼");
        approve(admin, rootId);

        // 自回：评论作者回复自己，不发评被回
        String selfReplyId = postJson("/api/community/comments/" + rootId + "/replies",
                Map.of("content", "自问自答"), bob).path("data").path("commentId").asText();
        approve(admin, selfReplyId);

        // 他人回复：parent 作者收评被回
        String carlReplyId = postJson("/api/community/comments/" + rootId + "/replies",
                Map.of("content", "赞同"), carl).path("data").path("commentId").asText();
        approve(admin, carlReplyId);

        assertThat(postCommentCountOf(postId)).as("根 1 + 两回复").isEqualTo(3);
        assertThat(notificationCountOf(owner.userId(), "POST_COMMENTED")).as("帖主收帖被评（根+两回复）").isEqualTo(3);
        assertThat(notificationCountOf(bob.userId(), "COMMENT_REPLIED")).as("小北只收小南的评被回").isEqualTo(1);
        assertThat(notificationCountOf(bob.userId(), "POST_COMMENTED")).isZero();
        assertThat(notificationCountOf(carl.userId(), "COMMENT_REPLIED")).isZero();
    }

    @Test
    @DisplayName("人工裁决拒绝：理由必填（缺省 400 不生效）；终态仅作者可见且带理由，无计数无互动通知")
    void decide_reject_requiresReason_visibleToAuthorWithReason() {
        Session owner = registerAndLogin("13800000001", "帖主");
        Session commenter = registerAndLogin("13800000002", "评论人");
        Session admin = admin();
        String postId = publishPost(owner, "正常帖子内容，欢迎讨论");
        String commentId = publishCommentAndGetId(postId, commenter, "攻击性内容");

        JsonNode missingReason = postJson("/api/admin/reviews/decide",
                Map.of("targetType", "COMMENT", "targetId", commentId, "approve", false), admin);
        assertThat(missingReason.path("code").asText()).as("拒绝理由必填：%s", missingReason).isEqualTo("400");
        assertThat(commentStatusOf(commentId)).as("缺理由裁决不生效").isEqualTo("PENDING_REVIEW");

        String reason = "涉及辱骂，违反社区规范";
        reject(admin, commentId, reason);

        assertThat(commentStatusOf(commentId)).as("拒绝应为终态").isEqualTo("REJECTED");

        // 作者本人可见被拒评论且带拒绝理由（默认档 = id 序；最热档不自见，见 CommentFeedIndexIntegrationTest）；
        // 他人与访客不可见、total 0、不计数
        JsonNode authorView = getJson("/api/community/posts/" + postId + "/comments?page=1&pageSize=10&sort=new", commenter);
        JsonNode mine = authorView.path("data").path("list").get(0);
        assertThat(mine.path("commentId").asText()).isEqualTo(commentId);
        assertThat(mine.path("rejectReason").asText()).as("被拒评论向作者带出理由").isEqualTo("其他：" + reason);
        assertThat(listCommentsAsGuest(postId, 1, 10).path("total").asLong()).isZero();
        assertThat(postCommentCountOf(postId)).isZero();

        // 作者收一条含理由的审核结果通知；不发互动通知
        List<String> contents = jdbcTemplate.queryForList(
                "SELECT content FROM notification WHERE user_id = ? AND type = 'AUDIT_RESULT'",
                String.class, commenter.userId());
        assertThat(contents).anyMatch(content -> content.contains(reason));
        assertThat(notificationCountOf(owner.userId(), "POST_COMMENTED")).isZero();
    }

    @Test
    @DisplayName("重复裁决幂等：重复通过只计一次数、只发一条互动通知；通过后不可改判拒绝")
    void decide_repeat_sameRoundIdempotent() {
        Session owner = registerAndLogin("13800000001", "帖主");
        Session commenter = registerAndLogin("13800000002", "评论人");
        Session admin = admin();
        String postId = publishPost(owner, "正常帖子内容，欢迎讨论");
        String commentId = publishCommentAndGetId(postId, commenter, "第一楼");

        approve(admin, commentId);
        approve(admin, commentId);

        assertThat(postCommentCountOf(postId)).as("计数只加一次").isEqualTo(1);
        assertThat(notificationCountOf(owner.userId(), "POST_COMMENTED")).as("互动通知只发一条").isEqualTo(1);
        assertThat(listCommentsAsGuest(postId, 1, 10).path("total").asLong()).isEqualTo(1);

        // NORMAL 改判拒绝：被状态闸门拦下
        JsonNode flip = postJson("/api/admin/reviews/decide",
                Map.of("targetType", "COMMENT", "targetId", commentId, "approve", false, "reasonCode", "OTHER", "supplement", "改判"), admin);
        assertThat(flip.path("code").asText()).as("通过后不可改判拒绝").isEqualTo("500");
        assertThat(commentStatusOf(commentId)).isEqualTo("NORMAL");
    }

    // ---------- 本测试类用例构造 ----------

    /** 管理员登录态：升级角色后重新走验证码登录，拿含 ADMIN 角色的 token */
    private Session admin() {
        Session registered = registerAndLogin("13900001001", "管理员");
        jdbcTemplate.update("UPDATE bs_user SET role = 'ADMIN' WHERE user_id = ?", registered.userId());
        postJson("/api/user/send-code", Map.of("phone", "13900001001"), null);
        String code = redissonClient.<String>getBucket(Constants.VERIFY_CODE_KEY + "13900001001").get();
        JsonNode loginResponse = postJson("/api/user/login", Map.of("phone", "13900001001", "code", code), null);
        assertSuccess(loginResponse, "管理员重新登录");
        return new Session(loginResponse.path("data").path("token").asText(), registered.userId());
    }

    private void approve(Session admin, String commentId) {
        JsonNode response = postJson("/api/admin/reviews/decide",
                Map.of("targetType", "COMMENT", "targetId", commentId, "approve", true), admin);
        assertSuccess(response, "裁决通过评论 " + commentId);
    }

    /** 直接驱动评论审核消费者（不等 outbox 扫描定时器）：按 commentId 取待发消息投喂消费者入口 */
    private void driveCommentConsumer(String commentId) {
        List<String> payloads = jdbcTemplate.queryForList(
                "SELECT payload FROM outbox_message WHERE destination = ? AND state = 0 ORDER BY id",
                String.class, Constants.COMMENT_REVIEW_TOPIC);
        for (String payload : payloads) {
            if (commentId.equals(readTree(payload).path("commentId").asText())) {
                commentReviewConsumer.onMessage(payload);
                return;
            }
        }
        throw new IllegalStateException("评论 " + commentId + " 的审核 outbox 消息不存在");
    }

    private void reject(Session admin, String commentId, String reason) {
        JsonNode response = postJson("/api/admin/reviews/decide",
                Map.of("targetType", "COMMENT", "targetId", commentId, "approve", false, "reasonCode", "OTHER", "supplement", reason), admin);
        assertSuccess(response, "裁决拒绝评论 " + commentId);
    }

    private long notificationCountOf(String userId, String type) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE user_id = ? AND type = ?", Long.class, userId, type);
        return count == null ? 0 : count;
    }
}
