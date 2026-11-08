package com.betterschool.integration.community;

import com.betterschool.infrastructure.adapter.repository.community.CommunityRepository;
import com.betterschool.integration.support.CommunityApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 根评论热评索引 {@code post_comment_feed_index}（comment-count-narrow-table 票 01）：
 * 照帖子 feed 形态只做「单帖内按赞数取页」一件事，行集 = 当前可见根评论。
 *
 * <p>四条链路各一组用例：<b>行诞生</b>（根评论发布 INSERT、子回复不入索引）、
 * <b>旁带同步</b>（赞/取消赞同事务刷索引快照，子回复只动计数窄表）、<b>软删退场</b>（根评论软删物理删行、
 * 子回复零动作）、<b>最热档读路径</b>（索引序 + 同数 comment_id 升序、offset 跨页不重不漏、不自见、
 * 排序真的读索引快照而不是计数窄表）。
 */
@DisplayName("根评论热评索引：行诞生 / 旁带同步 / 软删退场 / 最热档索引序")
class CommentFeedIndexIntegrationTest extends CommunityApiTestBase {

    @Test
    @DisplayName("根评论发布后索引行诞生且赞数 0；子回复发布不产行")
    void indexRowBornOnRootPublish_onlyForRoots() {
        Session postAuthor = registerAndLogin("13800000001", "帖主");
        Session commenter = registerAndLogin("13800000002", "评论人");
        String postId = publishPost(postAuthor, "热评索引行诞生的帖子");

        String rootId = publishCommentAndGetId(postId, commenter, "根评论");
        String replyId = postReplyAndGetId(rootId, commenter, "楼内子回复");

        // 根评论：发布即诞生，赞数必为 0（行诞生时还没机会被赞）
        assertThat(feedIndexRowOf(rootId)).as("根评论发布后应有索引行：%s", rootId).isNotNull();
        assertThat(feedIndexLikeCountOf(rootId)).as("行诞生时赞数 0").isZero();
        assertThat(feedIndexPostIdOf(rootId)).as("索引行带所属帖子").isEqualTo(postId);

        // 子回复不入索引（楼内不按赞排，只展示赞数）
        assertThat(feedIndexRowOf(replyId)).as("子回复不产索引行").isNull();
        assertThat(feedIndexRowCountOf(postId)).as("本帖索引行只有那一条根评论").isEqualTo(1);
    }

    @Test
    @DisplayName("赞/取消赞：根评论索引快照与计数窄表同步 ±N；子回复只动窄表、索引零动作")
    void likeSync_keepsIndexInStep_withNarrowCount_forRootsOnly() {
        Session postAuthor = registerAndLogin("13800000001", "帖主");
        Session commenter = registerAndLogin("13800000002", "评论人");
        Session third = registerAndLogin("13800000003", "路人");
        String postId = publishPost(postAuthor, "热评索引赞数同步的帖子");
        String rootId = publishCommentAndGetId(postId, commenter, "根评论");
        String replyId = postReplyAndGetId(rootId, commenter, "楼内子回复");

        // 两人赞根评论：窄表与索引快照同步推进到 2
        likeComment(rootId, postAuthor);
        likeComment(rootId, third);
        assertThat(commentLikeCountOf(rootId)).as("窄表 like_count").isEqualTo(2);
        assertThat(feedIndexLikeCountOf(rootId)).as("索引快照跟随窄表").isEqualTo(2);

        // 取消一个：两边一起回落到 1
        assertSuccess(deleteJson("/api/community/comments/" + rootId + "/like", third), "取消点赞");
        assertThat(commentLikeCountOf(rootId)).as("窄表回落").isEqualTo(1);
        assertThat(feedIndexLikeCountOf(rootId)).as("索引快照回落").isEqualTo(1);

        // 子回复点赞：只动计数窄表，索引里既没有它的行、也没有被误加一条（0 行命中不是漂移）
        likeComment(replyId, postAuthor);
        assertThat(commentLikeCountOf(replyId)).as("子回复窄表 like_count").isEqualTo(1);
        assertThat(feedIndexRowOf(replyId)).as("子回复点赞不产生索引行").isNull();
        assertThat(feedIndexRowCountOf(postId)).as("本帖索引行数仍是 1（根评论）").isEqualTo(1);
        assertThat(feedIndexLikeCountOf(rootId)).as("子回复点赞不碰根评论索引快照").isEqualTo(1);
    }

    @Test
    @DisplayName("根评论软删：索引行退场、楼内列表与帖子评论总数照常")
    void rootSoftDelete_evictsIndexRow_listAndCountsUnchanged() {
        Session postAuthor = registerAndLogin("13800000001", "帖主");
        Session replier = registerAndLogin("13800000002", "回复者");
        String postId = publishPost(postAuthor, "根评论软删的帖子");
        String rootId = publishCommentAndGetId(postId, postAuthor, "根评论");
        postReplyAndGetId(rootId, replier, "回复一");
        postReplyAndGetId(rootId, replier, "回复二");
        assertThat(postCommentCountOf(postId)).isEqualTo(3);
        assertThat(feedIndexRowOf(rootId)).as("发布后有索引行").isNotNull();

        assertSuccess(deleteJson("/api/community/comments/" + rootId, postAuthor), "作者删根评论");

        // 索引行退场（行集 = 当前可见根评论）；评论行保留（软删）
        assertThat(feedIndexRowOf(rootId)).as("根评论软删后索引行退场").isNull();
        assertThat(feedIndexRowCountOf(postId)).isZero();
        assertThat(commentStatusOf(rootId)).isEqualTo("DELETED");

        // 楼内与计数照常：整楼不可见、总数扣 1 + 楼内可见子回复数
        JsonNode replies = getJson("/api/community/comments/" + rootId + "/replies?page=1", null).path("data");
        assertThat(replies.path("list")).isEmpty();
        assertThat(replies.path("total").asLong()).isZero();
        assertThat(postCommentCountOf(postId)).isZero();
        assertThat(commentRowCountOf(postId)).as("软删不删行").isEqualTo(3);
    }

    @Test
    @DisplayName("子回复软删：索引零动作（根评论的索引行与赞数都不动）")
    void replySoftDelete_leavesIndexUntouched() {
        Session postAuthor = registerAndLogin("13800000001", "帖主");
        Session replier = registerAndLogin("13800000002", "回复者");
        String postId = publishPost(postAuthor, "子回复软删的帖子");
        String rootId = publishCommentAndGetId(postId, postAuthor, "根评论");
        String replyId = postReplyAndGetId(rootId, replier, "会被删的回复");
        likeComment(rootId, replier);
        assertThat(feedIndexLikeCountOf(rootId)).isEqualTo(1);

        assertSuccess(deleteJson("/api/community/comments/" + replyId, replier), "作者删子回复");

        assertThat(commentStatusOf(replyId)).isEqualTo("DELETED");
        assertThat(postCommentCountOf(postId)).as("总数 -1（根本身还在）").isEqualTo(1);
        assertThat(feedIndexRowOf(rootId)).as("根评论索引行不受子回复软删影响").isNotNull();
        assertThat(feedIndexLikeCountOf(rootId)).as("索引快照不变").isEqualTo(1);
        assertThat(feedIndexRowCountOf(postId)).isEqualTo(1);
    }

    @Test
    @DisplayName("最热档按索引序：赞数降序、同数 comment_id 升序；offset 跨页不重不漏（同数跨页）")
    void hotSort_isIndexOrder_pagingNoDuplicateNoMiss() {
        Session postAuthor = registerAndLogin("13800000001", "帖主");
        Session commenter = registerAndLogin("13800000002", "评论人");
        Session third = registerAndLogin("13800000003", "路人");
        String postId = publishPost(postAuthor, "热评排序与翻页的帖子");

        List<String> ids = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            ids.add(publishCommentAndGetId(postId, commenter, "评论 " + i));
        }
        // 最热档收敛键 = 索引表 (like_count DESC, create_time ASC, comment_id ASC)，索引行 create_time
        // 是发布时刻快照。UUID 同秒退化为字典序，错开索引表 create_time 固化「同赞数按发布先后」
        staggerCommentIndexCreateTimes(ids.toArray(new String[0]));
        String c1 = ids.get(0);
        String c2 = ids.get(1);
        String c3 = ids.get(2);
        String c4 = ids.get(3);
        String c5 = ids.get(4);
        String c6 = ids.get(5);

        // 赞数：c1=2、c2/c3/c4 各 1（同数靠 create_time, comment_id 升序收敛）、c5/c6 各 0
        likeComment(c1, postAuthor);
        likeComment(c1, third);
        likeComment(c2, postAuthor);
        likeComment(c3, postAuthor);
        likeComment(c4, postAuthor);

        List<String> expected = List.of(c1, c2, c3, c4, c5, c6);
        assertThat(commentIdsOf(listComments(postId, 1, 10, "hot", null))).as("最热全量序").containsExactlyElementsOf(expected);

        // 页大小 2：同数（1 分、0 分）都跨页，按页面拼接必须与全量序一致、且不重不漏
        List<String> paged = new ArrayList<>();
        for (int page = 1; page <= 3; page++) {
            paged.addAll(commentIdsOf(listComments(postId, page, 2, "hot", null)));
        }
        assertThat(paged).as("跨页拼接 = 全量序（同数跨页不重不漏）").containsExactlyElementsOf(expected);
        assertThat(paged).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("索引 like_count 被人工刷偏后最热序跟随索引值（排序读索引快照，不读计数窄表）")
    void hotSortFollowsIndexSnapshot_notMainColumn() {
        Session postAuthor = registerAndLogin("13800000001", "帖主");
        Session commenter = registerAndLogin("13800000002", "评论人");
        String postId = publishPost(postAuthor, "索引刷偏的帖子");
        String c1 = publishCommentAndGetId(postId, commenter, "评论一");
        String c2 = publishCommentAndGetId(postId, commenter, "评论二");
        String c3 = publishCommentAndGetId(postId, commenter, "评论三");
        // c1/c2 同为 0 赞：错开索引表 create_time，让同赞数收敛键落在发布先后上
        staggerCommentIndexCreateTimes(c1, c2, c3);

        // 窄表全是 0；把 c3 的索引快照刷成 5（模拟漂移 / 重建前的人为干预）
        jdbcTemplate.update("UPDATE post_comment_feed_index SET like_count = 5 WHERE comment_id = ?", c3);

        JsonNode hot = listComments(postId, 1, 10, "hot", null);
        assertThat(commentIdsOf(hot)).as("顺序跟随索引快照，c3 登顶").containsExactly(c3, c1, c2);
        // 展示值仍取窄表（0）：序跟着索引走、数跟着窄表走——证明排序确实读的是索引
        assertThat(likeCountOf(hot, c3)).as("展示赞数仍来自计数窄表").isZero();
    }

    @Test
    @DisplayName("根评论索引行缺失时点赞照常成功：落 warn、不补行（交重建 job），索引仍缺")
    void missingIndexRow_warnsAndDoesNotBackfill() {
        Session postAuthor = registerAndLogin("13800000001", "帖主");
        Session commenter = registerAndLogin("13800000002", "评论人");
        String postId = publishPost(postAuthor, "索引行缺失的帖子");
        String rootId = publishCommentAndGetId(postId, commenter, "根评论");
        // 人为造漂移：索引行被删（软删漏删 / 人工直改都长这样）
        jdbcTemplate.update("DELETE FROM post_comment_feed_index WHERE comment_id = ?", rootId);

        List<String> warns;
        try (LogCapture logs = captureWarnLogs(CommunityRepository.class)) {
            likeComment(rootId, postAuthor);
            warns = logs.warnMessagesContaining("post_comment_feed_index", "评论行不存在");
        }

        assertThat(commentLikeCountOf(rootId)).as("点赞本身不被索引缺失拖垮").isEqualTo(1);
        assertThat(feedIndexRowOf(rootId)).as("命中 0 行不 upsert：索引行不会被写路径补回来").isNull();
        assertThat(warns).as("漂移落 warn（commentId 在文案里），交重建 job")
                .anyMatch(message -> message.contains("post_comment_feed_index 行缺失")
                        && message.contains(String.valueOf(rootId)));
    }

    @Test
    @DisplayName("子回复点赞不产生 0 行 warn：它本来就不在索引里（写路径自己判根/子）")
    void replyLike_producesNoDriftWarning() {
        Session postAuthor = registerAndLogin("13800000001", "帖主");
        Session commenter = registerAndLogin("13800000002", "评论人");
        String postId = publishPost(postAuthor, "子回复点赞的帖子");
        String rootId = publishCommentAndGetId(postId, commenter, "根评论");
        String replyId = postReplyAndGetId(rootId, commenter, "楼内子回复");

        List<String> warns;
        try (LogCapture logs = captureWarnLogs(CommunityRepository.class)) {
            likeComment(replyId, postAuthor);
            warns = logs.warnMessagesContaining("post_comment_feed_index", "评论行不存在");
        }

        assertThat(commentLikeCountOf(replyId)).isEqualTo(1);
        assertThat(warns).as("子回复的 0 行不是漂移，不能告警").isEmpty();
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

    private JsonNode listComments(String postId, int page, int pageSize, String sort, Session session) {
        JsonNode response = getJson("/api/community/posts/" + postId + "/comments?page=" + page
                + "&pageSize=" + pageSize + "&sort=" + sort, session);
        assertSuccess(response, "评论列表");
        return response.path("data");
    }

    /** 索引行；不存在返回 null（断言 isNull / isNotNull 时用） */
    private Map<String, Object> feedIndexRowOf(String commentId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT comment_id, post_id, like_count FROM post_comment_feed_index WHERE comment_id = ?", commentId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long feedIndexLikeCountOf(String commentId) {
        Map<String, Object> row = feedIndexRowOf(commentId);
        assertThat(row).as("评论 %s 应有索引行", commentId).isNotNull();
        return ((Number) row.get("like_count")).longValue();
    }

    private String feedIndexPostIdOf(String commentId) {
        Map<String, Object> row = feedIndexRowOf(commentId);
        assertThat(row).as("评论 %s 应有索引行", commentId).isNotNull();
        return String.valueOf(row.get("post_id"));
    }

    private long feedIndexRowCountOf(String postId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM post_comment_feed_index WHERE post_id = ?", Long.class, postId);
        return count == null ? 0 : count;
    }

    /** 计数真源：窄表 post_comment_like_count（票 02 起；主列 post_comment.like_count 已随票 04 删除） */
    private long commentLikeCountOf(String commentId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT like_count FROM post_comment_like_count WHERE comment_id = ?", Long.class, commentId);
        assertThat(count).as("评论 %s 的窄表 like_count", commentId).isNotNull();
        return count;
    }

    private long likeCountOf(JsonNode listData, String commentId) {
        return java.util.stream.StreamSupport.stream(listData.path("list").spliterator(), false)
                .filter(node -> commentId.equals(node.path("commentId").asText()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("列表中找不到评论 " + commentId))
                .path("likeCount").asLong();
    }

    /**
     * 按传入顺序把索引行 create_time 依次错开（第 n 条早 n 秒）——作用于 post_comment_feed_index
     * 索引表（最热档第一级扫描读的是这张表的 create_time 快照）。
     * UUID 化后业务 id 无时间趋势，DATETIME 秒精度下同秒多条的收敛键退化为 comment_id 字典序；
     * 错开 create_time 让「同值段按发布先后」的断言在 UUID 下保持成立。
     */
    private void staggerCommentIndexCreateTimes(String... commentIds) {
        for (int i = 0; i < commentIds.length; i++) {
            jdbcTemplate.update(
                    "UPDATE post_comment_feed_index SET create_time = DATE_SUB(NOW(), INTERVAL ? SECOND) WHERE comment_id = ?",
                    commentIds.length - i, commentIds[i]);
        }
    }
}
