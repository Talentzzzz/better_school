package com.betterschool.integration.community;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 帖子草稿箱（票 02）集成测试（审核语义移除后翻新）：保存（可留空、仅本人可见）、编辑、
 * 提交（缺必填拦截、补全后提交即 NORMAL 全员可见）、作者软删。
 */
class PostDraftBoxIntegrationTest extends CommunityApiTestBase {

    // ---------- 保存草稿：部分字段可存、仅本人可见 ----------

    @Test
    void 帖子草稿部分字段保存成功_仅本人可见() {
        Session author = registerAndLogin("13800001001", "作者1");
        Session other = registerAndLogin("13800001002", "路人1");

        // 未填完：只有标题
        JsonNode save = postJson("/api/community/posts/draft",
                Map.of("title", "备忘：选课调研"), author);
        assertSuccess(save, "保存帖子草稿");
        String postId = save.path("data").asText();

        assertThat(postStatus(postId)).as("草稿状态应为 DRAFT").isEqualTo("DRAFT");

        JsonNode publicList = getJson("/api/community/posts", other);
        assertThat(postIdsOf(publicList)).as("草稿不应对外可见").doesNotContain(postId);

        JsonNode otherMine = getJson("/api/community/posts/mine", other);
        assertThat(postIdsOf(otherMine)).as("他人我的发帖不含该草稿").doesNotContain(postId);

        JsonNode otherDetail = getJson("/api/community/posts/" + postId, other);
        assertThat(otherDetail.path("code").asText()).as("他人看草稿详情应 404：%s", otherDetail).isEqualTo("404");

        JsonNode mine = getJson("/api/community/posts/mine", author);
        List<JsonNode> mineItems = StreamSupport.stream(mine.path("data").path("list").spliterator(), false)
                .filter(node -> postId.equals(node.path("postId").asText())).toList();
        assertThat(mineItems).as("我的发帖应含自己的草稿").hasSize(1);
        assertThat(mineItems.get(0).path("status").asText()).isEqualTo("DRAFT");

        JsonNode ownerDetail = getJson("/api/community/posts/" + postId, author);
        assertSuccess(ownerDetail, "本人看自己的草稿");
        assertThat(ownerDetail.path("data").path("title").asText()).isEqualTo("备忘：选课调研");
    }

    // ---------- 编辑草稿 ----------

    @Test
    void 帖子草稿可编辑全部内容并回显() {
        Session author = registerAndLogin("13800001003", "作者2");
        String postId = savePostDraft(author, "旧草稿");

        JsonNode update = putJson("/api/community/posts/" + postId + "/draft", Map.ofEntries(
                Map.entry("title", "失物招领：蓝色水杯"),
                Map.entry("content", "在三食堂捡到蓝色保温杯，联系我认领"),
                Map.entry("images", List.of("http://minio/bs-files/it/post-draft-1.jpg"))), author);
        assertSuccess(update, "编辑帖子草稿");

        JsonNode detail = getJson("/api/community/posts/" + postId, author);
        assertSuccess(detail, "回看编辑后的草稿");
        JsonNode data = detail.path("data");
        assertThat(data.path("title").asText()).isEqualTo("失物招领：蓝色水杯");
        assertThat(data.path("content").asText()).isEqualTo("在三食堂捡到蓝色保温杯，联系我认领");
        assertThat(data.path("images")).hasSize(1);
        assertThat(data.path("status").asText()).as("编辑不改状态").isEqualTo("DRAFT");
    }

    // ---------- 提交：缺必填拦截、补全后提交即可见 ----------

    @Test
    void 不完整草稿提交失败提示补全_补全后提交即NORMAL可见() {
        Session author = registerAndLogin("13800001004", "作者3");
        String postId = savePostDraft(author, "只有标题的草稿");

        // 缺正文（唯一必填项，feed-index-uv 票 06 分类退役）：提交被完整性校验拦截
        JsonNode response = postJson("/api/community/posts/" + postId + "/submit", null, author);
        assertThat(response.path("code").asText()).as("缺必填项应拒绝提交：%s", response).isEqualTo("500");
        assertThat(response.path("info").asText()).contains("不完整");
        assertThat(postStatus(postId)).as("校验失败停留草稿").isEqualTo("DRAFT");

        // 补正文后提交成功：即 NORMAL 全员可见（无审核环节）
        assertSuccess(putJson("/api/community/posts/" + postId + "/draft",
                Map.of("content", "正文补上了"), author), "补正文");
        assertSuccess(postJson("/api/community/posts/" + postId + "/submit", null, author), "补全后提交");
        assertThat(postStatus(postId)).as("提交即 NORMAL").isEqualTo("NORMAL");

        JsonNode publicList = getJson("/api/community/posts", null);
        assertThat(postIdsOf(publicList)).as("提交后全员可见").contains(postId);
    }

    // ---------- 作者软删任意状态帖子 ----------

    @Test
    void 作者可软删草稿与已发布帖() {
        Session author = registerAndLogin("13800001010", "作者7");
        String draftId = savePostDraft(author, "不要了的草稿");

        assertSuccess(deleteJson("/api/community/posts/" + draftId, author), "删除草稿");
        assertThat(postStatus(draftId)).as("草稿软删").isEqualTo("DELETED");
        JsonNode detail = getJson("/api/community/posts/" + draftId, author);
        assertThat(detail.path("code").asText()).as("已删草稿详情不可见").isEqualTo("404");

        String publishedId = publishPost(author, "发布后删除的帖子");
        assertSuccess(deleteJson("/api/community/posts/" + publishedId, author), "删除已发布帖");
        assertThat(postStatus(publishedId)).isEqualTo("DELETED");
    }

    // ---------- 工具 ----------

    private String savePostDraft(Session author, String title) {
        JsonNode save = postJson("/api/community/posts/draft", Map.of("title", title), author);
        assertSuccess(save, "保存帖子草稿");
        return save.path("data").asText();
    }

    private String postStatus(String postId) {
        return jdbcTemplate.queryForObject("SELECT state FROM post WHERE post_id = ?", String.class, postId);
    }

    private List<String> postIdsOf(JsonNode response) {
        return StreamSupport.stream(response.path("data").path("list").spliterator(), false)
                .map(node -> node.path("postId").asText()).toList();
    }
}
