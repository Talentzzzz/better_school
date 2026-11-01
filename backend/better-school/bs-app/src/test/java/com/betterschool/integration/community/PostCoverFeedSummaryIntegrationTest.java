package com.betterschool.integration.community;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 帖子封面独立列 + feed 摘要（票 05）集成测试：
 * 独立封面生效（可传相册外的图）、不传封面回退相册第一张（读时兜底不落库）、
 * 无封面判定（封面与相册均空）、草稿封面全量编辑、feed 摘要截断（约前 50 字）与"我的"列表全量正文。
 */
class PostCoverFeedSummaryIntegrationTest extends CommunityApiTestBase {

    private static final String ALBUM_FIRST = "http://minio/bs-files-it/album-1.jpg";
    private static final String ALBUM_SECOND = "http://minio/bs-files-it/album-2.jpg";
    private static final String STANDALONE_COVER = "http://minio/bs-files-it/cover-standalone.jpg";

    // ---------- 独立封面生效 ----------

    @Test
    void 发布指定独立封面_相册外图_列表与详情封面取独立封面() {
        Session author = registerAndLogin("13800002001", "封面作者1");

        JsonNode publish = postJson("/api/community/posts", Map.ofEntries(
                Map.entry("title", "封面优先级验证"),
                Map.entry("content", "带相册与独立封面的帖子"),
                Map.entry("images", List.of(ALBUM_FIRST, ALBUM_SECOND)),
                Map.entry("cover", STANDALONE_COVER)), author);
        assertSuccess(publish, "发布带独立封面帖");
        String postId = publish.path("data").asText();
        reviewCase.decide("POST", postId, true, null, null, author.userId());

        // 封面列存独立封面本身（不落回退值），相册不受影响
        assertThat(coverOf(postId)).as("独立封面应落独立列").isEqualTo(STANDALONE_COVER);
        assertThat(imagesOf(postId)).as("相册保持两图").isEqualTo("[\"" + ALBUM_FIRST + "\",\"" + ALBUM_SECOND + "\"]");

        JsonNode feedItem = feedItemOf(postId);
        assertThat(feedItem.path("cover").asText()).as("feed 封面取独立封面").isEqualTo(STANDALONE_COVER);

        JsonNode mineItem = mineItemOf(author, postId);
        assertThat(mineItem.path("cover").asText()).as("我的列表封面取独立封面").isEqualTo(STANDALONE_COVER);

        JsonNode detail = detailOf(author, postId);
        assertThat(detail.path("cover").asText()).as("详情封面取独立封面").isEqualTo(STANDALONE_COVER);
        assertThat(detail.path("images")).as("详情相册完整返回").hasSize(2);
    }

    // ---------- 不传封面：回退相册第一张（读时兜底） ----------

    @Test
    void 不传封面_列表与详情封面回退相册第一张_库不落回退值() {
        Session author = registerAndLogin("13800002002", "封面作者2");

        String postId = publishPostWithImages(author, "只传相册的帖子", List.of(ALBUM_FIRST, ALBUM_SECOND));

        assertThat(coverOf(postId)).as("不传封面时库保持 NULL（读时兜底，不落回退值）").isNull();

        JsonNode feedItem = feedItemOf(postId);
        assertThat(feedItem.path("cover").asText()).as("feed 封面回退相册第一张").isEqualTo(ALBUM_FIRST);
        assertThat(mineItemOf(author, postId).path("cover").asText()).isEqualTo(ALBUM_FIRST);
        assertThat(detailOf(author, postId).path("cover").asText()).isEqualTo(ALBUM_FIRST);
    }

    // ---------- 无封面判定：封面与相册均空 ----------

    @Test
    void 无图无封面帖_封面字段为null_即无封面帖() {
        Session author = registerAndLogin("13800002003", "封面作者3");

        String postId = publishPostWithImages(author, "纯文字帖", null);

        assertThat(coverOf(postId)).as("无图无封面落库 NULL").isNull();
        assertThat(feedItemOf(postId).path("cover").isNull())
                .as("feed 封面 null，前端按无封面帖（文字小卡）渲染").isTrue();
        assertThat(detailOf(author, postId).path("cover").isNull()).isTrue();
    }

    // ---------- 草稿封面：可指定、可编辑、编辑相册后封面自动跟随 ----------

    @Test
    void 草稿封面全量编辑_提交通过后封面生效() {
        Session author = registerAndLogin("13800002004", "封面作者4");

        JsonNode save = postJson("/api/community/posts/draft", Map.ofEntries(
                Map.entry("title", "带封面的草稿"),
                Map.entry("content", "草稿阶段先定独立封面"),
                Map.entry("cover", STANDALONE_COVER)), author);
        assertSuccess(save, "保存带封面草稿");
        String postId = save.path("data").asText();
        assertThat(coverOf(postId)).as("草稿封面落列").isEqualTo(STANDALONE_COVER);

        // 全量编辑：换独立封面并补相册（编辑接口全量覆盖，cover 同 images 一样参与覆盖）
        assertSuccess(putJson("/api/community/posts/" + postId + "/draft", Map.ofEntries(
                Map.entry("title", "带封面的草稿"),
                Map.entry("content", "草稿阶段先定独立封面"),
                Map.entry("images", List.of(ALBUM_FIRST)),
                Map.entry("cover", ALBUM_SECOND)), author), "编辑草稿封面");
        assertThat(coverOf(postId)).isEqualTo(ALBUM_SECOND);

        assertSuccess(postJson("/api/community/posts/" + postId + "/submit", null, author), "提交草稿");
        reviewCase.decide("POST", postId, true, null, null, author.userId());

        assertThat(feedItemOf(postId).path("cover").asText())
                .as("提交后 feed 封面取编辑后的独立封面").isEqualTo(ALBUM_SECOND);
    }

    @Test
    void 独立封面被编辑清空后_封面回退相册第一张() {
        Session author = registerAndLogin("13800002005", "封面作者5");

        // 发布带独立封面帖 → 撤回回草稿箱 → 编辑清空封面（cover 不传即清空）→ 重提
        JsonNode publish = postJson("/api/community/posts", Map.ofEntries(
                Map.entry("title", "相册在封面在"),
                Map.entry("content", "独立封面清空后回退相册"),
                Map.entry("images", List.of(ALBUM_FIRST, ALBUM_SECOND)),
                Map.entry("cover", STANDALONE_COVER)), author);
        assertSuccess(publish, "发布带独立封面帖");
        String postId = publish.path("data").asText();

        assertSuccess(postJson("/api/community/posts/" + postId + "/withdraw", null, author), "撤回待审回草稿箱");

        JsonNode clearCover = putJson("/api/community/posts/" + postId + "/draft", Map.ofEntries(
                Map.entry("title", "相册在封面在"),
                Map.entry("content", "独立封面清空后回退相册"),
                Map.entry("images", List.of(ALBUM_FIRST, ALBUM_SECOND))), author);
        assertSuccess(clearCover, "编辑清空封面");
        assertThat(coverOf(postId)).as("编辑不传 cover 应清空独立封面").isNull();

        assertSuccess(postJson("/api/community/posts/" + postId + "/submit", null, author), "重提草稿");
        reviewCase.decide("POST", postId, true, null, null, author.userId());

        assertThat(feedItemOf(postId).path("cover").asText())
                .as("清空独立封面后回退相册第一张（读时兜底跟随相册）").isEqualTo(ALBUM_FIRST);
    }

    // ---------- feed 摘要截断 ----------

    @Test
    void feed摘要正文截断约前50字_我的列表正文保持全量() {
        Session author = registerAndLogin("13800002006", "摘要作者");
        String longContent = "这是一段用于验证信息流摘要截断的正文字符。" + "再补一段日常描述让正文超过五十个字的长度限制哦。" + "结尾标记。" + "额外补充。";
        assertThat(longContent.length()).as("前置：正文确超 50 字").isGreaterThan(50);

        String postId = publishPostWithImages(author, longContent, null);

        String feedContent = feedItemOf(postId).path("content").asText();
        assertThat(feedContent).as("feed 摘要应为前 50 字").isEqualTo(longContent.substring(0, 50));

        assertThat(mineItemOf(author, postId).path("content").asText())
                .as("我的列表正文口径不变：全量").isEqualTo(longContent);

        JsonNode guestFeedItem = feedItemOf(postId);
        assertThat(guestFeedItem.path("viewCount").isNull())
                .as("访客 feed 不暴露浏览量（口径回归）").isTrue();
    }

    @Test
    void feed摘要短正文原样返回() {
        Session author = registerAndLogin("13800002007", "摘要作者2");
        String shortContent = "不足五十字的正文";
        String postId = publishPostWithImages(author, shortContent, null);
        assertThat(feedItemOf(postId).path("content").asText()).isEqualTo(shortContent);
    }

    // ---------- fixtures & 取数 ----------

    /** 发布（带可选相册，无封面）并裁决通过，返回 postId */
    private String publishPostWithImages(Session author, String content, List<String> images) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("title", "测试帖子");
        body.put("content", content);
        if (images != null) {
            body.put("images", images);
        }
        JsonNode response = postJson("/api/community/posts", body, author);
        assertSuccess(response, "发帖");
        String postId = response.path("data").asText();
        reviewCase.decide("POST", postId, true, null, null, author.userId());
        return postId;
    }

    private String coverOf(String postId) {
        return jdbcTemplate.queryForObject("SELECT cover FROM post WHERE post_id = ?", String.class, postId);
    }

    private String imagesOf(String postId) {
        return jdbcTemplate.queryForObject("SELECT images FROM post WHERE post_id = ?", String.class, postId);
    }

    /** 主页 feed 中指定帖子的列表项 */
    private JsonNode feedItemOf(String postId) {
        JsonNode response = getJson("/api/community/posts?page=1&pageSize=50", null);
        assertSuccess(response, "主页 feed");
        return itemOf(response, postId, "feed");
    }

    private JsonNode mineItemOf(Session author, String postId) {
        JsonNode response = getJson("/api/community/posts/mine?page=1&pageSize=50", author);
        assertSuccess(response, "我的列表");
        return itemOf(response, postId, "我的列表");
    }

    private JsonNode detailOf(Session author, String postId) {
        JsonNode response = getJson("/api/community/posts/" + postId, author);
        assertSuccess(response, "帖子详情");
        return response.path("data");
    }

    private JsonNode itemOf(JsonNode response, String postId, String what) {
        return StreamSupport.stream(response.path("data").path("list").spliterator(), false)
                .filter(node -> postId.equals(node.path("postId").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(what + "中找不到帖子 " + postId));
    }
}
