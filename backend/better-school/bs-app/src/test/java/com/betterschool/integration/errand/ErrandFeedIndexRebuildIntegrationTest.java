package com.betterschool.integration.errand;

import com.betterschool.domain.errand.adapter.repository.IErrandRepository;
import com.betterschool.infrastructure.adapter.merge.CountAction;
import com.betterschool.infrastructure.adapter.merge.CountEvent;
import com.betterschool.infrastructure.adapter.merge.ICountFlushHandler;
import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.trigger.job.MarketFeedIndexRebuildJob;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * market-feed-uv 票 06：跑腿 feed 索引全量重建 job——兜底恢复手段。
 * 经 trigger 层 handler（xxl-job 入口）调真实链路，断言只看外部可观察状态：重建后 errand_feed_index 与
 * errand_order / content_review / errand_view_count 严格一致、errand_view_count 恒等于
 * errand_view_history 行数；行常驻口径下「通过前流转点 0 行」「浏览旁路命中 0 行」的缺行与跨表直改的
 * 漂移人工制造后由重建修复；重跑是幂等不动点（照帖子 {@code FeedIndexRebuildIntegrationTest} 先例）。
 */
@DisplayName("跑腿 feed 索引全量重建：严格一致 / 缺行与漂移修复 / 计数校准 / 幂等")
class ErrandFeedIndexRebuildIntegrationTest extends CommunityApiTestBase {

    private static final String PUBLISHER_ID = "publisher-999";

    @Autowired
    private MarketFeedIndexRebuildJob rebuildJob;

    @Autowired
    private IErrandRepository errandRepository;

    @Autowired
    private ICountFlushHandler flushHandler;

    @Test
    @DisplayName("重建后索引行与订单状态/悬赏/feed_time/热度严格一致，并修复缺行与跨表直改的漂移")
    void 重建后索引与源表严格一致且修复缺行漂移() {
        // 真实链路四态：通过（有审批流水）、通过后取消（有行的不可见态）、从未通过审（不该有行）、
        // 存量在厅单（直插 PUBLISHED、无审批流水，feed_time 取 create_time 兜底）
        String approvedId = insertOrder("PENDING_REVIEW", "8.50");
        approve(approvedId);
        String cancelledId = insertOrder("PENDING_REVIEW", "5.00");
        approve(cancelledId);
        assertThat(errandRepository.cancelByPublisher(cancelledId, PUBLISHER_ID)).as("发布方取消").isTrue();
        String draftId = insertOrder("PENDING_PAYMENT", "3.00");
        String legacyId = insertLegacyPublishedOrder("6.00");

        // 缺行边角：索引行被删后浏览——旁路 UPDATE 命中 0 行、窄表照涨；再把窄表直改成 99 制造漂移
        String missingRowId = insertOrder("PENDING_REVIEW", "9.00");
        approve(missingRowId);
        jdbcTemplate.update("DELETE FROM errand_feed_index WHERE errand_order_id = ?", missingRowId);
        flushViews(missingRowId, "u501");
        jdbcTemplate.update("UPDATE errand_view_count SET view_count = 99 WHERE errand_order_id = ?", missingRowId);

        // 过审前被发布方浏览：明细与窄表已记、索引行彼时不存在（0 行 warn 交重建）——重建把这段漏计追平
        String preViewId = insertOrder("PENDING_REVIEW", "7.00");
        flushViews(preViewId, "u502");
        approve(preViewId);
        assertThat(indexViewCountOf(preViewId)).as("过审前浏览旁路命中 0 行，索引热度键未计").isZero();

        // 孤儿计数行：无浏览明细却建了行（懒建行口径下不该存在）
        String orphanId = insertOrder("PENDING_REVIEW", "4.00");
        approve(orphanId);
        jdbcTemplate.update("INSERT INTO errand_view_count (errand_order_id, view_count, create_time, update_time) "
                + "VALUES (?, 7, NOW(), NOW())", orphanId);

        rebuildJob.rebuildErrand(null);

        // 缺行修复：行重生，热度键按明细真值（不是被直改的窄表 99）落回
        Map<String, Object> repaired = feedRow(missingRowId);
        assertThat(repaired).as("缺失的索引行由重建补回").isNotNull();
        assertThat(repaired.get("state")).isEqualTo("PUBLISHED");
        assertThat(((Number) repaired.get("view_count")).intValue())
                .as("窄表直改的漂移校准回明细行数（1 条明细）").isEqualTo(1);
        assertThat(acceleratedCountOf(missingRowId)).as("计数窄表同步校准").isEqualTo(1);

        // 不可见态行保留（行常驻口径）：通过过再取消的订单有行，state 快照 = errand_order.state
        assertThat(feedState(cancelledId)).as("取消订单的索引行保留且不可见化").isEqualTo("CANCELLED");

        // 从未通过审的订单不凭空进索引
        assertThat(feedRow(draftId)).as("从未通过审的订单不进索引").isNull();

        // 存量在厅单：无审批流水，feed_time 兜底 = create_time
        assertThat(queryFeedTime(legacyId)).as("无审批流水时 feed_time 兜底 create_time")
                .isEqualTo(queryCreateTime(legacyId));

        // 过审前浏览的漏计追平；孤儿计数行清掉
        assertThat(indexViewCountOf(preViewId)).as("过审前浏览的漏计追平到明细真值").isEqualTo(1);
        assertThat(countRowExists(orphanId)).as("孤儿计数行清掉（无浏览不建行）").isFalse();

        // 全表扫严格一致：state / price / view_count / feed_time 四列逐行对齐
        assertThat(inconsistentIndexOrderIds()).as("索引行与 errand_order/content_review/窄表逐行一致").isEmpty();
        assertThat(ordersWithoutIndexRow()).as("每行该有的订单都有索引行").isEmpty();
        assertThat(viewCountRowsMismatchingHistory()).as("加速列与明细行数严格一致").isEmpty();
        assertThat(historyOrdersWithoutCountRow()).as("有明细的订单都有计数行").isEmpty();

        // 重跑 = 幂等不动点
        rebuildJob.rebuildErrand(null);
        assertThat(inconsistentIndexOrderIds()).isEmpty();
        assertThat(ordersWithoutIndexRow()).isEmpty();
        assertThat(viewCountRowsMismatchingHistory()).isEmpty();
        assertThat(((Number) feedRow(missingRowId).get("view_count")).intValue()).isEqualTo(1);
        assertThat(feedRow(draftId)).isNull();
    }

    @Test
    @DisplayName("浏览加速列校准为明细行数：漂移刷回、缺行补建、孤儿清掉，索引热度键同步追平")
    void 浏览加速列校准为明细行数并清孤儿补缺行() {
        String viewedId = insertOrder("PENDING_REVIEW", "8.00");
        approve(viewedId);
        flushViews(viewedId, "u601", "u602", "u601"); // 重复浏览：明细只刷 viewed_at，UV 不加数
        String lostRowId = insertOrder("PENDING_REVIEW", "6.00");
        approve(lostRowId);
        flushViews(lostRowId, "u601");
        String orphanId = insertOrder("PENDING_REVIEW", "5.00");
        approve(orphanId); // 无浏览，人为插计数行

        // 漂移：直接改加速列（明细行数的真值是 2）
        jdbcTemplate.update("UPDATE errand_view_count SET view_count = 999 WHERE errand_order_id = ?", viewedId);
        // 缺行：真实浏览有明细，计数行被误删
        jdbcTemplate.update("DELETE FROM errand_view_count WHERE errand_order_id = ?", lostRowId);
        // 孤儿：无浏览却建了计数行（懒建行口径下不该存在）
        jdbcTemplate.update("INSERT INTO errand_view_count (errand_order_id, view_count, create_time, update_time) "
                + "VALUES (?, 7, NOW(), NOW())", orphanId);

        rebuildJob.rebuildErrand(null);

        assertThat(acceleratedCountOf(viewedId)).as("漂移值校准回明细行数").isEqualTo(2);
        assertThat(acceleratedCountOf(lostRowId)).as("缺失的计数行由重建补回").isEqualTo(1);
        assertThat(countRowExists(orphanId)).as("孤儿计数行清掉（无浏览不建行）").isFalse();
        assertThat(indexViewCountOf(viewedId)).as("索引热度键随校准后的窄表追平").isEqualTo(2);
        assertThat(indexViewCountOf(lostRowId)).isEqualTo(1);

        // 全表扫：加速列 ≡ 明细行数投影（一行不多、一行不少、值恒等）
        assertThat(viewCountRowsMismatchingHistory()).as("加速列与明细行数严格一致").isEmpty();
        assertThat(historyOrdersWithoutCountRow()).as("有明细的订单都有计数行").isEmpty();
    }

    // ---------- 夹具与可观察状态 ----------

    /** 直插一行指定态订单（deadline 落未来），返回业务 ID（uuid-biz-id 票 05：UUID 字符串） */
    private String insertOrder(String state, String reward) {
        String orderId = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO errand_order (errand_order_id, publisher_id, type, description, "
                        + "pickup_location, delivery_location, reward, time_limit_hours, state, deadline, "
                        + "create_time, update_time) "
                        + "VALUES (?, ?, 'EXPRESS_PICKUP', '重建夹具', '驿站', '南门', ?, 4, ?, "
                        + "DATE_ADD(NOW(), INTERVAL 1 DAY), NOW(), NOW())",
                orderId, PUBLISHER_ID, reward, state);
        return orderId;
    }

    /** 直插一行存量 PUBLISHED 订单（无审批流水），create_time 拨回两天前供 feed_time 兜底断言 */
    private String insertLegacyPublishedOrder(String reward) {
        String orderId = UUID.randomUUID().toString();
        Timestamp twoDaysAgo = Timestamp.valueOf(LocalDateTime.now().minusDays(2));
        jdbcTemplate.update("INSERT INTO errand_order (errand_order_id, publisher_id, type, description, "
                        + "pickup_location, delivery_location, reward, time_limit_hours, state, deadline, "
                        + "create_time, update_time) "
                        + "VALUES (?, ?, 'EXPRESS_PICKUP', '重建存量单', '驿站', '南门', ?, 4, 'PUBLISHED', "
                        + "DATE_ADD(NOW(), INTERVAL 1 DAY), ?, ?)",
                orderId, PUBLISHER_ID, reward, twoDaysAgo, twoDaysAgo);
        return orderId;
    }

    /**
     * 审核通过（真实仓储链路：状态流转 + 索引行诞生）并补一条 APPROVE 审核流水。
     * 真实链路里「审核记录」与「索引 feed_time」出自同一事件（秒级精度下可能跨秒），夹具按索引落下的
     * 时刻补记录，让「feed_time = MAX(APPROVE) 兜底 create_time」这条投影口径在数据上严格成立
     */
    private void approve(String orderId) {
        assertThat(errandRepository.reviewApproveById(orderId, "auditor-1")).as("待审订单通过").isTrue();
        LocalDateTime feedTime = queryFeedTime(orderId);
        jdbcTemplate.update("INSERT INTO content_review (target_type, target_id, author_id, review_round, "
                        + "action, operator_id, create_time, update_time) "
                        + "VALUES ('ERRAND', ?, ?, 1, 'APPROVE', 1, ?, ?)",
                orderId, PUBLISHER_ID, Timestamp.valueOf(feedTime), Timestamp.valueOf(feedTime));
    }

    /** 一组浏览落库（同步合并，等价于合并窗口过后的三步 + 索引旁路） */
    private void flushViews(String orderId, String... userIds) {
        List<CountEvent> events = Arrays.stream(userIds)
                .map(userId -> new CountEvent(CountAction.ERRAND_VIEW, orderId, userId))
                .toList();
        flushHandler.flushGroup(CountAction.ERRAND_VIEW, orderId, events);
    }

    /** 索引行整行（无行 = null） */
    private Map<String, Object> feedRow(String orderId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT state, feed_time, price, view_count FROM errand_feed_index WHERE errand_order_id = ?", orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String feedState(String orderId) {
        Map<String, Object> row = feedRow(orderId);
        return row == null ? null : (String) row.get("state");
    }

    private LocalDateTime queryFeedTime(String orderId) {
        return ((LocalDateTime) feedRow(orderId).get("feed_time"));
    }

    private LocalDateTime queryCreateTime(String orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT create_time FROM errand_order WHERE errand_order_id = ?", Timestamp.class, orderId)
                .toLocalDateTime();
    }

    private int indexViewCountOf(String orderId) {
        return ((Number) feedRow(orderId).get("view_count")).intValue();
    }

    private int acceleratedCountOf(String orderId) {
        List<Integer> rows = jdbcTemplate.queryForList(
                "SELECT view_count FROM errand_view_count WHERE errand_order_id = ?", Integer.class, orderId);
        return rows.isEmpty() ? 0 : rows.get(0);
    }

    private boolean countRowExists(String orderId) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM errand_view_count WHERE errand_order_id = ?", Long.class, orderId);
        return count != null && count > 0;
    }

    /** 逐行扫一致性破坏者：state ≠ errand_order.state、price ≠ reward、view_count ≠ 窄表（无行 = 0），
     *  或 feed_time ≠ 最近通过（兜底 create_time）的索引行 */
    private List<String> inconsistentIndexOrderIds() {
        return jdbcTemplate.queryForList(
                "SELECT fi.errand_order_id FROM errand_feed_index fi "
                        + "JOIN errand_order e ON e.errand_order_id = fi.errand_order_id "
                        + "LEFT JOIN errand_view_count evc ON evc.errand_order_id = fi.errand_order_id "
                        + "WHERE fi.state <> e.state OR fi.price <> e.reward "
                        + "OR fi.view_count <> COALESCE(evc.view_count, 0) "
                        + "OR fi.feed_time <> COALESCE((SELECT MAX(cr.create_time) FROM content_review cr "
                        + "  WHERE cr.target_type = 'ERRAND' AND cr.target_id = fi.errand_order_id "
                        + "    AND cr.action = 'APPROVE'), e.create_time)", String.class);
    }

    /** 覆盖破坏者：该有行而没有行的订单（在厅 ∪ 通过过审） */
    private List<String> ordersWithoutIndexRow() {
        return jdbcTemplate.queryForList(
                "SELECT e.errand_order_id FROM errand_order e "
                        + "LEFT JOIN errand_feed_index fi ON fi.errand_order_id = e.errand_order_id "
                        + "WHERE fi.errand_order_id IS NULL AND (e.state = 'PUBLISHED' "
                        + "  OR EXISTS (SELECT 1 FROM content_review cr WHERE cr.target_type = 'ERRAND' "
                        + "       AND cr.target_id = e.errand_order_id AND cr.action = 'APPROVE'))", String.class);
    }

    /** 投影破坏者：view_count ≠ 明细行数的加速列行 */
    private List<String> viewCountRowsMismatchingHistory() {
        return jdbcTemplate.queryForList(
                "SELECT vc.errand_order_id FROM errand_view_count vc "
                        + "LEFT JOIN (SELECT errand_order_id, COUNT(*) AS cnt FROM errand_view_history "
                        + "  GROUP BY errand_order_id) h ON h.errand_order_id = vc.errand_order_id "
                        + "WHERE vc.view_count <> COALESCE(h.cnt, 0)", String.class);
    }

    /** 覆盖破坏者：有浏览明细却没有计数行的订单 */
    private List<String> historyOrdersWithoutCountRow() {
        return jdbcTemplate.queryForList(
                "SELECT DISTINCT h.errand_order_id FROM errand_view_history h "
                        + "LEFT JOIN errand_view_count vc ON vc.errand_order_id = h.errand_order_id "
                        + "WHERE vc.errand_order_id IS NULL", String.class);
    }
}
