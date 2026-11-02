package com.betterschool.integration.moderation;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 工单详情聚合（票 07）集成测试：四类内容各取一次详情断言通用列（内容/配图/作者/进入待审时刻/审核轮次）
 * + 类型专属上下文（跑腿悬赏、评论的所属帖子/父评论）+ 同一内容历轮裁决 + 同一作者历史裁决
 * （content_review 单表 author_id 直查）；详情纯只读不改变业务行状态（工单不承载可见性）。
 * seam = trigger 层 HTTP 入口 + MySQL 可观察状态。
 */
class ReviewTaskDetailIntegrationTest extends CommunityApiTestBase {

    // ---------- 四类型详情聚合：通用列 + 类型专属上下文 ----------

    @Test
    void 四类型详情聚合通用列与类型专属上下文() {
        Session author = registerAndLogin("13800007001", "作者七");
        Session admin = adminSession();
        jdbcTemplate.update("UPDATE bs_user SET avatar = ? WHERE user_id = ?",
                "http://minio.test/bs-files-it/avatar.png", author.userId());

        // 帖子（带配图）：发布落库即待审
        String postId = publishPendingPostWithImages(author);
        // 商品：提交落库即待审
        String productId = createProduct(author);
        // 评论：宿主帖由另一用户发布并裁决通过（其工单停 DONE；且其裁决记录不落入 author 的作者历史），根评论 + 回复各开一张工单
        Session hostAuthor = registerAndLogin("13800007004", "宿主作者");
        String hostPostId = publishPostWithCustomTitle(hostAuthor, "评论宿主帖子标题");
        String rootCommentId = publishCommentAndGetId(hostPostId, author, "根评论正文");
        String replyCommentId = publishReplyAndGetId(hostPostId, rootCommentId, author, "楼中楼回复正文");
        // 跑腿：支付成功进待审
        String orderId = createPaidOrder(author);

        // ---- 帖子详情：通用列齐 + 无类型专属上下文 ----
        JsonNode postDetail = taskDetail(admin, taskIdOf("POST", postId));
        assertThat(postDetail.path("targetType").asText()).isEqualTo("POST");
        assertThat(postDetail.path("targetId").asText()).isEqualTo(postId);
        assertThat(postDetail.path("reviewRound").asInt()).isEqualTo(1);
        assertThat(postDetail.path("claimState").asText()).isEqualTo("PENDING");
        assertThat(postDetail.path("enteredAt").isNull()).isFalse();
        assertThat(postDetail.path("authorId").asText()).isEqualTo(author.userId());
        assertThat(postDetail.path("authorNickname").asText()).isEqualTo("作者七");
        assertThat(postDetail.path("authorAvatar").asText()).isEqualTo("http://minio.test/bs-files-it/avatar.png");
        assertThat(postDetail.path("content").asText()).contains("待审帖子", "带配图的正文内容");
        assertThat(imagesOf(postDetail)).containsExactly("http://minio.test/bs-files-it/post1.png");
        assertThat(postDetail.path("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(postDetail.path("contextPostId").isNull()).as("帖子无评论上下文").isTrue();
        assertThat(postDetail.path("reward").isNull()).isTrue();
        assertThat(postDetail.path("targetReviews").size()).as("尚无裁决记录").isZero();
        assertThat(postDetail.path("authorReviews").size()).isZero();

        // ---- 商品详情：价格沿用 reward 列 ----
        JsonNode productDetail = taskDetail(admin, taskIdOf("PRODUCT", productId));
        assertThat(productDetail.path("targetType").asText()).isEqualTo("PRODUCT");
        assertThat(productDetail.path("content").asText()).contains("待审商品", "自用闲置九成新");
        assertThat(productDetail.path("reward").decimalValue()).isEqualByComparingTo("19.9");
        assertThat(productDetail.path("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(productDetail.path("enteredAt").isNull()).isFalse();

        // ---- 跑腿详情：悬赏 ----
        JsonNode errandDetail = taskDetail(admin, taskIdOf("ERRAND", orderId));
        assertThat(errandDetail.path("targetType").asText()).isEqualTo("ERRAND");
        assertThat(errandDetail.path("content").asText()).isEqualTo("帮忙取个快递");
        assertThat(errandDetail.path("reward").decimalValue()).isEqualByComparingTo("10");
        assertThat(errandDetail.path("enteredAt").isNull()).isFalse();

        // ---- 评论详情（根评论）：所属帖子上下文，无父评论 ----
        JsonNode rootDetail = taskDetail(admin, taskIdOf("COMMENT", rootCommentId));
        assertThat(rootDetail.path("targetType").asText()).isEqualTo("COMMENT");
        assertThat(rootDetail.path("content").asText()).isEqualTo("根评论正文");
        assertThat(rootDetail.path("enteredAt").isNull()).isFalse();
        assertThat(rootDetail.path("contextPostId").asText()).isEqualTo(hostPostId);
        assertThat(rootDetail.path("contextPostTitle").asText()).isEqualTo("评论宿主帖子标题");
        assertThat(rootDetail.path("contextParentId").isNull()).as("根评论无父评论").isTrue();
        assertThat(rootDetail.path("contextParentContent").isNull()).isTrue();
        assertThat(rootDetail.path("authorReviews").size()).as("评论作者历史同口径可查").isZero();

        // ---- 评论详情（楼中楼回复）：父评论文本可取 ----
        JsonNode replyDetail = taskDetail(admin, taskIdOf("COMMENT", replyCommentId));
        assertThat(replyDetail.path("contextPostId").asText()).isEqualTo(hostPostId);
        assertThat(replyDetail.path("contextParentId").asText()).isEqualTo(rootCommentId);
        assertThat(replyDetail.path("contextParentContent").asText()).isEqualTo("根评论正文");

        // ---- 详情纯只读：调用后业务行状态不变（工单不承载可见性） ----
        assertThat(jdbcTemplate.queryForObject("SELECT state FROM post WHERE post_id = ?", String.class, postId))
                .isEqualTo("PENDING_REVIEW");
        assertThat(jdbcTemplate.queryForObject("SELECT state FROM product WHERE product_id = ?", String.class, productId))
                .isEqualTo("PENDING_REVIEW");
        assertThat(jdbcTemplate.queryForObject("SELECT state FROM errand_order WHERE errand_order_id = ?", String.class, orderId))
                .isEqualTo("PENDING_REVIEW");
        assertThat(jdbcTemplate.queryForObject("SELECT state FROM post_comment WHERE comment_id = ?", String.class, rootCommentId))
                .isEqualTo("PENDING_REVIEW");
    }

    // ---------- 历轮裁决与作者历史裁决 ----------

    @Test
    void 同一内容历轮裁决与同一作者历史单表可查() {
        Session author = registerAndLogin("13800007002", "作者八");
        Session admin = adminSession();

        // 商品两轮：提交 → 拒(轮1) → 转草稿 → 重提(轮2) → 拒(轮2)（遗留入口收口：裁决前先领单）
        String productId = createProduct(author);
        claimTaskOf(admin, "PRODUCT", productId);
        assertSuccess(postJson("/api/moderation/review",
                Map.of("targetType", "PRODUCT", "targetId", productId, "action", "REJECT",
                        "reasonCode", "OTHER", "supplement", "图片与实物不符"), admin), "轮1拒绝");
        String round1TaskId = taskIdOf("PRODUCT", productId);
        assertSuccess(postJson("/api/trade/products/" + productId + "/to-draft", null, author), "被拒转草稿");
        assertSuccess(postJson("/api/trade/products/" + productId + "/submit", null, author), "重提");
        String round2TaskId = taskIdOf("PRODUCT", productId);
        assertThat(round2TaskId).as("重提 = 新轮次 = 新工单行").isNotEqualTo(round1TaskId);

        // 轮 2 在途时详情：历轮已有轮 1 的拒绝记录
        JsonNode pendingDetail = taskDetail(admin, round2TaskId);
        assertThat(pendingDetail.path("reviewRound").asInt()).isEqualTo(2);
        assertThat(pendingDetail.path("claimState").asText()).isEqualTo("PENDING");
        assertThat(pendingDetail.path("targetReviews").size()).isEqualTo(1);
        assertThat(pendingDetail.path("targetReviews").get(0).path("reviewRound").asInt()).isEqualTo(1);
        assertThat(pendingDetail.path("targetReviews").get(0).path("action").asText()).isEqualTo("REJECT");
        assertThat(pendingDetail.path("targetReviews").get(0).path("reason").asText()).contains("图片与实物不符");

        // 轮 2 再拒：DONE 工单详情仍可看（不筛业务行状态），两轮各自结论与拒绝理由齐
        claimTaskOf(admin, "PRODUCT", productId);
        assertSuccess(postJson("/api/moderation/review",
                Map.of("targetType", "PRODUCT", "targetId", productId, "action", "REJECT",
                        "reasonCode", "OTHER", "supplement", "描述与实物不符"), admin), "轮2拒绝");
        JsonNode doneDetail = taskDetail(admin, round2TaskId);
        assertThat(doneDetail.path("claimState").asText()).isEqualTo("DONE");
        assertThat(doneDetail.path("decidedAt").isNull()).isFalse();
        assertThat(doneDetail.path("targetReviews").size()).as("两轮各一条").isEqualTo(2);
        assertThat(doneDetail.path("targetReviews").get(0).path("reviewRound").asInt()).isEqualTo(1);
        assertThat(doneDetail.path("targetReviews").get(0).path("reason").asText()).contains("图片与实物不符");
        assertThat(doneDetail.path("targetReviews").get(1).path("reviewRound").asInt()).isEqualTo(2);
        assertThat(doneDetail.path("targetReviews").get(1).path("reason").asText()).contains("描述与实物不符");

        // 同作者另一内容（跑腿）被拒：作者历史单表聚合（content_review.author_id 直查，免 join 工单表）
        String orderId = createPaidOrder(author);
        claimTaskOf(admin, "ERRAND", orderId);
        assertSuccess(postJson("/api/moderation/review",
                Map.of("targetType", "ERRAND", "targetId", orderId, "action", "REJECT",
                        "reasonCode", "OTHER", "supplement", "与描述不符"), admin), "跑腿拒绝");

        JsonNode detailWithHistory = taskDetail(admin, round2TaskId);
        JsonNode authorReviews = detailWithHistory.path("authorReviews");
        assertThat(authorReviews.size()).as("同作者 3 条裁决：商品两轮 + 跑腿一轮").isEqualTo(3);
        List<String> historyTargetIds = StreamSupport.stream(authorReviews.spliterator(), false)
                .map(node -> node.path("targetId").asText()).toList();
        assertThat(historyTargetIds).contains(productId, orderId);
        StreamSupport.stream(authorReviews.spliterator(), false)
                .forEach(node -> assertThat(node.path("targetType").asText())
                        .as("单表按 author_id 聚合，跨内容类型齐").isIn("PRODUCT", "ERRAND"));

        // 轮 1 已 DONE 的旧工单详情同样可看（不因工单终态失效）
        JsonNode round1Detail = taskDetail(admin, round1TaskId);
        assertThat(round1Detail.path("claimState").asText()).isEqualTo("DONE");
        assertThat(round1Detail.path("targetReviews").size()).isEqualTo(2);
    }

    // ---------- 异常与越权 ----------

    @Test
    void 工单不存在返回404且非审核员不可见详情() {
        Session author = registerAndLogin("13800007003", "作者九");
        JsonNode notFound = getJson("/api/moderation/tasks/999999", adminSession());
        assertThat(notFound.path("code").asText()).isEqualTo("404");

        // 用真实存在的工单验证越权：作者自己发的跑腿单的工单 id
        String orderId = createPaidOrder(author);
        String taskId = taskIdOf("ERRAND", orderId);
        JsonNode forbidden = getJson("/api/moderation/tasks/" + taskId, author);
        assertThat(forbidden.path("code").asText()).as("普通用户不可读工单详情").isEqualTo("403");
    }

    // ---------- 工具 ----------

    /** 详情取数入口：GET /api/moderation/tasks/{taskId} */
    private JsonNode taskDetail(Session admin, String taskId) {
        JsonNode response = getJson("/api/moderation/tasks/" + taskId, admin);
        assertSuccess(response, "工单详情");
        return response.path("data");
    }

    /** 按类型 + 内容定位最新一轮工单业务键 task_id（UUID 字符串，uuid-biz-id 票 06） */
    private String taskIdOf(String targetType, String targetId) {
        String taskId = jdbcTemplate.queryForObject(
                "SELECT task_id FROM review_task WHERE target_type = ? AND target_id = ? " +
                        "ORDER BY review_round DESC LIMIT 1", String.class, targetType, targetId);
        assertThat(taskId).as("%s/%s 应已开单", targetType, targetId).isNotNull();
        return taskId;
    }

    private List<String> imagesOf(JsonNode detail) {
        return StreamSupport.stream(detail.path("images").spliterator(), false)
                .map(JsonNode::asText).toList();
    }

    private String publishPendingPostWithImages(Session author) {
        JsonNode response = postJson("/api/community/posts",
                Map.of("title", "待审帖子", "content", "带配图的正文内容",
                        "images", List.of("http://minio.test/bs-files-it/post1.png")), author);
        assertSuccess(response, "发帖");
        return response.path("data").asText();
    }

    private String publishPostWithCustomTitle(Session hostAuthor, String title) {
        JsonNode response = postJson("/api/community/posts",
                Map.of("title", title, "content", "宿主正文"), hostAuthor);
        assertSuccess(response, "发帖");
        String postId = response.path("data").asText();
        reviewCase.decide("POST", postId, true, null, null, hostAuthor.userId());
        return postId;
    }

    /** 发布楼中楼回复（回复根评论；postId 由父评论定位），返回新评论 id */
    private String publishReplyAndGetId(String postId, String parentCommentId, Session author, String content) {
        JsonNode response = postJson("/api/community/comments/" + parentCommentId + "/replies",
                Map.of("content", content), author);
        assertSuccess(response, "发布回复");
        return response.path("data").path("commentId").asText();
    }

    private String createProduct(Session seller) {
        JsonNode response = postJson("/api/trade/products", Map.ofEntries(
                Map.entry("title", "待审商品"),
                Map.entry("description", "自用闲置九成新"),
                Map.entry("images", List.of()),
                Map.entry("price", 19.9),
                Map.entry("tradeLocation", "图书馆门口"),
                Map.entry("category", "BOOKS")), seller);
        assertSuccess(response, "发布商品");
        return response.path("data").asText();
    }

    private String createPaidOrder(Session author) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", "EXPRESS_PICKUP");
        body.put("description", "帮忙取个快递");
        body.put("pickupLocation", "菜鸟驿站");
        body.put("deliveryLocation", "6号楼楼下");
        body.put("reward", 10);
        body.put("timeLimit", "HOURS_2");
        JsonNode createResponse = postJson("/api/errand/orders", body, author);
        assertSuccess(createResponse, "发布跑腿");
        String orderId = createResponse.path("data").asText();
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/pay", null, author), "支付");
        return orderId;
    }

    /** 管理员登录态：先注册（USER），改库提权后重新走验证码登录拿含 ADMIN 角色的 JWT */
    private Session adminSession() {
        Session registered = registerAndLogin("13900007001", "管理员");
        jdbcTemplate.update("UPDATE bs_user SET role = 'ADMIN' WHERE user_id = ?", registered.userId());
        postJson("/api/user/send-code", Map.of("phone", "13900007001"), null);
        String code = redissonClient.<String>getBucket(Constants.VERIFY_CODE_KEY + "13900007001").get();
        JsonNode loginResponse = postJson("/api/user/login", Map.of("phone", "13900007001", "code", code), null);
        assertSuccess(loginResponse, "管理员重新登录");
        return new Session(loginResponse.path("data").path("token").asText(), registered.userId());
    }
}
