package com.betterschool.integration.trade;

import com.betterschool.domain.trade.adapter.repository.ITradeRepository;
import com.betterschool.infrastructure.merge.count.CountAction;
import com.betterschool.infrastructure.merge.count.CountEvent;
import com.betterschool.infrastructure.merge.count.ICountFlushHandler;
import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.trigger.job.MarketFeedIndexRebuildJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * market-feed-uv 票 06：商品 feed 索引全量重建 job——兜底恢复手段。
 * 经 trigger 层 handler（xxl-job 入口）调真实链路，断言只看外部可观察状态：重建后 product_feed_index 与
 * product / product_view_count 严格一致（重灌行集 = 仅 ON_SALE，feed_time = product.create_time）、
 * product_view_count 恒等于 product_view_history 行数；同步写路径的两类已知损伤（浏览旁路命中 0 行的缺行、
 * 跨表被绕过直改的漂移）人工制造后由重建修复；重跑是幂等不动点（照帖子 {@code FeedIndexRebuildIntegrationTest} 先例）。
 */
@DisplayName("商品 feed 索引全量重建：严格一致 / 缺行与漂移修复 / 计数校准 / 幂等")
class ProductFeedIndexRebuildIntegrationTest extends CommunityApiTestBase {

    @Autowired
    private MarketFeedIndexRebuildJob rebuildJob;

    @Autowired
    private ITradeRepository tradeRepository;

    @Autowired
    private ICountFlushHandler flushHandler;

    @Test
    @DisplayName("重建后索引行与商品状态/价格/feed_time/热度严格一致，并修复缺行与跨表直改的漂移")
    void 重建后索引与源表严格一致且修复缺行漂移() {
        // 真实链路四态：提交上架（有索引行）、上架后软删（重灌不进索引的不可见态）、从未提交（草稿，不该有行）、
        // 存量在售（直插 ON_SALE、无索引行，feed_time 取 create_time）
        String onSaleId = insertProduct("DRAFT", "10.00");
        publish(onSaleId);
        String deletedId = insertProduct("DRAFT", "20.00");
        publish(deletedId);
        tradeRepository.deleteById(deletedId); // 上架后软删：行快照翻 DELETED，重灌后不进索引
        String draftId = insertProduct("DRAFT", "30.00");
        String legacyId = insertLegacyOnSaleProduct("40.00");

        // 缺行边角：索引行被删后浏览——旁路 UPDATE 命中 0 行、窄表照涨；再把窄表直改成 99 制造漂移
        String missingRowId = insertProduct("DRAFT", "50.00");
        publish(missingRowId);
        jdbcTemplate.update("DELETE FROM product_feed_index WHERE product_id = ?", missingRowId);
        flushViews(missingRowId, "u301");
        jdbcTemplate.update("UPDATE product_view_count SET view_count = 99 WHERE product_id = ?", missingRowId);

        // 上架前被浏览：明细与窄表已记、索引行彼时不存在（0 行 warn 交重建）——重建把这段漏计追平
        String preViewId = insertProduct("DRAFT", "60.00");
        flushViews(preViewId, "u302");
        publish(preViewId);
        assertThat(indexViewCountOf(preViewId)).as("上架前浏览旁路命中 0 行，索引热度键未计").isZero();

        // 孤儿计数行：无浏览明细却建了行（懒建行口径下不该存在）
        String orphanId = insertProduct("DRAFT", "70.00");
        publish(orphanId);
        jdbcTemplate.update("INSERT INTO product_view_count (product_id, view_count, create_time, update_time) "
                + "VALUES (?, 7, NOW(), NOW())", orphanId);

        rebuildJob.rebuildProduct(null);

        // 缺行修复：行重生，热度键按明细真值（不是被直改的窄表 99）落回
        Map<String, Object> repaired = feedRow(missingRowId);
        assertThat(repaired).as("缺失的索引行由重建补回").isNotNull();
        assertThat(repaired.get("state")).isEqualTo("ON_SALE");
        assertThat(((Number) repaired.get("view_count")).intValue())
                .as("窄表直改的漂移校准回明细行数（1 条明细）").isEqualTo(1);
        assertThat(acceleratedCountOf(missingRowId)).as("计数窄表同步校准").isEqualTo(1);

        // 重灌行集 = 仅 ON_SALE：非在售商品不凭空进索引（软删/草稿均无行）
        assertThat(feedRow(deletedId)).as("已删商品重灌后不进索引").isNull();
        assertThat(feedRow(draftId)).as("从未提交的草稿商品不进索引").isNull();

        // 存量在售商品：直插无索引行，重建补行，feed_time = create_time
        assertThat(queryFeedTime(legacyId)).as("重灌口径 feed_time 即 create_time")
                .isEqualTo(queryCreateTime(legacyId));

        // 上架前浏览的漏计追平；孤儿计数行清掉
        assertThat(indexViewCountOf(preViewId)).as("上架前浏览的漏计追平到明细真值").isEqualTo(1);
        assertThat(countRowExists(orphanId)).as("孤儿计数行清掉（无浏览不建行）").isFalse();

        // 全表扫严格一致：state / price / view_count / feed_time 四列逐行对齐
        assertThat(inconsistentIndexProductIds()).as("索引行与 product/窄表逐行一致").isEmpty();
        assertThat(productsWithoutIndexRow()).as("每行该有的商品都有索引行").isEmpty();
        assertThat(viewCountRowsMismatchingHistory()).as("加速列与明细行数严格一致").isEmpty();
        assertThat(historyProductsWithoutCountRow()).as("有明细的商品都有计数行").isEmpty();

        // 重跑 = 幂等不动点
        rebuildJob.rebuildProduct(null);
        assertThat(inconsistentIndexProductIds()).isEmpty();
        assertThat(productsWithoutIndexRow()).isEmpty();
        assertThat(viewCountRowsMismatchingHistory()).isEmpty();
        assertThat(((Number) feedRow(missingRowId).get("view_count")).intValue()).isEqualTo(1);
        assertThat(feedRow(draftId)).isNull();
    }

    @Test
    @DisplayName("浏览加速列校准为明细行数：漂移刷回、缺行补建、孤儿清掉，索引热度键同步追平")
    void 浏览加速列校准为明细行数并清孤儿补缺行() {
        String viewedId = insertProduct("DRAFT", "10.00");
        publish(viewedId);
        flushViews(viewedId, "u401", "u402", "u401"); // 重复浏览：明细只刷 viewed_at，UV 不加数
        String lostRowId = insertProduct("DRAFT", "20.00");
        publish(lostRowId);
        flushViews(lostRowId, "u401");
        String orphanId = insertProduct("DRAFT", "30.00");
        publish(orphanId); // 无浏览，人为插计数行

        // 漂移：直接改加速列（明细行数的真值是 2）
        jdbcTemplate.update("UPDATE product_view_count SET view_count = 999 WHERE product_id = ?", viewedId);
        // 缺行：真实浏览有明细，计数行被误删
        jdbcTemplate.update("DELETE FROM product_view_count WHERE product_id = ?", lostRowId);
        // 孤儿：无浏览却建了计数行（懒建行口径下不该存在）
        jdbcTemplate.update("INSERT INTO product_view_count (product_id, view_count, create_time, update_time) "
                + "VALUES (?, 7, NOW(), NOW())", orphanId);

        rebuildJob.rebuildProduct(null);

        assertThat(acceleratedCountOf(viewedId)).as("漂移值校准回明细行数").isEqualTo(2);
        assertThat(acceleratedCountOf(lostRowId)).as("缺失的计数行由重建补回").isEqualTo(1);
        assertThat(countRowExists(orphanId)).as("孤儿计数行清掉（无浏览不建行）").isFalse();
        assertThat(indexViewCountOf(viewedId)).as("索引热度键随校准后的窄表追平").isEqualTo(2);
        assertThat(indexViewCountOf(lostRowId)).isEqualTo(1);

        // 全表扫：两域加速列 ≡ 明细行数投影（一行不多、一行不少、值恒等）
        assertThat(viewCountRowsMismatchingHistory()).as("加速列与明细行数严格一致").isEmpty();
        assertThat(historyProductsWithoutCountRow()).as("有明细的商品都有计数行").isEmpty();
    }

    // ---------- 夹具与可观察状态 ----------

    /** 直插一行指定态商品（存量形态；uuid-biz-id 票 05 起业务 id 直插 UUID，应用侧发号），返回业务 ID */
    private String insertProduct(String state, String price) {
        String productId = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO product (product_id, user_id, title, description, price, "
                        + "trade_location, category, state, create_time, update_time, expire_at) "
                        + "VALUES (?, 999, '重建夹具', '夹具描述', ?, '驿站', 'BOOKS', ?, NOW(), NOW(), "
                        + "DATE_ADD(NOW(), INTERVAL 30 DAY))",
                productId, price, state);
        return productId;
    }

    /** 直插一行存量 ON_SALE 商品（绕过发布链路：无索引行），create_time 拨回两天前供 feed_time 断言 */
    private String insertLegacyOnSaleProduct(String price) {
        String productId = java.util.UUID.randomUUID().toString();
        Timestamp twoDaysAgo = Timestamp.valueOf(LocalDateTime.now().minusDays(2));
        jdbcTemplate.update("INSERT INTO product (product_id, user_id, title, description, price, "
                        + "trade_location, category, state, create_time, update_time, expire_at) "
                        + "VALUES (?, 999, '重建存量商品', '夹具描述', ?, '驿站', 'BOOKS', 'ON_SALE', ?, ?, "
                        + "DATE_ADD(NOW(), INTERVAL 30 DAY))",
                productId, price, twoDaysAgo, twoDaysAgo);
        return productId;
    }

    /** 草稿提交上架（真实仓储链路：DRAFT→ON_SALE + 索引行诞生 + 搜索同步消息） */
    private void publish(String productId) {
        assertThat(tradeRepository.submitDraft(productId)).as("草稿提交即上架").isTrue();
    }

    /** 一组浏览落库（同步合并，等价于合并窗口过后的三步 + 索引旁路） */
    private void flushViews(String productId, String... userIds) {
        List<CountEvent> events = java.util.Arrays.stream(userIds)
                .map(userId -> new CountEvent(CountAction.PRODUCT_VIEW, productId, userId))
                .toList();
        flushHandler.flushGroup(CountAction.PRODUCT_VIEW, productId, events);
    }

    /** 索引行整行（无行 = null） */
    private Map<String, Object> feedRow(String productId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT state, feed_time, price, view_count FROM product_feed_index WHERE product_id = ?", productId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private LocalDateTime queryFeedTime(String productId) {
        return ((LocalDateTime) feedRow(productId).get("feed_time"));
    }

    private LocalDateTime queryCreateTime(String productId) {
        return jdbcTemplate.queryForObject(
                "SELECT create_time FROM product WHERE product_id = ?", Timestamp.class, productId).toLocalDateTime();
    }

    private int indexViewCountOf(String productId) {
        return ((Number) feedRow(productId).get("view_count")).intValue();
    }

    private int acceleratedCountOf(String productId) {
        List<Integer> rows = jdbcTemplate.queryForList(
                "SELECT view_count FROM product_view_count WHERE product_id = ?", Integer.class, productId);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private boolean countRowExists(String productId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product_view_count WHERE product_id = ?", Long.class, productId);
        return count != null && count > 0;
    }

    /** 逐行扫一致性破坏者：state ≠ product.state、price ≠ product.price、view_count ≠ 窄表（无行 = 0），
     *  或 feed_time ≠ create_time 的索引行 */
    private List<String> inconsistentIndexProductIds() {
        return jdbcTemplate.queryForList(
                "SELECT fi.product_id FROM product_feed_index fi "
                        + "JOIN product p ON p.product_id = fi.product_id "
                        + "LEFT JOIN product_view_count pvc ON pvc.product_id = fi.product_id "
                        + "WHERE fi.state <> p.state OR fi.price <> p.price "
                        + "OR fi.view_count <> COALESCE(pvc.view_count, 0) "
                        + "OR fi.feed_time <> p.create_time", String.class);
    }

    /** 覆盖破坏者：该有行而没有行的商品（在售商品，重灌行集 = 仅 ON_SALE） */
    private List<String> productsWithoutIndexRow() {
        return jdbcTemplate.queryForList(
                "SELECT p.product_id FROM product p "
                        + "LEFT JOIN product_feed_index fi ON fi.product_id = p.product_id "
                        + "WHERE fi.product_id IS NULL AND p.state = 'ON_SALE'", String.class);
    }

    /** 投影破坏者：view_count ≠ 明细行数的加速列行 */
    private List<String> viewCountRowsMismatchingHistory() {
        return jdbcTemplate.queryForList(
                "SELECT vc.product_id FROM product_view_count vc "
                        + "LEFT JOIN (SELECT product_id, COUNT(*) AS cnt FROM product_view_history GROUP BY product_id) h "
                        + "  ON h.product_id = vc.product_id "
                        + "WHERE vc.view_count <> COALESCE(h.cnt, 0)", String.class);
    }

    /** 覆盖破坏者：有浏览明细却没有计数行的商品 */
    private List<String> historyProductsWithoutCountRow() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT h.product_id FROM product_view_history h "
                        + "LEFT JOIN product_view_count vc ON vc.product_id = h.product_id "
                        + "WHERE vc.product_id IS NULL", String.class);
    }
}
