package com.betterschool.integration.errand;

import com.betterschool.infrastructure.adapter.merge.CountMerger;
import com.betterschool.integration.support.CommunityApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * market-feed-uv 票 05：跑腿大厅的已读灰显（{@code viewed} 标记）与匿名兼容。
 *
 * <p>口径与闲置侧同构（照帖子 {@code listPosts} 先例）：{@code errand_view_history} 明细是事实源，
 * 大厅列表组装后按页内 id 批量回查；只有大厅推荐接口填 viewed（详情与其他列表为 null），
 * 匿名一律 false。大厅匿名可浏览、游标可续页一并钉死。
 */
@DisplayName("跑腿大厅已读灰显：登录浏览后 viewed=true / 匿名恒 false / 仅列表填充")
class ErrandViewedIntegrationTest extends CommunityApiTestBase {

    /** 浏览走 fire-and-forget 合并写，断言列表前先排空合并窗口 */
    private static final long DRAIN_MILLIS = 5_000;
    private static final int PAGE_SIZE = 2;

    @Autowired
    private CountMerger countMerger;

    @Test
    @DisplayName("登录浏览详情后大厅该单 viewed=true、未浏览的 false；详情为 null")
    void 浏览详情后大厅已读灰显() {
        Session publisher = registerAndLogin("13600001501", "灰显发单人");
        String viewedOrderId = seedPublishedOrder(publisher, "看过的一单");
        String unseenOrderId = seedPublishedOrder(publisher, "没看过的一单");
        Session viewer = registerAndLogin("13600001502", "跑腿浏览者");

        assertSuccess(getJson("/api/errand/orders/" + viewedOrderId, viewer), "浏览跑腿详情");
        assertThat(countMerger.awaitIdle(DRAIN_MILLIS)).as("窗口过后浏览明细必须落库").isTrue();

        JsonNode data = fetchPage(viewer, null);
        assertThat(viewedOf(data, viewedOrderId)).as("浏览过的订单在大厅里已读").isTrue();
        assertThat(viewedOf(data, unseenOrderId)).as("未浏览的订单不灰显").isFalse();

        // 带 type 筛选走旧直查（另一条组装路径）：已读标记与索引路径同一口径
        JsonNode filtered = getJson("/api/errand/orders?status=PUBLISHED&type=EXPRESS_PICKUP&pageSize=10", viewer);
        assertSuccess(filtered, "类型筛选列表");
        assertThat(viewedOf(filtered.path("data"), viewedOrderId)).as("筛选直查路径同样带已读标记").isTrue();

        JsonNode detail = getJson("/api/errand/orders/" + viewedOrderId, viewer).path("data");
        assertThat(detail.path("viewed").isNull()).as("viewed 仅大厅列表填充，详情为 null").isTrue();
    }

    @Test
    @DisplayName("匿名可浏览：大厅 viewed 恒 false（明确的字段值），热度排序下浏览量照常可读，游标续页不重不漏")
    void 匿名大厅灰显恒为false且游标可用() {
        Session publisher = registerAndLogin("13600001503", "匿名灰显发单人");
        List<String> seeded = seedPublishedOrders(publisher, 3);

        assertSuccess(getJson("/api/errand/orders/" + seeded.get(0), null), "匿名浏览跑腿详情");
        assertThat(countMerger.awaitIdle(DRAIN_MILLIS)).as("窗口过后必须排空").isTrue();

        List<String> walked = new ArrayList<>();
        JsonNode data = fetchPage(null, null);
        int pages = 0;
        while (data != null) {
            for (JsonNode item : data.path("list")) {
                assertThat(item.path("viewed").isBoolean()).as("匿名也需要明确的 false，不是缺字段").isTrue();
                assertThat(item.path("viewed").asBoolean()).as("匿名永远不灰显").isFalse();
                // 卡片浏览量展示的数据源（票 05 起两域卡片同口径）：大厅取索引热度快照，恒可读
                assertThat(item.path("viewCount").isNumber()).as("大厅列表必须带浏览量").isTrue();
                walked.add(item.path("orderId").asText());
            }
            pages++;
            String next = data.path("cursor").asText(null);
            data = next == null ? null : fetchPage(null, next);
        }
        assertThat(walked).as("匿名游标续页不重不漏").containsExactlyInAnyOrderElementsOf(seeded);
        assertThat(pages).as("3 条 / pageSize 2 → 两页").isEqualTo(2);
    }

    // ---------- 夹具与可观察状态 ----------

    private List<String> seedPublishedOrders(Session publisher, int count) {
        List<String> orderIds = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            orderIds.add(seedPublishedOrder(publisher, "灰显夹具" + i));
        }
        return orderIds;
    }

    /** 直插一条已发布订单 + 对应索引行（大厅只走索引路径，缺索引行就不会出现在列表里） */
    private String seedPublishedOrder(Session publisher, String description) {
        String orderId = UUID.randomUUID().toString();
        BigDecimal reward = BigDecimal.valueOf(15).setScale(2);
        jdbcTemplate.update("INSERT INTO errand_order (errand_order_id, publisher_id, type, description, "
                        + "pickup_location, delivery_location, reward, time_limit_hours, deadline, state, "
                        + "create_time, update_time) VALUES (?, ?, 'EXPRESS_PICKUP', ?, '菜鸟驿站', '6号楼', ?, 2, "
                        + "DATE_ADD(NOW(), INTERVAL 2 HOUR), 'PUBLISHED', NOW(), NOW())",
                orderId, publisher.userId(), description, reward);
        jdbcTemplate.update("INSERT INTO errand_feed_index (errand_order_id, state, feed_time, create_time, price, "
                        + "view_count, update_time) VALUES (?, 'PUBLISHED', ?, NOW(), ?, 0, NOW())",
                orderId, LocalDateTime.now(), reward);
        return orderId;
    }

    /** 大厅请求（data 节点） */
    private JsonNode fetchPage(Session session, String cursor) {
        String url = "/api/errand/orders?pageSize=" + PAGE_SIZE + "&status=PUBLISHED"
                + (cursor == null ? "" : "&cursor=" + cursor);
        JsonNode response = getJson(url, session);
        assertSuccess(response, "跑腿大厅");
        return response.path("data");
    }

    /** 页内某订单的 viewed 值（订单必须在本页，缺行直接断言失败而不是静默 false） */
    private boolean viewedOf(JsonNode data, String orderId) {
        for (JsonNode item : data.path("list")) {
            if (item.path("orderId").asText().equals(orderId)) {
                return item.path("viewed").asBoolean();
            }
        }
        throw new AssertionError("订单不在本页：orderId=" + orderId + "，页内容=" + data);
    }
}
