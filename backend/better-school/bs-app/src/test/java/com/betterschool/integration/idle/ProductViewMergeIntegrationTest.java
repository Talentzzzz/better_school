package com.betterschool.integration.idle;

import com.betterschool.infrastructure.adapter.repository.CountAction;
import com.betterschool.infrastructure.adapter.repository.CountEvent;
import com.betterschool.infrastructure.adapter.repository.idle.IdleRepository;
import com.betterschool.infrastructure.adapter.repository.LikeState;
import com.betterschool.integration.support.PostApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商品浏览的 UV 口径（market-feed-uv 票 02：{@code product_view_history} 明细即事实源 +
 * {@code product_view_count} 加速列，与帖子 {@code viewPosts} 合并器三步同构）。
 *
 * <p>语义变更钉死在数据上：首次浏览计 1、重复浏览不计数只刷 viewed_at、<b>匿名不计入</b>
 * （旧口径匿名也累加 PV，随本票作废）、加速列恒等于明细行数；旧 PV 列 {@code product.view_count}
 * 随本票停写、票 06 删列（主表已无该列）。浏览走真实详情链路（commit 合并写、等终态必须全落、
 * 队列非空即发车，没有收集窗口），断言前排空合并器只为兜底。
 */
@DisplayName("商品浏览 UV：首次计 1 / 重复只刷 viewed_at / 匿名不计 / 加速列=明细行数")
class ProductViewMergeIntegrationTest extends PostApiTestBase {

    private static final long DRAIN_MILLIS = 5_000;


    @Autowired
    private IdleRepository flushHandler;

    @Test
    @DisplayName("登录浏览：明细落一行、加速列计 1，详情响应读计数表")
    void 登录浏览计一并读计数表() {
        String productId = insertOnSaleProduct("登录浏览计数的台灯");
        Session viewer = registerAndLogin("13700006001", "商品浏览者");

        assertSuccess(getJson("/api/idle/products/" + productId, viewer), "登录浏览商品详情");
        assertThat(flushHandler.awaitIdle(DRAIN_MILLIS)).as("合并器必须已排空").isTrue();

        assertThat(historyCountOf(productId)).as("明细即事实源：一人一商品一行").isEqualTo(1);
        assertThat(acceleratedCountOf(productId)).as("加速列 = 明细行数").isEqualTo(1);

        JsonNode detail = getJson("/api/idle/products/" + productId, viewer).path("data");
        assertThat(detail.path("viewCount").asLong()).as("详情 viewCount 读计数表（换源）").isEqualTo(1);
        assertThat(outboxCountOf("bs-product-count")).as("商品浏览不走 outbox + MQ（沿用合并写）").isZero();
    }

    @Test
    @DisplayName("重复浏览：不计数，唯一可见效果是 viewed_at 变新")
    void 重复浏览只刷ViewedAt不加数() {
        String productId = insertOnSaleProduct("重复浏览的键盘");
        Session viewer = registerAndLogin("13700006002", "回头客");
        // 预置一小时前的浏览明细（首次浏览已落），加速列对齐
        jdbcTemplate.update("INSERT INTO product_view_history (user_id, product_id, viewed_at) "
                + "VALUES (?, ?, DATE_SUB(NOW(), INTERVAL 1 HOUR))", viewer.userId(), productId);
        jdbcTemplate.update("INSERT INTO product_view_count (product_id, view_count, create_time, update_time) "
                + "VALUES (?, 1, NOW(), NOW())", productId);
        LocalDateTime before = jdbcTemplate.queryForObject(
                "SELECT viewed_at FROM product_view_history WHERE user_id = ? AND product_id = ?",
                LocalDateTime.class, viewer.userId(), productId);

        assertSuccess(getJson("/api/idle/products/" + productId, viewer), "重复浏览商品详情");
        assertThat(flushHandler.awaitIdle(DRAIN_MILLIS)).as("合并器必须已排空").isTrue();

        assertThat(historyCountOf(productId)).as("重复浏览不新增明细行").isEqualTo(1);
        assertThat(acceleratedCountOf(productId)).as("重复浏览不计数").isEqualTo(1);
        LocalDateTime after = jdbcTemplate.queryForObject(
                "SELECT viewed_at FROM product_view_history WHERE user_id = ? AND product_id = ?",
                LocalDateTime.class, viewer.userId(), productId);
        assertThat(after).as("viewed_at 刷新为浏览时刻").isAfter(before);
    }

    @Test
    @DisplayName("匿名浏览不计入：明细与加速列都不落（商品语义变更）")
    void 匿名浏览不计入() {
        String productId = insertOnSaleProduct("匿名浏览的手电筒");
        assertSuccess(getJson("/api/idle/products/" + productId, null), "匿名浏览商品详情");
        assertThat(flushHandler.awaitIdle(DRAIN_MILLIS)).as("合并器必须已排空").isTrue();

        assertThat(historyCountOf(productId)).as("匿名落不了明细行（主键一半是 user_id）").isZero();
        assertThat(acceleratedCountOf(productId)).as("无明细则无计数").isZero();
    }

    @Test
    @DisplayName("一组 N 件合并：批内同人不重复计，加速列按 INSERT 影响行数增量且与明细行数一致")
    void 一组浏览件按去重后新增数落库() {
        String productId = insertOnSaleProduct("一组浏览件的商品");
        Session viewerA = registerAndLogin("13700006003", "浏览者甲");
        Session viewerB = registerAndLogin("13700006004", "浏览者乙");

        List<LikeState> results = flushHandler.flushViewGroup(productId,
                List.of(viewEvent(productId, viewerA.userId()), viewEvent(productId, viewerA.userId()),
                        viewEvent(productId, viewerB.userId())));

        assertThat(results).as("必须等长回填，否则引擎会把这些件记成 failed")
                .hasSize(3).doesNotContainNull().containsOnly(LikeState.APPLIED);
        assertThat(historyCountOf(productId)).as("同组同人只落一行明细").isEqualTo(2);
        assertThat(acceleratedCountOf(productId)).as("加速列按新增 UV 数增量 = 明细行数").isEqualTo(2);

        // 再来一组全重复：INSERT IGNORE 全撞键，计数与明细都不动（幂等不动点）
        flushHandler.flushViewGroup(productId,
                List.of(viewEvent(productId, viewerA.userId()), viewEvent(productId, viewerB.userId())));
        assertThat(historyCountOf(productId)).isEqualTo(2);
        assertThat(acceleratedCountOf(productId)).as("重复组不产生增量").isEqualTo(2);
    }

    // ---------- 工具 ----------

    private static CountEvent viewEvent(String productId, String userId) {
        return new CountEvent(CountAction.PRODUCT_VIEW, productId, userId);
    }

    /** 直插一条在售商品（对外可见 ⇒ 登录/匿名详情均可用）（uuid-biz-id 票 05） */
    private String insertOnSaleProduct(String title) {
        // uuid-biz-id 票 05 起业务 id 直插 UUID 形态（应用侧发号，自增同值回写已删）
        String productId = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO product (product_id, user_id, title, description, price, "
                        + "trade_location, category, state, create_time, update_time, expire_at) "
                        + "VALUES (?, 999, ?, '浏览计数的夹具', 10.00, '驿站', 'BOOKS', 'ON_SALE', "
                        + "NOW(), NOW(), DATE_ADD(NOW(), INTERVAL 30 DAY))", productId, title);
        return productId;
    }

    private int historyCountOf(String productId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_view_history WHERE product_id = ?", Integer.class, productId);
        return count == null ? 0 : count;
    }

    private int acceleratedCountOf(String productId) {
        List<Integer> rows = jdbcTemplate.queryForList(
                "SELECT view_count FROM product_view_count WHERE product_id = ?", Integer.class, productId);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private int outboxCountOf(String destination) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM mq_outbox WHERE destination = ?", Integer.class, destination);
        return count == null ? 0 : count;
    }
}
