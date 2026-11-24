package com.betterschool.integration.post;

import com.betterschool.integration.support.PostApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 匿名整体移除（v2 票 02）的行为固化。
 *
 * <p>选定行为：请求体中的 anonymous 字段为未知字段，Jackson 静默忽略——旧客户端
 * 继续携带 anonymous=true 发帖/评论不会失败，内容一律实名发布与展示；
 * 所有响应不再携带 anonymous 字段。
 */
@DisplayName("匿名移除：旧客户端字段被忽略，内容全实名")
class AnonymousRemovalIntegrationTest extends PostApiTestBase {

    @Test
    @DisplayName("旧客户端带 anonymous=true 发帖：成功且实名，详情无 anonymous 字段；多带的已退役 category 字段被 Jackson 忽略（票 06 兼容）")
    void publishPost_withLegacyAnonymousField_realIdentity() {
        Session author = registerAndLogin("13800000001", "实名作者");

        JsonNode response = postJson("/api/community/posts",
                Map.of("title", "旧客户端发的帖", "content", "内容不含敏感词", "category", "DAILY", "anonymous", true), author);
        assertSuccess(response, "旧客户端带 anonymous/category 字段发帖");
        String postId = response.path("data").asText();

        JsonNode detail = getJson("/api/community/posts/" + postId, null);
        assertSuccess(detail, "帖子详情");
        JsonNode post = detail.path("data");
        assertThat(post.path("nickname").asText()).as("匿名已移除，必须实名展示").isEqualTo("实名作者");
        assertThat(post.path("userId").asText()).isEqualTo(author.userId());
        assertThat(post.path("anonymous").isMissingNode()).as("响应不应再有 anonymous 字段").isTrue();
    }

    @Test
    @DisplayName("旧客户端带 anonymous=true 评论：成功且实名，列表无 anonymous 字段")
    void publishComment_withLegacyAnonymousField_realIdentity() {
        Session author = registerAndLogin("13800000001", "帖子作者");
        String postId = publishPost(author, "正常帖子内容，欢迎评论");
        Session commenter = registerAndLogin("13800000002", "实名评论者");

        JsonNode response = postJson("/api/community/posts/" + postId + "/comments",
                Map.of("content", "老版本 App 的评论", "anonymous", true), commenter);
        assertSuccess(response, "旧客户端带 anonymous 字段评论");

        JsonNode listData = listCommentsAsGuest(postId, 1, 10);
        assertThat(listData.path("list")).hasSize(1);
        JsonNode comment = listData.path("list").get(0);
        assertThat(comment.path("nickname").asText()).as("匿名已移除，必须实名展示").isEqualTo("实名评论者");
        assertThat(comment.path("userId").asText()).isEqualTo(commenter.userId());
        assertThat(comment.path("anonymous").isMissingNode()).as("响应不应再有 anonymous 字段").isTrue();

        // 数据库不再存在匿名标记：评论行归属真实用户
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM post_comment WHERE post_id = ? AND user_id = ?", Integer.class, postId, commenter.userId());
        assertThat(rows).isEqualTo(1);
    }
}
