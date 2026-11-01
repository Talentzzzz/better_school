package com.betterschool.integration.community;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 删除语义统一（v2 票 07）：用户删除与举报下架同轨软删（DELETED），物理 DELETE 废弃。
 *
 * <p>删根 = 整楼对所有人消失：根转 DELETED、子回复行不动、同事务扣减 1 + 楼内可见子回复数；
 * 删子回复 = 仅隐藏该条：reply_count -1、总数 -1；删审核中的根评论计数不动（本就未计入）。
 * 三条路径（用户删除 / 举报下架 / 审核拒绝）在软删语义上一致：行保留、列表不可见、计数按规则。
 */
@DisplayName("删除语义统一：软删 / 计数规则 / 指向昵称保留 / 三路径一致")
class CommentDeleteSemanticsIntegrationTest extends CommunityApiTestBase {

    @Test
    @DisplayName("删根评论 = 整楼消失：楼内列表不可达、计数扣 1+楼内可见数、子回复行不动")
    void deleteRootComment_wholeThreadHidden_countsDeducted() {
        Session rootAuthor = registerAndLogin("13800000001", "楼主");
        Session replier = registerAndLogin("13800000002", "回复者");
        String postId = publishPost(rootAuthor, "删除整楼的帖子");
        String rootId = publishCommentAndGetId(postId, rootAuthor, "根评论");
        postReplyAndGetId(rootId, replier, "回复一");
        postReplyAndGetId(rootId, replier, "回复二");
        driveAllCommentReviews();
        assertThat(postCommentCountOf(postId)).isEqualTo(3);

        assertSuccess(deleteJson("/api/community/comments/" + rootId, rootAuthor), "作者删根评论");

        // 整楼对所有人消失：根列表与楼内列表都不可见
        assertThat(commentIdsOf(listCommentsAsGuest(postId, 1, 10))).isEmpty();
        JsonNode replies = getJson("/api/community/comments/" + rootId + "/replies?page=1", null)
                .path("data");
        assertThat(replies.path("list")).isEmpty();
        assertThat(replies.path("total").asLong()).isEqualTo(0);

        // 计数：根 1 + 楼内可见子回复 2 一并扣减
        assertThat(postCommentCountOf(postId)).isEqualTo(0);

        // 软删：根行仍在库（DELETED），子回复行不动（保持 NORMAL）
        assertThat(commentStatusOf(rootId)).isEqualTo("DELETED");
        assertThat(commentRowCountOf(postId)).isEqualTo(3);
    }

    @Test
    @DisplayName("删审核中的根评论（作者秒删自己未过审的评论）：转 DELETED，计数不动")
    void deletePendingRootComment_countUnchanged() {
        Session author = registerAndLogin("13800000001", "评论作者");
        String postId = publishPost(author, "秒删待审评论的帖子");
        String commentId = publishCommentAndGetId(postId, author, "还没过审就想撤回");

        assertSuccess(deleteJson("/api/community/comments/" + commentId, author), "作者删待审评论");

        assertThat(commentStatusOf(commentId)).isEqualTo("DELETED");
        assertThat(commentRowCountOf(postId)).isEqualTo(1);
        // 待审从未计入：总数不变
        assertThat(postCommentCountOf(postId)).isEqualTo(0);
        // 对所有人（含作者视角）消失
        assertThat(commentIdsOf(listCommentsAsGuest(postId, 1, 10))).isEmpty();
        JsonNode authorList = getJson("/api/community/posts/" + postId + "/comments?page=1&pageSize=10", author)
                .path("data");
        assertThat(commentIdsOf(authorList)).isEmpty();
    }

    @Test
    @DisplayName("删子回复 = 仅隐藏该条：行转 DELETED，reply_count 与总数各 -1")
    void deleteReply_onlyHidesItself_countsMinusOne() {
        Session rootAuthor = registerAndLogin("13800000001", "楼主");
        Session replier = registerAndLogin("13800000002", "回复者");
        String postId = publishPost(rootAuthor, "楼内删回复的帖子");
        String rootId = publishCommentAndGetId(postId, rootAuthor, "根评论");
        postReplyAndGetId(rootId, replier, "保留的回复");
        String r2 = postReplyAndGetId(rootId, replier, "被删的回复");
        driveAllCommentReviews();
        assertThat(postCommentCountOf(postId)).isEqualTo(3);

        assertSuccess(deleteJson("/api/community/comments/" + r2, replier), "作者删子回复");

        assertThat(commentStatusOf(r2)).isEqualTo("DELETED");
        assertThat(commentRowCountOf(postId)).isEqualTo(3);
        JsonNode replies = getJson("/api/community/comments/" + rootId + "/replies?page=1", null).path("data");
        assertThat(replies.path("total").asLong()).isEqualTo(1);
        assertThat(postCommentCountOf(postId)).isEqualTo(2);
    }

    @Test
    @DisplayName("指向已软删评论的『▶ 昵称』仍显示（03 的 JOIN 忽略状态固化回归）")
    void replyToNickname_preservedAfterParentSoftDeleted() {
        Session rootAuthor = registerAndLogin("13800000001", "楼主");
        Session b = registerAndLogin("13800000002", "用户B");
        Session c = registerAndLogin("13800000003", "用户C");
        String postId = publishPost(rootAuthor, "指向昵称保留的帖子");
        String rootId = publishCommentAndGetId(postId, rootAuthor, "根评论");
        String r1 = postReplyAndGetId(rootId, b, "B 的回复");
        String r2 = postReplyAndGetId(r1, c, "C 回复 B");
        driveAllCommentReviews();

        assertSuccess(deleteJson("/api/community/comments/" + r1, b), "B 删除被指向的子回复");

        // r1 消失、r2 仍可见且指向昵称取自已软删的 r1 行
        JsonNode replies = getJson("/api/community/comments/" + rootId + "/replies?page=1", null).path("data");
        assertThat(commentIdsOf(replies)).containsExactly(r2);
        JsonNode r2Node = replies.path("list").get(0);
        assertThat(r2Node.path("replyToUserId").asText()).isEqualTo(b.userId());
        assertThat(r2Node.path("replyToNickname").asText()).isEqualTo("用户B");
    }

    @Test
    @DisplayName("重复删除幂等：第二次成功且计数不重复扣减")
    void deleteTwice_idempotent_countsNotDoubleDeducted() {
        Session rootAuthor = registerAndLogin("13800000001", "楼主");
        Session replier = registerAndLogin("13800000002", "回复者");
        String postId = publishPost(rootAuthor, "重复删除的帖子");
        String rootId = publishCommentAndGetId(postId, rootAuthor, "根评论");
        postReplyAndGetId(rootId, replier, "回复一");
        driveAllCommentReviews();

        assertSuccess(deleteJson("/api/community/comments/" + rootId, rootAuthor), "第一次删除");
        assertSuccess(deleteJson("/api/community/comments/" + rootId, rootAuthor), "第二次删除（幂等）");

        assertThat(postCommentCountOf(postId)).isEqualTo(0);
        assertThat(commentRowCountOf(postId)).isEqualTo(2);
    }

    @Test
    @DisplayName("举报下架与用户删除同轨：软删语义一致；下架后帖主再删幂等")
    void reportTakedown_sameSoftDeleteSemantics_asUserDelete() {
        Session rootAuthor = registerAndLogin("13800000001", "楼主");
        Session replier = registerAndLogin("13800000002", "回复者");
        Session reporter = registerAndLogin("13800000003", "举报人");
        String postId = publishPost(rootAuthor, "被举报下架的帖子");
        String rootId = publishCommentAndGetId(postId, rootAuthor, "违规根评论");
        postReplyAndGetId(rootId, replier, "楼内回复");
        driveAllCommentReviews();
        assertThat(postCommentCountOf(postId)).isEqualTo(2);

        JsonNode reportResponse = postJson("/api/moderation/reports",
                Map.of("targetType", "COMMENT", "targetId", rootId, "reason", "违规内容"), reporter);
        assertSuccess(reportResponse, "提交举报");
        long reportId = reportResponse.path("data").asLong();

        assertSuccess(postJson("/api/moderation/reports/" + reportId + "/process",
                Map.of("action", "PROCESSED"), auditorSession()), "审核员核实下架");

        // 与用户删除同一套软删语义：根行 DELETED、子回复行不动、计数扣 1+楼内可见数、整楼不可见
        assertThat(commentStatusOf(rootId)).isEqualTo("DELETED");
        assertThat(commentRowCountOf(postId)).isEqualTo(2);
        assertThat(postCommentCountOf(postId)).isEqualTo(0);
        assertThat(commentIdsOf(listCommentsAsGuest(postId, 1, 10))).isEmpty();

        // 下架后帖主再删：幂等成功，计数不重复扣
        assertSuccess(deleteJson("/api/community/comments/" + rootId, rootAuthor), "帖主删已下架评论");
        assertThat(postCommentCountOf(postId)).isEqualTo(0);
    }

    @Test
    @DisplayName("审核拒绝路径同样软删：行保留、计数不动、之后用户删除幂等")
    void reviewReject_softDeleteConsistency() {
        Session author = registerAndLogin("13800000001", "评论作者");
        String postId = publishPost(author, "审核拒绝的帖子");
        String commentId = publishCommentAndGetId(postId, author, "会被驳回的评论");
        driveCommentReject(commentId, "OTHER", "内容违规");

        // 驳回：行保留（REJECTED）、从未计数、对所有人不可见
        assertThat(commentStatusOf(commentId)).isEqualTo("REJECTED");
        assertThat(commentRowCountOf(postId)).isEqualTo(1);
        assertThat(postCommentCountOf(postId)).isEqualTo(0);
        assertThat(commentIdsOf(listCommentsAsGuest(postId, 1, 10))).isEmpty();

        // 作者再删被驳回的评论：终态幂等，状态不回退
        assertSuccess(deleteJson("/api/community/comments/" + commentId, author), "作者删被驳回评论");
        assertThat(commentRowCountOf(postId)).isEqualTo(1);
        assertThat(postCommentCountOf(postId)).isEqualTo(0);
    }

    // ---------- 本测试类用例构造 ----------

    private String postReplyAndGetId(String targetCommentId, Session author, String content) {
        JsonNode response = postJson("/api/community/comments/" + targetCommentId + "/replies",
                Map.of("content", content), author);
        assertSuccess(response, "回复评论 " + targetCommentId);
        return response.path("data").path("commentId").asText();
    }

    /** 审核员登录态：角色在登录时写入 JWT，先改库再重新走验证码登录 */
    private Session auditorSession() {
        Session auditor = registerAndLogin("13900000001", "审核员");
        jdbcTemplate.update("UPDATE bs_user SET role = 'AUDITOR' WHERE user_id = ?", auditor.userId());
        postJson("/api/user/send-code", Map.of("phone", "13900000001"), null);
        String code = redissonClient.<String>getBucket(Constants.VERIFY_CODE_KEY + "13900000001").get();
        JsonNode loginResponse = postJson("/api/user/login", Map.of("phone", "13900000001", "code", code), null);
        assertSuccess(loginResponse, "审核员重新登录");
        return new Session(loginResponse.path("data").path("token").asText(), auditor.userId());
    }
}
