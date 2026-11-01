package com.betterschool.integration.trade;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 草稿箱（票 02）集成测试：保存（不做初筛、部分字段、仅本人可见）、编辑、删除、
 * 提交（初筛命中停留草稿；商品进待审）、商品待审撤回进草稿箱、重新提交回到发布流程。
 */
class TradeDraftBoxIntegrationTest extends CommunityApiTestBase {

    // ---------- 保存草稿：部分字段可存、不做初筛、仅本人可见 ----------

    @Test
    void 商品草稿部分字段保存成功_不做初筛_仅本人可见() {
        Session seller = registerAndLogin("13800004001", "卖家1");
        Session other = registerAndLogin("13800004002", "路人1");

        // 未填完：只有标题；且含敏感词——草稿保存不做初筛，直接发布则会被拦
        JsonNode save = postJson("/api/trade/products/draft",
                Map.of("title", "出售作弊神器"), seller);
        assertSuccess(save, "保存商品草稿");
        long productId = save.path("data").asLong();

        assertThat(productStatus(productId)).as("草稿状态应为 DRAFT").isEqualTo("DRAFT");

        JsonNode publicList = getJson("/api/trade/products", other);
        assertSuccess(publicList, "对外商品列表");
        assertThat(productIdsOf(publicList)).as("草稿不应对外可见").doesNotContain(productId);

        JsonNode otherMine = getJson("/api/trade/products/mine", other);
        assertThat(productIdsOf(otherMine)).as("他人我的发布不含该草稿").doesNotContain(productId);

        JsonNode otherDetail = getJson("/api/trade/products/" + productId, other);
        assertThat(otherDetail.path("code").asText()).as("他人看草稿详情应 404：%s", otherDetail).isEqualTo("404");

        JsonNode mine = getJson("/api/trade/products/mine", seller);
        List<JsonNode> mineItems = StreamSupport.stream(mine.path("data").path("list").spliterator(), false)
                .filter(node -> node.path("productId").asLong() == productId).toList();
        assertThat(mineItems).as("我的发布应含自己的草稿").hasSize(1);
        assertThat(mineItems.get(0).path("status").asText()).isEqualTo("DRAFT");

        JsonNode ownerDetail = getJson("/api/trade/products/" + productId, seller);
        assertSuccess(ownerDetail, "本人看自己的草稿");
        assertThat(ownerDetail.path("data").path("title").asText()).isEqualTo("出售作弊神器");
    }

    // ---------- 编辑草稿 ----------

    @Test
    void 商品草稿可编辑全部内容并回显() {
        Session seller = registerAndLogin("13800004003", "卖家2");
        long productId = saveProductDraft(seller, "旧台灯");

        JsonNode update = putJson("/api/trade/products/" + productId + "/draft", Map.ofEntries(
                Map.entry("title", "九成新护眼台灯"),
                Map.entry("description", "宿舍自用，毕业出"),
                Map.entry("images", List.of("http://minio/bs-files/it/draft-1.jpg", "http://minio/bs-files/it/draft-2.jpg")),
                Map.entry("price", 29.9),
                Map.entry("tradeLocation", "南门快递站"),
                Map.entry("category", "DIGITAL")), seller);
        assertSuccess(update, "编辑商品草稿");

        JsonNode detail = getJson("/api/trade/products/" + productId, seller);
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
        long draftId = saveProductDraft(seller, "不要了的草稿");

        JsonNode deleteDraft = deleteJson("/api/trade/products/" + draftId, seller);
        assertSuccess(deleteDraft, "删除草稿");
        assertThat(productStatus(draftId)).as("草稿软删").isEqualTo("DELETED");
        JsonNode detail = getJson("/api/trade/products/" + draftId, seller);
        assertThat(detail.path("code").asText()).as("已删草稿详情不可见").isEqualTo("404");

        // 回归：删除端点放行 DRAFT 后，在售商品的删除能力保持不变
        // 直插夹具：product_id NOT NULL 且无默认值（应用侧才走 insert 0 + 回写 id），夹具显式给带外业务 ID 并返回它
        long onSaleId = 900_000L + fixtureSeq++;
        jdbcTemplate.update("INSERT INTO product (product_id, user_id, title, description, price, "
                        + "trade_location, category, state, create_time, update_time, expire_at) "
                        + "VALUES (?, ?, '在售存量商品', '回归用', 10.00, '驿站', 'BOOKS', 'ON_SALE', NOW(), NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY))",
                onSaleId, seller.userId());
        JsonNode deleteOnSale = deleteJson("/api/trade/products/" + onSaleId, seller);
        assertSuccess(deleteOnSale, "删除在售商品");
        assertThat(productStatus(onSaleId)).isEqualTo("DELETED");
    }

    // ---------- 提交：初筛命中停留草稿；商品进待审 ----------

    @Test
    void 商品草稿提交命中初筛停留草稿_改后提交进入待审() {
        Session seller = registerAndLogin("13800004005", "卖家4");
        long productId = saveProductDraft(seller, "九成新吉他");

        // 编辑带入违禁词后提交：初筛命中应失败，且草稿原样保留可继续改
        updateProductDraft(seller, productId, "九成新吉他", "可代考复习资料");
        JsonNode hit = postJson("/api/trade/products/" + productId + "/submit", null, seller);
        assertThat(hit.path("code").asText()).as("初筛命中应业务失败：%s", hit).isEqualTo("500");
        assertThat(hit.path("info").asText()).contains("违规词汇");
        assertThat(productStatus(productId)).as("命中后停留草稿").isEqualTo("DRAFT");
        assertThat(countRows("product")).as("命中不新增行").isEqualTo(1);

        JsonNode detail = getJson("/api/trade/products/" + productId, seller);
        assertThat(detail.path("data").path("status").asText()).isEqualTo("DRAFT");

        // 改掉违禁词再提交：进待审
        updateProductDraft(seller, productId, "九成新吉他", "去年考证用过的资料");
        JsonNode retry = postJson("/api/trade/products/" + productId + "/submit", null, seller);
        assertSuccess(retry, "改后重新提交");
        assertThat(productStatus(productId)).as("先审后售：提交即待审").isEqualTo("PENDING_REVIEW");

        JsonNode publicList = getJson("/api/trade/products", seller);
        assertThat(productIdsOf(publicList)).as("待审对外仍不可见").doesNotContain(productId);
    }

    @Test
    void 不完整草稿提交失败提示补全() {
        Session seller = registerAndLogin("13800004006", "卖家5");
        long productId = saveProductDraft(seller, "只有标题的草稿");

        JsonNode response = postJson("/api/trade/products/" + productId + "/submit", null, seller);
        assertThat(response.path("code").asText()).as("缺价等必填项应拒绝提交：%s", response).isEqualTo("500");
        assertThat(response.path("info").asText()).contains("不完整");
        assertThat(productStatus(productId)).as("校验失败停留草稿").isEqualTo("DRAFT");
    }

    // ---------- 商品待审撤回 → 草稿箱 → 重新提交回发布流程 ----------

    @Test
    void 商品待审不可编辑_可撤回进草稿箱_重新提交回到待审() {
        Session seller = registerAndLogin("13800004007", "卖家6");
        long productId = createProduct(seller, "九成新滑板");
        assertThat(productStatus(productId)).isEqualTo("PENDING_REVIEW");

        JsonNode editPending = putJson("/api/trade/products/" + productId + "/draft",
                Map.of("title", "改标题"), seller);
        assertThat(editPending.path("code").asText())
                .as("待审中不可编辑：%s", editPending).isEqualTo("500");
        assertThat(editPending.path("info").asText()).contains("撤回");

        JsonNode withdraw = postJson("/api/trade/products/" + productId + "/withdraw", null, seller);
        assertSuccess(withdraw, "待审撤回");
        assertThat(productStatus(productId)).as("撤回进草稿箱").isEqualTo("DRAFT");

        // 撤回后可编辑，重新提交回到发布流程（再次待审）
        updateProductDraft(seller, productId, "全新滑板", "拆封未用");
        JsonNode resubmit = postJson("/api/trade/products/" + productId + "/submit", null, seller);
        assertSuccess(resubmit, "草稿重新提交");
        assertThat(productStatus(productId)).as("重新提交回到待审").isEqualTo("PENDING_REVIEW");
    }

    // ---------- 工具 ----------

    /** 直插夹具的商品业务 ID 取号带：远高于应用自增 id，避开 uk_product_id */
    private long fixtureSeq;

    private long saveProductDraft(Session seller, String title) {
        JsonNode save = postJson("/api/trade/products/draft", Map.of("title", title), seller);
        assertSuccess(save, "保存商品草稿");
        return save.path("data").asLong();
    }

    private void updateProductDraft(Session seller, long productId, String title, String description) {
        JsonNode update = putJson("/api/trade/products/" + productId + "/draft", Map.ofEntries(
                Map.entry("title", title),
                Map.entry("description", description),
                Map.entry("price", 19.9),
                Map.entry("tradeLocation", "图书馆门口"),
                Map.entry("category", "BOOKS")), seller);
        assertSuccess(update, "编辑商品草稿");
    }

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
