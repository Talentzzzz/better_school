package com.betterschool.integration.post;

import com.betterschool.integration.support.PostApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评论楼中楼（v2 票 03）：树模型写读的外部行为。
 *
 * <p>语义见 docs/adr/0001-comment-tree-model.md：回复根评论 root=parent=根评论 id；
 * 回复楼内子回复 root=楼根 id、parent=被回复子回复 id；展示平铺，指向昵称 JOIN parent 行取、忽略其状态。
 * 楼内分页：默认首页 3 条、之后每页 10 条，id 升序；reply_count 仅统计 NORMAL 子回复，同事务维护。
 */
@DisplayName("评论楼中楼：回复写读 / 树模型 / 楼内分页")
class CommentThreadingIntegrationTest extends PostApiTestBase {


    @Test
    @DisplayName("回复根评论：root=parent=根评论 id，楼内列表含指向信息")
    void replyToRoot_rootAndParentComputed() {
        Session rootAuthor = registerAndLogin("13800000001", "楼主");
        Session replier = registerAndLogin("13800000002", "回复者");
        String postId = publishPost(rootAuthor, "正常帖子内容，欢迎讨论");
        String rootId = publishCommentAndGetId(postId, rootAuthor, "根评论：今天食堂吃什么？");

        JsonNode response = postJson("/api/community/comments/" + rootId + "/replies",
                Map.of("content", "回复根评论的内容"), replier);
        assertSuccess(response, "回复根评论");
        String replyId = response.path("data").path("commentId").asText();

        // 数据库可观察状态：树模型字段由后端计算
        Map<String, Object> row = commentRowOf(replyId);
        assertThat(row.get("root_id")).isEqualTo(rootId);
        assertThat(row.get("parent_id")).isEqualTo(rootId);

        // 楼内列表：平铺、指向根评论作者
        JsonNode replies = listRepliesAsGuest(rootId, 1, null);
        assertThat(replies.path("total").asLong()).isEqualTo(1);
        JsonNode reply = replies.path("list").get(0);
        assertThat(reply.path("commentId").asText()).isEqualTo(replyId);
        assertThat(reply.path("nickname").asText()).isEqualTo("回复者");
        assertThat(reply.path("content").asText()).isEqualTo("回复根评论的内容");
        assertThat(reply.path("parentId").asText()).isEqualTo(rootId);
        assertThat(reply.path("replyToUserId").asText()).isEqualTo(rootAuthor.userId());
        assertThat(reply.path("replyToNickname").asText()).isEqualTo("楼主");

        // 根评论列表带 replyCount；帖子评论总数 = 根 + 子回复
        JsonNode rootList = listCommentsAsGuest(postId, 1, 10);
        assertThat(rootList.path("list").get(0).path("replyCount").asLong()).isEqualTo(1);
        assertThat(postCommentCountOf(postId)).isEqualTo(2);
    }

    @Test
    @DisplayName("回复楼内子回复与更深层级：root 恒为楼根、parent 为被回复子回复")
    void replyToNestedReply_staysInThread() {
        Session rootAuthor = registerAndLogin("13800000001", "楼主");
        Session b = registerAndLogin("13800000002", "用户B");
        Session c = registerAndLogin("13800000003", "用户C");
        String postId = publishPost(rootAuthor, "正常帖子内容，欢迎讨论");
        String rootId = publishCommentAndGetId(postId, rootAuthor, "根评论");

        String r1 = postReplyAndGetId(rootId, b, "B 回复根评论");
        String r2 = postReplyAndGetId(r1, c, "C 回复 B");
        String r3 = postReplyAndGetId(r2, b, "B 回复 C");
        // UUID 化后业务 id 无时间趋势：同秒发布的楼内平铺序收敛键 = create_time, comment_id。
        // DATETIME 是秒精度，把三条 create_time 错开，固化「create_time 升序（= 发布先后）」的期望口径
        staggerCommentCreateTimes(r1, r2, r3);

        // 数据可嵌套任意深度，root 恒为楼根、parent 为被回复的那条
        assertThat(commentRowOf(r2).get("root_id")).isEqualTo(rootId);
        assertThat(commentRowOf(r2).get("parent_id")).isEqualTo(r1);
        assertThat(commentRowOf(r3).get("root_id")).isEqualTo(rootId);
        assertThat(commentRowOf(r3).get("parent_id")).isEqualTo(r2);

        // 展示平铺：楼内 3 条子回复按 id 升序
        JsonNode replies = listRepliesAsGuest(rootId, 1, null);
        assertThat(commentIdsOf(replies)).containsExactly(r1, r2, r3);
        // 指向：r3 回复 C（parent 行作者）
        JsonNode r3Node = replies.path("list").get(2);
        assertThat(r3Node.path("parentId").asText()).isEqualTo(r2);
        assertThat(r3Node.path("replyToUserId").asText()).isEqualTo(c.userId());
    }

    @Test
    @DisplayName("客户端伪造 root/parent 字段被服务端忽略，归属由后端计算")
    void forgedRootAndParent_overriddenByServer() {
        Session rootAuthor = registerAndLogin("13800000001", "楼主");
        Session forged = registerAndLogin("13800000002", "伪造者");
        String postId = publishPost(rootAuthor, "正常帖子内容，欢迎讨论");
        String rootId = publishCommentAndGetId(postId, rootAuthor, "根评论");
        String r1 = postReplyAndGetId(rootId, rootAuthor, "楼内子回复");

        // 请求体夹带伪造归属字段
        JsonNode response = postJson("/api/community/comments/" + r1 + "/replies",
                Map.of("content", "伪造归属的回复", "rootId", 99999, "parentId", 88888), forged);
        assertSuccess(response, "伪造字段回复");

        Map<String, Object> row = commentRowOf(response.path("data").path("commentId").asText());
        assertThat(row.get("root_id")).isEqualTo(rootId);
        assertThat(row.get("parent_id")).isEqualTo(r1);
    }

    @Test
    @DisplayName("楼内分页：默认首页 3 条、之后每页 10 条，id 升序；总数与 replyCount 一致")
    void repliesPagination_default3Then10() {
        Session rootAuthor = registerAndLogin("13800000001", "楼主");
        String postId = publishPost(rootAuthor, "正常帖子内容，欢迎讨论");
        String rootId = publishCommentAndGetId(postId, rootAuthor, "根评论");

        List<String> replyIds = new java.util.ArrayList<>();
        for (int i = 1; i <= 13; i++) {
            replyIds.add(postReplyAndGetId(rootId, rootAuthor, "楼内回复 " + i));
        }
        // 同秒 13 条的平铺序收敛键退化为 comment_id 字典序（与发布先后无关）：
        // 把 create_time 按发布顺序错开，分页断言维持「按发布先后」的语义
        staggerCommentCreateTimes(replyIds.toArray(new String[0]));

        JsonNode page1 = listRepliesAsGuest(rootId, 1, null);
        assertThat(page1.path("total").asLong()).isEqualTo(13);
        assertThat(page1.path("pageSize").asLong()).isEqualTo(3);
        assertThat(commentIdsOf(page1)).isEqualTo(replyIds.subList(0, 3));

        JsonNode page2 = listRepliesAsGuest(rootId, 2, null);
        assertThat(page2.path("pageSize").asLong()).isEqualTo(10);
        assertThat(commentIdsOf(page2)).isEqualTo(replyIds.subList(3, 13));

        JsonNode page3 = listRepliesAsGuest(rootId, 3, null);
        assertThat(commentIdsOf(page3)).isEmpty();

        // 根评论列表的 replyCount 与楼内可见子回复数一致
        JsonNode rootList = listCommentsAsGuest(postId, 1, 10);
        assertThat(rootList.path("list").get(0).path("replyCount").asLong()).isEqualTo(13);
    }

    @Test
    @DisplayName("删除楼内子回复：reply_count -1，楼内列表同步减少（软删行保留）")
    void deleteReply_decrementsReplyCount() {
        Session rootAuthor = registerAndLogin("13800000001", "楼主");
        Session replier = registerAndLogin("13800000002", "回复者");
        String postId = publishPost(rootAuthor, "正常帖子内容，欢迎讨论");
        String rootId = publishCommentAndGetId(postId, rootAuthor, "根评论");

        postReplyAndGetId(rootId, replier, "回复一");
        String r2 = postReplyAndGetId(rootId, replier, "回复二");

        JsonNode response = deleteJson("/api/community/comments/" + r2, replier);
        assertSuccess(response, "删除子回复");

        assertThat(replyCountOnRootList(postId, rootId)).isEqualTo(1);
        JsonNode replies = listRepliesAsGuest(rootId, 1, null);
        assertThat(replies.path("total").asLong()).isEqualTo(1);
        // 软删（票 07）：行保留 DELETED
        assertThat(commentStatusOf(r2)).isEqualTo("DELETED");
        assertThat(commentRowCountOf(postId)).isEqualTo(3);
    }

    @Test
    @DisplayName("回复不存在的评论：404；访客回复需登录 401")
    void reply_errorCases() {
        Session user = registerAndLogin("13800000001", "用户A");

        JsonNode notFound = postJson("/api/community/comments/99999/replies",
                Map.of("content", "回复不存在评论"), user);
        assertThat(notFound.path("code").asText()).isEqualTo("404");

        ResponseEntity<String> anonymous = exchange(HttpMethod.POST,
                "/api/community/comments/1/replies", Map.of("content", "游客回复"), null);
        assertThat(anonymous.getStatusCode().value()).isEqualTo(401);
    }

    // ---------- 本测试类用例构造 ----------

    private String postReplyAndGetId(String targetCommentId, Session author, String content) {
        JsonNode response = postJson("/api/community/comments/" + targetCommentId + "/replies",
                Map.of("content", content), author);
        assertSuccess(response, "回复评论 " + targetCommentId);
        return response.path("data").path("commentId").asText();
    }

    /** 楼内子回复列表（guest）：data 节点 = {page, pageSize, total, list}；pageSize 传 null 用默认 3/10 */
    private JsonNode listRepliesAsGuest(String commentId, int page, Integer pageSize) {
        String url = "/api/community/comments/" + commentId + "/replies?page=" + page
                + (pageSize == null ? "" : "&pageSize=" + pageSize);
        JsonNode response = getJson(url, null);
        assertSuccess(response, "楼内子回复列表");
        return response.path("data");
    }

    private long replyCountOnRootList(String postId, String rootId) {
        JsonNode listData = listCommentsAsGuest(postId, 1, 10);
        for (JsonNode node : listData.path("list")) {
            if (rootId.equals(node.path("commentId").asText())) {
                return node.path("replyCount").asLong();
            }
        }
        throw new IllegalStateException("根评论 " + rootId + " 不在帖子 " + postId + " 的根列表中");
    }

    // ---------- 数据库可观察状态 ----------

    private Map<String, Object> commentRowOf(String commentId) {
        return jdbcTemplate.queryForMap(
                "SELECT comment_id, root_id, parent_id, state FROM post_comment WHERE comment_id = ?", commentId);
    }

    /**
     * 按传入顺序把评论 create_time 依次错开（第 n 条早 n 秒）。
     * UUID 化后业务 id 无自增时间趋势，DATETIME 秒精度下同秒多条的确定序收敛键退化为 comment_id 字典序；
     * 错开 create_time 让「平铺/最热档次序 = 发布先后」的断言在 UUID 下保持成立。
     */
    private void staggerCommentCreateTimes(String... commentIds) {
        for (int i = 0; i < commentIds.length; i++) {
            jdbcTemplate.update(
                    "UPDATE post_comment SET create_time = DATE_SUB(NOW(), INTERVAL ? SECOND) WHERE comment_id = ?",
                    commentIds.length - i, commentIds[i]);
        }
    }
}
