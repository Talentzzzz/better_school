package com.betterschool.integration.trade;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 拒绝转草稿重提 + 编辑语义（票 05）集成测试：
 * 商品被拒转草稿箱 → 编辑重提回待审（轮次 +1，两次审核记录并存）；
 * 商品上架后改价立即生效（状态不变、不进审核、无通知）；改标题/描述/图片回待审（他人不可见、卖家本人可见），
 * 重审通过恢复可见并通知、再次拒绝按拒绝路径处理；
 * 待审中编辑请求被拒（只能撤回转草稿）。
 */
class TradeRejectDraftAndEditIntegrationTest extends CommunityApiTestBase {

    // ---------- 商品：被拒 → 转草稿箱 → 重提两轮审核记录并存 ----------

    @Test
    void 商品被拒转草稿箱编辑重提产生第二轮审核且两轮记录并存() {
        Session seller = registerAndLogin("13800006001", "卖家1");
        String productId = createProduct(seller, "九成新吉他", List.of("http://minio/bs-files/it/r-1.jpg"));
        Session admin = adminSession();
        String firstReason = "配图与描述不符";
        rejectProduct(admin, productId, firstReason);
        assertThat(productStatus(productId)).isEqualTo("REJECTED");

        // 非被拒状态不可转草稿（在售商品先验证闸门）
        String onSaleId = createProduct(seller, "九成新贝斯", List.of());
        approveProduct(admin, onSaleId);
        JsonNode guard = postJson("/api/trade/products/" + onSaleId + "/to-draft", null, seller);
        assertThat(guard.path("code").asText()).as("仅被拒商品可转草稿箱：%s", guard).isEqualTo("500");

        // 被拒 → 转草稿箱 → 编辑 → 重提回待审
        assertSuccess(postJson("/api/trade/products/" + productId + "/to-draft", null, seller), "被拒转草稿箱");
        assertThat(productStatus(productId)).as("被拒商品应转入草稿箱").isEqualTo("DRAFT");
        assertSuccess(putJson("/api/trade/products/" + productId + "/draft",
                Map.ofEntries(Map.entry("title", "九五新单板吉他"),
                        Map.entry("description", "换了描述重新提"),
                        Map.entry("images", List.of()),
                        Map.entry("price", 19.9),
                        Map.entry("tradeLocation", "图书馆门口"),
                        Map.entry("category", "BOOKS")), seller), "草稿编辑");
        assertSuccess(postJson("/api/trade/products/" + productId + "/submit", null, seller), "草稿重新提交");
        assertThat(productStatus(productId)).isEqualTo("PENDING_REVIEW");
        Integer productRound = jdbcTemplate.queryForObject(
                "SELECT review_round FROM product WHERE product_id = ?", Integer.class, productId);
        assertThat(productRound).as("重提应轮次 +1 进第二轮").isEqualTo(2);

        // 第二轮再次拒绝：两次审核记录并存，拒绝理由取最新
        String secondReason = "重提后内容仍不合规";
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "PRODUCT", "targetId", productId, "approve", false, "reasonCode", "OTHER", "supplement", secondReason), admin),
                "第二轮拒绝");
        assertThat(productStatus(productId)).isEqualTo("REJECTED");
        Integer reviewRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_review WHERE target_type = 'PRODUCT' AND target_id = ?",
                Integer.class, productId);
        assertThat(reviewRows).as("两轮审核记录并存").isEqualTo(2);
        List<Integer> rounds = jdbcTemplate.queryForList(
                "SELECT review_round FROM content_review WHERE target_type = 'PRODUCT' AND target_id = ? " +
                        "AND action = 'REJECT' ORDER BY review_round", Integer.class, productId);
        assertThat(rounds).as("两轮拒绝分别落轮次 1、2").containsExactly(1, 2);
        JsonNode ownerDetail = getJson("/api/trade/products/" + productId, seller);
        assertThat(ownerDetail.path("data").path("rejectReason").asText())
                .as("拒绝理由按最新一次展示").isEqualTo("其他：" + secondReason);

        // 再次被拒仍可转草稿箱（自救路径可重复走）
        assertSuccess(postJson("/api/trade/products/" + productId + "/to-draft", null, seller), "再次被拒转草稿箱");
        assertThat(productStatus(productId)).isEqualTo("DRAFT");
    }

    // ---------- 商品：上架后改价立即生效 ----------

    @Test
    void 商品上架后改价格立即生效不进审核无通知() {
        Session seller = registerAndLogin("13800006002", "卖家2");
        String productId = createProduct(seller, "九成新台灯", List.of());
        Session admin = adminSession();
        approveProduct(admin, productId);
        int syncCountBefore = searchSyncMessageCount(productId);
        int notificationCountBefore = auditNotificationCount(seller.userId());

        assertSuccess(putJson("/api/trade/products/" + productId,
                Map.of("price", 29.9), seller), "仅改价");

        assertThat(productStatus(productId)).as("改价状态不变").isEqualTo("ON_SALE");
        BigDecimal price = jdbcTemplate.queryForObject(
                "SELECT price FROM product WHERE product_id = ?", BigDecimal.class, productId);
        assertThat(price).as("价格立即生效").isEqualByComparingTo("29.90");
        Integer reviewRound = jdbcTemplate.queryForObject(
                "SELECT review_round FROM product WHERE product_id = ?", Integer.class, productId);
        assertThat(reviewRound).as("改价不进审核，轮次不变").isEqualTo(1);
        Integer reviewRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_review WHERE target_type = 'PRODUCT' AND target_id = ?",
                Integer.class, productId);
        assertThat(reviewRows).as("改价不产生审核记录").isEqualTo(1);
        assertThat(auditNotificationCount(seller.userId())).as("改价无通知").isEqualTo(notificationCountBefore);
        assertThat(searchSyncMessageCount(productId)).as("价格不进搜索文档，改价无索引同步")
                .isEqualTo(syncCountBefore);
        JsonNode detail = getJson("/api/trade/products/" + productId, seller);
        assertThat(detail.path("data").path("price").asDouble()).isEqualTo(29.9);
    }

    // ---------- 商品：上架后改内容回待审 ----------

    @Test
    void 商品上架后改内容回待审他人不可见本人可见重审通过恢复可见() {
        Session seller = registerAndLogin("13800006003", "卖家3");
        Session other = registerAndLogin("13800006004", "路人1");
        String productId = createProduct(seller, "九成新滑板", List.of());
        Session admin = adminSession();
        approveProduct(admin, productId);

        // 他人可编辑他人商品应 403
        JsonNode forbidden = putJson("/api/trade/products/" + productId,
                Map.of("title", "路人乱改"), other);
        assertThat(forbidden.path("code").asText()).as("非卖家编辑应 403：%s", forbidden).isEqualTo("403");

        // 初筛命中：编辑请求被拒，状态与内容均不变
        JsonNode screened = putJson("/api/trade/products/" + productId,
                Map.of("title", "帮我作弊一下"), seller);
        assertThat(screened.path("code").asText()).as("新增文字需过初筛：%s", screened).isEqualTo("500");
        assertThat(productStatus(productId)).isEqualTo("ON_SALE");

        // 改标题/描述/图片 → 回待审（轮次 +1），进入审核队列
        assertSuccess(putJson("/api/trade/products/" + productId,
                Map.of("title", "专业级滑板", "description", "改描述后的新文案",
                        "images", List.of("http://minio/bs-files/it/r-2.jpg")), seller), "改内容");
        assertThat(productStatus(productId)).as("改内容应回待审").isEqualTo("PENDING_REVIEW");
        Integer productRound = jdbcTemplate.queryForObject(
                "SELECT review_round FROM product WHERE product_id = ?", Integer.class, productId);
        assertThat(productRound).as("重审轮次 +1").isEqualTo(2);
        assertThat(productIdsOf(getJson("/api/trade/products", null)))
                .as("重审期间对外列表不可见").doesNotContain(productId);
        JsonNode otherDetail = getJson("/api/trade/products/" + productId, other);
        assertThat(otherDetail.path("code").asText()).as("重审期间他人应 404：%s", otherDetail).isEqualTo("404");
        JsonNode ownerDetail = getJson("/api/trade/products/" + productId, seller);
        assertThat(ownerDetail.path("data").path("title").asText())
                .as("重审期间卖家本人可见新内容（不保留旧版展示）").isEqualTo("专业级滑板");
        assertThat(moderationQueueIds("PRODUCT", admin)).as("重审应进入审核队列").contains(productId);

        // 待审中编辑请求被拒（只能撤回转草稿）
        JsonNode pendingEdit = putJson("/api/trade/products/" + productId,
                Map.of("description", "待审中还想改"), seller);
        assertThat(pendingEdit.path("code").asText()).as("待审中不可编辑：%s", pendingEdit).isEqualTo("500");
        assertThat(pendingEdit.path("info").asText()).contains("撤回");

        // 重审通过 → 恢复对外可见 + 新内容 + 通过通知 + 搜索同步
        int syncCountBefore = searchSyncMessageCount(productId);
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "PRODUCT", "targetId", productId, "approve", true), admin), "重审通过");
        assertThat(productStatus(productId)).isEqualTo("ON_SALE");
        assertThat(productIdsOf(getJson("/api/trade/products", null))).contains(productId);
        JsonNode visibleDetail = getJson("/api/trade/products/" + productId, other);
        assertThat(visibleDetail.path("data").path("title").asText()).isEqualTo("专业级滑板");
        List<String> contents = jdbcTemplate.queryForList(
                "SELECT content FROM notification WHERE user_id = ? AND type = 'AUDIT_RESULT'",
                String.class, seller.userId());
        assertThat(contents).as("重审通过应通知").anyMatch(content -> content.contains("通过审核"));
        assertThat(searchSyncMessageCount(productId)).as("重审通过应落搜索同步消息").isGreaterThan(syncCountBefore);
        Integer approveRound = jdbcTemplate.queryForObject(
                "SELECT review_round FROM content_review WHERE target_type = 'PRODUCT' AND target_id = ? " +
                        "AND action = 'APPROVE' ORDER BY review_round DESC LIMIT 1", Integer.class, productId);
        assertThat(approveRound).as("重审通过记录落轮次 2").isEqualTo(2);
    }

    @Test
    void 商品重审再次拒绝按拒绝路径处理可再转草稿() {
        Session seller = registerAndLogin("13800006005", "卖家4");
        String productId = createProduct(seller, "九成新耳机", List.of());
        Session admin = adminSession();
        approveProduct(admin, productId);

        assertSuccess(putJson("/api/trade/products/" + productId,
                Map.of("title", "改后仍违规的标题"), seller), "改内容回待审");
        String reason = "重审仍不通过";
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "PRODUCT", "targetId", productId, "approve", false, "reasonCode", "OTHER", "supplement", reason), admin),
                "重审拒绝");
        assertThat(productStatus(productId)).as("再次拒绝转 REJECTED").isEqualTo("REJECTED");
        JsonNode ownerDetail = getJson("/api/trade/products/" + productId, seller);
        assertThat(ownerDetail.path("data").path("rejectReason").asText()).isEqualTo("其他：" + reason);
        assertSuccess(postJson("/api/trade/products/" + productId + "/to-draft", null, seller), "再次拒绝仍可转草稿箱");
        assertThat(productStatus(productId)).isEqualTo("DRAFT");
    }

    // ---------- 工具 ----------

    /** 管理员登录态：先注册（USER），改库提权后重新走验证码登录拿含 ADMIN 角色的 JWT */
    private Session adminSession() {
        Session registered = registerAndLogin("13900006001", "管理员");
        jdbcTemplate.update("UPDATE bs_user SET role = 'ADMIN' WHERE user_id = ?", registered.userId());
        postJson("/api/user/send-code", Map.of("phone", "13900006001"), null);
        String code = redissonClient.<String>getBucket(Constants.VERIFY_CODE_KEY + "13900006001").get();
        JsonNode loginResponse = postJson("/api/user/login", Map.of("phone", "13900006001", "code", code), null);
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

    private void approveProduct(Session admin, String productId) {
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "PRODUCT", "targetId", productId, "approve", true), admin), "商品裁决通过");
        assertThat(productStatus(productId)).isEqualTo("ON_SALE");
    }

    private void rejectProduct(Session admin, String productId, String reason) {
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "PRODUCT", "targetId", productId, "approve", false, "reasonCode", "OTHER", "supplement", reason), admin),
                "商品裁决拒绝");
        assertThat(productStatus(productId)).isEqualTo("REJECTED");
    }

    private List<String> moderationQueueIds(String targetType, Session admin) {
        JsonNode queue = getJson("/api/moderation/queue?targetType=" + targetType + "&page=1&pageSize=50", admin);
        assertSuccess(queue, "审核队列");
        return StreamSupport.stream(queue.path("data").path("list").spliterator(), false)
                .map(node -> node.path("targetId").asText()).toList();
    }

    private int searchSyncMessageCount(String productId) {
        return (int) jdbcTemplate.queryForList(
                "SELECT payload FROM outbox_message WHERE destination = ?", String.class, Constants.SEARCH_SYNC_TOPIC)
                .stream().map(this::readTree)
                .filter(node -> "product".equals(node.path("type").asText())
                        && node.path("id").asText().equals(productId))
                .count();
    }

    private int auditNotificationCount(String userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE user_id = ? AND type = 'AUDIT_RESULT'",
                Integer.class, userId);
        return count == null ? 0 : count;
    }

    private String productStatus(String productId) {
        return jdbcTemplate.queryForObject("SELECT state FROM product WHERE product_id = ?", String.class, productId);
    }

    private List<String> productIdsOf(JsonNode response) {
        return StreamSupport.stream(response.path("data").path("list").spliterator(), false)
                .map(node -> node.path("productId").asText()).toList();
    }
}
