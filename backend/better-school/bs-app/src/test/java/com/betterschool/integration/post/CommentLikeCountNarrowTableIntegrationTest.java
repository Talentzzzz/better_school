package com.betterschool.integration.post;

import com.betterschool.infrastructure.adapter.repository.post.PostRepository;
import com.betterschool.integration.support.PostApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评论点赞计数真源窄表 {@code post_comment_like_count}（comment-count-narrow-table 票 02）：
 * <b>行诞生</b>（根评论与子回复发布即建行）、<b>落窄表</b>（点赞合并与取消赞同事务 ±N、
 * 主行 update_time 不再被刷）、<b>回查填充</b>（根列表两档 / 楼内平铺 / 作者主页 tab 同一通路，读的是窄表值
 * 而不是关系行条数）、<b>缺行语义</b>（写路径 warn 不补行、读路径显示 0）。
 *
 * <p>主列 {@code post_comment.like_count} 已在票 04 删除（票 02 先停写停读），所以这里只剩窄表一侧可断言；
 * 计数写不再刷主行 {@code update_time} 这一条仍在本类钉着——它是「计数不再碰主行」的直接证据。
 */
@DisplayName("评论点赞计数窄表：行诞生 / 落窄表 / 回查填充 / 缺行语义")
class CommentLikeCountNarrowTableIntegrationTest extends PostApiTestBase {

    private static final String LEGACY_UPDATE_TIME = "2020-01-01 00:00:00";

    @Test
    @DisplayName("根评论与子回复发布后窄表行诞生且为 0")
    void narrowRowBornOnPublish_forRootsAndReplies() {
        Session postAuthor = registerAndLogin("13800000001", "帖主");
        Session commenter = registerAndLogin("13800000002", "评论人");
        String postId = publishPost(postAuthor, "评论计数窄表的行诞生");

        String rootId = publishCommentAndGetId(postId, commenter, "根评论");
        String replyId = postReplyAndGetId(rootId, commenter, "楼内子回复");

        // 根评论与子回复都入表（子回复只展示赞数、不入热评索引，但计数一样要有真源）
        assertThat(narrowLikeCountOf(rootId)).as("根评论发布后应有计数行").isZero();
        assertThat(narrowLikeCountOf(replyId)).as("子回复发布后应有计数行").isZero();
        assertThat(narrowRowCountOf(List.of(rootId, replyId)))
                .as("两条评论都有计数行").isEqualTo(2);
    }

    @Test
    @DisplayName("点赞 N 次窄表 = N、取消回退；子回复同样落窄表；主行 update_time 不动")
    void likeAndUnlike_moveNarrowCount_mainRowUntouched() {
        Session postAuthor = registerAndLogin("13800000001", "帖主");
        Session commenter = registerAndLogin("13800000002", "评论人");
        Session third = registerAndLogin("13800000003", "路人");
        String postId = publishPost(postAuthor, "评论点赞落窄表的帖子");
        String rootId = publishCommentAndGetId(postId, commenter, "根评论");
        String replyId = postReplyAndGetId(rootId, commenter, "楼内子回复");

        // 主行 update_time 先钉成过去值：计数写若还刷主行，这里会立刻显形
        jdbcTemplate.update("UPDATE post_comment SET update_time = ? WHERE comment_id = ?", LEGACY_UPDATE_TIME, rootId);

        likeComment(rootId, postAuthor);
        likeComment(rootId, third);
        assertThat(narrowLikeCountOf(rootId)).as("两赞 → 窄表 2").isEqualTo(2);

        assertSuccess(deleteJson("/api/community/comments/" + rootId + "/like", third), "取消点赞");
        assertThat(narrowLikeCountOf(rootId)).as("取消一个 → 窄表 1").isEqualTo(1);

        // 子回复：同样落窄表（它只是不进热评索引）
        likeComment(replyId, postAuthor);
        assertThat(narrowLikeCountOf(replyId)).as("子回复赞数落窄表").isEqualTo(1);

        assertThat(mainUpdateTimeOf(rootId)).as("计数写不再刷主行 update_time")
                .isEqualTo(LEGACY_UPDATE_TIME);
    }

    @Test
    @DisplayName("窄表行被人工删除后点赞：落 warn、不补行、点赞事务不中断")
    void missingNarrowRow_warnsAndDoesNotBackfill() {
        Session postAuthor = registerAndLogin("13800000001", "帖主");
        Session commenter = registerAndLogin("13800000002", "评论人");
        String postId = publishPost(postAuthor, "窄表行缺失的帖子");
        String rootId = publishCommentAndGetId(postId, commenter, "根评论");
        // 人为造漂移：计数行被删（手工对账前的那种状态）
        jdbcTemplate.update("DELETE FROM post_comment_like_count WHERE comment_id = ?", rootId);

        List<String> warns;
        try (LogCapture logs = captureWarnLogs(PostRepository.class)) {
            likeComment(rootId, postAuthor);
            warns = logs.warnMessagesContaining("post_comment_like_count");
        }

        assertThat(commentLikeRowCountOf(rootId)).as("关系行照常写入：点赞不被计数缺口拖垮").isEqualTo(1);
        assertThat(narrowRowOf(rootId)).as("命中 0 行不 ODKU：计数行不会被写路径补回来").isNull();
        assertThat(warns).as("漂移落 warn（commentId + delta 在文案里），交手工对账")
                .anyMatch(message -> message.contains("post_comment_like_count 行缺失")
                        && message.contains(String.valueOf(rootId))
                        && message.contains("delta=1"));
        // 读路径缺行补 0：这条评论仍显示 0 赞（关系行有 1 条，但真源缺口不靠展示路径猜）
        assertThat(likeCountInRootList(postId, "hot", rootId)).as("缺行时展示 0").isZero();
    }

    @Test
    @DisplayName("四条读路径的 likeCount 均取窄表值：根列表两档 / 楼内平铺 / 作者主页 tab")
    void allReadPaths_projectNarrowCount() {
        Session postAuthor = registerAndLogin("13800000001", "帖主");
        Session commenter = registerAndLogin("13800000002", "评论人");
        Session third = registerAndLogin("13800000003", "路人");
        String postId = publishPost(postAuthor, "四条读路径取窄表的帖子");
        String rootId = publishCommentAndGetId(postId, commenter, "根评论");
        String replyId = postReplyAndGetId(rootId, commenter, "楼内子回复");

        likeComment(rootId, postAuthor);
        likeComment(rootId, third);
        likeComment(replyId, postAuthor);
        // 把根评论的窄表值刷到 7：展示若真读窄表就应显示 7（关系行只有 2 条，
        // 按关系行计数会得 2——两条歧路都会露馅）
        jdbcTemplate.update("UPDATE post_comment_like_count SET like_count = 7 WHERE comment_id = ?", rootId);

        assertThat(likeCountInRootList(postId, "hot", rootId)).as("根列表 hot 档").isEqualTo(7);
        assertThat(likeCountInRootList(postId, "new", rootId)).as("根列表默认档").isEqualTo(7);
        assertThat(likeCountInReplies(rootId, replyId)).as("楼内平铺").isEqualTo(1);
        assertThat(likeCountInUserComments(commenter, rootId)).as("作者主页 tab（根评论）").isEqualTo(7);
        assertThat(likeCountInUserComments(commenter, replyId)).as("作者主页 tab（子回复）").isEqualTo(1);
    }

    @Test
    @DisplayName("窄表缺行时四条读路径均显示 0（存量语义：没有计数行的评论显示 0）")
    void missingNarrowRow_readsAsZero() {
        Session postAuthor = registerAndLogin("13800000001", "帖主");
        Session commenter = registerAndLogin("13800000002", "评论人");
        String postId = publishPost(postAuthor, "窄表缺行显示 0 的帖子");
        String rootId = publishCommentAndGetId(postId, commenter, "根评论");
        String replyId = postReplyAndGetId(rootId, commenter, "楼内子回复");
        likeComment(rootId, postAuthor);
        likeComment(replyId, postAuthor);
        assertThat(narrowLikeCountOf(rootId)).isEqualTo(1);

        jdbcTemplate.update("DELETE FROM post_comment_like_count WHERE comment_id IN (?, ?)", rootId, replyId);

        assertThat(likeCountInRootList(postId, "hot", rootId)).as("根列表 hot 档缺行补 0").isZero();
        assertThat(likeCountInRootList(postId, "new", rootId)).as("根列表默认档缺行补 0").isZero();
        assertThat(likeCountInReplies(rootId, replyId)).as("楼内平铺缺行补 0").isZero();
        assertThat(likeCountInUserComments(commenter, rootId)).as("作者主页 tab 缺行补 0").isZero();
    }

    // ---------- 本测试类局部辅助 ----------

    private String postReplyAndGetId(String targetCommentId, Session author, String content) {
        JsonNode response = postJson("/api/community/comments/" + targetCommentId + "/replies",
                Map.of("content", content), author);
        assertSuccess(response, "回复评论 " + targetCommentId);
        return response.path("data").path("commentId").asText();
    }

    private void likeComment(String commentId, Session session) {
        assertSuccess(postJson("/api/community/comments/" + commentId + "/like", Map.of(), session),
                "点赞评论 " + commentId);
    }

    private JsonNode listComments(String postId, String sort, Session session) {
        JsonNode response = getJson("/api/community/posts/" + postId + "/comments?page=1&pageSize=10&sort=" + sort, session);
        assertSuccess(response, "评论列表");
        return response.path("data");
    }

    private long likeCountInRootList(String postId, String sort, String commentId) {
        return likeCountOfInList(listComments(postId, sort, null), commentId);
    }

    private long likeCountInReplies(String rootId, String replyId) {
        JsonNode response = getJson("/api/community/comments/" + rootId + "/replies?page=1", null);
        assertSuccess(response, "楼内列表");
        return likeCountOfInList(response.path("data"), replyId);
    }

    private long likeCountInUserComments(Session author, String commentId) {
        JsonNode response = getJson("/api/community/users/" + author.userId() + "/comments?page=1&pageSize=10", null);
        assertSuccess(response, "作者主页评论 tab");
        return likeCountOfInList(response.path("data"), commentId);
    }

    private long likeCountOfInList(JsonNode listData, String commentId) {
        return java.util.stream.StreamSupport.stream(listData.path("list").spliterator(), false)
                .filter(node -> commentId.equals(node.path("commentId").asText()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("列表中找不到评论 " + commentId))
                .path("likeCount").asLong();
    }

    /** 窄表行；不存在返回 null（缺行语义的用例要区分 0 与无行） */
    private Map<String, Object> narrowRowOf(String commentId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT comment_id, like_count FROM post_comment_like_count WHERE comment_id = ?", commentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long narrowLikeCountOf(String commentId) {
        Map<String, Object> row = narrowRowOf(commentId);
        assertThat(row).as("评论 %s 应有计数行", commentId).isNotNull();
        return ((Number) row.get("like_count")).longValue();
    }

    private long narrowRowCountOf(List<String> commentIds) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM post_comment_like_count WHERE comment_id IN (?, ?, ?, ?)", Long.class,
                commentIds.toArray());
        return count == null ? 0 : count;
    }

    /** 主行 update_time：计数写不该碰它（主行没有排序键依赖它） */
    private String mainUpdateTimeOf(String commentId) {
        return jdbcTemplate.queryForObject(
                "SELECT DATE_FORMAT(update_time, '%Y-%m-%d %H:%i:%s') FROM post_comment WHERE comment_id = ?",
                String.class, commentId);
    }

    private long commentLikeRowCountOf(String commentId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM post_comment_like WHERE comment_id = ?", Long.class, commentId);
        return count == null ? 0 : count;
    }

}
