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
 * 理由口径全站枚举化：人工拒绝 reasonCode 必填且为合法枚举 code（非法 400 拦截），
 * 落库统一"枚举文案：补充说明"格式（无补充为纯枚举文案，超长截断 ≤200 受 reason varchar(200) 约束）；
 * 作者端 rejectReason 展示行为不变（仍读最新 REJECT 记录）；通过操作不受影响（理由可空）。
 */
@DisplayName("拒绝理由枚举化：非法 code 拦截 + 落库\"枚举文案：补充说明\" + 通过不受影响")
class RejectReasonEnumIntegrationTest extends CommunityApiTestBase {

    @Test
    @DisplayName("非法 reason_code 拒绝：400 拦截不生效，不落审核记录")
    void reject_illegalReasonCode_blocked() {
        Session author = registerAndLogin("13800002001", "发帖人");
        String postId = publishPendingPost(author);
        Session admin = adminSession();

        JsonNode illegal = postJson("/api/admin/reviews/decide",
                Map.of("targetType", "POST", "targetId", postId, "approve", false, "reasonCode", "DIRTY_WORD"), admin);
        assertThat(illegal.path("code").asText()).as("非法枚举 code 应被拦截：%s", illegal).isEqualTo("400");
        assertThat(postStatusOf(postId)).as("裁决未生效").isEqualTo("PENDING_REVIEW");
        assertThat(countRejectReviews(postId)).as("不应落拒绝审核记录").isZero();
    }

    @Test
    @DisplayName("非法 reason_code 走审核员入口 + COMMENT 类型：同样 400 拦截（校验收口在统一用例）")
    void reject_illegalReasonCode_moderationEntryAndComment_blocked() {
        Session author = registerAndLogin("13800002006", "发帖人");
        Session commenter = registerAndLogin("13800002007", "评论人");
        String postId = publishPendingPost(author);
        Session admin = adminSession();
        // 帖子先裁决通过才可评论
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "POST", "targetId", postId, "approve", true), admin), "帖子裁决通过");
        JsonNode comment = postJson("/api/community/posts/" + postId + "/comments",
                Map.of("content", "待审评论内容"), commenter);
        assertSuccess(comment, "发布评论");
        String commentId = comment.path("data").path("commentId").asText();

        // 遗留审核入口已收口（票 09）：先领单才能裁——非法 code 在领单后到达统一用例被 400 拦截
        claimTaskOf(admin, "COMMENT", commentId);
        JsonNode illegal = postJson("/api/moderation/review",
                Map.of("targetType", "COMMENT", "targetId", commentId, "action", "REJECT", "reasonCode", "SPAM"), admin);
        assertThat(illegal.path("code").asText()).as("审核员入口非法 code 同样拦截：%s", illegal).isEqualTo("400");
        assertThat(commentStatusOf(commentId)).as("裁决未生效").isEqualTo("PENDING_REVIEW");
        Integer rejectRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_review WHERE target_type = 'COMMENT' AND target_id = ? AND action = 'REJECT'",
                Integer.class, commentId);
        assertThat(rejectRows).as("不应落拒绝审核记录").isZero();
    }

    @Test
    @DisplayName("枚举 + 补充说明：落库\"枚举文案：补充说明\"，作者端展示与通知一致")
    void reject_enumWithSupplement_storedFormatted() {
        Session author = registerAndLogin("13800002002", "发帖人");
        String postId = publishPendingPost(author);
        Session admin = adminSession();

        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "POST", "targetId", postId, "approve", false,
                        "reasonCode", "AD_PROMOTION", "supplement", "含校外兼职导流二维码"), admin), "帖子裁决拒绝");

        assertThat(postStatusOf(postId)).isEqualTo("REJECTED");
        assertThat(latestRejectReason(postId)).as("落库为枚举文案+补充说明")
                .isEqualTo("广告导流：含校外兼职导流二维码");
        JsonNode ownerDetail = getJson("/api/community/posts/" + postId, author);
        assertThat(ownerDetail.path("data").path("rejectReason").asText())
                .as("作者端展示行为不变（透传审核记录 reason）").isEqualTo("广告导流：含校外兼职导流二维码");
        List<String> contents = jdbcTemplate.queryForList(
                "SELECT content FROM notification WHERE user_id = ? AND type = 'AUDIT_RESULT'",
                String.class, author.userId());
        assertThat(contents).as("拒绝通知含落库理由").anyMatch(content -> content.contains("广告导流：含校外兼职导流二维码"));
    }

    @Test
    @DisplayName("仅枚举无补充说明：落库纯枚举文案")
    void reject_enumOnly_storedAsLabel() {
        Session author = registerAndLogin("13800002003", "发帖人");
        String postId = publishPendingPost(author);
        Session admin = adminSession();

        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "POST", "targetId", postId, "approve", false, "reasonCode", "PORNOGRAPHY"), admin),
                "帖子裁决拒绝");

        assertThat(latestRejectReason(postId)).as("无补充落库纯枚举文案").isEqualTo("色情低俗");
    }

    @Test
    @DisplayName("补充说明超长：整体截断 ≤200 字不报错")
    void reject_overlongSupplement_truncatedTo200() {
        Session author = registerAndLogin("13800002004", "发帖人");
        String postId = publishPendingPost(author);
        Session admin = adminSession();

        String overlong = "长".repeat(300);
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "POST", "targetId", postId, "approve", false,
                        "reasonCode", "OTHER", "supplement", overlong), admin), "帖子裁决拒绝");

        String reason = latestRejectReason(postId);
        assertThat(reason.length()).as("受 reason varchar(200) 约束").isLessThanOrEqualTo(200);
        assertThat(reason).as("保留枚举文案与截断后的补充说明").startsWith("其他：长");
    }

    @Test
    @DisplayName("通过不受影响：reasonCode 可空，审核记录照常落")
    void approve_withoutReasonCode_unaffected() {
        Session author = registerAndLogin("13800002005", "发帖人");
        String postId = publishPendingPost(author);
        Session admin = adminSession();

        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "POST", "targetId", postId, "approve", true), admin), "帖子裁决通过");

        assertThat(postStatusOf(postId)).isEqualTo("NORMAL");
        Integer approveRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_review WHERE target_type = 'POST' AND target_id = ? AND action = 'APPROVE'",
                Integer.class, postId);
        assertThat(approveRows).as("审核记录只插一行").isEqualTo(1);
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

    private String latestRejectReason(String postId) {
        return jdbcTemplate.queryForObject(
                "SELECT reason FROM content_review WHERE target_type = 'POST' AND target_id = ? AND action = 'REJECT'",
                String.class, postId);
    }

    private int countRejectReviews(String postId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_review WHERE target_type = 'POST' AND target_id = ? AND action = 'REJECT'",
                Integer.class, postId);
        return count == null ? 0 : count;
    }
}
