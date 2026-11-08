package com.betterschool.integration.community;

import com.betterschool.infrastructure.adapter.merge.CountMerger;
import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.trigger.job.FeedIndexRebuildJob;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * feed-index-uv 票 07：feed 索引全量重建 job——兜底恢复手段。经 trigger 层 handler（xxl-job 入口）
 * 调真实链路，断言只看外部可观察状态：重建后 post_feed_index 与 post / post_like_count 严格一致、
 * post_view_count 恒等于 user_view_history 行数；同步写路径的两类已知损伤（赞合并旁路命中 0 行的
 * 缺行、窄表被绕过直改的漂移）人工制造后由重建修复；重跑是幂等不动点。
 */
@DisplayName("feed 索引全量重建：严格一致 / 0 行缺行与漂移修复 / 浏览校准 / 幂等")
class FeedIndexRebuildIntegrationTest extends CommunityApiTestBase {

    /** 浏览是 fire-and-forget 合并写：断言前等窗口排空 */
    private static final long DRAIN_MILLIS = 5_000;

    @Autowired
    private FeedIndexRebuildJob feedIndexRebuildJob;
    @Autowired
    private CountMerger countMerger;

    @Test
    void 重建后索引行与post状态和窄表严格一致且修复缺行漂移() {
        Session author = registerAndLogin("13900002501", "重建作者");
        Session liker = registerAndLogin("13900002502", "重建点赞人");

        // 真实链路三态：发布（有赞）、发布后删除（有行的不可见态）、
        // 存量老帖（直插 NORMAL，feed_time 取 create_time）
        String approvedId = publishPost(author, "重建通过帖");
        like(approvedId, liker);
        String deletedId = publishPost(author, "重建删除帖");
        deleteJson("/api/community/posts/" + deletedId, author);
        String legacyId = insertLegacyNormalPost(author.userId());

        // 0 行更新边角：赞合并旁路的 UPDATE 命中 0 行（索引行缺失）——窄表继续涨、快照丢失
        String missingRowId = publishPost(author, "重建缺行帖");
        jdbcTemplate.update("DELETE FROM post_feed_index WHERE post_id = ?", missingRowId);
        like(missingRowId, liker);
        // 直接 UPDATE 窄表制造漂移（不经索引旁路）
        jdbcTemplate.update("UPDATE post_like_count SET like_count = 99 WHERE post_id = ?", missingRowId);

        feedIndexRebuildJob.rebuild(null);

        // 缺行修复：行重生，like_count 从窄表抄回（与直改后的窄表同值）
        Map<String, Object> repaired = feedRow(missingRowId);
        assertThat(repaired).as("缺失的索引行由重建补回").isNotNull();
        assertThat(repaired.get("state")).isEqualTo("NORMAL");
        assertThat(((Number) repaired.get("like_count")).intValue()).as("like_count 追平窄表直改值").isEqualTo(99);

        // 不可见态行保留：通过过再删除的帖子有行，state 快照 = post.state
        assertThat(feedState(deletedId)).as("删除帖的索引行保留且不可见化").isEqualTo("DELETED");
        assertThat(((Number) feedRow(deletedId).get("like_count")).intValue()).isZero();

        // 发布后索引行诞生（likedId 有一条 like_count = 1 的行）
        assertThat(feedRow(approvedId)).as("发布帖进索引").isNotNull();
        assertThat(((Number) feedRow(approvedId).get("like_count")).intValue()).as("赞数抄窄表").isEqualTo(1);

        // 存量老帖：feed_time = create_time
        assertThat(feedRow(legacyId)).as("存量 NORMAL 帖进索引").isNotNull();
        assertThat(queryFeedTime(legacyId)).isEqualTo(jdbcTemplate.queryForObject(
                "SELECT create_time FROM post WHERE post_id = ?", java.sql.Timestamp.class, legacyId).toLocalDateTime());

        // 全表扫严格一致：索引行 state == post.state、like_count == 窄表（无行 = 0）；
        // 每 NORMAL 帖都有行（feed 出 ID → 回查组装的读路径不漏帖）
        assertThat(inconsistentIndexPostIds()).as("索引行与 post/窄表逐行一致").isEmpty();
        assertThat(normalPostsWithoutIndexRow()).as("每 NORMAL 帖都有索引行").isEmpty();

        // 重跑 = 幂等不动点
        feedIndexRebuildJob.rebuild(null);
        assertThat(inconsistentIndexPostIds()).isEmpty();
        assertThat(normalPostsWithoutIndexRow()).isEmpty();
        assertThat(((Number) feedRow(missingRowId).get("like_count")).intValue()).isEqualTo(99);
    }

    @Test
    void 浏览加速列校准为明细行数并清孤儿补缺行() {
        Session author = registerAndLogin("13900002503", "重建浏览作者");
        Session viewer1 = registerAndLogin("13900002504", "重建浏览人一");
        Session viewer2 = registerAndLogin("13900002505", "重建浏览人二");

        String viewedId = publishPost(author, "重建浏览帖");
        view(viewedId, viewer1);
        view(viewedId, viewer2);
        view(viewedId, viewer1); // 重复浏览：明细只刷 viewed_at，UV 不加数
        String lostRowId = publishPost(author, "重建缺计数行帖");
        view(lostRowId, viewer1);
        String orphanId = publishPost(author, "重建孤儿帖"); // 无浏览，人为插计数行

        assertThat(countMerger.awaitIdle(DRAIN_MILLIS)).as("窗口过后必须排空").isTrue();
        // 漂移：直接改加速列（明细行数的真值是 2）
        jdbcTemplate.update("UPDATE post_view_count SET view_count = 999 WHERE post_id = ?", viewedId);
        // 缺行：真实浏览有明细，计数行被误删
        jdbcTemplate.update("DELETE FROM post_view_count WHERE post_id = ?", lostRowId);
        // 孤儿：无浏览却建了计数行（懒建行口径下不该存在）
        jdbcTemplate.update("INSERT INTO post_view_count (post_id, view_count, create_time, update_time) "
                + "VALUES (?, 7, NOW(), NOW())", orphanId);

        feedIndexRebuildJob.rebuild(null);

        assertThat(viewCountOf(viewedId)).as("漂移值校准回明细行数").isEqualTo(2);
        assertThat(viewCountOf(lostRowId)).as("缺失的计数行由重建补回").isEqualTo(1);
        assertThat(countRowExists(orphanId))
                .as("孤儿计数行清掉（无浏览不建行）").isFalse();

        // 全表扫：post_view_count ≡ user_view_history 行数投影（一行不多、一行不少、值恒等）
        assertThat(viewCountRowsMismatchingHistory()).as("加速列与明细行数严格一致").isEmpty();
        assertThat(historyPostsWithoutCountRow()).as("有明细的帖子都有计数行").isEmpty();
    }

    // ---------- 夹具与可观察状态 ----------

    private void like(String postId, Session user) {
        assertSuccess(postJson("/api/community/posts/" + postId + "/like", Map.of(), user), "点赞");
    }

    private void view(String postId, Session user) {
        assertSuccess(getJson("/api/community/posts/" + postId, user), "浏览详情");
    }

    /** 直插一行存量 NORMAL 帖（绕过发布链路），返回 postId */
    private String insertLegacyNormalPost(String userId) {
        // 时间参数 Java 侧算好再绑：ShardingSphere 解析 NOW() - INTERVAL n DAY 会 bad SQL grammar
        java.sql.Timestamp twoDaysAgo = java.sql.Timestamp.valueOf(LocalDateTime.now().minusDays(2));
        // 票 03 起业务 id 在应用层生成（UUID.randomUUID()，自增同值回写已删），直插存量行走 UUID 形态
        String postId = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO post (post_id, user_id, title, content, state, create_time, update_time) "
                + "VALUES (?, ?, '重建存量帖', '存量正文', 'NORMAL', ?, ?)", postId, userId, twoDaysAgo, twoDaysAgo);
        return postId;
    }

    /** 索引行整行（无行 = null） */
    private Map<String, Object> feedRow(String postId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT state, feed_time, like_count FROM post_feed_index WHERE post_id = ?", postId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String feedState(String postId) {
        Map<String, Object> row = feedRow(postId);
        return row == null ? null : (String) row.get("state");
    }

    private LocalDateTime queryFeedTime(String postId) {
        return ((LocalDateTime) feedRow(postId).get("feed_time"));
    }

    private boolean countRowExists(String postId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM post_view_count WHERE post_id = ?", Long.class, postId);
        return count != null && count > 0;
    }

    private int viewCountOf(String postId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT view_count FROM post_view_count WHERE post_id = ?", Integer.class, postId);
        return value == null ? 0 : value;
    }

    /** 逐行扫一致性破坏者：state ≠ post.state、like_count ≠ 窄表（无行 = 0）或 feed_time ≠ post.create_time 的索引行 */
    private List<String> inconsistentIndexPostIds() {
        return jdbcTemplate.queryForList(
                "SELECT fi.post_id FROM post_feed_index fi "
                        + "JOIN post p ON p.post_id = fi.post_id "
                        + "LEFT JOIN post_like_count lc ON lc.post_id = fi.post_id "
                        + "WHERE fi.state <> p.state OR fi.like_count <> COALESCE(lc.like_count, 0) "
                        + "OR fi.feed_time <> p.create_time", String.class);
    }

    /** 覆盖破坏者：该有行而没有行的 NORMAL 帖 */
    private List<String> normalPostsWithoutIndexRow() {
        return jdbcTemplate.queryForList(
                "SELECT p.post_id FROM post p "
                        + "LEFT JOIN post_feed_index fi ON fi.post_id = p.post_id "
                        + "WHERE p.state = 'NORMAL' AND fi.post_id IS NULL", String.class);
    }

    /** 投影破坏者：view_count ≠ 明细行数的加速列行 */
    private List<String> viewCountRowsMismatchingHistory() {
        return jdbcTemplate.queryForList(
                "SELECT vc.post_id FROM post_view_count vc "
                        + "LEFT JOIN (SELECT post_id, COUNT(*) AS cnt FROM user_view_history GROUP BY post_id) h "
                        + "  ON h.post_id = vc.post_id "
                        + "WHERE vc.view_count <> COALESCE(h.cnt, 0)", String.class);
    }

    /** 覆盖破坏者：有浏览明细却没有计数行的帖子 */
    private List<String> historyPostsWithoutCountRow() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT h.post_id FROM user_view_history h "
                        + "LEFT JOIN post_view_count vc ON vc.post_id = h.post_id "
                        + "WHERE vc.post_id IS NULL", String.class);
    }
}
