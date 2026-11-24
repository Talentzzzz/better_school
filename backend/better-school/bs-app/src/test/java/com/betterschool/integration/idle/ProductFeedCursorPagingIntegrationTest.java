package com.betterschool.integration.idle;

import com.betterschool.domain.idle.adapter.repository.IIdleRepository;
import com.betterschool.domain.idle.model.entity.ProductEntity;
import com.betterschool.domain.idle.model.valobj.ProductFeedPageVO;
import com.betterschool.domain.idle.service.IdleServiceImpl;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.withSettings;

/**
 * market-feed-uv 票 04：闲置推荐页读路径切换——product_feed_index 索引序扫描 + 五档排序游标分页。
 *
 * <p>断言只看外部可观察状态：HTTP 响应的 list / total / cursor。五档排序 × 游标翻页的「不重不漏」
 * 以库内索引序为期望序（排序键与 product_id tie-break 都按索引列序）；夹具刻意造同值并列
 * （价格四档循环、热度三档循环、feed_time 五分钟循环）钉死 tie-break。筛选与降级路径走旧直查；
 * 降级测试是白盒（黑盒造不出索引表查询故障，照帖子 FeedCursorPagingIntegrationTest 先例）。
 */
@DisplayName("闲置推荐页读路径：五档排序游标翻页 / 换排序拒游标 / 越尾空页 / 筛选直查 / 降级直查")
class ProductFeedCursorPagingIntegrationTest extends CommunityApiTestBase {

    private static final int PAGE_SIZE = 10;
    private static final int PRODUCT_COUNT = 25;
    private static final List<String> SORTS = List.of("time", "time_asc", "hot", "price_asc", "price_desc");

    @Autowired
    private IIdleRepository idleRepository;

    @Test
    @DisplayName("五档排序游标翻页不重不漏、整序与索引序一致、total 降口径 -1")
    void 五档排序游标翻页不重不漏且total降口径() {
        Session seller = registerAndLogin("13700001401", "闲置游标卖家");
        List<String> seeded = seedOnSaleProducts(seller, PRODUCT_COUNT);

        for (String sort : SORTS) {
            List<String> expected = indexOrderOf(sort);
            assertThat(expected).as("%s 期望序取自索引表", sort).hasSize(PRODUCT_COUNT);

            JsonNode first = fetchPage(sort, null);
            assertThat(first.path("total").asLong()).as("%s：索引路径 total 降口径", sort).isEqualTo(-1);
            assertThat(first.path("page").asLong()).as("%s：page/pageSize 兼容字段照常回显", sort).isEqualTo(1);
            assertThat(first.path("pageSize").asLong()).isEqualTo(PAGE_SIZE);

            List<String> walked = new ArrayList<>();
            int pages = 0;
            JsonNode data = first;
            while (data != null) {
                List<String> ids = productIdsOf(data);
                assertThat(ids).as("%s：每页不超过 pageSize", sort).size().isLessThanOrEqualTo(PAGE_SIZE);
                assertThat(new HashSet<>(ids)).as("%s：单页内无重复", sort).hasSameSizeAs(ids);
                walked.addAll(ids);
                pages++;
                String next = data.path("cursor").asText(null);
                data = next == null ? null : fetchPage(sort, next);
            }
            assertThat(walked).as("%s：游标翻页不重不漏、整序与索引序一致", sort).containsExactlyElementsOf(expected);
            assertThat(pages).as("%s：25 条 / pageSize 10 → 三页（末页不满页自然收尾）", sort).isEqualTo(3);
            assertThat(walked).containsAll(seeded);
        }
    }

    @Test
    @DisplayName("换排序复用旧游标 / 篡改游标一律报参数错误（不静默错位）")
    void 换排序复用旧游标与非法游标报参数错误() {
        Session seller = registerAndLogin("13700001402", "闲置游标校验卖家");
        seedOnSaleProducts(seller, PRODUCT_COUNT);

        String timeCursor = fetchPage("time", null).path("cursor").asText();
        assertThat(timeCursor).as("夹具够多，首页应发放续页游标").isNotNull();
        // 换 tab 复用：同一游标拿去热度排序，解码出的排序与请求不符
        assertThat(getJson("/api/idle/products?sort=hot&cursor=" + timeCursor, null).path("code").asText())
                .as("游标排序与请求不一致 → PARAM_ERROR").isEqualTo("400");
        assertThat(getJson("/api/idle/products?sort=price_asc&cursor=" + timeCursor, null).path("code").asText())
                .as("价格排序复用最新游标 → PARAM_ERROR").isEqualTo("400");
        assertThat(getJson("/api/idle/products?sort=time&cursor=garbage!!", null).path("code").asText())
                .as("Base64 解不开 → PARAM_ERROR").isEqualTo("400");
        // 跨域复用：帖子域游标喂给闲置推荐页，域标签不符
        assertThat(getJson("/api/idle/products?sort=time&cursor=" + foreignDomainCursorSeed(), null).path("code").asText())
                .as("跨域游标 → PARAM_ERROR（不静默错位）").isEqualTo("400");
    }

    @Test
    @DisplayName("游标越过末尾返回空页：不重发第一页、不发续页游标、不降级直查")
    void 游标越过末尾返回空页() {
        Session seller = registerAndLogin("13700001403", "闲置末尾卖家");
        seedOnSaleProducts(seller, 5);

        // 手工构造锚在末行上的游标（(feed_time, product_id) 最小，续页扫描必为空——
        // 正常端点不会对末页发游标，越尾游标只来自陈旧客户端 / 行被删后的锚点）。
        // uuid-biz-id 票 05 起游标 5 段：product | sort | 排序键 | create_time（time 档留空） | product_id
        Map<String, Object> tail = jdbcTemplate.queryForList(
                "SELECT product_id, feed_time FROM product_feed_index WHERE state = 'ON_SALE' "
                        + "ORDER BY feed_time ASC, product_id ASC LIMIT 1").get(0);
        String raw = "product|time|" + ((LocalDateTime) tail.get("feed_time")) + "||" + tail.get("product_id");
        String cursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));

        JsonNode data = fetchPage("time", cursor);
        assertThat(productIdsOf(data)).as("越过末尾 = 空页（不重发第一页）").isEmpty();
        assertThat(data.path("cursor").asText(null)).as("空页不发续页游标").isNull();
        assertThat(data.path("total").asLong()).as("空页仍走索引路径，不降级直查").isEqualTo(-1);
    }

    @Test
    @DisplayName("带分类 / 价格区间筛选走旧直查：精确 total、无游标，筛选结果与直查口径一致")
    void 带筛选走旧直查() {
        Session seller = registerAndLogin("13700001404", "闲置筛选卖家");
        seedOnSaleProducts(seller, PRODUCT_COUNT);

        JsonNode byCategory = getJson("/api/idle/products?category=BOOKS&pageSize=" + PAGE_SIZE, null);
        assertSuccess(byCategory, "分类筛选列表");
        JsonNode categoryData = byCategory.path("data");
        long categoryTotal = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product WHERE state = 'ON_SALE' AND category = 'BOOKS'", Long.class);
        assertThat(categoryData.path("total").asLong()).as("筛选路径 total 仍精确").isEqualTo(categoryTotal);
        assertThat(categoryData.path("cursor").asText(null)).as("筛选路径不发游标（按 page 续页）").isNull();
        assertThat(productIdsOf(categoryData)).as("分类筛选结果与旧直查一致").isNotEmpty()
                .hasSize(Math.min(PAGE_SIZE, (int) categoryTotal));

        JsonNode byPrice = getJson("/api/idle/products?minPrice=11&maxPrice=16&pageSize=" + PAGE_SIZE, null);
        assertSuccess(byPrice, "价格区间筛选列表");
        JsonNode priceData = byPrice.path("data");
        long priceTotal = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product WHERE state = 'ON_SALE' AND price BETWEEN 11 AND 16", Long.class);
        assertThat(priceData.path("total").asLong()).as("价格区间 total 仍精确").isEqualTo(priceTotal);
        assertThat(priceData.path("cursor").asText(null)).isNull();
        assertThat(productIdsOf(priceData)).isNotEmpty();
    }

    @Test
    @DisplayName("索引查询异常时降级 DB 直查：indexPath=false、不发游标、仍出首页数据")
    void 索引查询异常时降级DB直查() {
        Session seller = registerAndLogin("13700001405", "闲置降级卖家");
        List<String> seeded = seedOnSaleProducts(seller, PRODUCT_COUNT);

        // 黑盒造不出索引表查询故障：白盒构造一个索引扫描必抛异常的域服务，仓储 mock 只覆盖
        // scanFeedIndex，其余方法委托真实实现（真实仓储 + 真实数据，降级后走同一条旧直查）
        IIdleRepository failingIndexRepo = mock(IIdleRepository.class,
                withSettings().defaultAnswer(AdditionalAnswers.delegatesTo(idleRepository)));
        doThrow(new IllegalStateException("索引表不可用")).when(failingIndexRepo)
                .scanFeedIndex(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyInt());

        ProductFeedPageVO page = new IdleServiceImpl(failingIndexRepo)
                .listProducts(null, null, null, "time", null, 1, PAGE_SIZE);
        assertThat(page.isIndexPath()).as("降级后 indexPath=false（total 由调用方精确 COUNT）").isFalse();
        assertThat(page.getNextCursor()).as("降级路径不发游标").isNull();
        assertThat(page.getProducts()).as("降级路径仍出满页数据").hasSize(PAGE_SIZE);
        // 旧直查按 create_time DESC, product_id DESC：夹具逐条错开 create_time（uuid-biz-id 票 05 起
        // 业务 id 是 UUID、无自增时间趋势，「首页 = 最新发出的 PAGE_SIZE 条」靠错开秒数固化）
        assertThat(page.getProducts()).extracting(ProductEntity::getProductId)
                .containsAll(seeded.subList(PRODUCT_COUNT - PAGE_SIZE, PRODUCT_COUNT));
    }

    // ---------- 夹具与可观察状态 ----------

    /**
     * 直插 N 条在售商品 + 对应索引行（读路径测试只关心投影，不重复走发布链）。
     * 夹具刻意造并列：价格四档循环、热度三档循环、feed_time 五分钟循环，tie-break 由 product_id 收；
     * uuid-biz-id 票 05 起业务 id 直插 UUID 形态（应用侧发号），并逐条错开 create_time——
     * 热度档同值收敛键（view_count DESC, create_time DESC, product_id DESC）与降级直查都吃它
     */
    private List<String> seedOnSaleProducts(Session seller, int count) {
        List<String> productIds = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String productId = java.util.UUID.randomUUID().toString();
            BigDecimal price = BigDecimal.valueOf((i % 4) * 5L + 1).setScale(2);
            String category = i % 2 == 0 ? "BOOKS" : "DIGITAL";
            int viewCount = (i % 3) * 2;
            // 时间参数 Java 侧算好再绑（ShardingSphere 解析 NOW() - INTERVAL n SECOND 会 bad SQL grammar）：
            // 第 i 条 = (count - i) 秒前，后插的更新，与原自增 id 升序同向
            java.sql.Timestamp created = java.sql.Timestamp.valueOf(LocalDateTime.now().minusSeconds(count - i));
            jdbcTemplate.update("INSERT INTO product (product_id, user_id, title, description, price, "
                            + "trade_location, category, state, create_time, update_time, "
                            + "expire_at) VALUES (?, ?, ?, '夹具描述', ?, '驿站', ?, 'ON_SALE', ?, ?, "
                            + "DATE_ADD(NOW(), INTERVAL 30 DAY))",
                    productId, seller.userId(), "闲置游标夹具" + i, price, category, created, created);
            jdbcTemplate.update("INSERT INTO product_feed_index (product_id, state, feed_time, create_time, price, "
                            + "view_count, update_time) VALUES (?, 'ON_SALE', ?, ?, ?, ?, NOW())",
                    productId, LocalDateTime.now().minusMinutes(i % 5), created.toLocalDateTime(), price, viewCount);
            productIds.add(productId);
        }
        return productIds;
    }

    /** 期望序 = 库内索引序（与 mapper 的 ORDER BY 逐字同构） */
    private List<String> indexOrderOf(String sort) {
        String orderBy = switch (sort) {
            // uuid-biz-id 票 05：热度档三段收敛 = view_count DESC, create_time DESC, product_id DESC
            case "hot" -> "view_count DESC, create_time DESC, product_id DESC";
            case "time_asc" -> "feed_time ASC, product_id ASC";
            case "price_asc" -> "price ASC, product_id ASC";
            case "price_desc" -> "price DESC, product_id DESC";
            default -> "feed_time DESC, product_id DESC";
        };
        return jdbcTemplate.queryForList(
                "SELECT product_id FROM product_feed_index WHERE state = 'ON_SALE' ORDER BY " + orderBy, String.class);
    }

    /** 手工造一条帖子域游标：只用于跨域复用校验（域标签不符即拒，不依赖真实帖子行） */
    private String foreignDomainCursorSeed() {
        String raw = "post|time|" + LocalDateTime.now() + "|1";
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 闲置推荐页请求（data 节点） */
    private JsonNode fetchPage(String sort, String cursor) {
        String url = "/api/idle/products?pageSize=" + PAGE_SIZE + "&sort=" + sort
                + (cursor == null ? "" : "&cursor=" + cursor);
        JsonNode response = getJson(url, null);
        assertSuccess(response, "闲置推荐页");
        return response.path("data");
    }

    private List<String> productIdsOf(JsonNode data) {
        List<String> ids = new ArrayList<>();
        data.path("list").forEach(node -> ids.add(node.path("productId").asText()));
        return ids;
    }
}
