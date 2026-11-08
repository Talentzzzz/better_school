package com.betterschool.integration.community;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 根评论列表 v2（票 04）：最热/最新排序 + 作者视角 + 真实总数。
 *
 * <p>排序：sort=hot（赞数降序、同数 comment_id 升序，顺序来自根评论热评索引）与 sort=new（id 升序），
 * 请求不带 sort 时按最热。票 06 起点赞上线、hot 按真实点赞数生效（见 CommentLikeIntegrationTest）；
 * 本类保留无赞场景：赞数全 0 时 hot 退化为 comment_id 升序。
 * 作者视角与访客视角一致：所有 NORMAL 根评论全员可见，total 为全员可见口径。
 */
@DisplayName("根评论列表 v2：最热/最新排序 / 真实总数")
class CommentRootListV2IntegrationTest extends CommunityApiTestBase {

    @Test
    @DisplayName("默认排序 = 最热；无赞（全 0 赞）时 hot 退化为 id 升序，与 sort=new 一致")
    void defaultSort_isHot_degeneratesToIdAscending() {
        Session author = registerAndLogin("13800000001", "帖子作者");
        String postId = publishPost(author, "评论区排序的帖子");
        Session commenter = registerAndLogin("13800000002", "路人甲");
        List<String> publishedIds = new java.util.ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            JsonNode published = publishComment(postId, commenter, "评论 " + i);
            assertSuccess(published, "发布评论 " + i);
            publishedIds.add(published.path("data").path("commentId").asText());
        }
        // uuid-biz-id 票 03：new 档 = post_comment.create_time, comment_id；hot 档同赞数收敛键 = 索引表
        // create_time, comment_id。同秒 UUID 都退化为字典序（与发布先后无关），两表各错开 create_time
        // 固化「发布先后」口径，退化一致性断言才可比
        staggerCommentCreateTimes(publishedIds.toArray(new String[0]));
        staggerCommentIndexCreateTimes(publishedIds.toArray(new String[0]));

        // 不传 sort → 默认最热；全 0 赞退化为发布先后序
        JsonNode defaultData = listCommentsAsGuest(postId, 1, 10);
        assertThat(commentIdsOf(defaultData)).as("默认（最热）退化序 = 发布先后").containsExactlyElementsOf(publishedIds);

        JsonNode hotData = listComments(postId, 1, 10, "hot", null);
        assertThat(commentIdsOf(hotData)).as("显式 hot 与默认结果一致").isEqualTo(commentIdsOf(defaultData));

        JsonNode newData = listComments(postId, 1, 10, "new", null);
        assertThat(commentIdsOf(newData)).as("new = 发布先后序").containsExactlyElementsOf(publishedIds);
        assertThat(commentIdsOf(newData)).as("退化期 hot 与 new 序列一致").isEqualTo(commentIdsOf(defaultData));
    }

    @Test
    @DisplayName("分页：真实 total 与切片，第 2 页接续第 1 页")
    void pagination_realTotal() {
        Session author = registerAndLogin("13800000001", "帖子作者");
        String postId = publishPost(author, "长评论区的帖子");
        Session commenter = registerAndLogin("13800000002", "路人甲");
        List<String> publishedIds = new java.util.ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            JsonNode published = publishComment(postId, commenter, "评论 " + i);
            assertSuccess(published, "发布评论 " + i);
            publishedIds.add(published.path("data").path("commentId").asText());
        }
        // 默认档（不传 sort）= 最热档，第一级扫描读索引表 create_time 快照：两表各错开
        staggerCommentCreateTimes(publishedIds.toArray(new String[0]));
        staggerCommentIndexCreateTimes(publishedIds.toArray(new String[0]));

        JsonNode page1 = listCommentsAsGuest(postId, 1, 10);
        assertThat(page1.path("total").asLong()).as("真实总数").isEqualTo(15);
        assertThat(commentIdsOf(page1)).as("第 1 页 = 发布序前 10 条")
                .containsExactlyElementsOf(publishedIds.subList(0, 10));

        JsonNode page2 = listCommentsAsGuest(postId, 2, 10);
        assertThat(page2.path("total").asLong()).isEqualTo(15);
        assertThat(commentIdsOf(page2)).as("第 2 页 = 发布序后 5 条")
                .containsExactlyElementsOf(publishedIds.subList(10, 15));

        List<String> page1Ids = commentIdsOf(page1);
        List<String> page2Ids = commentIdsOf(page2);
        assertThat(page1Ids).doesNotContainAnyElementsOf(page2Ids);
    }

    // ---------- 本测试类局部辅助 ----------

    /** 指定排序与登录态的根评论列表：data 节点 = {page, pageSize, total, list} */
    private JsonNode listComments(String postId, int page, int pageSize, String sort, Session session) {
        JsonNode response = getJson("/api/community/posts/" + postId + "/comments?page=" + page
                + "&pageSize=" + pageSize + "&sort=" + sort, session);
        assertSuccess(response, "评论列表");
        return response.path("data");
    }

    private List<String> contentsOf(JsonNode listData) {
        return stream(listData).map(node -> node.path("content").asText()).toList();
    }

    private java.util.stream.Stream<JsonNode> stream(JsonNode listData) {
        return java.util.stream.StreamSupport.stream(listData.path("list").spliterator(), false);
    }

    /** 按传入顺序把评论 create_time 依次错开（第 n 条早 n 秒）——post_comment 主表（new 档/默认档回查） */
    private void staggerCommentCreateTimes(String... commentIds) {
        for (int i = 0; i < commentIds.length; i++) {
            jdbcTemplate.update(
                    "UPDATE post_comment SET create_time = DATE_SUB(NOW(), INTERVAL ? SECOND) WHERE comment_id = ?",
                    commentIds.length - i, commentIds[i]);
        }
    }

    /** 同上，但作用于 post_comment_feed_index 索引表（hot 档第一级扫描的 create_time 快照） */
    private void staggerCommentIndexCreateTimes(String... commentIds) {
        for (int i = 0; i < commentIds.length; i++) {
            jdbcTemplate.update(
                    "UPDATE post_comment_feed_index SET create_time = DATE_SUB(NOW(), INTERVAL ? SECOND) WHERE comment_id = ?",
                    commentIds.length - i, commentIds[i]);
        }
    }
}
