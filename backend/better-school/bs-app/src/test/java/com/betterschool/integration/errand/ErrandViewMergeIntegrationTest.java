package com.betterschool.integration.errand;

import com.betterschool.infrastructure.merge.count.CountAction;
import com.betterschool.infrastructure.merge.count.CountEvent;
import com.betterschool.infrastructure.merge.count.CountMerger;
import com.betterschool.infrastructure.merge.count.ICountFlushHandler;
import com.betterschool.infrastructure.merge.count.LikeState;
import com.betterschool.integration.support.CommunityApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跑腿浏览的 UV 口径（market-feed-uv 票 02：{@code errand_view_history} 明细即事实源 +
 * {@code errand_view_count} 加速列，与帖子 / 商品同构；跑腿此前无浏览量，从切码起按 UV 精确累计）。
 *
 * <p>断言只看外部可观察状态：HTTP 详情响应 + MySQL 数据。浏览走真实详情链路
 * （commit 合并写、等终态必须全落），断言前排空合并窗口。
 */
@DisplayName("跑腿浏览 UV：详情接线 viewCount / 首次计 1 / 重复只刷 viewed_at / 匿名不计")
class ErrandViewMergeIntegrationTest extends CommunityApiTestBase {

    private static final long DRAIN_MILLIS = 5_000;

    @Autowired
    private CountMerger countMerger;

    @Autowired
    private ICountFlushHandler flushHandler;

    @Test
    @DisplayName("登录浏览：明细落一行、加速列计 1，详情响应 viewCount 接线计数表")
    void 登录浏览计一并接线到详情() {
        String orderId = insertPublishedOrder("帮忙取一个快递");
        Session viewer = registerAndLogin("13800008001", "跑腿浏览者");

        JsonNode first = getJson("/api/errand/orders/" + orderId, viewer).path("data");
        assertThat(first.path("viewCount").asLong())
                .as("详情读的是库里的当前值：合并窗口未过，本次请求读到的还是浏览前的 0")
                .isZero();
        assertThat(countMerger.awaitIdle(DRAIN_MILLIS)).as("窗口过后必须排空").isTrue();

        assertThat(historyCountOf(orderId)).as("明细即事实源：一人一单一行").isEqualTo(1);
        assertThat(acceleratedCountOf(orderId)).as("加速列 = 明细行数").isEqualTo(1);

        JsonNode detail = getJson("/api/errand/orders/" + orderId, viewer).path("data");
        assertThat(detail.path("viewCount").asLong()).as("详情 viewCount 读计数表（接线）").isEqualTo(1);
    }

    @Test
    @DisplayName("重复浏览不计数只刷 viewed_at；匿名浏览不计入")
    void 重复浏览与匿名都不加数() {
        String orderId = insertPublishedOrder("代买一杯奶茶");
        Session viewer = registerAndLogin("13800008002", "回头客");
        // 预置一小时前的浏览明细（首次浏览已落），加速列对齐
        jdbcTemplate.update("INSERT INTO errand_view_history (user_id, errand_order_id, viewed_at) "
                + "VALUES (?, ?, DATE_SUB(NOW(), INTERVAL 1 HOUR))", viewer.userId(), orderId);
        jdbcTemplate.update("INSERT INTO errand_view_count (errand_order_id, view_count, create_time, update_time) "
                + "VALUES (?, 1, NOW(), NOW())", orderId);
        LocalDateTime before = jdbcTemplate.queryForObject(
                "SELECT viewed_at FROM errand_view_history WHERE user_id = ? AND errand_order_id = ?",
                LocalDateTime.class, viewer.userId(), orderId);

        assertSuccess(getJson("/api/errand/orders/" + orderId, viewer), "重复浏览跑腿详情");
        assertSuccess(getJson("/api/errand/orders/" + orderId, null), "匿名浏览跑腿详情");
        assertThat(countMerger.awaitIdle(DRAIN_MILLIS)).as("窗口过后必须排空").isTrue();

        assertThat(historyCountOf(orderId)).as("重复浏览不新增明细行、匿名落不了明细行").isEqualTo(1);
        assertThat(acceleratedCountOf(orderId)).as("两者都不计数").isEqualTo(1);
        LocalDateTime after = jdbcTemplate.queryForObject(
                "SELECT viewed_at FROM errand_view_history WHERE user_id = ? AND errand_order_id = ?",
                LocalDateTime.class, viewer.userId(), orderId);
        assertThat(after).as("viewed_at 刷新为浏览时刻").isAfter(before);
    }

    @Test
    @DisplayName("一组 N 件合并：批内同人不重复计，加速列按 INSERT 影响行数增量且与明细行数一致")
    void 一组浏览件按去重后新增数落库() {
        String orderId = insertPublishedOrder("帮忙搬一箱书");
        Session viewerA = registerAndLogin("13800008003", "浏览者甲");
        Session viewerB = registerAndLogin("13800008004", "浏览者乙");

        List<LikeState> results = flushHandler.flushGroup(CountAction.ERRAND_VIEW, orderId,
                List.of(viewEvent(orderId, viewerA.userId()), viewEvent(orderId, viewerA.userId()),
                        viewEvent(orderId, viewerB.userId())));

        assertThat(results).as("必须等长回填，否则引擎会把这些件记成 failed")
                .hasSize(3).doesNotContainNull().containsOnly(LikeState.APPLIED);
        assertThat(historyCountOf(orderId)).as("同组同人只落一行明细").isEqualTo(2);
        assertThat(acceleratedCountOf(orderId)).as("加速列按新增 UV 数增量 = 明细行数").isEqualTo(2);
    }

    // ---------- 工具 ----------

    private static CountEvent viewEvent(String orderId, String userId) {
        return new CountEvent(CountAction.ERRAND_VIEW, orderId, userId);
    }

    /** 直插一条大厅公开（PUBLISHED）订单：对外可见 ⇒ 登录/匿名详情均可用（uuid-biz-id 票 05：业务 ID 为 UUID） */
    private String insertPublishedOrder(String description) {
        String orderId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO errand_order (errand_order_id, publisher_id, type, description, "
                        + "pickup_location, delivery_location, reward, state, "
                        + "create_time, update_time) "
                        + "VALUES (?, 999, 'EXPRESS_PICKUP', ?, '驿站', '南门', 5.00, 'PUBLISHED', NOW(), NOW())",
                orderId, description);
        return orderId;
    }

    private int historyCountOf(String orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM errand_view_history WHERE errand_order_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private int acceleratedCountOf(String orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT view_count FROM errand_view_count WHERE errand_order_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }
}
