package com.betterschool.integration.post;

import com.betterschool.integration.support.PostApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.stream.StreamSupport;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评论区基线（票 01）：发布 / 列表 / 删除的外部行为，v2 重构期间随票替换实现同步改写。
 *
 * <p>04 票改写：total 真实总数。
 * 07 票改写：删除统一软删（行保留 DELETED），详细语义见 CommentDeleteSemanticsIntegrationTest。
 * 审核语义移除后：评论发布即 NORMAL 可见、计数 +1。
 */
@DisplayName("评论区现状基线：发布 / 列表 / 删除（平铺版现状）")
class PostCommentBaselineIntegrationTest extends PostApiTestBase {

    @Test
    @DisplayName("发布评论后：NORMAL 可见，帖子评论数 +1")
    void publish_normalComment_visibleToList() {
        Session author = registerAndLogin("13800000001", "评论作者");
        String postId = publishPost(author, "正常帖子内容，欢迎评论");
        Session commenter = registerAndLogin("13800000002", "路人甲");

        publishCommentAndGetId(postId, commenter, "我来围观一下");

        // 访客视角可见：内容、昵称、实名作者
        JsonNode listData = listCommentsAsGuest(postId, 1, 10);
        assertThat(listData.path("list")).hasSize(1);
        JsonNode comment = listData.path("list").get(0);
        assertThat(comment.path("content").asText()).isEqualTo("我来围观一下");
        assertThat(comment.path("nickname").asText()).isEqualTo("路人甲");
        assertThat(comment.path("userId").asText()).isEqualTo(commenter.userId());
        // 匿名概念已整体移除（v2 票 02）：响应不再携带 anonymous 字段
        assertThat(comment.path("anonymous").isMissingNode()).isTrue();

        // 评论数口径：发布时 +1（帖子详情可观察）
        assertThat(postCommentCountOf(postId)).isEqualTo(1);
    }

    @Test
    @DisplayName("评论列表分页：按 id 升序切片；total 为真实总数（04 票修复）")
    void listComments_pagination_realTotal() {
        Session author = registerAndLogin("13800000001", "帖子作者");
        String postId = publishPost(author, "长评论区的帖子");
        Session commenter = registerAndLogin("13800000002", "路人甲");
        List<String> publishedIds = new java.util.ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            publishedIds.add(publishCommentAndGetId(postId, commenter, "评论 " + i));
        }
        // uuid-biz-id 票 03：默认档序 = create_time, comment_id。同秒发布的 UUID 退化为字典序（与发布先后
        // 无关），错开 create_time 固化「按发布先后切片」的期望口径；id 本身不再有序，isSorted 断言随之退役
        staggerCommentCreateTimes(publishedIds.toArray(new String[0]));
        // 默认档（不传 sort）= 最热档，第一级扫描读索引表 create_time 快照：同样错开
        staggerCommentIndexCreateTimes(publishedIds.toArray(new String[0]));

        JsonNode page1 = listCommentsAsGuest(postId, 1, 10);
        assertThat(page1.path("list")).hasSize(10);
        assertThat(commentIdsOf(page1)).as("默认档按发布先后：第 1 页应是最早的 10 条")
                .containsExactlyElementsOf(publishedIds.subList(0, 10));
        // 真实总数（04 票修复原 total 恒 0 缺陷）
        assertThat(page1.path("total").asLong()).isEqualTo(15);

        JsonNode page2 = listCommentsAsGuest(postId, 2, 10);
        assertThat(page2.path("list")).hasSize(5);
        assertThat(page2.path("total").asLong()).isEqualTo(15);

        // 切片顺序：第 2 页必须整体晚于第 1 页（仅页内序列锁不住跨页切片）
        List<String> page1Ids = commentIdsOf(page1);
        List<String> page2Ids = commentIdsOf(page2);
        assertThat(page2Ids).as("第 2 页 = 发布序的后 5 条").containsExactlyElementsOf(publishedIds.subList(10, 15));
        assertThat(page1Ids).as("页与页之间不应重叠").doesNotContainAnyElementsOf(page2Ids);
        assertThat(page1Ids.size() + page2Ids.size()).isEqualTo(15);
    }

    @Test
    @DisplayName("作者删除自己的评论：软删（行保留 DELETED），评论数扣减")
    void deleteComment_byAuthor_softDelete() {
        Session author = registerAndLogin("13800000001", "帖子作者");
        String postId = publishPost(author, "待删除评论的帖子");
        Session commenter = registerAndLogin("13800000002", "评论作者");
        publishCommentAndGetId(postId, commenter, "第一条评论");
        publishCommentAndGetId(postId, commenter, "第二条评论");

        // UUID 化后同秒评论的默认档序收敛键 = create_time, comment_id（同秒退化为字典序，与发布先后无关）：
        // 按内容定位目标评论，不假设列表首位是先发的那条
        JsonNode listData = listCommentsAsGuest(postId, 1, 10);
        String targetCommentId = commentIdByContent(listData, "第一条评论");
        assertSuccess(deleteJson("/api/community/comments/" + targetCommentId, commenter), "作者删除自己的评论");

        // 列表可见性：被删评论消失，另一条保留
        listData = listCommentsAsGuest(postId, 1, 10);
        assertThat(commentIdsOf(listData)).doesNotContain(targetCommentId).hasSize(1);
        assertThat(listData.path("list").get(0).path("content").asText()).isEqualTo("第二条评论");

        // 软删（票 07）：行仍在库，状态 DELETED
        assertThat(commentRowCountOf(postId)).as("软删后行仍保留").isEqualTo(2);
        assertThat(commentStatusOf(targetCommentId)).isEqualTo("DELETED");

        // 计数扣减：NORMAL 评论删除 -1
        assertThat(postCommentCountOf(postId)).isEqualTo(1);
    }

    @Test
    @DisplayName("帖主可删除自己帖子下的任何评论")
    void deleteComment_byPostOwner_allowed() {
        Session postOwner = registerAndLogin("13800000001", "帖主");
        String postId = publishPost(postOwner, "帖主维护评论区的帖子");
        Session commenter = registerAndLogin("13800000002", "评论者");
        publishCommentAndGetId(postId, commenter, "会被帖主删掉的评论");

        String targetCommentId = firstCommentId(postId);
        assertSuccess(deleteJson("/api/community/comments/" + targetCommentId, postOwner), "帖主删除他人评论");

        assertThat(commentIdsOf(listCommentsAsGuest(postId, 1, 10))).isEmpty();
        assertThat(commentStatusOf(targetCommentId)).isEqualTo("DELETED");
        assertThat(postCommentCountOf(postId)).isEqualTo(0);
    }

    @Test
    @DisplayName("无关用户删除他人评论：403 拒绝，评论与计数不变")
    void deleteComment_byUnrelatedUser_forbidden() {
        Session author = registerAndLogin("13800000001", "帖子作者");
        String postId = publishPost(author, "权限校验的帖子");
        Session commenter = registerAndLogin("13800000002", "评论作者");
        String targetCommentId = publishComment(postId, commenter, "不该被别人删的评论")
                .path("data").path("commentId").asText();

        Session stranger = registerAndLogin("13800000003", "无关路人");
        JsonNode response = deleteJson("/api/community/comments/" + targetCommentId, stranger);
        assertThat(response.path("code").asText()).as("无关用户删除应被拒绝：%s", response).isEqualTo("403");

        assertThat(commentIdsOf(listCommentsAsGuest(postId, 1, 10))).containsExactly(targetCommentId);
        assertThat(postCommentCountOf(postId)).isEqualTo(1);
    }

    @Test
    @DisplayName("访客（未登录）发布/删除评论：HTTP 401")
    void commentWrite_asGuest_unauthorized() {
        Session author = registerAndLogin("13800000001", "帖子作者");
        String postId = publishPost(author, "需要登录才能互动的帖子");
        Session commenter = registerAndLogin("13800000002", "评论作者");
        String commentId = publishComment(postId, commenter, "已登录用户发的评论")
                .path("data").path("commentId").asText();

        // Security 白名单只放行 GET /api/community/**，写操作 401（HTTP 状态层面）
        int publishStatus = rawStatus(HttpMethod.POST,
                "/api/community/posts/" + postId + "/comments", Map.of("content", "访客的评论"));
        assertThat(publishStatus).as("访客发布评论应 401").isEqualTo(401);

        int deleteStatus = rawStatus(HttpMethod.DELETE, "/api/community/comments/" + commentId, null);
        assertThat(deleteStatus).as("访客删除评论应 401").isEqualTo(401);
    }

    // ---------- 本类局部辅助 ----------

    private String firstCommentId(String postId) {
        JsonNode listData = listCommentsAsGuest(postId, 1, 10);
        return listData.path("list").get(0).path("commentId").asText();
    }

    /** 按内容在评论列表里定位 commentId（UUID 下同秒序与发布先后无关，位置断言不可靠） */
    private String commentIdByContent(JsonNode listData, String content) {
        return StreamSupport.stream(listData.path("list").spliterator(), false)
                .filter(node -> content.equals(node.path("content").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("列表中找不到评论：" + content))
                .path("commentId").asText();
    }

    /** 401/403 场景只看 HTTP 状态（Security 层 401 返回 HTTP 401；业务拒绝走 HTTP 200 + body.code） */
    private int rawStatus(HttpMethod method, String url, Object body) {
        return exchange(method, url, body, null).getStatusCode().value();
    }

    /** 按传入顺序把评论 create_time 依次错开（第 n 条早 n 秒）：UUID 同秒收敛键退化为 comment_id 字典序，
     *  错开 create_time 让「同值段按发布先后」的断言在 UUID 下保持成立 */
    private void staggerCommentCreateTimes(String... commentIds) {
        for (int i = 0; i < commentIds.length; i++) {
            jdbcTemplate.update(
                    "UPDATE post_comment SET create_time = DATE_SUB(NOW(), INTERVAL ? SECOND) WHERE comment_id = ?",
                    commentIds.length - i, commentIds[i]);
        }
    }

    /** 同 staggerCommentCreateTimes，但作用于 post_comment_feed_index 索引表（最热档第一级扫描的 create_time 快照） */
    private void staggerCommentIndexCreateTimes(String... commentIds) {
        for (int i = 0; i < commentIds.length; i++) {
            jdbcTemplate.update(
                    "UPDATE post_comment_feed_index SET create_time = DATE_SUB(NOW(), INTERVAL ? SECOND) WHERE comment_id = ?",
                    commentIds.length - i, commentIds[i]);
        }
    }
}
