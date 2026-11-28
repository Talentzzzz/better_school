package com.betterschool.integration.post;

import com.betterschool.integration.support.PostApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 评论点赞 + 通知补全（issue 06）：点赞/取消幂等 + 计数窄表（post_comment_like_count，票 02 起的真源）
 * 同事务 ±N + 互动通知去重（sender+目标）+ 不可见评论拒绝点赞 + 最热真实排序。
 * 通知为 chat 域衍生数据：评论点赞在 case 层直接发布通知事件；帖子点赞先发「谁赞了谁」的事实事件
 * （bs-post-liked），由 PostLikedConsumer 决定通知内容后直接落库投递。两条路径都不回滚点赞。
 * 测试断言通知前须 consumeNotifyEvents() 喂消费方（通知落库时机=事件消费时）。
 */
@DisplayName("评论点赞：幂等/计数/通知去重/不可见拒绝/最热真实排序")
class CommentLikeIntegrationTest extends PostApiTestBase {

    @Test
    @DisplayName("点赞幂等（唯一键）：重复赞报错、计数精确 ±1、取消后可再赞")
    void likeIsIdempotent_countExact_canRelikeAfterUnlike() {
        Session postAuthor = registerAndLogin("13800000001", "帖主");
        Session commentAuthor = registerAndLogin("13800000002", "评论人");
        String postId = publishPost(postAuthor, "有评论区的帖子");
        String commentId = publishCommentAndGetId(postId, commentAuthor, "第一楼");

        // 点赞 → 计数 1；重复点赞 → 业务错误且计数不变
        assertSuccess(postJson("/api/community/comments/" + commentId + "/like", Map.of(), postAuthor), "点赞");
        assertThat(commentLikeCountOf(commentId)).as("点赞后 like_count").isEqualTo(1);
        JsonNode repeat = postJson("/api/community/comments/" + commentId + "/like", Map.of(), postAuthor);
        assertThat(repeat.path("code").asText()).as("重复点赞应报业务错误").isNotEqualTo("200");
        assertThat(commentLikeCountOf(commentId)).as("重复点赞计数不变").isEqualTo(1);

        // 取消 → 计数 0；重复取消 → 业务错误
        assertSuccess(deleteJson("/api/community/comments/" + commentId + "/like", postAuthor), "取消点赞");
        assertThat(commentLikeCountOf(commentId)).as("取消后 like_count").isZero();
        JsonNode repeatUnlike = deleteJson("/api/community/comments/" + commentId + "/like", postAuthor);
        assertThat(repeatUnlike.path("code").asText()).as("未赞时取消应报业务错误").isNotEqualTo("200");

        // 取消后可再赞
        assertSuccess(postJson("/api/community/comments/" + commentId + "/like", Map.of(), postAuthor), "再次点赞");
        assertThat(commentLikeCountOf(commentId)).as("再赞后 like_count").isEqualTo(1);
    }

    @Test
    @DisplayName("评论被赞通知：赞→取消→再赞只一条；不同人各一条；取消不撤回已发通知")
    void commentLikeNotification_dedupBySenderAndTarget_unlikeDoesNotRetract() {
        Session postAuthor = registerAndLogin("13800000001", "帖主");
        Session bob = registerAndLogin("13800000002", "小北");
        Session carl = registerAndLogin("13800000003", "小南");
        String postId = publishPost(postAuthor, "有评论区的帖子");
        String commentId = publishCommentAndGetId(postId, bob, "第一楼");

        // 小北赞 → 小北的评论作者收到一条通知；取消不撤回；再赞去重仍一条
        assertSuccess(postJson("/api/community/comments/" + commentId + "/like", Map.of(), carl), "小南点赞");
        consumeNotifyEvents();
        assertThat(notificationCountOf(bob.userId(), "COMMENT_LIKED")).isEqualTo(1);
        assertSuccess(deleteJson("/api/community/comments/" + commentId + "/like", carl), "小南取消赞");
        consumeNotifyEvents();
        assertThat(notificationCountOf(bob.userId(), "COMMENT_LIKED")).as("取消不撤回已发通知").isEqualTo(1);
        assertSuccess(postJson("/api/community/comments/" + commentId + "/like", Map.of(), carl), "小南再赞");
        consumeNotifyEvents();
        assertThat(notificationCountOf(bob.userId(), "COMMENT_LIKED")).as("同一人对同一目标只发一条").isEqualTo(1);

        // 换帖主点赞 → 不同人各一条
        assertSuccess(postJson("/api/community/comments/" + commentId + "/like", Map.of(), postAuthor), "帖主点赞");
        consumeNotifyEvents();
        assertThat(notificationCountOf(bob.userId(), "COMMENT_LIKED")).as("不同触发者各一条").isEqualTo(2);

        // 通知内容：sender_id 与 ref 指向被赞评论（首条来自小南）
        List<Map<String, Object>> notices = jdbcTemplate.queryForList(
                "SELECT sender_id, ref_type, ref_id FROM notification WHERE user_id = ? AND type = 'COMMENT_LIKED' ORDER BY id",
                bob.userId());
        assertThat(notices).hasSize(2);
        assertThat((String) notices.get(0).get("sender_id")).isEqualTo(carl.userId());
        assertThat(notices.get(0).get("ref_type")).isEqualTo("COMMENT");
        assertThat(notices.get(0).get("ref_id")).as("ref_id 指向被赞评论").isEqualTo(commentId);
    }

    @Test
    @DisplayName("帖子被赞通知：同一去重规则；自赞不发")
    void postLikeNotification_dedup_selfLikeNoNotification() {
        Session postAuthor = registerAndLogin("13800000001", "帖主");
        Session bob = registerAndLogin("13800000002", "小北");
        Session carl = registerAndLogin("13800000003", "小南");
        String postId = publishPost(postAuthor, "被赞的帖子");

        assertSuccess(postJson("/api/community/posts/" + postId + "/like", Map.of(), bob), "小北赞帖");
        assertSuccess(postJson("/api/community/posts/" + postId + "/like", Map.of(), carl), "小南赞帖");
        consumeNotifyEvents();
        assertThat(notificationCountOf(postAuthor.userId(), "POST_LIKED")).isEqualTo(2);

        // 小北取消后再赞：仍只两条（不撤回 + 去重）
        assertSuccess(deleteJson("/api/community/posts/" + postId + "/like", bob), "小北取消赞");
        assertSuccess(postJson("/api/community/posts/" + postId + "/like", Map.of(), bob), "小北再赞");
        consumeNotifyEvents();
        assertThat(notificationCountOf(postAuthor.userId(), "POST_LIKED")).as("同一人对同一帖子只发一条").isEqualTo(2);

        // 自赞不发通知
        assertSuccess(postJson("/api/community/posts/" + postId + "/like", Map.of(), postAuthor), "自赞");
        consumeNotifyEvents();
        assertThat(notificationCountOf(postAuthor.userId(), "POST_LIKED")).as("自赞不发").isEqualTo(2);
        assertThat(notificationCountOf(bob.userId(), "POST_LIKED")).isZero();
    }

    @Test
    @DisplayName("对 DELETED 状态的评论点赞被拒绝")
    void likeOnInvisibleCommentRejected() {
        Session postAuthor = registerAndLogin("13800000001", "帖主");
        Session bob = registerAndLogin("13800000002", "小北");
        String postId = publishPost(postAuthor, "有评论区的帖子");

        // 软删：state=DELETED（测试直改状态模拟该终态）
        String deletedId = publishCommentAndGetId(postId, bob, "会被下架的评论");
        jdbcTemplate.update("UPDATE post_comment SET state = 'DELETED', update_time = NOW() WHERE comment_id = ?", deletedId);
        JsonNode deletedLike = postJson("/api/community/comments/" + deletedId + "/like", Map.of(), postAuthor);
        assertThat(deletedLike.path("code").asText()).as("已下架评论不可赞").isNotEqualTo("200");
    }

    @Test
    @DisplayName("重复点赞不重复计（C6）：同一人连点两次，关系行与 like_count 都只算一次")
    void 重复点赞不重复计() {
        Session postAuthor = registerAndLogin("13800000001", "帖主");
        Session commentAuthor = registerAndLogin("13800000002", "评论人");
        String postId = publishPost(postAuthor, "重复点赞不重复计的帖子");
        String commentId = publishCommentAndGetId(postId, commentAuthor, "只应被计一次的评论");

        assertSuccess(postJson("/api/community/comments/" + commentId + "/like", Map.of(), postAuthor), "首次点赞");
        assertThat(commentLikeRowCountOf(commentId)).as("关系行 1 条").isEqualTo(1);
        assertThat(commentLikeCountOf(commentId)).as("计数 1").isEqualTo(1);

        // 前端双击 / HTTP 重试：第二次由唯一键裁决，不再推进计数
        JsonNode repeat = postJson("/api/community/comments/" + commentId + "/like", Map.of(), postAuthor);
        assertThat(repeat.path("code").asText()).as("重复点赞报业务错误").isNotEqualTo("200");
        assertThat(commentLikeRowCountOf(commentId)).as("关系行仍是 1 条").isEqualTo(1);
        assertThat(commentLikeCountOf(commentId)).as("计数仍是 1（不重复计）").isEqualTo(1);
    }

    @Test
    @DisplayName("最热排序按真实点赞数生效：like_count 降序、同数 id 升序；列表项带 likeCount/liked")
    void hotSortByRealLikeCounts() {
        Session postAuthor = registerAndLogin("13800000001", "帖主");
        Session bob = registerAndLogin("13800000002", "小北");
        Session carl = registerAndLogin("13800000003", "小南");
        String postId = publishPost(postAuthor, "评论区排序的帖子");
        String c1 = publishCommentAndGetId(postId, bob, "评论一");
        String c2 = publishCommentAndGetId(postId, bob, "评论二");
        String c3 = publishCommentAndGetId(postId, bob, "评论三");
        // UUID 化后默认档序 = create_time, comment_id：错开 create_time 固化发布先后
        staggerCommentCreateTimes(c1, c2, c3);

        // c2 两赞（帖主+小南），c3 一赞（小南）
        assertSuccess(postJson("/api/community/comments/" + c2 + "/like", Map.of(), postAuthor), "帖主赞 c2");
        assertSuccess(postJson("/api/community/comments/" + c2 + "/like", Map.of(), carl), "小南赞 c2");
        assertSuccess(postJson("/api/community/comments/" + c3 + "/like", Map.of(), carl), "小南赞 c3");

        JsonNode hotData = listComments(postId, 1, 10, "hot", null);
        assertThat(commentIdsOf(hotData)).as("最热：like_count 降序、同数 id 升序").containsExactly(c2, c3, c1);

        JsonNode newData = listComments(postId, 1, 10, "new", null);
        assertThat(commentIdsOf(newData)).as("最新仍按 id 升序").containsExactly(c1, c2, c3);

        // 列表项携带点赞数与当前用户已赞态（小南视角：c2/c3 已赞；访客 liked=false）
        assertThat(likeCountOf(hotData, c2)).isEqualTo(2);
        assertThat(likeCountOf(hotData, c3)).isEqualTo(1);
        assertThat(likeCountOf(hotData, c1)).isZero();
        JsonNode carlView = listComments(postId, 1, 10, "hot", carl);
        assertThat(likedOf(carlView, c2)).as("小南对 c2 已赞").isTrue();
        assertThat(likedOf(carlView, c3)).as("小南对 c3 已赞").isTrue();
        assertThat(likedOf(carlView, c1)).isFalse();
        assertThat(likedOf(hotData, c2)).as("访客视角 liked=false").isFalse();
    }

    @Test
    @DisplayName("楼内子回复可赞：列表项带 likeCount/liked；通知同规则")
    void repliesAreLikable_withLikedStateInList() {
        Session postAuthor = registerAndLogin("13800000001", "帖主");
        Session bob = registerAndLogin("13800000002", "小北");
        String postId = publishPost(postAuthor, "有楼中楼的帖子");
        String rootId = publishCommentAndGetId(postId, bob, "第一楼");
        String replyId = postJson("/api/community/comments/" + rootId + "/replies", Map.of("content", "赞同"), bob)
                .path("data").path("commentId").asText();

        assertSuccess(postJson("/api/community/comments/" + replyId + "/like", Map.of(), postAuthor), "帖主赞子回复");
        assertThat(commentLikeCountOf(replyId)).isEqualTo(1);
        consumeNotifyEvents();
        assertThat(notificationCountOf(bob.userId(), "COMMENT_LIKED")).as("子回复被赞通知其作者").isEqualTo(1);

        JsonNode replies = getJson("/api/community/comments/" + rootId + "/replies?page=1", postAuthor).path("data");
        assertThat(replies.path("list").size()).isEqualTo(1);
        JsonNode replyNode = replies.path("list").get(0);
        assertThat(replyNode.path("commentId").asText()).isEqualTo(replyId);
        assertThat(replyNode.path("likeCount").asLong()).as("楼内列表项点赞数").isEqualTo(1);
        assertThat(replyNode.path("liked").asBoolean()).as("帖主视角已赞态").isTrue();
    }

    // ---------- 本测试类局部辅助 ----------

    private JsonNode listComments(String postId, int page, int pageSize, String sort, Session session) {
        JsonNode response = getJson("/api/community/posts/" + postId + "/comments?page=" + page
                + "&pageSize=" + pageSize + "&sort=" + sort, session);
        assertSuccess(response, "评论列表");
        return response.path("data");
    }

    /** 评论点赞计数真源：窄表 post_comment_like_count（票 02 起；主列 like_count 已随票 04 删除） */
    private long commentLikeCountOf(String commentId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT like_count FROM post_comment_like_count WHERE comment_id = ?", Long.class, commentId);
        assertThat(count).as("评论 %s 的窄表 like_count", commentId).isNotNull();
        return count;
    }

    /** 评论点赞关系行条数：与 like_count 必须始终相等（同步合并写的判据） */
    private long commentLikeRowCountOf(String commentId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM post_comment_like WHERE comment_id = ?", Long.class, commentId);
        return count == null ? 0 : count;
    }

    private long notificationCountOf(String userId, String type) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE user_id = ? AND type = ?", Long.class, userId, type);
        return count == null ? 0 : count;
    }

    private long likeCountOf(JsonNode listData, String commentId) {
        return streamOf(listData)
                .filter(node -> commentId.equals(node.path("commentId").asText()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("列表中找不到评论 " + commentId))
                .path("likeCount").asLong();
    }

    private boolean likedOf(JsonNode listData, String commentId) {
        return streamOf(listData)
                .filter(node -> commentId.equals(node.path("commentId").asText()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("列表中找不到评论 " + commentId))
                .path("liked").asBoolean();
    }

    private java.util.stream.Stream<JsonNode> streamOf(JsonNode listData) {
        return java.util.stream.StreamSupport.stream(listData.path("list").spliterator(), false);
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
}
