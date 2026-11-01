package com.betterschool.integration.errand;

import com.betterschool.domain.errand.adapter.port.IErrandGrabAbusePort;
import com.betterschool.domain.errand.adapter.repository.IErrandRepository;
import com.betterschool.domain.errand.model.entity.ErrandOrderEntity;
import com.betterschool.domain.errand.model.valobj.ErrandFeedPageVO;
import com.betterschool.domain.errand.service.ErrandOrderServiceImpl;
import com.betterschool.integration.support.CommunityApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * market-feed-uv 票 04：跑腿大厅读路径切换——errand_feed_index 索引序扫描 + 五档排序游标分页。
 *
 * <p>断言只看外部可观察状态：HTTP 响应的 list / total / cursor。五档排序 × 游标翻页的「不重不漏」
 * 以库内索引序为期望序；夹具刻意造同值并列（悬赏四档循环、热度三档循环、feed_time 五分钟循环）。
 * 大厅公开口径 = 无类型筛选 + status=PUBLISHED，其余口径与降级走旧直查。
 */
@DisplayName("跑腿大厅读路径：五档排序游标翻页 / 换排序拒游标 / 越尾空页 / 筛选直查 / 降级直查")
class ErrandFeedCursorPagingIntegrationTest extends CommunityApiTestBase {

    private static final int PAGE_SIZE = 10;
    private static final int ORDER_COUNT = 25;
    private static final List<String> SORTS = List.of("time", "time_asc", "hot", "price_asc", "price_desc");

    @Autowired
    private IErrandRepository errandRepository;
    @Autowired
    private IErrandGrabAbusePort errandGrabAbusePort;

    @Test
    @DisplayName("五档排序游标翻页不重不漏、整序与索引序一致、total 降口径 -1")
    void 五档排序游标翻页不重不漏且total降口径() {
        Session publisher = registerAndLogin("13600001401", "跑腿游标发单人");
        List<String> seeded = seedPublishedOrders(publisher, ORDER_COUNT);

        for (String sort : SORTS) {
            List<String> expected = indexOrderOf(sort);
            assertThat(expected).as("%s 期望序取自索引表", sort).hasSize(ORDER_COUNT);

            JsonNode first = fetchPage(sort, null, null);
            assertThat(first.path("total").asLong()).as("%s：索引路径 total 降口径", sort).isEqualTo(-1);
            assertThat(first.path("page").asLong()).as("%s：page/pageSize 兼容字段照常回显", sort).isEqualTo(1);

            List<String> walked = new ArrayList<>();
            int pages = 0;
            JsonNode data = first;
            while (data != null) {
                List<String> ids = orderIdsOf(data);
                assertThat(ids).as("%s：每页不超过 pageSize", sort).size().isLessThanOrEqualTo(PAGE_SIZE);
                assertThat(new HashSet<>(ids)).as("%s：单页内无重复", sort).hasSameSizeAs(ids);
                walked.addAll(ids);
                pages++;
                String next = data.path("cursor").asText(null);
                data = next == null ? null : fetchPage(sort, next, null);
            }
            assertThat(walked).as("%s：游标翻页不重不漏、整序与索引序一致", sort).containsExactlyElementsOf(expected);
            assertThat(pages).as("%s：25 条 / pageSize 10 → 三页（末页不满页自然收尾）", sort).isEqualTo(3);
            assertThat(walked).containsAll(seeded);
        }
    }

    @Test
    @DisplayName("大厅列表带索引热度快照（浏览量），供热度排序与展示同源")
    void 大厅列表带索引热度快照() {
        Session publisher = registerAndLogin("13600001402", "跑腿热度发单人");
        String orderId = seedPublishedOrders(publisher, 1).get(0);
        jdbcTemplate.update("UPDATE errand_feed_index SET view_count = 7 WHERE errand_order_id = ?", orderId);

        JsonNode data = fetchPage("hot", null, null);
        assertThat(orderIdsOf(data)).containsExactly(orderId);
        assertThat(data.path("list").get(0).path("viewCount").asLong())
                .as("列表浏览量取索引热度快照（详情仍走计数窄表精确值）").isEqualTo(7);
    }

    @Test
    @DisplayName("换排序复用旧游标 / 篡改游标一律报参数错误（不静默错位）")
    void 换排序复用旧游标与非法游标报参数错误() {
        Session publisher = registerAndLogin("13600001403", "跑腿游标校验发单人");
        seedPublishedOrders(publisher, ORDER_COUNT);

        String timeCursor = fetchPage("time", null, null).path("cursor").asText();
        assertThat(timeCursor).as("夹具够多，首页应发放续页游标").isNotNull();
        assertThat(getJson("/api/errand/orders?sort=price_desc&cursor=" + timeCursor, null).path("code").asText())
                .as("游标排序与请求不一致 → PARAM_ERROR").isEqualTo("400");
        assertThat(getJson("/api/errand/orders?sort=hot&cursor=" + timeCursor, null).path("code").asText())
                .as("热度排序复用最新游标 → PARAM_ERROR").isEqualTo("400");
        assertThat(getJson("/api/errand/orders?sort=time&cursor=garbage!!", null).path("code").asText())
                .as("Base64 解不开 → PARAM_ERROR").isEqualTo("400");
        // 跨域复用：闲置推荐页游标喂给跑腿大厅，域标签不符
        String productCursor = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("product|time|" + LocalDateTime.now() + "|1").getBytes(StandardCharsets.UTF_8));
        assertThat(getJson("/api/errand/orders?sort=time&cursor=" + productCursor, null).path("code").asText())
                .as("跨域游标 → PARAM_ERROR（不静默错位）").isEqualTo("400");
    }

    @Test
    @DisplayName("游标越过末尾返回空页：不重发第一页、不发续页游标、不降级直查")
    void 游标越过末尾返回空页() {
        Session publisher = registerAndLogin("13600001404", "跑腿末尾发单人");
        seedPublishedOrders(publisher, 5);

        Map<String, Object> tail = jdbcTemplate.queryForList(
                "SELECT errand_order_id, feed_time FROM errand_feed_index WHERE state = 'PUBLISHED' "
                        + "ORDER BY feed_time ASC, errand_order_id ASC LIMIT 1").get(0);
        // uuid-biz-id 票 05：游标 5 段 errand|sort|排序键|create_time|orderId（非热度档第二段留空）
        String raw = "errand|time|" + ((LocalDateTime) tail.get("feed_time")) + "||" + tail.get("errand_order_id");
        String cursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));

        JsonNode data = fetchPage("time", cursor, null);
        assertThat(orderIdsOf(data)).as("越过末尾 = 空页（不重发第一页）").isEmpty();
        assertThat(data.path("cursor").asText(null)).as("空页不发续页游标").isNull();
        assertThat(data.path("total").asLong()).as("空页仍走索引路径，不降级直查").isEqualTo(-1);
    }

    @Test
    @DisplayName("带类型筛选 / 非 PUBLISHED 口径走旧直查：精确 total、无游标")
    void 带筛选或非公开状态走旧直查() {
        Session publisher = registerAndLogin("13600001405", "跑腿筛选发单人");
        seedPublishedOrders(publisher, ORDER_COUNT);

        JsonNode byType = getJson("/api/errand/orders?type=EXPRESS_PICKUP&pageSize=" + PAGE_SIZE, null);
        assertSuccess(byType, "类型筛选列表");
        JsonNode typeData = byType.path("data");
        long typeTotal = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM errand_order WHERE state = 'PUBLISHED' AND type = 'EXPRESS_PICKUP'",
                Long.class);
        assertThat(typeData.path("total").asLong()).as("类型筛选 total 仍精确").isEqualTo(typeTotal);
        assertThat(typeData.path("cursor").asText(null)).as("类型筛选不发游标（按 page 续页）").isNull();
        assertThat(orderIdsOf(typeData)).isNotEmpty().hasSize((int) Math.min(PAGE_SIZE, typeTotal));

        // 筛选（旧直查）路径的浏览量读计数窄表：与索引快照同源，筛选不会把浏览量掉成 0
        String filteredFirst = orderIdsOf(typeData).get(0);
        jdbcTemplate.update("INSERT INTO errand_view_count (errand_order_id, view_count, create_time, update_time) "
                + "VALUES (?, 5, NOW(), NOW())", filteredFirst);
        JsonNode refetched = getJson("/api/errand/orders?type=EXPRESS_PICKUP&pageSize=" + PAGE_SIZE, null);
        assertSuccess(refetched, "类型筛选列表复读");
        assertThat(refetched.path("data").path("list").get(0).path("viewCount").asLong())
                .as("旧直查路径浏览量读计数窄表（两路口径对齐）").isEqualTo(5);

        // 非公开状态（COMPLETED）不在索引可见集内：同样落旧直查，total 精确
        jdbcTemplate.update("UPDATE errand_order SET state = 'COMPLETED' WHERE errand_order_id = ?",
                orderIdsOf(typeData).get(0));
        JsonNode completed = getJson("/api/errand/orders?status=COMPLETED&pageSize=" + PAGE_SIZE, null);
        assertSuccess(completed, "已完结核对口径列表");
        assertThat(completed.path("data").path("total").asLong()).as("非 PUBLISHED 口径 total 精确").isEqualTo(1);
        assertThat(completed.path("data").path("cursor").asText(null)).isNull();
    }

    @Test
    @DisplayName("索引查询异常时降级 DB 直查：indexPath=false、不发游标、仍出首页数据")
    void 索引查询异常时降级DB直查() {
        Session publisher = registerAndLogin("13600001406", "跑腿降级发单人");
        List<String> seeded = seedPublishedOrders(publisher, ORDER_COUNT);

        IErrandRepository failingIndexRepo = mock(IErrandRepository.class,
                withSettings().defaultAnswer(AdditionalAnswers.delegatesTo(errandRepository)));
        doThrow(new IllegalStateException("索引表不可用")).when(failingIndexRepo)
                .scanFeedIndex(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyInt());

        ErrandFeedPageVO page = new ErrandOrderServiceImpl(failingIndexRepo, errandGrabAbusePort)
                .listOrders(null, "PUBLISHED", "time", null, 1, PAGE_SIZE);
        assertThat(page.isIndexPath()).as("降级后 indexPath=false（total 由调用方精确 COUNT）").isFalse();
        assertThat(page.getNextCursor()).as("降级路径不发游标").isNull();
        assertThat(page.getOrders()).as("降级路径仍出满页数据").hasSize(PAGE_SIZE);
        // 旧直查按 create_time DESC：夹具 create_time 逐条错开（越晚插入越新），整序确定 → 首页取最新一段
        // （uuid-biz-id 票 05：tie-break 换 errand_order_id 字典序，UUID 无序，夹具不能靠它表达插入先后）
        assertThat(page.getOrders()).extracting(ErrandOrderEntity::getOrderId)
                .containsAll(seeded.subList(0, PAGE_SIZE));
    }

    // ---------- 夹具与可观察状态 ----------

    /**
     * 直插 N 条已发布订单 + 对应索引行（读路径测试只关心投影，不重复走付费/审核链）。
     * 夹具刻意造并列：悬赏四档循环、热度三档循环、feed_time 五分钟循环，tie-break 由业务 ID 收；
     * 业务 ID 是 UUID（uuid-biz-id 票 05，无序），create_time 逐条错开一分钟保证旧直查时间序确定
     */
    private List<String> seedPublishedOrders(Session publisher, int count) {
        List<String> orderIds = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String orderId = UUID.randomUUID().toString();
            BigDecimal reward = BigDecimal.valueOf((i % 4) * 5L + 1).setScale(2);
            String type = i % 2 == 0 ? "EXPRESS_PICKUP" : "BUY";
            int viewCount = (i % 3) * 2;
            jdbcTemplate.update("INSERT INTO errand_order (errand_order_id, publisher_id, type, description, "
                            + "pickup_location, delivery_location, reward, time_limit_hours, deadline, state, "
                            + "create_time, update_time) VALUES (?, ?, ?, '大厅游标夹具', '菜鸟驿站', '6号楼', ?, 2, "
                            + "DATE_ADD(NOW(), INTERVAL 2 HOUR), 'PUBLISHED', "
                            + "DATE_SUB(NOW(), INTERVAL ? MINUTE), NOW())",
                    orderId, publisher.userId(), type, reward, i);
            jdbcTemplate.update("INSERT INTO errand_feed_index (errand_order_id, state, feed_time, create_time, "
                            + "price, view_count, update_time) VALUES (?, 'PUBLISHED', ?, NOW(), ?, ?, NOW())",
                    orderId, LocalDateTime.now().minusMinutes(i % 5), reward, viewCount);
            orderIds.add(orderId);
        }
        return orderIds;
    }

    /** 期望序 = 库内索引序（与 mapper 的 ORDER BY 逐字同构；热度档三段收敛键随 uuid-biz-id 票 05 对齐） */
    private List<String> indexOrderOf(String sort) {
        String orderBy = switch (sort) {
            case "hot" -> "view_count DESC, create_time DESC, errand_order_id DESC";
            case "time_asc" -> "feed_time ASC, errand_order_id ASC";
            case "price_asc" -> "price ASC, errand_order_id ASC";
            case "price_desc" -> "price DESC, errand_order_id DESC";
            default -> "feed_time DESC, errand_order_id DESC";
        };
        return jdbcTemplate.queryForList(
                "SELECT errand_order_id FROM errand_feed_index WHERE state = 'PUBLISHED' ORDER BY " + orderBy,
                String.class);
    }

    /** 跑腿大厅请求（data 节点）；type 非空时走筛选直查 */
    private JsonNode fetchPage(String sort, String cursor, String type) {
        String url = "/api/errand/orders?pageSize=" + PAGE_SIZE + "&status=PUBLISHED&sort=" + sort
                + (type == null ? "" : "&type=" + type)
                + (cursor == null ? "" : "&cursor=" + cursor);
        JsonNode response = getJson(url, null);
        assertSuccess(response, "跑腿大厅");
        return response.path("data");
    }

    private List<String> orderIdsOf(JsonNode data) {
        List<String> ids = new ArrayList<>();
        data.path("list").forEach(node -> ids.add(node.path("orderId").asText()));
        return ids;
    }
}
