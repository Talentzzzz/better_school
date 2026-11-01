package com.betterschool.integration.trade;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.usecase.moderation.ReviewCase;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一裁决接入商品（票 04）集成测试：
 * 商品通过 → ON_SALE 对外可见（无资金环节）；商品拒绝 → REJECTED + 通知含理由 + 详情/我的发布带理由；
 * 重复裁决幂等（条件翻转 + 审核记录轮次幂等）；同一目标两次裁决轮次互不冲突；
 * 审核队列带出待审商品及配图；非管理员 403；用例层不校验调用者角色（operatorId 直传）。
 */
class TradeReviewDecideIntegrationTest extends CommunityApiTestBase {

    @Autowired
    private ReviewCase reviewCase;

    // ---------- 商品：通过 → ON_SALE ----------

    @Test
    void 商品裁决通过转ON_SALE对外可见且作者收通知() {
        Session seller = registerAndLogin("13800005001", "卖家1");
        String productId = createProduct(seller, "九成新吉他", List.of("http://minio/bs-files/it/p-1.jpg"));
        Session admin = adminSession();

        JsonNode response = postJson("/api/admin/reviews/decide",
                Map.of("targetType", "PRODUCT", "targetId", productId, "approve", true), admin);
        assertSuccess(response, "商品裁决通过");
        // 重试场景：重复通过应幂等（ON_SALE 跳过 + 审核记录轮次幂等不重复）
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "PRODUCT", "targetId", productId, "approve", true), admin), "重复通过");

        assertThat(productStatus(productId)).as("通过应转 ON_SALE").isEqualTo("ON_SALE");
        Integer approveRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_review WHERE target_type = 'PRODUCT' AND target_id = ? AND action = 'APPROVE'",
                Integer.class, productId);
        assertThat(approveRows).as("审核记录只插一行").isEqualTo(1);
        Integer reviewRound = jdbcTemplate.queryForObject(
                "SELECT review_round FROM content_review WHERE target_type = 'PRODUCT' AND target_id = ? AND action = 'APPROVE'",
                Integer.class, productId);
        assertThat(reviewRound).as("直接发布的商品首轮审核轮次为 1").isEqualTo(1);
        assertThat(productIdsOf(getJson("/api/trade/products", null)))
                .as("对外列表应可见").contains(productId);

        List<String> contents = jdbcTemplate.queryForList(
                "SELECT content FROM notification WHERE user_id = ? AND type = 'AUDIT_RESULT'",
                String.class, seller.userId());
        assertThat(contents).as("卖家应收到通过通知").anyMatch(content -> content.contains("通过审核"));

        List<String> syncPayloads = jdbcTemplate.queryForList(
                "SELECT payload FROM outbox_message WHERE destination = ?", String.class, Constants.SEARCH_SYNC_TOPIC);
        assertThat(syncPayloads.stream().map(this::readTree)
                .anyMatch(node -> "product".equals(node.path("type").asText())
                        && node.path("id").asText().equals(productId)))
                .as("通过转 ON_SALE 应落搜索同步消息").isTrue();
    }

    // ---------- 商品：拒绝 → REJECTED ----------

    @Test
    void 商品拒绝无理由被参数拒绝且状态不变() {
        Session seller = registerAndLogin("13800005002", "卖家2");
        String productId = createProduct(seller, "九成新台灯", List.of());
        Session admin = adminSession();

        JsonNode response = postJson("/api/admin/reviews/decide",
                Map.of("targetType", "PRODUCT", "targetId", productId, "approve", false), admin);

        assertThat(response.path("code").asText()).as("拒绝理由必填：%s", response).isEqualTo("400");
        assertThat(productStatus(productId)).as("裁决未生效").isEqualTo("PENDING_REVIEW");
    }

    @Test
    void 商品拒绝转REJECTED通知含理由且详情带理由() {
        Session seller = registerAndLogin("13800005003", "卖家3");
        Session other = registerAndLogin("13800005004", "路人1");
        String productId = createProduct(seller, "九成新滑板", List.of());
        String reason = "配图与描述不符";
        Session admin = adminSession();

        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "PRODUCT", "targetId", productId, "approve", false, "reasonCode", "OTHER", "supplement", reason), admin),
                "商品裁决拒绝");

        assertThat(productStatus(productId)).as("拒绝应转 REJECTED").isEqualTo("REJECTED");
        String reviewReason = jdbcTemplate.queryForObject(
                "SELECT reason FROM content_review WHERE target_type = 'PRODUCT' AND target_id = ? AND action = 'REJECT'",
                String.class, productId);
        assertThat(reviewReason).as("拒绝理由应落审核记录").isEqualTo("其他：" + reason);

        List<String> contents = jdbcTemplate.queryForList(
                "SELECT content FROM notification WHERE user_id = ? AND type = 'AUDIT_RESULT'",
                String.class, seller.userId());
        assertThat(contents).as("拒绝通知应含理由").anyMatch(content -> content.contains(reason));

        JsonNode ownerDetail = getJson("/api/trade/products/" + productId, seller);
        assertSuccess(ownerDetail, "卖家看被拒商品");
        assertThat(ownerDetail.path("data").path("rejectReason").asText())
                .as("详情对卖家带出拒绝理由").isEqualTo("其他：" + reason);

        JsonNode mine = getJson("/api/trade/products/mine", seller);
        JsonNode mineItem = mineItemsOf(mine.path("data")).stream()
                .filter(node -> node.path("productId").asText().equals(productId)).findFirst().orElseThrow();
        assertThat(mineItem.path("rejectReason").asText()).as("我的发布带拒绝理由").isEqualTo("其他：" + reason);

        JsonNode otherDetail = getJson("/api/trade/products/" + productId, other);
        assertThat(otherDetail.path("code").asText()).as("他人看被拒商品应 404：%s", otherDetail).isEqualTo("404");
    }

    // ---------- 轮次幂等：同一目标两次裁决互不冲突 ----------

    @Test
    void 同一商品两次裁决审核记录轮次互不冲突且理由取最新() {
        Session seller = registerAndLogin("13800005008", "卖家4");
        String productId = createProduct(seller, "九成新相机", List.of());
        Session admin = adminSession();

        // 第一轮：通过（APPROVE 落轮次 1）
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "PRODUCT", "targetId", productId, "approve", true), admin), "第一轮通过");
        // 售出后重新上架回到待审（review_round +1 = 2）
        assertSuccess(postJson("/api/trade/products/" + productId + "/sold", null, seller), "标记已出");
        assertSuccess(postJson("/api/trade/products/" + productId + "/relist", null, seller), "重新上架");
        assertThat(productStatus(productId)).isEqualTo("PENDING_REVIEW");

        // 第二轮：拒绝（REJECT 落轮次 2，与第一轮 APPROVE 不冲突）
        String reason = "重新上架后内容不合规";
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "PRODUCT", "targetId", productId, "approve", false, "reasonCode", "OTHER", "supplement", reason), admin),
                "第二轮拒绝");

        Integer reviewRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_review WHERE target_type = 'PRODUCT' AND target_id = ?",
                Integer.class, productId);
        assertThat(reviewRows).as("两轮裁决各留一行").isEqualTo(2);
        Integer rejectRound = jdbcTemplate.queryForObject(
                "SELECT review_round FROM content_review WHERE target_type = 'PRODUCT' AND target_id = ? AND action = 'REJECT'",
                Integer.class, productId);
        assertThat(rejectRound).as("第二轮拒绝应落轮次 2").isEqualTo(2);
        assertThat(productStatus(productId)).isEqualTo("REJECTED");

        JsonNode ownerDetail = getJson("/api/trade/products/" + productId, seller);
        assertThat(ownerDetail.path("data").path("rejectReason").asText())
                .as("拒绝理由按最新一次展示").isEqualTo("其他：" + reason);
    }

    // ---------- 轮次幂等：撤回重提产生第二轮审核 ----------

    @Test
    void 商品撤回重提进入第二轮审核且裁决落轮次2() {
        Session seller = registerAndLogin("13800005015", "卖家8");
        String productId = createProduct(seller, "九成新音箱", List.of());
        Session admin = adminSession();

        // 第一轮未裁决先撤回（ticket 02 路径）：撤回不加轮次，草稿重提轮次 +1
        assertSuccess(postJson("/api/trade/products/" + productId + "/withdraw", null, seller), "待审撤回");
        assertThat(productStatus(productId)).isEqualTo("DRAFT");
        assertSuccess(postJson("/api/trade/products/" + productId + "/submit", null, seller), "草稿重新提交");
        assertThat(productStatus(productId)).isEqualTo("PENDING_REVIEW");
        Integer productRound = jdbcTemplate.queryForObject(
                "SELECT review_round FROM product WHERE product_id = ?", Integer.class, productId);
        assertThat(productRound).as("撤回不加轮次，重提应 +1 进第二轮").isEqualTo(2);

        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "PRODUCT", "targetId", productId, "approve", true), admin), "第二轮裁决通过");
        Integer approveRound = jdbcTemplate.queryForObject(
                "SELECT review_round FROM content_review WHERE target_type = 'PRODUCT' AND target_id = ? AND action = 'APPROVE'",
                Integer.class, productId);
        assertThat(approveRound).as("审核记录按业务行轮次落 2").isEqualTo(2);
        assertThat(productStatus(productId)).isEqualTo("ON_SALE");
    }

    // ---------- COMMENT 无回归 ----------

    @Test
    void 评论人工裁决走统一裁决无回归() {
        Session author = registerAndLogin("13800005016", "评论作者");
        String postId = publishPost(author, "正常帖子内容");
        Session admin = adminSession();

        // 先审后发：正常内容落库即待审；拒绝理由必填（缺省 400，无默认文案兜底）
        String rejectedCommentId = publishCommentAndGetId(postId, author, "会被驳回的评论");
        assertThat(commentStatusOf(rejectedCommentId)).isEqualTo("PENDING_REVIEW");
        JsonNode missingReason = postJson("/api/admin/reviews/decide",
                Map.of("targetType", "COMMENT", "targetId", rejectedCommentId, "approve", false), admin);
        assertThat(missingReason.path("code").asText()).as("评论拒绝理由必填").isEqualTo("400");
        assertThat(commentStatusOf(rejectedCommentId)).isEqualTo("PENDING_REVIEW");

        String reason = "涉及辱骂，违反社区规范";
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "COMMENT", "targetId", rejectedCommentId, "approve", false, "reasonCode", "OTHER", "supplement", reason),
                admin), "评论裁决拒绝");
        assertThat(commentStatusOf(rejectedCommentId)).isEqualTo("REJECTED");
        String reviewReason = jdbcTemplate.queryForObject(
                "SELECT reason FROM content_review WHERE target_type = 'COMMENT' AND target_id = ? AND action = 'REJECT'",
                String.class, rejectedCommentId);
        assertThat(reviewReason).as("拒绝理由落审核记录").isEqualTo("其他：" + reason);

        // 通过：审核记录落轮次 1，状态转 NORMAL
        String approvedCommentId = publishCommentAndGetId(postId, author, "正常评论内容");
        assertThat(commentStatusOf(approvedCommentId)).isEqualTo("PENDING_REVIEW");
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "COMMENT", "targetId", approvedCommentId, "approve", true), admin), "评论裁决通过");
        assertThat(commentStatusOf(approvedCommentId)).isEqualTo("NORMAL");
        Integer approveRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_review WHERE target_type = 'COMMENT' AND target_id = ? AND action = 'APPROVE'",
                Integer.class, approvedCommentId);
        assertThat(approveRows).as("审核记录只插一行").isEqualTo(1);
    }

    // ---------- 审核队列 ----------

    @Test
    void 审核队列带出待审商品及配图() {
        Session seller = registerAndLogin("13800005009", "卖家5");
        String productId = createProduct(seller, "九成新键盘",
                List.of("http://minio/bs-files/it/q-1.jpg", "http://minio/bs-files/it/q-2.jpg"));
        Session admin = adminSession();

        JsonNode productQueue = getJson("/api/moderation/queue?targetType=PRODUCT&page=1&pageSize=10", admin);
        assertSuccess(productQueue, "商品审核队列");
        JsonNode productItem = mineItemsOf(productQueue.path("data")).stream()
                .filter(node -> node.path("targetId").asText().equals(productId)).findFirst().orElseThrow();
        assertThat(productItem.path("targetType").asText()).isEqualTo("PRODUCT");
        assertThat(productItem.path("images")).as("队列带出商品配图").hasSize(2);
        assertThat(productItem.path("content").asText()).contains("九成新键盘");

        JsonNode detail = getJson("/api/moderation/queue/PRODUCT/" + productId, admin);
        assertSuccess(detail, "商品审核详情");
        assertThat(detail.path("data").path("images")).hasSize(2);

        // 裁决后队列即时刷新
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "PRODUCT", "targetId", productId, "approve", true), admin), "裁决通过");
        JsonNode queueAfter = getJson("/api/moderation/queue?targetType=PRODUCT&page=1&pageSize=10", admin);
        assertThat(mineItemsOf(queueAfter.path("data")).stream()
                .map(node -> node.path("targetId").asText()))
                .as("已裁决商品应移出队列").doesNotContain(productId);
    }

    // ---------- 权限与调用者口径 ----------

    @Test
    void 非管理员调用裁决被拒() {
        Session seller = registerAndLogin("13800005011", "卖家6");
        String productId = createProduct(seller, "九成新耳机", List.of());
        Session normalUser = registerAndLogin("13800005012", "普通用户");

        JsonNode response = postJson("/api/admin/reviews/decide",
                Map.of("targetType", "PRODUCT", "targetId", productId, "approve", true), normalUser);
        assertThat(response.path("code").asText()).as("普通用户应 403：%s", response).isEqualTo("403");
        assertThat(productStatus(productId)).isEqualTo("PENDING_REVIEW");

        ResponseEntity<String> anonymous = exchange(HttpMethod.POST, "/api/admin/reviews/decide",
                Map.of("targetType", "PRODUCT", "targetId", "1", "approve", true), null);
        assertThat(anonymous.getStatusCode().value()).as("未登录应 401").isEqualTo(401);
    }

    @Test
    void 系统账号operatorId直调用例裁决商品生效() {
        Session seller = registerAndLogin("13800005013", "卖家7");
        String productId = createProduct(seller, "九成新自行车", List.of());
        // operatorId 直传即可走通，用例不校验登录态/角色（HTTP 层拦截角色）
        Session systemAccount = registerAndLogin("13800005014", "系统账号");

        reviewCase.decide("PRODUCT", productId, true, null, null, systemAccount.userId());

        assertThat(productStatus(productId)).as("系统账号 operatorId 裁决应生效").isEqualTo("ON_SALE");
        String operatorId = jdbcTemplate.queryForObject(
                "SELECT operator_id FROM content_review WHERE target_type = 'PRODUCT' AND target_id = ? AND action = 'APPROVE'",
                String.class, productId);
        assertThat(operatorId).as("裁决记录应落系统账号 operatorId").isEqualTo(systemAccount.userId());
    }

    // ---------- 工具 ----------

    /** 管理员登录态：先注册（USER），改库提权后重新走验证码登录拿含 ADMIN 角色的 JWT */
    private Session adminSession() {
        Session registered = registerAndLogin("13900005001", "管理员");
        jdbcTemplate.update("UPDATE bs_user SET role = 'ADMIN' WHERE user_id = ?", registered.userId());
        postJson("/api/user/send-code", Map.of("phone", "13900005001"), null);
        String code = redissonClient.<String>getBucket(Constants.VERIFY_CODE_KEY + "13900005001").get();
        JsonNode loginResponse = postJson("/api/user/login", Map.of("phone", "13900005001", "code", code), null);
        assertSuccess(loginResponse, "管理员重新登录");
        return new Session(loginResponse.path("data").path("token").asText(), registered.userId());
    }

    /** 直接发布商品（先审后售 → PENDING_REVIEW），返回 productId */
    private String createProduct(Session seller, String title, List<String> images) {
        JsonNode response = postJson("/api/trade/products", Map.ofEntries(
                Map.entry("title", title),
                Map.entry("description", "自用闲置，九成新"),
                Map.entry("images", images),
                Map.entry("price", 19.9),
                Map.entry("tradeLocation", "图书馆门口"),
                Map.entry("category", "BOOKS")), seller);
        assertSuccess(response, "发布商品");
        String productId = response.path("data").asText();
        assertThat(productStatus(productId)).as("先审后售：提交即待审").isEqualTo("PENDING_REVIEW");
        return productId;
    }

    private String productStatus(String productId) {
        return jdbcTemplate.queryForObject("SELECT state FROM product WHERE product_id = ?", String.class, productId);
    }

    private List<JsonNode> mineItemsOf(JsonNode listData) {
        return StreamSupport.stream(listData.path("list").spliterator(), false).toList();
    }

    private List<String> productIdsOf(JsonNode response) {
        return StreamSupport.stream(response.path("data").path("list").spliterator(), false)
                .map(node -> node.path("productId").asText()).toList();
    }
}
