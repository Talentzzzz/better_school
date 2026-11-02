package com.betterschool.integration.community;

import com.betterschool.integration.support.CommunityApiTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * feed-index-uv 票 01：三张新表的存量回填脚本（backend/better-school/sql/backfill/feed-index-uv-backfill.sql）。
 *
 * <p>回填口径钉死两件事——NORMAL 帖才进 feed 索引、feed_time 取审核记录里最近一次通过时刻（查不到审批
 * 流水取 create_time 兜底）；另盯住脚本的可重复执行性：重跑 = 追平回填后旧代码仍在写入产生的窗口期漂移，
 * 业务列不漂不移。初版的 post.view_count → post_view_count 搬数段已随票 07 删列收口移除。
 *
 * <p>测试直接对测试库执行脚本文件本身（不内联复制语句，防脚本与测试漂移）；
 * 开发库/测试库平时不跑回填（由 schema.sql 重建），本测试是把线上库的一次性操作当被测对象钉住。
 */
@DisplayName("feed 索引存量回填：NORMAL 过滤 / feed_time 取最近通过 / 幂等追平")
class FeedIndexUvBackfillIntegrationTest extends CommunityApiTestBase {

    private static final String SCRIPT_RELATIVE = Paths.get("sql", "backfill", "feed-index-uv-backfill.sql").toString();

    @Test
    void 回填只收NORMAL帖且feed_time取最近一次通过时刻() {
        Session author = registerAndLogin("13900001401", "回填作者");

        // 走真实链路的已通过帖：发布 → 裁决通过，content_review 里有 APPROVE 流水
        String approvedPostId = publishPost(author, "回填已通过帖");

        // 无审批流水的存量老帖（直插 NORMAL，绕过审核链路）
        String legacyPostId = insertPost(author.userId(), "NORMAL");

        // 多轮审核的帖子：第一轮三天前通过、第二轮一小时前重提再通过——feed_time 应取最近一次
        String reapprovedPostId = insertPost(author.userId(), "NORMAL");
        jdbcTemplate.update("INSERT INTO content_review (review_id, target_type, target_id, author_id, review_round, action, "
                        + "operator_id, create_time, update_time) VALUES (?, 'POST', ?, ?, 1, 'APPROVE', ?, ?, ?)",
                java.util.UUID.randomUUID().toString(), reapprovedPostId, author.userId(), author.userId(), ts(-3 * 24), ts(-3 * 24));
        jdbcTemplate.update("INSERT INTO content_review (review_id, target_type, target_id, author_id, review_round, action, "
                        + "operator_id, create_time, update_time) VALUES (?, 'POST', ?, ?, 2, 'APPROVE', ?, ?, ?)",
                java.util.UUID.randomUUID().toString(), reapprovedPostId, author.userId(), author.userId(), ts(-1), ts(-1));

        // 非 NORMAL 帖不进索引（被拒）
        String rejectedPostId = insertPost(author.userId(), "REJECTED");

        // 点赞计数窄表：只有 approvedPost 有行（窄表懒建行，无行 = 0）
        jdbcTemplate.update("INSERT INTO post_like_count (post_id, like_count, create_time, update_time) "
                + "VALUES (?, 5, NOW(), NOW())", approvedPostId);

        runBackfillScript();

        // NORMAL 帖各有一行索引
        assertThat(jdbcTemplate.queryForList("SELECT post_id FROM post_feed_index", String.class))
                .as("只回填 NORMAL 帖：被拒帖不进索引").containsExactlyInAnyOrder(approvedPostId, legacyPostId, reapprovedPostId);

        // feed_time = 最近一次 APPROVE 时刻（读库内真实值比对，不做时钟换算）
        java.sql.Timestamp approvedAt = jdbcTemplate.queryForObject(
                "SELECT MAX(create_time) FROM content_review WHERE target_type = 'POST' AND target_id = ? AND action = 'APPROVE'",
                java.sql.Timestamp.class, approvedPostId);
        assertThat(queryFeedTime(approvedPostId)).as("feed_time = 审核通过时刻").isEqualTo(approvedAt.toLocalDateTime());

        assertThat(queryFeedTime(reapprovedPostId))
                .as("feed_time 取最近一次通过（重提再通过 > 首次通过）")
                .isEqualTo(jdbcTemplate.queryForObject(
                        "SELECT MAX(create_time) FROM content_review WHERE target_type = 'POST' AND target_id = ? AND action = 'APPROVE'",
                        java.sql.Timestamp.class, reapprovedPostId).toLocalDateTime());

        // 查不到审批流水 → create_time 兜底
        assertThat(queryFeedTime(legacyPostId)).as("无审批流水兜底 create_time")
                .isEqualTo(jdbcTemplate.queryForObject("SELECT create_time FROM post WHERE post_id = ?",
                        java.sql.Timestamp.class, legacyPostId).toLocalDateTime());

        // like_count 从窄表抄入，无行 = 0
        assertThat(queryLikeCount(approvedPostId)).as("like_count 自窄表抄入").isEqualTo(5);
        assertThat(queryLikeCount(legacyPostId)).as("窄表无行 = like_count 0").isZero();
    }

    @Test
    void 脚本可重复执行且重跑追平窗口期漂移() {
        Session author = registerAndLogin("13900001402", "幂等作者");
        String postId = publishPost(author, "幂等回填帖");
        String leavingPostId = insertPost(author.userId(), "NORMAL");
        jdbcTemplate.update("INSERT INTO post_like_count (post_id, like_count, create_time, update_time) "
                + "VALUES (?, 1, NOW(), NOW())", postId);

        runBackfillScript();
        // 首跑后的"窗口期"：旧代码还在写旧表——点赞涨了、一帖离开 NORMAL
        jdbcTemplate.update("UPDATE post_like_count SET like_count = 9 WHERE post_id = ?", postId);
        jdbcTemplate.update("UPDATE post SET state = 'DELETED' WHERE post_id = ?", leavingPostId);

        runBackfillScript();

        assertThat(queryLikeCount(postId)).as("重跑追平点赞漂移").isEqualTo(9);
        assertThat(jdbcTemplate.queryForObject("SELECT state FROM post_feed_index WHERE post_id = ?", String.class, leavingPostId))
                .as("收口段把离开 NORMAL 的索引行同步成不可见").isEqualTo("DELETED");

        // 再跑一遍：已对齐的行 UPDATE 落回同值，业务列不动（幂等不动点）
        runBackfillScript();
        assertThat(queryLikeCount(postId)).isEqualTo(9);
        assertThat(jdbcTemplate.queryForList("SELECT post_id FROM post_feed_index WHERE state = 'NORMAL'", String.class))
                .as("幂等不动点：索引行不再漂移").containsExactly(postId);
    }

    // ---------- 夹具与脚本执行 ----------

    /** 直插一行 NORMAL/REJECTED 帖（绕过审核链路的存量形态；口径同 SearchIndexRebuildJob 的直插夹具） */
    private String insertPost(String userId, String state) {
        // 时间参数 Java 侧算好再绑：ShardingSphere 解析 NOW() - INTERVAL n DAY 会 bad SQL grammar
        java.sql.Timestamp twoDaysAgo = ts(-48);
        String legacyId = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO post (post_id, user_id, title, content, state, "
                + "create_time, update_time) VALUES (?, ?, '回填存量帖', '存量正文', ?, ?, ?)",
                legacyId, userId, state, twoDaysAgo, twoDaysAgo);
        return legacyId;
    }

    /** now − hours（小时可含小数）；直插夹具造时间差用 */
    private java.sql.Timestamp ts(double hoursAgo) {
        return java.sql.Timestamp.valueOf(java.time.LocalDateTime.now().minusMinutes((long) (hoursAgo * 60)));
    }

    private java.time.LocalDateTime queryFeedTime(String postId) {
        return jdbcTemplate.queryForObject("SELECT feed_time FROM post_feed_index WHERE post_id = ?",
                java.sql.Timestamp.class, postId).toLocalDateTime();
    }

    private int queryLikeCount(String postId) {
        return jdbcTemplate.queryForObject("SELECT like_count FROM post_feed_index WHERE post_id = ?", Integer.class, postId);
    }

    /**
     * 对测试库执行回填脚本文件本身：剥掉 -- 注释行后按分号切语句逐条执行。
     * 脚本不自带 USE（库名由命令行给），所以连到 better_school_test 就灌进测试库。
     */
    private void runBackfillScript() {
        Path script = Paths.get("..", SCRIPT_RELATIVE).toAbsolutePath().normalize();
        if (!Files.exists(script)) {
            script = Paths.get(SCRIPT_RELATIVE).toAbsolutePath().normalize();
        }
        assertThat(Files.exists(script)).as("回填脚本应存在：%s", script).isTrue();
        try {
            String cleaned = Files.readAllLines(script, StandardCharsets.UTF_8).stream()
                    .map(line -> {
                        int comment = line.indexOf("--");
                        return comment >= 0 ? line.substring(0, comment) : line;
                    })
                    .collect(Collectors.joining("\n"));
            for (String statement : cleaned.split(";")) {
                if (!statement.isBlank()) {
                    jdbcTemplate.execute(statement);
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("执行回填脚本失败：" + script, e);
        }
    }
}
