package com.betterschool.integration.idle;

import com.betterschool.integration.support.PostApiTestBase;
import com.betterschool.trigger.job.IdleExpireScanJob;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 下架/到期汇入草稿箱（票 06）集成测试：
 * 商品上架后主动下架进草稿箱（退出搜索可见），可编辑后重新提交上架；
 * 到期下架扫描将上架满 30 天的商品汇入草稿箱，不再进入任何终态。
 */
class IdleTakedownExpireRemindIntegrationTest extends PostApiTestBase {

    @Autowired
    private IdleExpireScanJob idleExpireScanJob;

    // ---------- 商品：上架后主动下架 → 草稿箱 → 重提上架 ----------

    @Test
    void 商品上架后主动下架进草稿箱退出可见可重提上架() {
        Session seller = registerAndLogin("13800008001", "卖家1");
        Session other = registerAndLogin("13800008002", "路人1");
        String productId = createOnSaleProduct(seller, "九成新吉他");
        int syncCountBefore = searchSyncMessageCount(productId);

        assertSuccess(postJson("/api/idle/products/" + productId + "/takedown", null, seller), "在售商品下架");
        assertThat(productStatus(productId)).as("下架应汇入草稿箱").isEqualTo("DRAFT");
        assertThat(productIdsOf(getJson("/api/idle/products", null)))
                .as("下架后对外列表不可见").doesNotContain(productId);
        JsonNode otherDetail = getJson("/api/idle/products/" + productId, other);
        assertThat(otherDetail.path("code").asText()).as("下架后他人应 404：%s", otherDetail).isEqualTo("404");
        assertThat(searchSyncMessageCount(productId)).as("下架应落搜索同步消息（退出可见）")
                .isGreaterThan(syncCountBefore);

        // 草稿箱可编辑，重新提交上架
        assertSuccess(putJson("/api/idle/products/" + productId + "/draft",
                Map.ofEntries(Map.entry("title", "九五新单板吉他"),
                        Map.entry("description", "改后重新提"),
                        Map.entry("images", List.of()),
                        Map.entry("price", 21.9),
                        Map.entry("tradeLocation", "图书馆门口"),
                        Map.entry("category", "BOOKS")), seller), "草稿编辑");
        assertSuccess(postJson("/api/idle/products/" + productId + "/submit", null, seller), "草稿重新提交");
        assertThat(productStatus(productId)).as("重提应回在售").isEqualTo("ON_SALE");
        String expireAt = jdbcTemplate.queryForObject(
                "SELECT expire_at FROM product WHERE product_id = ?", String.class, productId);
        assertThat(expireAt).as("重提应刷新到期时间（重新获得 30 天窗口）").isNotNull();
    }

    // ---------- 到期下架：商品汇入草稿箱，不再进入任何终态 ----------

    @Test
    void 到期下架扫描商品进草稿箱() {
        Session seller = registerAndLogin("13800008005", "卖家3");
        String productId = createOnSaleProduct(seller, "九成新台灯");
        int productSyncBefore = searchSyncMessageCount(productId);

        // 对外可见满 30 天（回拨 expire_at 模拟到期）
        jdbcTemplate.update("UPDATE product SET expire_at = DATE_SUB(NOW(), INTERVAL 1 HOUR) WHERE product_id = ?", productId);

        idleExpireScanJob.scan(null);

        assertThat(productStatus(productId)).as("到期商品汇入草稿箱而非终态").isEqualTo("DRAFT");
        assertThat(searchSyncMessageCount(productId)).as("到期下架退出搜索可见")
                .isGreaterThan(productSyncBefore);
        assertThat(productIdsOf(getJson("/api/idle/products", null))).doesNotContain(productId);

        // 再扫一次幂等：草稿箱行不再被扫出
        idleExpireScanJob.scan(null);
        assertThat(productStatus(productId)).isEqualTo("DRAFT");
    }

    // ---------- 工具 ----------

    /** 发布商品（即 ON_SALE），返回 productId */
    private String createOnSaleProduct(Session seller, String title) {
        JsonNode response = postJson("/api/idle/products", Map.ofEntries(
                Map.entry("title", title),
                Map.entry("description", "自用闲置，九成新"),
                Map.entry("images", List.of()),
                Map.entry("price", 19.9),
                Map.entry("tradeLocation", "图书馆门口"),
                Map.entry("category", "BOOKS")), seller);
        assertSuccess(response, "发布商品");
        String productId = response.path("data").asText();
        assertThat(productStatus(productId)).as("发布即可见").isEqualTo("ON_SALE");
        return productId;
    }

    private int searchSyncMessageCount(String productId) {
        return (int) jdbcTemplate.queryForList(
                "SELECT payload FROM mq_outbox WHERE destination = ?", String.class, Constants.SEARCH_SYNC_TOPIC)
                .stream().map(this::readTree)
                .filter(node -> "product".equals(node.path("type").asText())
                        && node.path("id").asText().equals(productId))
                .count();
    }

    private String productStatus(String productId) {
        return jdbcTemplate.queryForObject("SELECT state FROM product WHERE product_id = ?", String.class, productId);
    }

    private List<String> productIdsOf(JsonNode response) {
        return StreamSupport.stream(response.path("data").path("list").spliterator(), false)
                .map(node -> node.path("productId").asText()).toList();
    }
}
