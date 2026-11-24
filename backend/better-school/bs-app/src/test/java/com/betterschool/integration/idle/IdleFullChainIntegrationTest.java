package com.betterschool.integration.idle;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.trigger.job.IdleExpireScanJob;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 全链路集成测试汇总（票 07）：在唯一测试 seam（HTTP 直驱 + 真实库 + 定时任务直调）上
 * 对闲置交易写全场景回归，覆盖发布即上架时序（无审核环节：无 PENDING_REVIEW/REJECTED，
 * 敏感词初筛已删，编辑在售立即生效）。
 * 只断言外部行为（接口响应、库内状态、feed 索引、事件流水），不断言实现细节。
 */
class IdleFullChainIntegrationTest extends CommunityApiTestBase {

    @Autowired
    private IdleExpireScanJob idleExpireScanJob;

    // ---------- 发布链路：发布即上架对外可见（feed 索引行同步诞生） ----------

    @Test
    void 商品发布链路_发布即上架对外可见() {
        Session seller = registerAndLogin("13800009003", "卖家2");
        Session other = registerAndLogin("13800009005", "路人1");

        LocalDateTime publishedAt = LocalDateTime.now();
        String productId = createProduct(seller, "九成新吉他", List.of(
                "http://minio/bs-files/it/f-1.jpg", "http://minio/bs-files/it/f-2.jpg"));

        // 发布即对外可见（无需任何裁决环节）：他人大厅列表与详情都可见
        assertThat(productIdsOf(getJson("/api/idle/products", other)))
                .as("发布即对外可见").contains(productId);
        JsonNode otherDetail = getJson("/api/idle/products/" + productId, other);
        assertSuccess(otherDetail, "他人看商品详情");
        assertThat(otherDetail.path("data").path("title").asText()).isEqualTo("九成新吉他");
        assertThat(otherDetail.path("data").path("images")).as("详情带出配图").hasSize(2);

        // feed 索引行同步诞生：发布即上架，行随发布事务落地
        Map<String, Object> feedRow = feedRow(productId);
        assertThat(feedRow).as("发布后索引行诞生").isNotNull();
        assertThat(feedRow.get("state")).isEqualTo("ON_SALE");
        assertThat(((BigDecimal) feedRow.get("price"))).as("price 快照 = 发布价格").isEqualByComparingTo("19.90");
        assertThat(((LocalDateTime) feedRow.get("feed_time")))
                .as("feed_time = 发布时刻")
                .isBetween(publishedAt.minusSeconds(5), LocalDateTime.now().plusSeconds(5));
    }

    // ---------- 草稿箱/下架：草稿可编辑可删除；在售下架汇入草稿、重提即回大厅 ----------

    @Test
    void 草稿箱与下架_草稿编辑删除与在售下架重提回大厅() {
        Session seller = registerAndLogin("13800009011", "卖家4");
        Session other = registerAndLogin("13800009014", "路人2");

        // 草稿：可编辑、可删除
        String draftId = saveProductDraft(seller, "九成新台灯");
        updateProductDraft(seller, draftId, "九五新护眼台灯", "改了文案");
        assertSuccess(deleteJson("/api/idle/products/" + draftId, seller), "删除草稿");
        assertThat(productStatus(draftId)).isEqualTo("DELETED");

        // 在售下架 → 草稿箱 → 编辑 → 重提即回 ON_SALE（不再有中间待审态）
        String onSaleId = createProduct(seller, "九成新吉他", List.of());
        assertSuccess(postJson("/api/idle/products/" + onSaleId + "/takedown", null, seller), "在售商品下架");
        assertThat(productStatus(onSaleId)).as("下架汇入草稿箱").isEqualTo("DRAFT");
        assertThat(productIdsOf(getJson("/api/idle/products", other)))
                .as("下架后对外不可见").doesNotContain(onSaleId);

        updateProductDraft(seller, onSaleId, "九五新单板吉他", "下架后改了文案");
        assertSuccess(postJson("/api/idle/products/" + onSaleId + "/submit", null, seller), "下架后重提");
        assertThat(productStatus(onSaleId)).as("重提即上架").isEqualTo("ON_SALE");
        assertThat(productIdsOf(getJson("/api/idle/products", other)))
                .as("重提后恢复对外可见").contains(onSaleId);
    }

    // ---------- 编辑语义：改价/改内容立即生效，状态保持 ON_SALE（不再回待审） ----------

    @Test
    void 编辑语义_改价改内容立即生效状态保持在售() {
        Session seller = registerAndLogin("13800009013", "卖家5");
        Session other = registerAndLogin("13800009014", "路人3");

        String productId = createProduct(seller, "九成新台灯", List.of());

        // 仅改价：立即生效，状态不变
        assertSuccess(putJson("/api/idle/products/" + productId, Map.of("price", 29.9), seller), "仅改价");
        assertThat(productStatus(productId)).as("改价状态不变").isEqualTo("ON_SALE");
        assertThat(getJson("/api/idle/products/" + productId, other).path("data").path("price").asDouble())
                .as("价格立即生效").isEqualTo(29.9);

        // 改内容（混提新价）：立即生效不回待审，期间对外可见不隐藏；价格快照同步索引
        assertSuccess(putJson("/api/idle/products/" + productId,
                Map.of("title", "专业级台灯", "description", "改后的新文案", "price", 35.5), seller), "改内容");
        assertThat(productStatus(productId)).as("改内容状态保持 ON_SALE").isEqualTo("ON_SALE");
        assertThat(productIdsOf(getJson("/api/idle/products", other)))
                .as("改内容期间仍对外可见").contains(productId);
        JsonNode detail = getJson("/api/idle/products/" + productId, other);
        assertThat(detail.path("data").path("title").asText())
                .as("新内容立即生效").isEqualTo("专业级台灯");
        assertThat(detail.path("data").path("price").asDouble()).isEqualTo(35.5);

        Map<String, Object> feedRow = feedRow(productId);
        assertThat(feedRow.get("state")).as("索引行保持可见").isEqualTo("ON_SALE");
        assertThat(((BigDecimal) feedRow.get("price")))
                .as("索引 price 快照同步混提的新价（价格排序键不漂移）").isEqualByComparingTo("35.50");
    }

    // ---------- 下架/到期：商品进草稿 ----------

    @Test
    void 下架到期扫描_商品进草稿() {
        Session seller = registerAndLogin("13800009016", "卖家6");
        String productId = createProduct(seller, "九成新台灯", List.of());

        jdbcTemplate.update("UPDATE product SET expire_at = DATE_SUB(NOW(), INTERVAL 1 HOUR) WHERE product_id = ?", productId);
        idleExpireScanJob.scan(null);

        assertThat(productStatus(productId)).as("到期商品进草稿箱而非终态").isEqualTo("DRAFT");
        assertThat(productIdsOf(getJson("/api/idle/products", null))).doesNotContain(productId);
    }

    // ---------- 商品订单事件流水（票 05 验收项 2） ----------

    @Test
    void 商品订单状态流转逐条落事件流水且取消分支带原因类型() {
        Session seller = registerAndLogin("13800009030", "卖家8");
        Session buyer = registerAndLogin("13800009031", "买家1");
        String productId = createProduct(seller, "九成新台灯", List.of());

        // 下单 → 支付 → 交付 → 确认：四次流转各落一条事件
        String orderId = createProductOrder(buyer, productId);
        assertThat(eventTypesOf(orderId)).as("下单只落创建事件").containsExactly("CREATED");
        assertThat(eventFromStateOf(orderId, "CREATED")).as("创建事件无前态").isNull();
        assertThat(eventRowOf(orderId, "CREATED"))
                .as("创建事件：→PENDING_PAYMENT、操作方 BUYER")
                .containsEntry("to_state", "PENDING_PAYMENT")
                .containsEntry("actor_role", "BUYER")
                .containsEntry("actor_id", buyer.userId());

        assertSuccess(postJson("/api/idle/orders/" + orderId + "/pay", null, buyer), "买家支付");
        assertSuccess(postJson("/api/idle/orders/" + orderId + "/mark-delivered", null, seller), "卖家标记已交付");
        assertSuccess(postJson("/api/idle/orders/" + orderId + "/confirm", null, buyer), "买家确认收货");

        assertThat(eventTypesOf(orderId))
                .as("事件序即状态机路径，同订单内 id 递增")
                .containsExactly("CREATED", "PAID", "DELIVERED", "COMPLETED");
        assertThat(eventRowOf(orderId, "PAID"))
                .as("支付事件：PENDING_PAYMENT→PAID、操作方 BUYER")
                .containsEntry("from_state", "PENDING_PAYMENT")
                .containsEntry("to_state", "PAID")
                .containsEntry("actor_role", "BUYER");
        assertThat(eventRowOf(orderId, "DELIVERED"))
                .as("交付事件：PAID→DELIVERED、操作方 SELLER")
                .containsEntry("from_state", "PAID")
                .containsEntry("to_state", "DELIVERED")
                .containsEntry("actor_role", "SELLER")
                .containsEntry("actor_id", seller.userId());
        assertThat(eventRowOf(orderId, "COMPLETED"))
                .as("确认事件：DELIVERED→COMPLETED")
                .containsEntry("from_state", "DELIVERED")
                .containsEntry("to_state", "COMPLETED")
                .containsEntry("actor_role", "BUYER");

        // 另一单走取消：事件同样落表，且原因类型与操作方与正常链路可区分
        String cancelledOrderId = createProductOrder(buyer, createProduct(seller, "九成新耳机", List.of()));
        assertSuccess(postJson("/api/idle/orders/" + cancelledOrderId + "/cancel", null, buyer), "买家取消");
        assertThat(eventTypesOf(cancelledOrderId))
                .as("取消单只落创建 + 取消").containsExactly("CREATED", "CANCELLED");
        assertThat(eventRowOf(cancelledOrderId, "CANCELLED"))
                .as("取消事件带原因类型与操作方")
                .containsEntry("to_state", "CANCELLED")
                .containsEntry("actor_role", "BUYER")
                .containsEntry("reason_type", "BUYER_CANCEL");
    }

    // ---------- 工具 ----------

    private String createProduct(Session seller, String title, List<String> images) {
        JsonNode response = postJson("/api/idle/products", Map.ofEntries(
                Map.entry("title", title),
                Map.entry("description", "自用闲置，九成新"),
                Map.entry("images", images),
                Map.entry("price", 19.9),
                Map.entry("tradeLocation", "图书馆门口"),
                Map.entry("category", "BOOKS")), seller);
        assertSuccess(response, "发布商品");
        String productId = response.path("data").asText();
        // uuid-biz-id 票 08 收口：发商品返回 id 必须是可解析的 UUID 字符串（ID 形态断言）
        assertUuidId(productId);
        assertThat(productStatus(productId)).as("发布即上架").isEqualTo("ON_SALE");
        return productId;
    }

    private String saveProductDraft(Session seller, String title) {
        JsonNode save = postJson("/api/idle/products/draft", Map.of("title", title), seller);
        assertSuccess(save, "保存商品草稿");
        return save.path("data").asText();
    }

    private void updateProductDraft(Session seller, String productId, String title, String description) {
        assertSuccess(putJson("/api/idle/products/" + productId + "/draft", Map.ofEntries(
                Map.entry("title", title),
                Map.entry("description", description),
                Map.entry("images", List.of()),
                Map.entry("price", 19.9),
                Map.entry("tradeLocation", "图书馆门口"),
                Map.entry("category", "BOOKS")), seller), "编辑商品草稿");
    }

    /** 买家对已上架商品下单，返回商品订单业务 ID */
    private String createProductOrder(Session buyer, String productId) {
        JsonNode response = postJson("/api/idle/orders?productId=" + productId, null, buyer);
        assertSuccess(response, "商品下单");
        String orderId = response.path("data").asText();
        // uuid-biz-id 票 08 收口：下单返回 id 必须是可解析的 UUID 字符串（ID 形态断言），后续流转以同一字符串引用成功
        assertUuidId(orderId);
        return orderId;
    }

    /** 该商品订单的事件流水类型序（id 递增即状态流转顺序） */
    private List<String> eventTypesOf(String orderId) {
        return jdbcTemplate.queryForList(
                "SELECT event_type FROM product_order_event WHERE order_id = ? ORDER BY id", String.class, orderId);
    }

    /** 某类事件的行（一条链路里每类事件至多一条；取首条足够） */
    private Map<String, Object> eventRowOf(String orderId, String eventType) {
        return jdbcTemplate.queryForMap(
                "SELECT from_state, to_state, actor_id, actor_role, reason_type, reason "
                        + "FROM product_order_event WHERE order_id = ? AND event_type = ? ORDER BY id LIMIT 1",
                orderId, eventType);
    }

    /** 事件前态；无该事件行或该列为 NULL 都返回 null（创建事件即 NULL 前态） */
    private String eventFromStateOf(String orderId, String eventType) {
        List<String> states = jdbcTemplate.queryForList(
                "SELECT from_state FROM product_order_event WHERE order_id = ? AND event_type = ? ORDER BY id LIMIT 1",
                String.class, orderId, eventType);
        return states.isEmpty() ? null : states.get(0);
    }

    /** 索引行整行（无行 = null） */
    private Map<String, Object> feedRow(String productId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT state, feed_time, price, view_count FROM product_feed_index WHERE product_id = ?", productId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String productStatus(String productId) {
        return jdbcTemplate.queryForObject("SELECT state FROM product WHERE product_id = ?", String.class, productId);
    }

    private List<String> productIdsOf(JsonNode response) {
        return StreamSupport.stream(response.path("data").path("list").spliterator(), false)
                .map(node -> node.path("productId").asText()).toList();
    }
}
