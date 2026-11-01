package com.betterschool.integration.community;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;


import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * feed-index-uv 票 03：post_feed_index 的同事务直写触发点——状态流转（审核通过行诞生、驳回/撤回/
 * 被拒转草稿/软删不可见化）与赞合并事务的 like_count ±N 旁路。
 *
 * <p>断言只看外部可观察状态：post_feed_index 行的 state / feed_time / like_count，以及
 * (like_count, post_id) 热榜序。 feed_time 只在 INSERT 侧落（行不存在才写），重提再通过走的是
 * INSERT（前一轮从未通过、无行）——feed_time 自然等于重提轮的通过时刻；ODKU 命中已有行是纯防御
 * 分支（双审并发），正常流不可达，不刷 feed_time 的口径由票 01 回填测试从脚本侧钉住。
 */
@DisplayName("feed 索引同步触发点：通过诞生 / 撤回重提 / 不可见化 / 点赞 ±N")
class PostFeedIndexSyncIntegrationTest extends CommunityApiTestBase {

    private static final String OPERATOR_ID = "auditor-1";

    @Test
    void 审核通过诞生索引行待审不产行() {
        Session author = registerAndLogin("13900002301", "索引作者");
        String postId = publishPendingPost(author, "索引通过帖");

        // 先审后发：待审对外不可见，也不进索引
        assertThat(feedRow(postId)).as("待审帖不进索引").isNull();

        LocalDateTime approveStartedAt = LocalDateTime.now();
        reviewCase.decide("POST", postId, true, null, null, OPERATOR_ID);

        Map<String, Object> row = feedRow(postId);
        assertThat(row).as("通过后索引行诞生").isNotNull();
        assertThat(row.get("state")).as("行以 NORMAL 诞生").isEqualTo("NORMAL");
        assertThat(((LocalDateTime) row.get("feed_time")))
                .as("feed_time = 通过时刻（行诞生时刻）")
                .isBetween(approveStartedAt.minusSeconds(5), LocalDateTime.now().plusSeconds(5));
        assertThat(((Number) row.get("like_count")).intValue()).as("诞生时 like_count = 0").isZero();

        // 浏览（UV 口径）不触发索引：详情读后索引行不受影响（票面第 3 条）
        getJson("/api/community/posts/" + postId, author);
        assertThat(((Number) feedRow(postId).get("like_count")).intValue()).as("浏览不碰索引行").isZero();
    }

    @Test
    void 撤回重提再通过后feed_time为重提轮的通过时刻() {
        Session author = registerAndLogin("13900002302", "撤回作者");
        String postId = publishPendingPost(author, "撤回重提帖");

        // 撤回回草稿（从未通过、无索引行，不可见化 0 行静默）
        postJson("/api/community/posts/" + postId + "/withdraw", Map.of(), author);
        assertThat(feedRow(postId)).as("撤回后仍无索引行（从未通过审）").isNull();

        LocalDateTime resubmitAt = LocalDateTime.now();
        postJson("/api/community/posts/" + postId + "/submit", Map.of(), author);
        reviewCase.decide("POST", postId, true, null, null, OPERATOR_ID);

        assertThat(feedState(postId)).as("重提再通过后可见").isEqualTo("NORMAL");
        assertThat(feedTimeOf(postId))
                .as("feed_time = 重提轮的通过时刻（不早于重提时刻）")
                .isAfterOrEqualTo(resubmitAt.minusSeconds(5));
    }

    @Test
    void 驳回与转草稿不产索引行删除后行不可见化() {
        Session author = registerAndLogin("13900002303", "驳回作者");

        // 驳回（终态）与被拒转草稿都发生在通过之前：索引行不存在，也不该凭空产生
        String rejectedPostId = publishPendingPost(author, "驳回帖");
        reviewCase.decide("POST", rejectedPostId, false, "OTHER", "驳回夹具", OPERATOR_ID);
        assertThat(feedRow(rejectedPostId)).as("驳回不产索引行").isNull();
        postJson("/api/community/posts/" + rejectedPostId + "/to-draft", Map.of(), author);
        assertThat(feedRow(rejectedPostId)).as("被拒转草稿不产索引行").isNull();

        // 通过后删除：索引行同事务翻 DELETED（软删/强删共用 softDelete 口）
        String deletedPostId = publishPost(author, "删除帖");
        assertThat(feedState(deletedPostId)).as("删除前可见").isEqualTo("NORMAL");
        deleteJson("/api/community/posts/" + deletedPostId, author);
        assertThat(feedState(deletedPostId)).as("删除后索引行不可见化").isEqualTo("DELETED");
        assertThat(normalIndexIds()).as("feed 取 NORMAL，删帖不在其列").doesNotContain(deletedPostId);
    }

    @Test
    void 点赞推进索引like_count并改变热榜位次() {
        Session author = registerAndLogin("13900002304", "热榜作者");
        Session liker1 = registerAndLogin("13900002305", "点赞人一");
        Session liker2 = registerAndLogin("13900002306", "点赞人二");
        Session liker3 = registerAndLogin("13900002307", "点赞人三");
        String postA = publishPost(author, "热榜帖A");
        String postB = publishPost(author, "热榜帖B");

        // 点赞走合并器，HTTP 返回时事务已提交：索引 like_count 与窄表同值
        like(postA, liker1);
        like(postA, liker2);
        like(postB, liker3);
        assertThat(indexLikeCount(postA)).as("索引 like_count 随赞合并 +N").isEqualTo(2);
        assertThat(indexLikeCount(postB)).isEqualTo(1);
        assertThat(postLikeCountOf(postA)).as("与窄表真源一致").isEqualTo(2);

        // 热榜序 (like_count DESC, feed_time DESC, post_id DESC)：A(2) 压过 B(1)
        assertThat(hotOrder()).as("热榜位次随赞变化").containsExactly(postA, postB);

        // 取消赞回落：A 掉到与 B 同分。uuid-biz-id 票 03：同分收敛键 = feed_time DESC, post_id DESC——
        // UUID 无序，同分位次确定但不再偏爱「后发的帖子」（自增时代的 id DESC 便车），按同一收敛键独立推导期望
        unlike(postA, liker2);
        assertThat(indexLikeCount(postA)).as("取消赞 -N 回落").isEqualTo(1);
        List<String> expectedTie = jdbcTemplate.queryForList(
                "SELECT post_id FROM post_feed_index WHERE post_id IN (?, ?) "
                        + "ORDER BY like_count DESC, feed_time DESC, post_id DESC", String.class, postA, postB);
        assertThat(expectedTie).hasSize(2);
        assertThat(hotOrder()).as("同分位次按收敛键确定").containsExactlyElementsOf(expectedTie);
    }

    // ---------- 夹具与可观察状态 ----------

    /** 发帖不裁决（先审后发：落库即待审），返回 postId */
    private String publishPendingPost(Session author, String content) {
        JsonNode response = postJson("/api/community/posts",
                Map.of("title", "测试帖子", "content", content), author);
        assertSuccess(response, "发帖");
        return response.path("data").asText();
    }

    private void like(String postId, Session user) {
        assertSuccess(postJson("/api/community/posts/" + postId + "/like", Map.of(), user), "点赞");
    }

    private void unlike(String postId, Session user) {
        assertSuccess(deleteJson("/api/community/posts/" + postId + "/like", user), "取消赞");
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

    private LocalDateTime feedTimeOf(String postId) {
        return ((LocalDateTime) feedRow(postId).get("feed_time"));
    }

    private int indexLikeCount(String postId) {
        return ((Number) feedRow(postId).get("like_count")).intValue();
    }

    /** 「最新」序下的可见帖（state = NORMAL 的索引行 id 集合） */
    private List<String> normalIndexIds() {
        return jdbcTemplate.queryForList(
                "SELECT post_id FROM post_feed_index WHERE state = 'NORMAL'", String.class);
    }

    /** 热榜序：(like_count, post_id) DESC——索引 idx_state_like 的扫描序 */
    private List<String> hotOrder() {
        return jdbcTemplate.queryForList(
                "SELECT post_id FROM post_feed_index WHERE state = 'NORMAL' "
                        + "ORDER BY like_count DESC, feed_time DESC, post_id DESC", String.class);
    }
}
