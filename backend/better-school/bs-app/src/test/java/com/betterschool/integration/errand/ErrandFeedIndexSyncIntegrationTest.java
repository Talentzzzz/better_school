package com.betterschool.integration.errand;

import com.betterschool.domain.errand.adapter.repository.IErrandRepository;
import com.betterschool.infrastructure.adapter.merge.CountAction;
import com.betterschool.infrastructure.adapter.merge.CountEvent;
import com.betterschool.infrastructure.adapter.merge.ICountFlushHandler;
import com.betterschool.infrastructure.adapter.merge.LikeState;
import com.betterschool.integration.support.CommunityApiTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跑腿 feed 索引的同事务直写触发点（market-feed-uv 票 03）：驻留口径 = 行常驻 + state 快照——
 * 行在审核通过事务诞生（唯一诞生点），此后 13 态流转点旁带快照同步，行永不删除，
 * 大厅可见 = PUBLISHED（读路径按快照挑状态）；浏览合并事务的 view_count ±N 旁路。
 *
 * <p>断言只看外部可观察状态：errand_feed_index 行的 state / feed_time / price / view_count。
 * 流转走真实仓储链路（真实事务、真实 SQL）；审核通过前的流转点（行不存在）0 行静默一并钉死。
 */
@DisplayName("跑腿 feed 索引同步：通过诞生 / 行常驻状态快照逐点 / 浏览旁路 ±N")
class ErrandFeedIndexSyncIntegrationTest extends CommunityApiTestBase {

    private static final String PUBLISHER_ID = "publisher-999";
    private static final String TAKER_ID = "taker-998";

    /** 夹具业务 ID 序列：同一用例内多次直插不撞 uk_errand_order_id */
    private static final AtomicLong SEQ = new AtomicLong();

    @Autowired
    private IErrandRepository errandRepository;

    @Autowired
    private ICountFlushHandler flushHandler;

    @Test
    @DisplayName("审核通过行诞生（reward 快照随行）；驳回不诞生（REJECTED 只可从待审可达）")
    void 审核通过诞生索引行_驳回不诞生() {
        long approvedId = insertOrder("PENDING_REVIEW", "8.50");

        LocalDateTime approveStartedAt = LocalDateTime.now();
        assertThat(errandRepository.reviewApproveById(approvedId, "auditor-1")).as("待审订单通过").isTrue();

        Map<String, Object> row = feedRow(approvedId);
        assertThat(row).as("通过后索引行诞生（唯一诞生点）").isNotNull();
        assertThat(row.get("state")).isEqualTo("PUBLISHED");
        assertThat(((BigDecimal) row.get("price"))).as("price 快照 = 悬赏（彼时已支付冻结，非空成立）")
                .isEqualByComparingTo("8.50");
        assertThat(((Number) row.get("view_count")).intValue()).as("诞生时热度键为 0").isZero();
        assertThat(((LocalDateTime) row.get("feed_time")))
                .as("feed_time = 进入大厅时刻（行诞生落第一次，跑腿无重提再过审）")
                .isBetween(approveStartedAt.minusSeconds(5), LocalDateTime.now().plusSeconds(5));

        long rejectedId = insertOrder("PENDING_REVIEW", "8.50");
        assertThat(errandRepository.reviewRejectById(rejectedId, "auditor-1", "配图模糊")).isTrue();
        assertThat(feedRow(rejectedId)).as("驳回不产索引行").isNull();
    }

    @Test
    @DisplayName("行常驻状态快照逐点镜像：锁单轮转回到 PUBLISHED 翻回可见，终态不可见，行不消失")
    void 行常驻状态快照逐点镜像() {
        // 锁单全链：抢单 → 放弃（LOCKED 回 PUBLISHED）→ 重抢 → 超时释放（回 PUBLISHED）→
        // 重抢 → 确认接单 → 取货 → 送达 → 发布方确认完成
        long chainId = insertOrder("PUBLISHED", "5.00");
        assertThat(errandRepository.grab(chainId, TAKER_ID)).isTrue();
        assertThat(feedState(chainId)).as("抢单 → LOCKED（行常驻不可见）").isEqualTo("LOCKED");
        assertThat(errandRepository.abandonLockedById(chainId, TAKER_ID)).isTrue();
        assertThat(feedState(chainId)).as("放弃 → 翻回 PUBLISHED（重新进大厅）").isEqualTo("PUBLISHED");
        assertThat(errandRepository.grab(chainId, TAKER_ID)).isTrue();
        assertThat(errandRepository.releaseLockedByTimeout(chainId, errandRepository.lockSeqOf(chainId))).isTrue();
        assertThat(feedState(chainId)).as("锁单超时释放 → 翻回 PUBLISHED").isEqualTo("PUBLISHED");
        assertThat(errandRepository.grab(chainId, TAKER_ID)).isTrue();
        assertThat(errandRepository.acceptLockedById(chainId, TAKER_ID)).isTrue();
        assertThat(errandRepository.pickupById(chainId, TAKER_ID)).isTrue();
        assertThat(feedState(chainId)).as("取货 → PICKED_UP").isEqualTo("PICKED_UP");
        assertThat(errandRepository.deliverById(chainId, TAKER_ID)).isTrue();
        assertThat(feedState(chainId)).as("送达 → DELIVERED").isEqualTo("DELIVERED");
        assertThat(errandRepository.confirmById(chainId, PUBLISHER_ID)).isTrue();
        assertThat(feedState(chainId)).as("发布方确认 → COMPLETED（终态不可见，行常驻）").isEqualTo("COMPLETED");

        // 接单方取消：CONFIRMED → 翻回 PUBLISHED
        long takerCancelId = insertOrder("PUBLISHED", "5.00");
        assertThat(errandRepository.grab(takerCancelId, TAKER_ID)).isTrue();
        assertThat(errandRepository.acceptLockedById(takerCancelId, TAKER_ID)).isTrue();
        assertThat(errandRepository.cancelByTaker(takerCancelId, TAKER_ID)).isTrue();
        assertThat(feedState(takerCancelId)).as("接单方取消 → 翻回 PUBLISHED").isEqualTo("PUBLISHED");

        // 发布方取消（大厅可见期）：→ CANCELLED
        long publisherCancelId = insertOrder("PUBLISHED", "5.00");
        assertThat(errandRepository.cancelByPublisher(publisherCancelId, PUBLISHER_ID)).isTrue();
        assertThat(feedState(publisherCancelId)).as("发布方取消 → CANCELLED").isEqualTo("CANCELLED");

        // 争议结案（取消向 / 完成向）与超时取消
        long disputedId = insertOrder("PUBLISHED", "5.00");
        assertThat(errandRepository.grab(disputedId, TAKER_ID)).isTrue();
        assertThat(errandRepository.acceptLockedById(disputedId, TAKER_ID)).isTrue();
        assertThat(errandRepository.disputeById(disputedId, PUBLISHER_ID, "PUBLISHER")).isTrue();
        assertThat(feedState(disputedId)).as("争议 → DISPUTED").isEqualTo("DISPUTED");
        assertThat(errandRepository.arbitrateCancelById(disputedId, "auditor-1")).isTrue();
        assertThat(feedState(disputedId)).as("仲裁取消结案 → CANCELLED").isEqualTo("CANCELLED");

        long arbitratedDoneId = insertOrder("PUBLISHED", "5.00");
        assertThat(errandRepository.grab(arbitratedDoneId, TAKER_ID)).isTrue();
        assertThat(errandRepository.acceptLockedById(arbitratedDoneId, TAKER_ID)).isTrue();
        assertThat(errandRepository.pickupById(arbitratedDoneId, TAKER_ID)).isTrue();
        assertThat(errandRepository.disputeById(arbitratedDoneId, TAKER_ID, "TAKER")).isTrue();
        assertThat(errandRepository.arbitrateCompleteById(arbitratedDoneId, "auditor-1")).isTrue();
        assertThat(feedState(arbitratedDoneId)).as("仲裁完成结案 → COMPLETED").isEqualTo("COMPLETED");

        long timedOutId = insertOrder("PUBLISHED", "5.00");
        jdbcTemplate.update("UPDATE errand_order SET deadline = DATE_SUB(NOW(), INTERVAL 1 HOUR) "
                + "WHERE errand_order_id = ?", timedOutId);
        assertThat(errandRepository.markTimeout(timedOutId)).isTrue();
        assertThat(feedState(timedOutId)).as("订单超时 → CANCELLED").isEqualTo("CANCELLED");

        // 送达后 24h 自动完成
        long autoId = insertOrder("PUBLISHED", "5.00");
        assertThat(errandRepository.grab(autoId, TAKER_ID)).isTrue();
        assertThat(errandRepository.acceptLockedById(autoId, TAKER_ID)).isTrue();
        assertThat(errandRepository.pickupById(autoId, TAKER_ID)).isTrue();
        assertThat(errandRepository.deliverById(autoId, TAKER_ID)).isTrue();
        assertThat(errandRepository.autoCompleteById(autoId)).isTrue();
        assertThat(feedState(autoId)).as("送达超时自动完成 → COMPLETED").isEqualTo("COMPLETED");

        // 审核通过前的取消（行不存在）：0 行静默，不凭空产行
        long pendingCancelId = insertOrder("PENDING_REVIEW", "5.00");
        assertThat(errandRepository.cancelByPublisher(pendingCancelId, PUBLISHER_ID)).isTrue();
        assertThat(feedRow(pendingCancelId)).as("过审前取消不产索引行").isNull();
    }

    @Test
    @DisplayName("浏览合并事务旁路 ±N 推进热度键；0 行不补行（未过审订单的浏览只落明细）")
    void 浏览旁路推进热度键_未过审不补行() {
        long publishedId = insertOrder("PUBLISHED", "5.00");

        List<LikeState> results = flushHandler.flushGroup(CountAction.ERRAND_VIEW, String.valueOf(publishedId),
                List.of(viewEvent(publishedId, "u201"), viewEvent(publishedId, "u201"), viewEvent(publishedId, "u202")));
        assertThat(results).hasSize(3).containsOnly(LikeState.APPLIED);
        assertThat(indexViewCount(publishedId)).as("索引热度键按新增 UV 数 +N").isEqualTo(2);
        assertThat(acceleratedCountOf(publishedId)).as("窄表真源同步推进").isEqualTo(2);

        // 全重复组：幂等不动点
        flushHandler.flushGroup(CountAction.ERRAND_VIEW, String.valueOf(publishedId), List.of(viewEvent(publishedId, "u201")));
        assertThat(indexViewCount(publishedId)).as("重复浏览不计入").isEqualTo(2);

        // 未过审订单（行不存在）：浏览旁路 0 行不补行，窄表照常推进（交重建 job 兜底）
        long pendingId = insertOrder("PENDING_REVIEW", "5.00");
        flushHandler.flushGroup(CountAction.ERRAND_VIEW, String.valueOf(pendingId), List.of(viewEvent(pendingId, "u203")));
        assertThat(feedRow(pendingId)).as("索引行缺失不被凭空补行").isNull();
        assertThat(acceleratedCountOf(pendingId)).as("窄表真源不受索引缺失影响").isEqualTo(1);
    }

    // ---------- 夹具与可观察状态 ----------

    private static CountEvent viewEvent(long orderId, String userId) {
        return new CountEvent(CountAction.ERRAND_VIEW, String.valueOf(orderId), userId);
    }

    /** 直插一行指定态订单（deadline 落未来，抢单时限校验可过），返回业务 ID。
     *  PUBLISHED 视作「此前已通过审核」的存量形态，同步直插索引行（行常驻口径下的行集起点） */
    private long insertOrder(String state, String reward) {
        long orderId = 930_000L + SEQ.incrementAndGet();
        jdbcTemplate.update("INSERT INTO errand_order (errand_order_id, publisher_id, type, description, "
                        + "pickup_location, delivery_location, reward, time_limit_hours, state, deadline, "
                        + "create_time, update_time) "
                        + "VALUES (?, ?, 'EXPRESS_PICKUP', '索引同步夹具', '驿站', '南门', ?, 4, ?, "
                        + "DATE_ADD(NOW(), INTERVAL 1 DAY), NOW(), NOW())",
                orderId, PUBLISHER_ID, reward, state);
        if ("PUBLISHED".equals(state)) {
            jdbcTemplate.update("INSERT INTO errand_feed_index (errand_order_id, state, feed_time, price, "
                    + "view_count, update_time) VALUES (?, 'PUBLISHED', NOW(), ?, 0, NOW())", orderId, reward);
        }
        return orderId;
    }

    /** 索引行整行（无行 = null） */
    private Map<String, Object> feedRow(long orderId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT state, feed_time, price, view_count FROM errand_feed_index WHERE errand_order_id = ?", orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String feedState(long orderId) {
        Map<String, Object> row = feedRow(orderId);
        return row == null ? null : (String) row.get("state");
    }

    private int indexViewCount(long orderId) {
        return ((Number) feedRow(orderId).get("view_count")).intValue();
    }

    private int acceleratedCountOf(long orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT view_count FROM errand_view_count WHERE errand_order_id = ?", Integer.class, orderId);
        return count == null ? 0 : count;
    }
}
