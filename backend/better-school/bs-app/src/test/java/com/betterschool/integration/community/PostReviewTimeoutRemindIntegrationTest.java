package com.betterschool.integration.community;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.trigger.job.PostReviewTimeoutScanJob;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 帖子 24h 待审超时提醒（票 04）集成测试：待审超 24h 提醒 ADMIN+AUDITOR；
 * 同一帖子同一轮次只提醒一次（dedup_key 含审核轮次，对齐先例并修正其重提不再提醒的缺口）；
 * 撤回重提后新一轮可再次提醒（轮次取 post.review_round，进入待审锚点取 post.review_entered_at）；
 * 未超 24h 与已裁决的帖子不触发；提醒不改状态，绝不自动裁决。
 */
class PostReviewTimeoutRemindIntegrationTest extends CommunityApiTestBase {

    @Autowired
    private PostReviewTimeoutScanJob postReviewTimeoutScanJob;

    @Test
    void 待审超24h提醒管理员与审核员同轮次去重且不改状态() {
        Session author = registerAndLogin("13800009001", "作者1");
        Session admin = registerAndLogin("13800009002", "管理员1");
        Session auditor = registerAndLogin("13800009003", "审核员1");
        Session normal = registerAndLogin("13800009004", "普通用户1");
        jdbcTemplate.update("UPDATE bs_user SET role = 'ADMIN' WHERE user_id = ?", admin.userId());
        jdbcTemplate.update("UPDATE bs_user SET role = 'AUDITOR' WHERE user_id = ?", auditor.userId());

        String postId = publishPendingPost(author, "超时待审帖");
        String freshPostId = publishPendingPost(author, "新鲜待审帖");
        String decidedPostId = publishPendingPost(author, "已裁决待审帖");
        // 已裁决帖先进入 24h 窗口再裁决：确认裁决后退出扫描集
        jdbcTemplate.update("UPDATE post SET review_entered_at = DATE_SUB(NOW(), INTERVAL 25 HOUR) WHERE post_id = ?",
                decidedPostId);
        reviewCase.decide("POST", decidedPostId, true, null, null, admin.userId());

        // postId 回拨进审时间模拟超 24h（freshPostId 不回拨 = 未超时不提醒）
        jdbcTemplate.update("UPDATE post SET review_entered_at = DATE_SUB(NOW(), INTERVAL 25 HOUR) WHERE post_id = ?",
                postId);

        postReviewTimeoutScanJob.scan(null);
        postReviewTimeoutScanJob.scan(null);

        assertThat(countReviewReminders(admin.userId(), List.of(postId)))
                .as("管理员一条，重复扫描不重复").isEqualTo(1);
        assertThat(countReviewReminders(auditor.userId(), List.of(postId)))
                .as("审核员一条，重复扫描不重复").isEqualTo(1);
        assertThat(countReviewReminders(normal.userId(), List.of(postId)))
                .as("普通用户不应收到提醒").isZero();
        assertThat(countReviewReminders(admin.userId(), List.of(freshPostId)))
                .as("未超 24h 的待审不提醒").isZero();
        assertThat(countReviewReminders(admin.userId(), List.of(decidedPostId)))
                .as("已裁决的帖子不提醒").isZero();

        // 提醒不改状态，绝不自动裁决
        assertThat(postStatus(postId)).isEqualTo("PENDING_REVIEW");
        assertThat(postStatus(freshPostId)).isEqualTo("PENDING_REVIEW");
    }

    @Test
    void 撤回重提进新一轮后可再次提醒() {
        Session author = registerAndLogin("13800009005", "作者2");
        Session admin = registerAndLogin("13800009006", "管理员2");
        jdbcTemplate.update("UPDATE bs_user SET role = 'ADMIN' WHERE user_id = ?", admin.userId());

        String postId = publishPendingPost(author, "重提轮次帖");
        jdbcTemplate.update("UPDATE post SET review_entered_at = DATE_SUB(NOW(), INTERVAL 25 HOUR) WHERE post_id = ?",
                postId);
        postReviewTimeoutScanJob.scan(null);
        assertThat(countReviewReminders(admin.userId(), List.of(postId))).as("第一轮提醒").isEqualTo(1);

        // 撤回 → 草稿箱 → 重提：轮次 +1、进入待审时间重置
        assertSuccess(postJson("/api/community/posts/" + postId + "/withdraw", null, author), "待审撤回");
        assertThat(postStatus(postId)).isEqualTo("DRAFT");
        assertSuccess(postJson("/api/community/posts/" + postId + "/submit", null, author), "草稿重提");
        assertThat(postStatus(postId)).isEqualTo("PENDING_REVIEW");
        Integer round = jdbcTemplate.queryForObject(
                "SELECT review_round FROM post WHERE post_id = ?", Integer.class, postId);
        assertThat(round).as("重提轮次 +1").isEqualTo(2);

        // 新一轮锚点刚重置，未超 24h 不新增提醒（旧轮提醒保留）
        postReviewTimeoutScanJob.scan(null);
        assertThat(countReviewReminders(admin.userId(), List.of(postId)))
                .as("重提后未超 24h 不新增").isEqualTo(1);

        jdbcTemplate.update("UPDATE post SET review_entered_at = DATE_SUB(NOW(), INTERVAL 25 HOUR) WHERE post_id = ?",
                postId);
        postReviewTimeoutScanJob.scan(null);
        postReviewTimeoutScanJob.scan(null);
        assertThat(countReviewReminders(admin.userId(), List.of(postId)))
                .as("重提后新一轮可再次提醒，且同轮次去重").isEqualTo(2);
    }

    // ---------- 工具 ----------

    /** 直接发帖（先审后发：落库即待审），返回 postId */
    private String publishPendingPost(Session author, String title) {
        JsonNode response = postJson("/api/community/posts",
                Map.of("title", title, "content", "正文内容，自用测试"), author);
        assertSuccess(response, "发帖");
        String postId = response.path("data").asText();
        assertThat(postStatus(postId)).as("先审后发：发布即待审").isEqualTo("PENDING_REVIEW");
        return postId;
    }

    private long countReviewReminders(String userId, List<String> postIds) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE user_id = ? AND type = 'ORDER_EVENT' "
                        + "AND content LIKE '%24 小时%' AND ref_type = 'POST' AND ref_id IN ("
                        + postIds.stream().map(id -> "'" + id + "'").reduce((a, b) -> a + "," + b).orElse("''") + ")",
                Integer.class, userId);
        return count == null ? 0 : count;
    }

    private String postStatus(String postId) {
        return jdbcTemplate.queryForObject("SELECT state FROM post WHERE post_id = ?", String.class, postId);
    }
}
