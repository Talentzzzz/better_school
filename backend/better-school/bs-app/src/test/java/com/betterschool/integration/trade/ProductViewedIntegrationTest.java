package com.betterschool.integration.trade;

import com.betterschool.infrastructure.adapter.repository.trade.TradeRepository;
import com.betterschool.integration.support.CommunityApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * market-feed-uv 票 05：闲置推荐页的已读灰显（{@code viewed} 标记）。
 *
 * <p>口径（照帖子 {@code listPosts} 先例）：浏览明细 {@code product_view_history} 是唯一事实源，
 * 列表组装后按页内 id 批量回查；只有<b>推荐列表接口</b>填 viewed（详情与其他列表为 null），
 * 匿名一律 false（不是缺字段）——市集两 tab 匿名可浏览，灰显只对登录用户有意义。
 * 断言只看 HTTP 响应（外部可观察），匿名与游标兼容一并钉死。
 */
@DisplayName("闲置推荐页已读灰显：登录浏览后 viewed=true / 匿名恒 false / 仅列表填充")
class ProductViewedIntegrationTest extends CommunityApiTestBase {

    /** 浏览是合并写（commit 等终态，无收集窗口），断言列表前先排空合并器只为兜底在途件 */
    private static final long DRAIN_MILLIS = 5_000;
    private static final int PAGE_SIZE = 2;

    @Autowired
    private TradeRepository flushHandler;

    @Test
    @DisplayName("登录浏览详情后列表该商品 viewed=true、未浏览的 false；筛选直查路径同样带标记；详情为 null")
    void 浏览详情后列表已读灰显() {
        Session seller = registerAndLogin("13700001501", "灰显卖家");
        String viewedProductId = seedOnSaleProduct(seller, "看过的台灯");
        String unseenProductId = seedOnSaleProduct(seller, "没看过的键盘");
        Session viewer = registerAndLogin("13700001502", "浏览者");

        assertSuccess(getJson("/api/trade/products/" + viewedProductId, viewer), "浏览商品详情");
        assertThat(flushHandler.awaitIdle(DRAIN_MILLIS)).as("窗口过后浏览明细必须落库").isTrue();

        JsonNode data = fetchPage(viewer, null, "");
        assertThat(viewedOf(data, viewedProductId)).as("浏览过的商品在列表里已读").isTrue();
        assertThat(viewedOf(data, unseenProductId)).as("未浏览的商品不灰显").isFalse();

        // 筛选走旧直查（另一条组装路径）：已读标记与索引路径同一口径
        JsonNode filtered = fetchPage(viewer, null, "&category=BOOKS");
        assertThat(viewedOf(filtered, viewedProductId)).as("筛选直查路径同样带已读标记").isTrue();

        // 只填列表：详情不填（JSON 记成 null，与「其余列表 null」一致）
        JsonNode detail = getJson("/api/trade/products/" + viewedProductId, viewer).path("data");
        assertThat(detail.path("viewed").isNull()).as("viewed 仅推荐列表填充，详情为 null").isTrue();
    }

    @Test
    @DisplayName("匿名可浏览：列表 viewed 恒 false（明确的字段值，不是缺字段），游标续页不重不漏")
    void 匿名列表灰显恒为false且游标可用() {
        Session seller = registerAndLogin("13700001503", "匿名灰显卖家");
        List<String> seeded = seedOnSaleProducts(seller, 3);

        // 匿名也能看详情，但不落浏览明细（匿名不计入），列表照常可浏览
        assertSuccess(getJson("/api/trade/products/" + seeded.get(0), null), "匿名浏览商品详情");
        assertThat(flushHandler.awaitIdle(DRAIN_MILLIS)).as("合并器必须已排空").isTrue();

        List<String> walked = new ArrayList<>();
        JsonNode data = fetchPage(null, null, "");
        int pages = 0;
        while (data != null) {
            for (JsonNode item : data.path("list")) {
                assertThat(item.path("viewed").isBoolean()).as("匿名也需要明确的 false，不是缺字段").isTrue();
                assertThat(item.path("viewed").asBoolean()).as("匿名永远不灰显").isFalse();
                walked.add(item.path("productId").asText());
            }
            pages++;
            String next = data.path("cursor").asText(null);
            data = next == null ? null : fetchPage(null, next, "");
        }
        assertThat(walked).as("匿名游标续页不重不漏").containsExactlyInAnyOrderElementsOf(seeded);
        assertThat(pages).as("3 条 / pageSize 2 → 两页").isEqualTo(2);
    }

    // ---------- 夹具与可观察状态 ----------

    /**
     * 直插 N 条在售商品 + 对应索引行（推荐列表只走索引路径，缺索引行就不会出现在列表里）。
     * uuid-biz-id 票 05 起业务 id 直插 UUID 形态（应用侧发号，自增同值回写已删）
     */
    private List<String> seedOnSaleProducts(Session seller, int count) {
        List<String> productIds = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            productIds.add(seedOnSaleProduct(seller, "灰显夹具" + i));
        }
        return productIds;
    }

    private String seedOnSaleProduct(Session seller, String title) {
        String productId = java.util.UUID.randomUUID().toString();
        BigDecimal price = BigDecimal.valueOf(12).setScale(2);
        jdbcTemplate.update("INSERT INTO product (product_id, user_id, title, description, price, trade_location, "
                        + "category, state, create_time, update_time, expire_at) "
                        + "VALUES (?, ?, ?, '灰显夹具描述', ?, '驿站', 'BOOKS', 'ON_SALE', NOW(), NOW(), "
                        + "DATE_ADD(NOW(), INTERVAL 30 DAY))",
                productId, seller.userId(), title, price);
        jdbcTemplate.update("INSERT INTO product_feed_index (product_id, state, feed_time, create_time, price, "
                        + "view_count, update_time) VALUES (?, 'ON_SALE', ?, NOW(), ?, 0, NOW())",
                productId, LocalDateTime.now(), price);
        return productId;
    }

    /** 推荐页请求（data 节点）；extraQuery 承载筛选 / 游标这类附加参数 */
    private JsonNode fetchPage(Session session, String cursor, String extraQuery) {
        String url = "/api/trade/products?pageSize=" + PAGE_SIZE
                + (cursor == null ? "" : "&cursor=" + cursor)
                + (extraQuery == null ? "" : extraQuery);
        JsonNode response = getJson(url, session);
        assertSuccess(response, "闲置推荐页");
        return response.path("data");
    }

    /** 页内某商品的 viewed 值（商品必须在本页，缺行直接断言失败而不是静默 false） */
    private boolean viewedOf(JsonNode data, String productId) {
        for (JsonNode item : data.path("list")) {
            if (item.path("productId").asText().equals(productId)) {
                return item.path("viewed").asBoolean();
            }
        }
        throw new AssertionError("商品不在本页：productId=" + productId + "，页内容=" + data);
    }
}
