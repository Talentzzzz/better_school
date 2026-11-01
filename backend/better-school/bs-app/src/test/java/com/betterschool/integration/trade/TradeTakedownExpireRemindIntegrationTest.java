package com.betterschool.integration.trade;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.trigger.job.TradeExpireScanJob;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 下架/到期汇入草稿箱 + 24h 待审超时提醒（票 06）集成测试：
 * 商品上架后主动下架进草稿箱（退出搜索可见），可编辑后重新提交回待审（轮次 +1）；
 * 到期下架扫描将可见满 30 天的商品汇入草稿箱，不再进入任何终态；
 * 待审超 24h 提醒 ADMIN+AUDITOR（去重、不改状态、未超时不提醒）。
 */
class TradeTakedownExpireRemindIntegrationTest extends CommunityApiTestBase {

    @Autowired
    private TradeExpireScanJob tradeExpireScanJob;

    // ---------- 商品：上架后主动下架 → 草稿箱 → 重提回待审 ----------

    @Test
    void 商品上架后主动下架进草稿箱退出可见可重提回待审() {
        Session seller = registerAndLogin("13800008001", "卖家1");
        Session other = registerAndLogin("13800008002", "路人1");
        long productId = createApprovedProduct(seller, "九成新吉他");
        int syncCountBefore = searchSyncMessageCount(productId);

        assertSuccess(postJson("/api/trade/products/" + productId + "/takedown", null, seller), "在售商品下架");
        assertThat(productStatus(productId)).as("下架应汇入草稿箱").isEqualTo("DRAFT");
        assertThat(productIdsOf(getJson("/api/trade/products", null)))
                .as("下架后对外列表不可见").doesNotContain(productId);
        JsonNode otherDetail = getJson("/api/trade/products/" + productId, other);
        assertThat(otherDetail.path("code").asText()).as("下架后他人应 404：%s", otherDetail).isEqualTo("404");
        assertThat(searchSyncMessageCount(productId)).as("下架应落搜索同步消息（退出可见）")
                .isGreaterThan(syncCountBefore);

        // 草稿箱可编辑，重新提交回待审（轮次 +1），初筛命中停留草稿
        JsonNode screened = putJson("/api/trade/products/" + productId + "/draft",
                Map.ofEntries(Map.entry("title", "帮我作弊一下"),
                        Map.entry("description", "描述"),
                        Map.entry("images", List.of()),
                        Map.entry("price", 19.9),
                        Map.entry("tradeLocation", "图书馆门口"),
                        Map.entry("category", "BOOKS")), seller);
        assertThat(screened.path("code").asText()).as("草稿编辑不做初筛但提交会拦：%s", screened).isEqualTo("200");
        assertThat(postJson("/api/trade/products/" + productId + "/submit", null, seller).path("code").asText())
                .as("初筛命中应停留草稿").isEqualTo("500");
        assertThat(productStatus(productId)).isEqualTo("DRAFT");

        assertSuccess(putJson("/api/trade/products/" + productId + "/draft",
                Map.ofEntries(Map.entry("title", "九五新单板吉他"),
                        Map.entry("description", "改后重新提"),
                        Map.entry("images", List.of()),
                        Map.entry("price", 21.9),
                        Map.entry("tradeLocation", "图书馆门口"),
                        Map.entry("category", "BOOKS")), seller), "草稿编辑");
        assertSuccess(postJson("/api/trade/products/" + productId + "/submit", null, seller), "草稿重新提交");
        assertThat(productStatus(productId)).as("重提应回待审").isEqualTo("PENDING_REVIEW");
        Integer round = jdbcTemplate.queryForObject(
                "SELECT review_round FROM product WHERE product_id = ?", Integer.class, productId);
        assertThat(round).as("重提轮次 +1").isEqualTo(2);
        String expireAt = jdbcTemplate.queryForObject(
                "SELECT expire_at FROM product WHERE product_id = ?", String.class, productId);
        assertThat(expireAt).as("重提应刷新到期时间（重新获得 30 天窗口）").isNotNull();
    }

    // ---------- 到期下架：商品汇入草稿箱，不再进入任何终态 ----------

    @Test
    void 到期下架扫描商品进草稿箱() {
        Session seller = registerAndLogin("13800008005", "卖家3");
        long productId = createApprovedProduct(seller, "九成新台灯");
        int productSyncBefore = searchSyncMessageCount(productId);

        // 对外可见满 30 天（回拨 expire_at 模拟到期）
        jdbcTemplate.update("UPDATE product SET expire_at = DATE_SUB(NOW(), INTERVAL 1 HOUR) WHERE product_id = ?", productId);

        tradeExpireScanJob.scan(null);

        assertThat(productStatus(productId)).as("到期商品汇入草稿箱而非终态").isEqualTo("DRAFT");
        assertThat(searchSyncMessageCount(productId)).as("到期下架退出搜索可见")
                .isGreaterThan(productSyncBefore);
        assertThat(productIdsOf(getJson("/api/trade/products", null))).doesNotContain(productId);

        // 再扫一次幂等：草稿箱行不再被扫出
        tradeExpireScanJob.scan(null);
        assertThat(productStatus(productId)).isEqualTo("DRAFT");
    }

    // ---------- 24h 待审超时提醒：ADMIN+AUDITOR 各一条、去重、不改状态、未超时不提醒 ----------

    @Test
    void 待审超24h提醒管理员与审核员去重且不改状态() {
        Session seller = registerAndLogin("13800008007", "卖家4");
        Session admin = registerAndLogin("13800008009", "管理员1");
        Session auditor = registerAndLogin("13800008010", "审核员1");
        Session normal = registerAndLogin("13800008011", "普通用户1");
        jdbcTemplate.update("UPDATE bs_user SET role = 'ADMIN' WHERE user_id = ?", admin.userId());
        jdbcTemplate.update("UPDATE bs_user SET role = 'AUDITOR' WHERE user_id = ?", auditor.userId());

        long productId = createProduct(seller, "九成新滑板");
        long freshProductId = createProduct(seller, "九成新耳机");

        // 回拨进审时间模拟超 24h（freshProductId 不回拨 = 未超时不提醒）
        jdbcTemplate.update("UPDATE product SET review_entered_at = DATE_SUB(NOW(), INTERVAL 25 HOUR) WHERE product_id = ?",
                productId);

        tradeExpireScanJob.scan(null);
        tradeExpireScanJob.scan(null);

        long adminRows = countReviewReminders(admin.userId(), List.of(productId));
        long auditorRows = countReviewReminders(auditor.userId(), List.of(productId));
        assertThat(adminRows).as("管理员应收商品一条，重复扫描不重复").isEqualTo(1);
        assertThat(auditorRows).as("审核员应收商品一条，重复扫描不重复").isEqualTo(1);
        assertThat(countReviewReminders(normal.userId(), List.of(productId)))
                .as("普通用户不应收到提醒").isZero();
        assertThat(countReviewReminders(admin.userId(), List.of(freshProductId)))
                .as("未超 24h 的待审不提醒").isZero();

        // 提醒不改状态，绝不自动裁决
        assertThat(productStatus(productId)).isEqualTo("PENDING_REVIEW");
        assertThat(productStatus(freshProductId)).isEqualTo("PENDING_REVIEW");
    }

    private long countReviewReminders(String userId, List<Long> targetIds) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE user_id = ? AND type = 'ORDER_EVENT' "
                        + "AND content LIKE '%24 小时%' AND ref_id IN ("
                        + targetIds.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("0") + ")",
                Integer.class, userId);
        return count == null ? 0 : count;
    }

    // ---------- 工具 ----------

    /** 直接发布商品（先审后售 → PENDING_REVIEW），返回 productId */
    private long createProduct(Session seller, String title) {
        JsonNode response = postJson("/api/trade/products", Map.ofEntries(
                Map.entry("title", title),
                Map.entry("description", "自用闲置，九成新"),
                Map.entry("images", List.of()),
                Map.entry("price", 19.9),
                Map.entry("tradeLocation", "图书馆门口"),
                Map.entry("category", "BOOKS")), seller);
        assertSuccess(response, "发布商品");
        long productId = response.path("data").asLong();
        assertThat(productStatus(productId)).as("先审后售：提交即待审").isEqualTo("PENDING_REVIEW");
        return productId;
    }

    /** 发布并通过审核（ON_SALE），返回 productId */
    private long createApprovedProduct(Session seller, String title) {
        long productId = createProduct(seller, title);
        assertSuccess(postJson("/api/admin/reviews/decide",
                Map.of("targetType", "PRODUCT", "targetId", productId, "approve", true), adminSession()), "商品通过");
        assertThat(productStatus(productId)).isEqualTo("ON_SALE");
        return productId;
    }

    /** 管理员登录态（每用例缓存复用，避免同用例内重复注册同号）：先注册（USER），改库提权后重新走验证码登录 */
    private Session adminSession() {
        if (cachedAdminSession != null) {
            return cachedAdminSession;
        }
        Session registered = registerAndLogin("13900008001", "管理员");
        jdbcTemplate.update("UPDATE bs_user SET role = 'ADMIN' WHERE user_id = ?", registered.userId());
        postJson("/api/user/send-code", Map.of("phone", "13900008001"), null);
        String code = redissonClient.<String>getBucket(Constants.VERIFY_CODE_KEY + "13900008001").get();
        JsonNode loginResponse = postJson("/api/user/login", Map.of("phone", "13900008001", "code", code), null);
        assertSuccess(loginResponse, "管理员重新登录");
        cachedAdminSession = new Session(loginResponse.path("data").path("token").asText(), registered.userId());
        return cachedAdminSession;
    }

    private Session cachedAdminSession;

    private int searchSyncMessageCount(long productId) {
        return (int) jdbcTemplate.queryForList(
                "SELECT payload FROM outbox_message WHERE destination = ?", String.class, Constants.SEARCH_SYNC_TOPIC)
                .stream().map(this::readTree)
                .filter(node -> "product".equals(node.path("type").asText())
                        && node.path("id").asLong() == productId)
                .count();
    }

    private String productStatus(long productId) {
        return jdbcTemplate.queryForObject("SELECT state FROM product WHERE product_id = ?", String.class, productId);
    }

    private List<Long> productIdsOf(JsonNode response) {
        return StreamSupport.stream(response.path("data").path("list").spliterator(), false)
                .map(node -> node.path("productId").asLong()).toList();
    }
}
