package com.betterschool.integration.idle;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 草稿箱（票 02）集成测试：保存（部分字段、仅本人可见）、编辑、删除、
 * 提交即上架（DRAFT→ON_SALE，敏感词初筛已删——含「作弊」等词的内容照常提交成功）。
 */
class IdleDraftBoxIntegrationTest extends CommunityApiTestBase {

    // ---------- 保存草稿：部分字段可存、仅本人可见 ----------

    @Test
    void 商品草稿部分字段保存成功_仅本人可见() {
        Session seller = registerAndLogin("13800004001", "卖家1");
        Session other = registerAndLogin("13800004002", "路人1");

        // 未填完：只有标题；标题含「作弊」等词也不拦——初筛已删，草稿照常保存
        JsonNode save = postJson("/api/idle/products/draft",
                Map.of("title", "出售作弊神器"), seller);
        assertSuccess(save, "保存商品草稿");
        String productId = save.path("data").asText();

        assertThat(productStatus(productId)).as("草稿状态应为 DRAFT").isEqualTo("DRAFT");

        JsonNode publicList = getJson("/api/idle/products", other);
        assertSuccess(publicList, "对外商品列表");
        assertThat(productIdsOf(publicList)).as("草稿不应对外可见").doesNotContain(productId);

        JsonNode otherMine = getJson("/api/idle/products/mine", other);
        assertThat(productIdsOf(otherMine)).as("他人我的发布不含该草稿").doesNotContain(productId);

        JsonNode otherDetail = getJson("/api/idle/products/" + productId, other);
        assertThat(otherDetail.path("code").asText()).as("他人看草稿详情应 404：%s", otherDetail).isEqualTo("404");

        JsonNode mine = getJson("/api/idle/products/mine", seller);
        List<JsonNode> mineItems = StreamSupport.stream(mine.path("data").path("list").spliterator(), false)
                .filter(node -> node.path("productId").asText().equals(productId)).toList();
        assertThat(mineItems).as("我的发布应含自己的草稿").hasSize(1);
        assertThat(mineItems.get(0).path("status").asText()).isEqualTo("DRAFT");

        JsonNode ownerDetail = getJson("/api/idle/products/" + productId, seller);
        assertSuccess(ownerDetail, "本人看自己的草稿");
        assertThat(ownerDetail.path("data").path("title").asText()).isEqualTo("出售作弊神器");
    }

    // ---------- 编辑草稿 ----------

    @Test
    void 商品草稿可编辑全部内容并回显() {
        Session seller = registerAndLogin("13800004003", "卖家2");
        String productId = saveProductDraft(seller, "旧台灯");

        JsonNode update = putJson("/api/idle/products/" + productId + "/draft", Map.ofEntries(
                Map.entry("title", "九成新护眼台灯"),
                Map.entry("description", "宿舍自用，毕业出"),
                Map.entry("images", List.of("http://minio/bs-files/it/draft-1.jpg", "http://minio/bs-files/it/draft-2.jpg")),
                Map.entry("price", 29.9),
                Map.entry("tradeLocation", "南门快递站"),
                Map.entry("category", "DIGITAL")), seller);
        assertSuccess(update, "编辑商品草稿");

        JsonNode detail = getJson("/api/idle/products/" + productId, seller);
        assertSuccess(detail, "回看编辑后的草稿");
        JsonNode data = detail.path("data");
        assertThat(data.path("title").asText()).isEqualTo("九成新护眼台灯");
        assertThat(data.path("description").asText()).isEqualTo("宿舍自用，毕业出");
        assertThat(data.path("images")).hasSize(2);
        assertThat(data.path("price").asDouble()).isEqualTo(29.9);
        assertThat(data.path("tradeLocation").asText()).isEqualTo("南门快递站");
        assertThat(data.path("category").asText()).isEqualTo("DIGITAL");
        assertThat(data.path("status").asText()).as("编辑不改状态").isEqualTo("DRAFT");
    }

    // ---------- 删除草稿 ----------

    @Test
    void 草稿可删除_在售商品删除不受影响() {
        Session seller = registerAndLogin("13800004004", "卖家3");
        String draftId = saveProductDraft(seller, "不要了的草稿");

        JsonNode deleteDraft = deleteJson("/api/idle/products/" + draftId, seller);
        assertSuccess(deleteDraft, "删除草稿");
        assertThat(productStatus(draftId)).as("草稿软删").isEqualTo("DELETED");
        JsonNode detail = getJson("/api/idle/products/" + draftId, seller);
        assertThat(detail.path("code").asText()).as("已删草稿详情不可见").isEqualTo("404");

        // 回归：删除端点放行 DRAFT 后，在售商品的删除能力保持不变
        // 直插夹具（uuid-biz-id 票 05 起业务 id UUID 形态、应用侧发号）：夹具显式给业务 ID 并返回它
        String onSaleId = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO product (product_id, user_id, title, description, price, "
                        + "trade_location, category, state, create_time, update_time, expire_at) "
                        + "VALUES (?, ?, '在售存量商品', '回归用', 10.00, '驿站', 'BOOKS', 'ON_SALE', NOW(), NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY))",
                onSaleId, seller.userId());
        JsonNode deleteOnSale = deleteJson("/api/idle/products/" + onSaleId, seller);
        assertSuccess(deleteOnSale, "删除在售商品");
        assertThat(productStatus(onSaleId)).isEqualTo("DELETED");
    }

    // ---------- 提交：提交即上架（敏感词初筛已删） ----------

    @Test
    void 商品草稿编辑后提交即上架_含敏感词正常通过() {
        Session seller = registerAndLogin("13800004005", "卖家4");
        String productId = saveProductDraft(seller, "九成新吉他");

        // 编辑带入「作弊」等敏感词后提交：初筛已删，直接提交成功上架
        updateProductDraft(seller, productId, "九成新吉他", "可代考复习资料");
        JsonNode submit = postJson("/api/idle/products/" + productId + "/submit", null, seller);
        assertSuccess(submit, "草稿提交");
        assertThat(productStatus(productId)).as("提交即上架：DRAFT→ON_SALE").isEqualTo("ON_SALE");

        JsonNode publicList = getJson("/api/idle/products", seller);
        assertThat(productIdsOf(publicList)).as("提交即对外可见").contains(productId);
    }

    @Test
    void 不完整草稿提交失败提示补全() {
        Session seller = registerAndLogin("13800004006", "卖家5");
        String productId = saveProductDraft(seller, "只有标题的草稿");

        JsonNode response = postJson("/api/idle/products/" + productId + "/submit", null, seller);
        assertThat(response.path("code").asText()).as("缺价等必填项应拒绝提交：%s", response).isEqualTo("500");
        assertThat(response.path("info").asText()).contains("不完整");
        assertThat(productStatus(productId)).as("校验失败停留草稿").isEqualTo("DRAFT");
    }

    // ---------- 工具 ----------

    private String saveProductDraft(Session seller, String title) {
        JsonNode save = postJson("/api/idle/products/draft", Map.of("title", title), seller);
        assertSuccess(save, "保存商品草稿");
        return save.path("data").asText();
    }

    private void updateProductDraft(Session seller, String productId, String title, String description) {
        JsonNode update = putJson("/api/idle/products/" + productId + "/draft", Map.ofEntries(
                Map.entry("title", title),
                Map.entry("description", description),
                Map.entry("price", 19.9),
                Map.entry("tradeLocation", "图书馆门口"),
                Map.entry("category", "BOOKS")), seller);
        assertSuccess(update, "编辑商品草稿");
    }

    private String productStatus(String productId) {
        return jdbcTemplate.queryForObject("SELECT state FROM product WHERE product_id = ?", String.class, productId);
    }

    private List<String> productIdsOf(JsonNode response) {
        return StreamSupport.stream(response.path("data").path("list").spliterator(), false)
                .map(node -> node.path("productId").asText()).toList();
    }

}
