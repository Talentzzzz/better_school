package com.betterschool.integration.trade;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 二手交易发布收口（票 01）集成测试：商品先审后售（提交进待审）、
 * 同步初筛命中不落库、可见性收口（待审对外不可见、我的发布本人可见）。
 */
class TradePublishPendingReviewIntegrationTest extends CommunityApiTestBase {

    // ---------- 商品：同步初筛硬拦截 ----------

    @Test
    void 商品标题初筛命中创建失败且不落库() {
        Session seller = registerAndLogin("13800003001", "卖家1");
        JsonNode response = postJson("/api/trade/products", productBody("出售作弊神器", "九成新"), seller);

        assertThat(response.path("code").asText()).as("标题初筛命中应业务失败：%s", response).isEqualTo("500");
        assertThat(response.path("info").asText()).contains("违规词汇");
        assertThat(countRows("product")).as("命中商品不应落库").isZero();
    }

    @Test
    void 商品描述初筛命中创建失败且不落库_改文案重新提交进入待审() {
        Session seller = registerAndLogin("13800003002", "卖家2");
        JsonNode hit = postJson("/api/trade/products", productBody("考研真题", "可以代考"), seller);
        assertThat(hit.path("code").asText()).as("描述初筛命中应业务失败").isEqualTo("500");
        assertThat(countRows("product")).as("命中商品不应落库").isZero();

        JsonNode retry = postJson("/api/trade/products", productBody("考研真题", "去年用过的资料"), seller);
        assertSuccess(retry, "改文案重新提交");

        long productId = retry.path("data").asLong();
        assertThat(productStatus(productId)).as("先审后售：提交即待审").isEqualTo("PENDING_REVIEW");
    }

    // ---------- 商品可见性收口 ----------

    @Test
    void 待审商品对外列表与详情不可见_卖家我的发布可见() {
        Session seller = registerAndLogin("13800003005", "卖家3");
        Session other = registerAndLogin("13800003006", "路人");
        long productId = createProduct(seller, "九成新台灯");

        JsonNode publicList = getJson("/api/trade/products", other);
        assertSuccess(publicList, "对外商品列表");
        assertThat(productIdsOf(publicList)).as("待审商品不应对外可见").doesNotContain(productId);

        JsonNode detail = getJson("/api/trade/products/" + productId, other);
        assertThat(detail.path("code").asText()).as("待审商品详情对他人应 404：%s", detail).isEqualTo("404");

        JsonNode ownerDetail = getJson("/api/trade/products/" + productId, seller);
        assertSuccess(ownerDetail, "卖家本人看自己的待审商品");
        assertThat(ownerDetail.path("data").path("status").asText()).isEqualTo("PENDING_REVIEW");

        JsonNode mine = getJson("/api/trade/products/mine", seller);
        assertSuccess(mine, "我的发布");
        assertThat(productIdsOf(mine)).as("我的发布应含待审商品").contains(productId);
    }

    @Test
    void 存量在售商品保持对外可见不回审() {
        insertProductRow("存量在售商品", "ON_SALE");
        insertProductRow("存量草稿商品", "DRAFT");

        JsonNode publicList = getJson("/api/trade/products", null);
        assertSuccess(publicList, "对外商品列表");
        assertThat(productIdsOf(publicList)).as("存量 ON_SALE 保持可见").hasSize(1);
    }

    // ---------- 工具 ----------

    /** 直插夹具的商品业务 ID 取号带：远高于应用自增 id，避开 uk_product_id */
    private long fixtureSeq;

    private long createProduct(Session seller, String title) {
        JsonNode response = postJson("/api/trade/products", productBody(title, "九成新"), seller);
        assertSuccess(response, "发布商品");
        return response.path("data").asLong();
    }

    private Map<String, Object> productBody(String title, String description) {
        return Map.ofEntries(
                Map.entry("title", title),
                Map.entry("description", description),
                Map.entry("images", List.of()),
                Map.entry("price", 19.9),
                Map.entry("tradeLocation", "图书馆门口"),
                Map.entry("category", "BOOKS"));
    }

    /**
     * 直插存量商品行：product_id NOT NULL 且无默认值（应用侧才走 insert 0 + 回写 id），夹具显式给业务 ID 并返回它。
     * 存量 ON_SALE 行必须同时有 feed 索引行——推荐页读路径走 product_feed_index（market-feed-uv 票 04），
     * 真实存量由票 01 回填脚本造索引行，这里不补就等于造了一条「线上不可能存在」的漂移数据
     */
    private long insertProductRow(String title, String state) {
        long productId = 900_000L + fixtureSeq++;
        jdbcTemplate.update("INSERT INTO product (product_id, user_id, title, description, price, "
                        + "trade_location, category, state, create_time, update_time, expire_at) "
                        + "VALUES (?, 999, ?, '存量数据', 10.00, '驿站', 'BOOKS', ?, NOW(), NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY))",
                productId, title, state);
        if ("ON_SALE".equals(state)) {
            jdbcTemplate.update("INSERT INTO product_feed_index (product_id, state, feed_time, price, view_count, "
                    + "update_time) VALUES (?, 'ON_SALE', NOW(), 10.00, 0, NOW())", productId);
        }
        return productId;
    }

    private String productStatus(long productId) {
        return jdbcTemplate.queryForObject("SELECT state FROM product WHERE product_id = ?", String.class, productId);
    }

    private int countRows(String table) {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return count == null ? 0 : count;
    }

    private List<Long> productIdsOf(JsonNode response) {
        return StreamSupport.stream(response.path("data").path("list").spliterator(), false)
                .map(node -> node.path("productId").asLong()).toList();
    }
}
