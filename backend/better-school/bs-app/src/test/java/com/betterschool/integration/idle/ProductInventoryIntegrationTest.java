package com.betterschool.integration.idle;

import com.betterschool.integration.support.PostApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商品库存台账 + 流水（multi-quantity-listing）全链路：
 * 发布建账 → 下单原子扣减（防超卖唯一闸门）→ 售罄随行翻 SOLD → 关单回补翻回 ON_SALE
 * → 卖家加货 + RESTOCK 流水 → 库存闸门下的 relist。
 * 断言只看外部可观察状态：product_inventory / product_stock_event / product.state / product_feed_index / product_order。
 */
@DisplayName("商品库存：建账 / 扣减防超卖 / 售罄翻态 / 回补翻回 / 加货 / relist 闸门")
class ProductInventoryIntegrationTest extends PostApiTestBase {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 发布多件挂单_台账与INIT流水随行诞生() {
        Session seller = registerAndLogin("13800009101", "库存卖家1");
        String productId = createProduct(seller, "同款马克杯", 3);

        Map<String, Object> inventory = inventoryRow(productId);
        assertThat(inventory).as("发布同事务建台账行").isNotNull();
        assertThat(inventory.get("quantity")).isEqualTo(3);
        assertThat(inventory.get("remaining")).isEqualTo(3);
        assertThat(stockEventTypes(productId)).as("建账落 INIT 流水").containsExactly("INIT");
        assertThat(stockEventRows(productId, "INIT").getFirst())
                .as("INIT 流水：建账 delta = +3、after = 3、操作方 SELLER")
                .containsEntry("delta", 3)
                .containsEntry("after_remaining", 3)
                .containsEntry("actor_role", "SELLER");
        assertThat(productStatus(productId)).isEqualTo("ON_SALE");
    }

    @Test
    void 下单原子扣减_售罄随行翻SOLD_超卖被闸门拦下() {
        Session seller = registerAndLogin("13800009102", "库存卖家2");
        Session buyer1 = registerAndLogin("13800009103", "库存买家1");
        Session buyer2 = registerAndLogin("13800009104", "库存买家2");
        String productId = createProduct(seller, "二手教材", 2);

        // 单 1：买 1 件 → remaining 2→1，订单带件数快照
        String orderId1 = createProductOrder(buyer1, productId, 1);
        assertThat(remaining(productId)).as("下单原子扣减").isEqualTo(1);
        assertThat(orderQuantity(orderId1)).as("订单落件数快照").isEqualTo(1);
        assertThat(stockEventRows(productId, "DEDUCT").getFirst())
                .as("DEDUCT 流水：delta = -1、after = 1、关联订单")
                .containsEntry("delta", -1)
                .containsEntry("after_remaining", 1)
                .containsEntry("order_id", orderId1);
        assertThat(productStatus(productId)).as("remaining > 0 商品仍在售").isEqualTo("ON_SALE");

        // 单 2：买 2 件但只剩 1 件 → 闸门 0 行，整单回滚无残留
        JsonNode oversell = postJson("/api/idle/orders?productId=" + productId + "&quantity=2", null, buyer2);
        assertThat(oversell.path("code").asInt())
                .as("库存不足下单被拒：%s", oversell).isNotEqualTo(200);
        assertThat(orderCountOf(productId)).as("失败单零残留（同事务回滚）").isEqualTo(1);
        assertThat(remaining(productId)).as("回滚不扣减").isEqualTo(1);
        assertThat(stockEventRows(productId, "DEDUCT")).as("回滚不落流水").hasSize(1);

        // 单 3：买最后 1 件 → remaining 0，商品随行翻 SOLD、索引同步退出可见
        String orderId3 = createProductOrder(buyer2, productId, 1);
        assertThat(remaining(productId)).isZero();
        assertThat(productStatus(productId)).as("售罄随行翻 SOLD").isEqualTo("SOLD");
        assertThat(feedState(productId)).as("索引行同事务翻 SOLD（feed 不再可见）").isEqualTo("SOLD");
        assertThat(stockEventRows(productId, "DEDUCT")).hasSize(2);
        assertThat(orderId3).isNotEqualTo(orderId1);
    }

    @Test
    void 关单回补翻回ON_SALE_加货走RESTOCK流水_relist受库存闸门约束() {
        Session seller = registerAndLogin("13800009105", "库存卖家3");
        Session buyer = registerAndLogin("13800009106", "库存买家3");
        String productId = createProduct(seller, "宿舍小风扇", 1);

        String orderId = createProductOrder(buyer, productId, 1);
        assertThat(productStatus(productId)).as("单件下单即售罄").isEqualTo("SOLD");

        // 真卖完（remaining = 0）：relist 被库存闸门拦下
        assertThat(postRelist(seller, productId)).as("真卖完（remaining = 0）不可重上架").isFalse();
        assertThat(productStatus(productId)).as("闸门拦下商品仍 SOLD").isEqualTo("SOLD");

        // 取消关单 → 回补 remaining 0→1，RELEASE 流水，商品翻回 ON_SALE、索引同步翻回
        assertSuccess(postJson("/api/idle/orders/" + orderId + "/cancel", null, buyer), "买家取消");
        assertThat(remaining(productId)).as("回补到顶（= quantity）").isEqualTo(1);
        assertThat(productStatus(productId)).as("回补后翻回 ON_SALE").isEqualTo("ON_SALE");
        assertThat(feedState(productId)).as("索引行同事务翻回可见").isEqualTo("ON_SALE");
        Map<String, Object> release = stockEventRows(productId, "RELEASE").getFirst();
        assertThat(release)
                .as("RELEASE 流水：delta = +1、after = 1、关联订单")
                .containsEntry("delta", 1)
                .containsEntry("after_remaining", 1)
                .containsEntry("order_id", orderId);

        // 再次售罄后：卖家加货 → 台账 quantity/remaining 同涨 + RESTOCK 流水，relist 闸门放行
        String orderId2 = createProductOrder(buyer, productId, 1);
        assertThat(productStatus(productId)).isEqualTo("SOLD");
        assertThat(postRestock(seller, productId, 2)).as("在售守卫：SOLD 不可加货").isFalse();

        assertSuccess(postJson("/api/idle/orders/" + orderId2 + "/cancel", null, buyer), "买家取消单2");
        assertThat(productStatus(productId)).isEqualTo("ON_SALE");
        assertSuccess(postJson("/api/idle/products/" + productId + "/sold", null, seller), "卖家标记已出");
        assertThat(productStatus(productId)).as("标记已出 → SOLD").isEqualTo("SOLD");
        assertThat(remaining(productId)).as("标记已出清零台账").isZero();

        assertSuccess(postJson("/api/idle/products/" + productId + "/stock?quantity=2", null, seller), "卖家加货");
        Map<String, Object> inventory = inventoryRow(productId);
        assertThat(inventory.get("quantity")).as("加货涨总件数").isEqualTo(2);
        assertThat(inventory.get("remaining")).as("加货涨剩余").isEqualTo(2);
        assertThat(stockEventRows(productId, "RESTOCK").getFirst())
                .as("RESTOCK 流水：delta = +2、after = 2、操作方 SELLER")
                .containsEntry("delta", 2)
                .containsEntry("after_remaining", 2)
                .containsEntry("actor_role", "SELLER");
        assertThat(postRelist(seller, productId)).as("加货后 relist 放行").isTrue();
        assertThat(productStatus(productId)).isEqualTo("ON_SALE");
    }

    @Test
    void 件数边界_下单与加货的非法件数被参数校验拦下() {
        Session seller = registerAndLogin("13800009107", "库存卖家4");
        Session buyer = registerAndLogin("13800009108", "库存买家4");
        String productId = createProduct(seller, "九成新水杯", 2);

        assertThat(postJson("/api/idle/orders?productId=" + productId + "&quantity=0", null, buyer).path("code").asInt())
                .as("0 件下单被拒").isNotEqualTo(200);
        assertThat(postJson("/api/idle/orders?productId=" + productId + "&quantity=1000", null, buyer).path("code").asInt())
                .as("超上限下单被拒").isNotEqualTo(200);
        assertThat(orderCountOf(productId)).as("非法件数零订单残留").isZero();
        assertThat(remaining(productId)).as("非法件数不扣减").isEqualTo(2);

        JsonNode publish = postJson("/api/idle/products", Map.ofEntries(
                Map.entry("title", "非法件数挂单"),
                Map.entry("description", "自用闲置"),
                Map.entry("images", List.of()),
                Map.entry("price", 5.5),
                Map.entry("tradeLocation", "驿站"),
                Map.entry("category", "BOOKS"),
                Map.entry("quantity", 0)), seller);
        assertThat(publish.path("code").asInt()).as("发布件数 0 被参数校验拦下").isNotEqualTo(200);
    }

    // ---------- 夹具与可观察状态 ----------

    /** 发布多件挂单（quantity 进台账），返回业务 ID */
    private String createProduct(Session seller, String title, int quantity) {
        JsonNode response = postJson("/api/idle/products", Map.ofEntries(
                Map.entry("title", title),
                Map.entry("description", "自用闲置"),
                Map.entry("images", List.of()),
                Map.entry("price", 19.9),
                Map.entry("tradeLocation", "图书馆门口"),
                Map.entry("category", "BOOKS"),
                Map.entry("quantity", quantity)), seller);
        assertSuccess(response, "发布商品");
        return response.path("data").asText();
    }

    private String createProductOrder(Session buyer, String productId, int quantity) {
        JsonNode response = postJson("/api/idle/orders?productId=" + productId + "&quantity=" + quantity, null, buyer);
        assertSuccess(response, "商品下单");
        return response.path("data").asText();
    }

    /** 卖家加货（返回 HTTP 是否成功） */
    private boolean postRestock(Session seller, String productId, int quantity) {
        JsonNode response = postJson("/api/idle/products/" + productId + "/stock?quantity=" + quantity, null, seller);
        return response.path("code").asInt() == 200;
    }

    /** 卖家重上架（返回 HTTP 是否成功） */
    private boolean postRelist(Session seller, String productId) {
        JsonNode response = postJson("/api/idle/products/" + productId + "/relist", null, seller);
        return response.path("code").asInt() == 200;
    }

    private Map<String, Object> inventoryRow(String productId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT quantity, remaining FROM product_inventory WHERE product_id = ?", productId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Integer remaining(String productId) {
        Map<String, Object> row = inventoryRow(productId);
        return row == null ? null : ((Number) row.get("remaining")).intValue();
    }

    private List<String> stockEventTypes(String productId) {
        return jdbcTemplate.queryForList(
                "SELECT event_type FROM product_stock_event WHERE product_id = ? ORDER BY id", String.class, productId);
    }

    private List<Map<String, Object>> stockEventRows(String productId, String eventType) {
        return jdbcTemplate.queryForList(
                "SELECT event_type, delta, after_remaining, order_id, actor_role "
                        + "FROM product_stock_event WHERE product_id = ? AND event_type = ? ORDER BY id",
                productId, eventType);
    }

    private long orderCountOf(String productId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM product_order WHERE product_id = ?", Long.class, productId);
        return count == null ? 0 : count;
    }

    private Integer orderQuantity(String orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT quantity FROM product_order WHERE product_order_id = ?", Integer.class, orderId);
    }

    private String productStatus(String productId) {
        return jdbcTemplate.queryForObject("SELECT state FROM product WHERE product_id = ?", String.class, productId);
    }

    private String feedState(String productId) {
        List<String> states = jdbcTemplate.queryForList(
                "SELECT state FROM product_feed_index WHERE product_id = ?", String.class, productId);
        return states.isEmpty() ? null : states.getFirst();
    }
}
