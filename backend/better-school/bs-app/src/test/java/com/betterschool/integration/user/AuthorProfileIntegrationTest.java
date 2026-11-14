package com.betterschool.integration.user;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 作者主页（票 04）集成测试：公开资料接口（免登录、不含手机号、获赞口径）、
 * 五个按用户查询 tab 的访客口径过滤、隐私开关仅他视生效、资料编辑与隐私写入。
 *
 * <p>seam = trigger 层 HTTP 入口；前置态尽量用 SQL 直插（过滤口径才是被测行为），
 * 帖子走发布链路以覆盖真实 NORMAL 语义（审核语义移除后：发布即 NORMAL）。
 */
class AuthorProfileIntegrationTest extends CommunityApiTestBase {

    /** 直插夹具的序号（post/comment 夹具的数字串 id 取号用；product/errand 已改 UUID 直插，uuid-biz-id 票 05） */
    private long fixtureSeq = 0;

    // ---------- 公开资料 ----------

    @Test
    void 公开资料免登录可读_不含手机号_获赞只算NORMAL帖() {
        Session author = registerAndLogin("13800009001", "主页作者");
        String normalPost = publishPost(author, "正常帖子");
        jdbcTemplate.update("INSERT INTO post_like_count (post_id, like_count, create_time, update_time) VALUES (?, ?, NOW(), NOW()) "
                + "ON DUPLICATE KEY UPDATE like_count = VALUES(like_count)", normalPost, 7);
        insertPost(author.userId(), "DRAFT", 100, "草稿帖子");

        JsonNode response = getJson("/api/user/" + author.userId() + "/profile", null);
        assertSuccess(response, "访客读公开资料");
        JsonNode data = response.path("data");
        assertThat(data.path("nickname").asText()).isEqualTo("主页作者");
        assertThat(data.has("phone")).as("公开资料不返回手机号").isFalse();
        assertThat(data.path("receivedLikeCount").asLong()).as("获赞只算 NORMAL 帖").isEqualTo(7);
        assertThat(data.path("showLiked").asBoolean()).as("开关默认公开").isTrue();
        assertThat(data.path("showComments").asBoolean()).as("开关默认公开").isTrue();
    }

    @Test
    void 公开资料_用户不存在报404() {
        JsonNode response = getJson("/api/user/99999999/profile", null);
        assertThat(response.path("code").asText()).as("不存在应 404：%s", response).isEqualTo("404");
    }

    // ---------- 资料编辑 ----------

    @Test
    void 编辑资料写入并可回显_清空与不展示可用() {
        Session author = registerAndLogin("13800009002", "资料作者");

        JsonNode updated = putJson("/api/user/profile",
                Map.of("signature", "热爱生活", "gender", "FEMALE"), author);
        assertSuccess(updated, "编辑资料");
        assertThat(updated.path("data").path("signature").asText()).isEqualTo("热爱生活");
        assertThat(updated.path("data").path("gender").asText()).isEqualTo("FEMALE");

        JsonNode guest = getJson("/api/user/" + author.userId() + "/profile", null);
        assertThat(guest.path("data").path("signature").asText()).as("访客可回显").isEqualTo("热爱生活");
        assertThat(guest.path("data").path("gender").asText()).isEqualTo("FEMALE");

        // 清空签名 + 性别不展示（null 显式传入，HashMap 允许 null 值）
        Map<String, Object> clearBody = new HashMap<>();
        clearBody.put("signature", "");
        clearBody.put("gender", null);
        JsonNode cleared = putJson("/api/user/profile", clearBody, author);
        assertSuccess(cleared, "清空资料");
        assertThat(cleared.path("data").path("signature").isNull()).as("空串清空签名").isTrue();
        assertThat(cleared.path("data").path("gender").isNull()).as("null 即不展示").isTrue();

        assertThat(jdbcTemplate.queryForObject("SELECT signature FROM bs_user WHERE user_id = ?", String.class, author.userId()))
                .as("签名落库为 NULL").isNull();
    }

    @Test
    void 编辑资料_签名超长与非法性别被拒() {
        Session author = registerAndLogin("13800009003", "资料作者2");

        JsonNode tooLong = putJson("/api/user/profile", Map.of("signature", "字".repeat(41)), author);
        assertThat(tooLong.path("code").asText()).as("41 字应拒绝：%s", tooLong).isEqualTo("400");

        JsonNode badGender = putJson("/api/user/profile", Map.of("gender", "OTHER"), author);
        assertThat(badGender.path("code").asText()).as("非法性别应拒绝：%s", badGender).isEqualTo("400");
    }

    // ---------- 隐私设置 ----------

    @Test
    void 隐私设置部分更新_缺省项不改() {
        Session author = registerAndLogin("13800009004", "隐私作者");

        JsonNode response = putJson("/api/user/privacy", Map.of("showLiked", false), author);
        assertSuccess(response, "改隐私");
        assertThat(response.path("data").path("showLiked").asBoolean()).isFalse();
        assertThat(response.path("data").path("showComments").asBoolean()).as("缺省项不改").isTrue();

        JsonNode profile = getJson("/api/user/" + author.userId() + "/profile", null);
        assertThat(profile.path("data").path("showLiked").asBoolean()).as("公开资料带出开关值").isFalse();
        assertThat(profile.path("data").path("showComments").asBoolean()).isTrue();
    }

    @Test
    void 隐私开关他视生效_本人不受影响() {
        Session author = registerAndLogin("13800009005", "隐私作者2");
        Session visitor = registerAndLogin("13800009006", "访客");
        String likedPost = publishPost(author, "被赞帖");
        String commentedPost = insertPost(author.userId(), "NORMAL", 0, "被评论帖");
        jdbcTemplate.update("INSERT INTO post_like (post_id, user_id, create_time, update_time) VALUES (?, ?, NOW(), NOW())",
                likedPost, author.userId());
        insertComment(commentedPost, author.userId(), "NORMAL", "我的评论", LocalDateTime.now());

        putJson("/api/user/privacy", Map.of("showLiked", false, "showComments", false), author);

        // 访客：两个 tab 均为空
        JsonNode guestLiked = getJson("/api/community/users/" + author.userId() + "/liked", visitor);
        assertThat(guestLiked.path("data").path("total").asLong()).as("他视关闭赞过应空").isZero();
        assertThat(guestLiked.path("data").path("list")).isEmpty();
        JsonNode guestComments = getJson("/api/community/users/" + author.userId() + "/comments", visitor);
        assertThat(guestComments.path("data").path("total").asLong()).as("他视关闭评论应空").isZero();

        // 本人：不受影响
        JsonNode ownLiked = getJson("/api/community/users/" + author.userId() + "/liked", author);
        assertThat(ownLiked.path("data").path("total").asLong()).as("本人看自己不受开关影响").isEqualTo(1);
        JsonNode ownComments = getJson("/api/community/users/" + author.userId() + "/comments", author);
        assertThat(ownComments.path("data").path("total").asLong()).isEqualTo(1);
    }

    // ---------- 五个 tab 的访客口径过滤 ----------

    @Test
    void 笔记tab只出NORMAL帖_本人看自己也不出草稿() {
        Session author = registerAndLogin("13800009007", "笔记作者");
        String normalPost = publishPost(author, "公开笔记");
        insertPost(author.userId(), "DRAFT", 0, "草稿笔记");

        JsonNode guest = getJson("/api/community/users/" + author.userId() + "/posts", null);
        assertSuccess(guest, "访客读笔记 tab");
        assertThat(postIdsOf(guest.path("data"))).containsExactly(normalPost);
        assertThat(guest.path("data").path("total").asLong()).isEqualTo(1);

        JsonNode owner = getJson("/api/community/users/" + author.userId() + "/posts", author);
        assertThat(postIdsOf(owner.path("data"))).as("本人同样走公开口径").containsExactly(normalPost);
    }

    @Test
    void 赞过tab源帖仅NORMAL_按点赞时间倒序() {
        Session author = registerAndLogin("13800009008", "赞过作者");
        String first = publishPost(author, "先赞的帖");
        String second = publishPost(author, "后赞的帖");
        String hiddenSource = insertPost(author.userId(), "DRAFT", 0, "草稿源帖");
        jdbcTemplate.update("INSERT INTO post_like (post_id, user_id, create_time, update_time) VALUES (?, ?, ?, ?)",
                first, author.userId(), LocalDateTime.now().minusHours(2), LocalDateTime.now().minusHours(2));
        jdbcTemplate.update("INSERT INTO post_like (post_id, user_id, create_time, update_time) VALUES (?, ?, ?, ?)",
                second, author.userId(), LocalDateTime.now().minusHours(1), LocalDateTime.now().minusHours(1));
        jdbcTemplate.update("INSERT INTO post_like (post_id, user_id, create_time, update_time) VALUES (?, ?, NOW(), NOW())",
                hiddenSource, author.userId());

        JsonNode response = getJson("/api/community/users/" + author.userId() + "/liked", null);
        assertSuccess(response, "访客读赞过 tab");
        assertThat(postIdsOf(response.path("data"))).as("非 NORMAL 源帖不外露且按点赞时间倒序")
                .containsExactly(second, first);
        assertThat(response.path("data").path("total").asLong()).isEqualTo(2);
    }

    @Test
    void 评论tab只出评论与源帖均NORMAL_带来源帖标题与封面() {
        Session author = registerAndLogin("13800009009", "评论作者");
        String visiblePost = insertPost(author.userId(), "NORMAL", 0, "可见来源帖");
        String draftPost = insertPost(author.userId(), "DRAFT", 0, "草稿来源帖");
        insertComment(visiblePost, author.userId(), "NORMAL", "正常评论", LocalDateTime.now());
        insertComment(visiblePost, author.userId(), "DELETED", "已删评论", LocalDateTime.now());
        insertComment(draftPost, author.userId(), "NORMAL", "源帖草稿的评论", LocalDateTime.now());

        JsonNode response = getJson("/api/community/users/" + author.userId() + "/comments", null);
        assertSuccess(response, "访客读评论 tab");
        JsonNode list = response.path("data").path("list");
        assertThat(response.path("data").path("total").asLong()).as("只剩评论与源帖均 NORMAL 的一条").isEqualTo(1);
        assertThat(list.get(0).path("content").asText()).isEqualTo("正常评论");
        assertThat(list.get(0).path("postId").asText()).isEqualTo(visiblePost);
        assertThat(list.get(0).path("postTitle").asText()).as("带来源帖标题").isEqualTo("可见来源帖");
    }

    @Test
    void 闲置tab只在售_草稿与已售不外露() {
        Session author = registerAndLogin("13800009010", "闲置作者");
        String onSale = insertProduct(author.userId(), "ON_SALE", "在售商品");
        insertProduct(author.userId(), "DRAFT", "草稿商品");
        insertProduct(author.userId(), "SOLD", "已售商品");

        JsonNode response = getJson("/api/trade/users/" + author.userId() + "/products", null);
        assertSuccess(response, "访客读闲置 tab");
        assertThat(productIdsOf(response.path("data"))).containsExactly(onSale);
        assertThat(response.path("data").path("total").asLong()).isEqualTo(1);
    }

    @Test
    void 跑腿tab只出发布中_不含接单与终态() {
        Session author = registerAndLogin("13800009011", "跑腿作者");
        Session other = registerAndLogin("13800009012", "接单者");
        String published = insertErrand(author.userId(), "PUBLISHED", "发布中的单", null);
        String confirmed = insertErrand(author.userId(), "CONFIRMED", "已被接的单", other.userId());
        insertErrand(author.userId(), "PENDING_PAYMENT", "待支付的单", null);
        insertErrand(author.userId(), "COMPLETED", "已完成的单", null);
        // 别人发布的、该作者接的单：不应出现在其主页
        insertErrand(other.userId(), "CONFIRMED", "我接的别人的单", author.userId());

        JsonNode response = getJson("/api/errand/users/" + author.userId() + "/orders", null);
        assertSuccess(response, "访客读跑腿 tab");
        assertThat(orderIdsOf(response.path("data"))).as("只列发布中、不含接单").containsExactlyInAnyOrder(confirmed, published);
        assertThat(response.path("data").path("total").asLong()).isEqualTo(2);
    }

    // ---------- 直插前置态 ----------

    private String insertPost(String userId, String status, int likeCount, String title) {
        String postId = String.valueOf(900_000L + (++fixtureSeq));
        jdbcTemplate.update("INSERT INTO post (post_id, user_id, title, content, images, state, "
                        + "create_time, update_time) "
                        + "VALUES (?, ?, ?, '正文', '[]', ?, NOW(), NOW())",
                postId, userId, title, status);
        jdbcTemplate.update("INSERT INTO post_like_count (post_id, like_count, create_time, update_time) VALUES (?, ?, NOW(), NOW()) "
                + "ON DUPLICATE KEY UPDATE like_count = VALUES(like_count)", postId, likeCount);
        jdbcTemplate.update("INSERT INTO post_comment_count (post_id, comment_count, create_time, update_time) VALUES (?, 0, NOW(), NOW()) "
                + "ON DUPLICATE KEY UPDATE comment_count = VALUES(comment_count)", postId);
        return postId;
    }

    /** 直插一行商品（uuid-biz-id 票 05 起业务 id UUID 形态、应用侧发号），返回业务 ID */
    private String insertProduct(String userId, String status, String title) {
        String productId = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO product (product_id, user_id, title, description, images, price, "
                        + "trade_location, category, state, create_time, update_time) "
                        + "VALUES (?, ?, ?, '描述', '[]', 10.00, '驿站', 'BOOKS', ?, NOW(), NOW())",
                productId, userId, title, status);
        return productId;
    }

    /** 直插一行跑腿单（uuid-biz-id 票 05 起业务 id UUID 形态、应用侧发号），返回业务 ID */
    private String insertErrand(String publisherId, String status, String description, String takerId) {
        String orderId = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO errand_order (errand_order_id, publisher_id, taker_id, type, description, "
                        + "pickup_location, delivery_location, reward, state, "
                        + "create_time, update_time) "
                        + "VALUES (?, ?, ?, 'EXPRESS_PICKUP', ?, '菜鸟驿站', '三号宿舍楼', 10.00, ?, NOW(), NOW())",
                orderId, publisherId, takerId, description, status);
        return orderId;
    }

    private String insertComment(String postId, String userId, String status, String content, LocalDateTime createdAt) {
        String commentId = String.valueOf(900_000L + (++fixtureSeq));
        jdbcTemplate.update("INSERT INTO post_comment (comment_id, post_id, user_id, content, state, root_id, parent_id, "
                        + "reply_count, create_time, update_time) VALUES (?, ?, ?, ?, ?, 0, 0, 0, ?, ?)",
                commentId, postId, userId, content, status, createdAt, createdAt);
        return commentId;
    }

    private List<String> postIdsOf(JsonNode listData) {
        return StreamSupport.stream(listData.path("list").spliterator(), false)
                .map(node -> node.path("postId").asText())
                .toList();
    }

    private List<String> productIdsOf(JsonNode listData) {
        return StreamSupport.stream(listData.path("list").spliterator(), false)
                .map(node -> node.path("productId").asText())
                .toList();
    }

    private List<String> orderIdsOf(JsonNode listData) {
        return StreamSupport.stream(listData.path("list").spliterator(), false)
                .map(node -> node.path("orderId").asText())
                .toList();
    }
}
