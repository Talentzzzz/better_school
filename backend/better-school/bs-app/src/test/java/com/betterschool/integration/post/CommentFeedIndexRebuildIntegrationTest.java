package com.betterschool.integration.post;

import com.betterschool.integration.support.PostApiTestBase;
import com.betterschool.trigger.job.CommentFeedIndexRebuildJob;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 根评论热评索引全量重建 job（comment-count-narrow-table 票 03）——兜底恢复手段。经 trigger 层 handler
 * （xxl-job 入口）调真实链路，断言只看外部可观察状态：重建后 {@code post_comment_feed_index} 与
 * {@code post_comment} 的 NORMAL 根评论 1:1、like_count 与计数窄表严格一致；同步写路径的两类已知损伤
 * （点赞旁带命中 0 行的缺行、索引快照被绕过直改）与软删漏删的残行/凭空进索引的行，人工制造后由重建修复；
 * 重建只动索引表（窄表是真值源，一字不改）；重跑是幂等不动点。
 */
@DisplayName("根评论热评索引全量重建：严格一致 / 漂移修复 / 只动索引表 / 幂等")
class CommentFeedIndexRebuildIntegrationTest extends PostApiTestBase {

    @Autowired
    private CommentFeedIndexRebuildJob commentFeedIndexRebuildJob;

    @Test
    @DisplayName("重建后索引与 NORMAL 根评论 1:1、快照追平窄表；缺行/刷偏/软删残行/子回复凭空行四类漂移全修")
    void 重建后索引与NORMAL根评论严格一致且修复四类漂移() {
        Session postAuthor = registerAndLogin("13900002601", "重建帖主");
        Session commenter = registerAndLogin("13900002602", "重建评论人");
        Session liker = registerAndLogin("13900002603", "重建点赞人");
        String postId = publishPost(postAuthor, "评论索引重建的帖子");

        String keptId = publishCommentAndGetId(postId, commenter, "正常根评论");
        String missingRowId = publishCommentAndGetId(postId, commenter, "索引行被误删的根评论");
        String driftedId = publishCommentAndGetId(postId, commenter, "索引快照被刷偏的根评论");
        String deletedId = publishCommentAndGetId(postId, commenter, "软删后残行留在索引里的根评论");
        String replyId = postReplyAndGetId(keptId, commenter, "楼内子回复");

        // 真实链路先攒出正常态：kept 被赞 2 次、drifted 被赞 1 次（窄表 = 索引快照）
        likeComment(keptId, postAuthor);
        likeComment(keptId, liker);
        likeComment(driftedId, postAuthor);
        assertThat(indexLikeCountOf(driftedId)).isEqualTo(1);

        // 漂移一：索引行被误删（点赞旁带 UPDATE 命中 0 行的 warn 就是这种状态）
        jdbcTemplate.update("DELETE FROM post_comment_feed_index WHERE comment_id = ?", missingRowId);
        // 漂移二：索引快照被绕过旁路直改（真值仍是窄表的 1）
        jdbcTemplate.update("UPDATE post_comment_feed_index SET like_count = 99 WHERE comment_id = ?", driftedId);
        // 漂移三：根评论软删（走真实链路，会自动删索引行），再人工插回残行——模拟软删漏删索引行
        assertSuccess(deleteJson("/api/community/comments/" + deletedId, commenter), "作者删根评论");
        insertIndexRow(deletedId, postId, 3);
        // 漂移四：子回复凭空进了索引（行集口径被绕过）
        insertIndexRow(replyId, postId, 5);

        // 存量行（本次改动前就已存在的 NORMAL 根评论、没有计数窄表行）
        String legacyId = insertLegacyNormalRoot(postId, commenter.userId());

        commentFeedIndexRebuildJob.rebuild(null);

        // 行集 1:1：只有 NORMAL 根评论有行——软删残行、凭空子回复行、子回复本身都不在
        assertThat(indexCommentIdsOf(postId)).as("索引行集 = 本帖 NORMAL 根评论（1:1，无残行无凭空行；行集断言不依赖序，"
                + "UUID 化后 ORDER BY comment_id 字典序 ≠ 发布顺序）")
                .containsExactlyInAnyOrder(keptId, missingRowId, driftedId, legacyId);
        assertThat(indexRowsWithoutNormalRoot()).as("全表无「非 NORMAL 根评论」的索引行").isEmpty();
        assertThat(normalRootsWithoutIndexRow()).as("每个 NORMAL 根评论都有索引行").isEmpty();

        // 快照追平窄表真值（含被刷偏的那条回到 1，不是保留 99）
        assertThat(indexLikeCountOf(keptId)).as("赞数抄窄表").isEqualTo(2);
        assertThat(indexLikeCountOf(missingRowId)).as("缺行重生，赞数 = 窄表 0").isZero();
        assertThat(indexLikeCountOf(driftedId)).as("刷偏的快照回到窄表真值").isEqualTo(1);
        assertThat(rowsMismatchingNarrowLikeCount()).as("全表 like_count 与窄表逐行一致").isEmpty();
        assertThat(indexPostIdOf(keptId)).as("重灌带所属帖子").isEqualTo(postId);

        // 时间列口径：create_time = 评论发布时刻（行诞生时刻），存量行同样取评论 create_time
        assertThat(indexCreateTimeOf(keptId)).as("create_time 取评论发布时刻")
                .isEqualTo(commentCreateTimeOf(keptId));
        assertThat(indexCreateTimeOf(legacyId)).as("存量行取评论 create_time")
                .isEqualTo(commentCreateTimeOf(legacyId));

        // 最热档跟随重建后的快照：2 → 1 → 0 / 0。同赞数的收敛键 = create_time ASC, comment_id ASC：
        // 存量 legacy 行的索引 create_time 来自评论 create_time（两天前），排在今天发布的 missing 行之前
        assertThat(hotCommentIds(postId)).as("最热序按重建后的索引快照")
                .containsExactly(keptId, driftedId, legacyId, missingRowId);
    }

    @Test
    @DisplayName("重建只动索引表：窄表行集与数值一字不改、缺窄表行的根评论重灌为 0 且不补行")
    void 重建只动索引表_窄表不被改写() {
        Session postAuthor = registerAndLogin("13900002611", "窄表帖主");
        Session commenter = registerAndLogin("13900002612", "窄表评论人");
        String postId = publishPost(postAuthor, "重建只动索引表的帖子");

        String likedId = publishCommentAndGetId(postId, commenter, "被赞两次的根评论");
        String driftedNarrowId = publishCommentAndGetId(postId, commenter, "窄表被直改的根评论");
        String lostNarrowId = publishCommentAndGetId(postId, commenter, "窄表行被误删的根评论");

        likeComment(likedId, postAuthor);
        likeComment(likedId, commenter);
        likeComment(lostNarrowId, postAuthor);

        // 窄表自身的两类漂移：被绕过旁路直改、行被误删。窄表是真值源，重建不该去"矫正"它
        jdbcTemplate.update("UPDATE post_comment_like_count SET like_count = 7 WHERE comment_id = ?", driftedNarrowId);
        jdbcTemplate.update("DELETE FROM post_comment_like_count WHERE comment_id = ?", lostNarrowId);
        Map<String, Integer> narrowBefore = narrowTableSnapshot();

        commentFeedIndexRebuildJob.rebuild(null);

        assertThat(indexLikeCountOf(likedId)).as("赞数照抄窄表").isEqualTo(2);
        assertThat(indexLikeCountOf(driftedNarrowId)).as("索引追平窄表当前值（重建抄窄表，不判窄表对错）").isEqualTo(7);
        assertThat(indexLikeCountOf(lostNarrowId)).as("窄表缺行 = 计数 0（存量语义）").isZero();

        assertThat(narrowTableSnapshot()).as("重建不校准窄表：行集与数值一字不动").isEqualTo(narrowBefore);
        assertThat(narrowTableSnapshot()).as("赞过的两条仍在窄表里").containsKeys(likedId, driftedNarrowId);
        assertThat(narrowTableSnapshot()).as("重建不给缺失的窄表行补行").doesNotContainKey(lostNarrowId);
    }

    @Test
    @DisplayName("重建是幂等不动点：连跑两次行数、内容（除技术时间戳 update_time）与 hot 序完全相同")
    void 重建是幂等不动点() {
        Session postAuthor = registerAndLogin("13900002621", "幂等帖主");
        Session commenter = registerAndLogin("13900002622", "幂等评论人");
        String postId = publishPost(postAuthor, "重建幂等的帖子");
        String likedId = publishCommentAndGetId(postId, commenter, "被赞的根评论");
        String plainId = publishCommentAndGetId(postId, commenter, "没被赞的根评论");
        likeComment(likedId, postAuthor);
        // 先造一条漂移，确保第一次重建真的在"改东西"，第二次才是纯不动点
        jdbcTemplate.update("UPDATE post_comment_feed_index SET like_count = 42 WHERE comment_id = ?", plainId);

        commentFeedIndexRebuildJob.rebuild(null);
        List<String> firstSnapshot = indexSnapshot();
        List<String> firstHotOrder = hotCommentIds(postId);

        commentFeedIndexRebuildJob.rebuild(null);

        assertThat(indexSnapshot()).as("行数、行内容（comment_id/post_id/like_count/create_time）与首跑一致")
                .isEqualTo(firstSnapshot);
        assertThat(hotCommentIds(postId)).as("hot 序一致").isEqualTo(firstHotOrder);
        assertThat(firstHotOrder).as("首跑已经把快照拉回窄表真值").containsExactly(likedId, plainId);
    }

    // ---------- 夹具与可观察状态 ----------

    /** 对目标评论发子回复（走真实链路），返回新回复的 commentId */
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

    /** 绕过链路直插一行索引（模拟软删漏删 / 凭空进索引的漂移态） */
    private void insertIndexRow(String commentId, String postId, int likeCount) {
        jdbcTemplate.update("INSERT INTO post_comment_feed_index (comment_id, post_id, like_count, create_time, update_time) "
                + "VALUES (?, ?, ?, NOW(), NOW())", commentId, postId, likeCount);
    }

    /**
     * 直插一条「本次改动前就已存在」的存量 NORMAL 根评论（绕过发布链路：没有计数窄表行），返回其 commentId
     */
    private String insertLegacyNormalRoot(String postId, String userId) {
        // 时间参数 Java 侧算好再绑：ShardingSphere 解析 NOW() - INTERVAL n DAY 会 bad SQL grammar。
        // 票 03 起业务 id 在应用层生成（UUID.randomUUID()，自增同值回写已删），直插存量行走 UUID 形态
        java.sql.Timestamp twoDaysAgo = java.sql.Timestamp.valueOf(LocalDateTime.now().minusDays(2));
        String commentId = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO post_comment "
                + "(comment_id, post_id, user_id, content, state, root_id, parent_id, create_time, update_time) "
                + "VALUES (?, ?, ?, '存量根评论', 'NORMAL', '0', '0', ?, ?)", commentId, postId, userId, twoDaysAgo, twoDaysAgo);
        return commentId;
    }

    /** 本帖索引里的 comment_id（按字典序；UUID 化后与发布先后无关） */
    private List<String> indexCommentIdsOf(String postId) {
        return jdbcTemplate.queryForList(
                "SELECT comment_id FROM post_comment_feed_index WHERE post_id = ? ORDER BY comment_id", String.class, postId);
    }

    private long indexLikeCountOf(String commentId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT like_count FROM post_comment_feed_index WHERE comment_id = ?", Integer.class, commentId);
        assertThat(value).as("评论 %s 应有索引行", commentId).isNotNull();
        return value;
    }

    private String indexPostIdOf(String commentId) {
        String value = jdbcTemplate.queryForObject(
                "SELECT post_id FROM post_comment_feed_index WHERE comment_id = ?", String.class, commentId);
        assertThat(value).as("评论 %s 应有索引行", commentId).isNotNull();
        return value;
    }

    private LocalDateTime indexCreateTimeOf(String commentId) {
        return jdbcTemplate.queryForObject(
                "SELECT create_time FROM post_comment_feed_index WHERE comment_id = ?", LocalDateTime.class, commentId);
    }

    /** 评论行自己的 create_time（索引 create_time 的口径来源） */
    private LocalDateTime commentCreateTimeOf(String commentId) {
        return jdbcTemplate.queryForObject(
                "SELECT create_time FROM post_comment WHERE comment_id = ?", LocalDateTime.class, commentId);
    }

    /** 破坏者：索引行对应的评论不是「NORMAL 根评论」（软删残行 / 凭空子回复行） */
    private List<String> indexRowsWithoutNormalRoot() {
        return jdbcTemplate.queryForList(
                "SELECT fi.comment_id FROM post_comment_feed_index fi "
                        + "LEFT JOIN post_comment c ON c.comment_id = fi.comment_id "
                        + "WHERE c.comment_id IS NULL OR c.root_id <> '0' OR c.state <> 'NORMAL'", String.class);
    }

    /** 覆盖破坏者：该有索引行而没有行的 NORMAL 根评论 */
    private List<String> normalRootsWithoutIndexRow() {
        return jdbcTemplate.queryForList(
                "SELECT c.comment_id FROM post_comment c "
                        + "LEFT JOIN post_comment_feed_index fi ON fi.comment_id = c.comment_id "
                        + "WHERE c.root_id = '0' AND c.state = 'NORMAL' AND fi.comment_id IS NULL", String.class);
    }

    /** 投影破坏者：like_count ≠ 窄表当前值（无行 = 0）的索引行 */
    private List<String> rowsMismatchingNarrowLikeCount() {
        return jdbcTemplate.queryForList(
                "SELECT fi.comment_id FROM post_comment_feed_index fi "
                        + "LEFT JOIN post_comment_like_count lc ON lc.comment_id = fi.comment_id "
                        + "WHERE fi.like_count <> COALESCE(lc.like_count, 0)", String.class);
    }

    /** 索引整表快照（含 update_time 以外的全部列）——幂等比对用 */
    private List<String> indexSnapshot() {
        return jdbcTemplate.query(
                "SELECT comment_id, post_id, like_count, create_time FROM post_comment_feed_index ORDER BY comment_id",
                (rs, rowNum) -> rs.getString("comment_id") + ":" + rs.getString("post_id") + ":"
                        + rs.getInt("like_count") + ":" + rs.getTimestamp("create_time").toLocalDateTime());
    }

    /** 计数窄表快照（comment_id → like_count），用于断言重建没碰它 */
    private Map<String, Integer> narrowTableSnapshot() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT comment_id, like_count FROM post_comment_like_count ORDER BY comment_id",
                rs -> {
                    counts.put(rs.getString("comment_id"), rs.getInt("like_count"));
                });
        return counts;
    }

    /** 最热档（默认排序即 hot，此处显式传 sort 钉住口径）本页 comment_id 序 */
    private List<String> hotCommentIds(String postId) {
        JsonNode response = getJson("/api/community/posts/" + postId + "/comments?page=1&pageSize=10&sort=hot", null);
        assertSuccess(response, "最热评论列表");
        List<String> ids = new ArrayList<>();
        response.path("data").path("list").forEach(node -> ids.add(node.path("commentId").asText()));
        return ids;
    }
}
