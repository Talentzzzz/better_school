package com.betterschool.integration.errand;

import com.betterschool.domain.timeout.model.entity.TimeoutTaskEntity;
import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.integration.support.TimeoutCallbackFixture;
import com.betterschool.types.common.Constants;
import com.betterschool.usecase.timeout.TimeoutTaskCallbackDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 锁单超时接入超时中心（票 04）集成测试：接单方抢到单后 5 分钟不确认，订单自动回落 {@code PUBLISHED} 让他人可抢
 * ——由超时中心到点回调完成（中心内嵌化后回调就是进程内分发，票 05 删掉了原 Dubbo 回调边）。
 *
 * <p>两个入口按真实形状驱动：注册边看 {@code timeout_task}（复合 bizId = {@code {orderId}:{lockSeq}}，
 * 抢单事务内直插、提交即可见——内嵌超时中心票 03）；回调边直接打进程内分发器
 * {@code TimeoutTaskCallbackDispatcher}（扫描 case 到点后调的就是它）。
 * 断言只看外部可观察状态。
 */
@DisplayName("票 04：锁单超时接入超时中心（复合 bizId + 轮次守卫释放）")
class ErrandLockTimeoutIntegrationTest extends CommunityApiTestBase {

    @Autowired
    private TimeoutTaskCallbackDispatcher timeoutTaskCallback;

    // ---------- 注册：复合 bizId 带轮次 + 从锁单时刻起算的 5 分钟窗口 ----------

    @Test
    void 抢单同事务注册锁单超时_复合bizId带轮次且窗口从锁单时刻起算() {
        Session author = registerAndLogin("13800006001", "发单用户1");
        Session taker = registerAndLogin("13800006002", "接单用户1");
        String orderId = publishableOrder(author);

        grab(orderId, taker);

        // 注册行：taskType 是独立键，bizId 是复合键（不是裸 orderId），抢单事务提交后立即可见
        Map<String, Object> row = registerRowOf(orderId, 1);
        assertThat(row.get("status")).as("注册即 WAITING（无 outbox 中间态）").isEqualTo("WAITING");
        assertThat((LocalDateTime) row.get("action_time")).as("计时起点 = 锁单时刻（不是创建时刻），窗口 5min")
                .isBetween(LocalDateTime.now().plusMinutes(4), LocalDateTime.now().plusMinutes(6));

        // 锁单超时与支付超时是两条独立注册键，互不覆盖
        assertThat(actionTimeOf(Constants.ERRAND_PAY_TIMEOUT, orderId))
                .as("同一订单的支付超时注册键仍是裸 orderId").isNotNull();
    }

    @Test
    void 放弃后重抢新一轮拿到完整五分钟窗口_陈旧轮次回调零副作用() {
        Session author = registerAndLogin("13800006003", "发单用户2");
        Session takerA = registerAndLogin("13800006004", "接单用户2");
        Session takerB = registerAndLogin("13800006005", "接单用户3");
        String orderId = publishableOrder(author);

        grab(orderId, takerA);
        LocalDateTime roundOneActionTime = actionTimeOf(Constants.ERRAND_LOCK_TIMEOUT, orderId + ":1");
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/abandon", null, takerA), "放弃接单");

        grab(orderId, takerB);

        // 复合 bizId 的意义：新一轮是**新键插入**，携带自己的 action_time（完整 5 分钟），
        // 而不是被旧键的在途任务幂等复位成 t0+5（那样确认窗口会被压缩甚至卡死）
        assertThat(registerRowCount(orderId)).as("两次锁单两行独立注册").isEqualTo(2);
        Map<String, Object> roundTwo = registerRowOf(orderId, 2);
        assertThat((LocalDateTime) roundTwo.get("action_time"))
                .as("新一轮窗口从本次锁单时刻起算（完整 5min，不被旧轮次压缩）")
                .isBetween(LocalDateTime.now().plusMinutes(4), LocalDateTime.now().plusMinutes(6))
                .isAfterOrEqualTo(roundOneActionTime);

        // 陈旧轮次（旧 bizId）回调：单仍是新一轮 LOCKED，但 lock_seq 已被守卫挡住 → 零副作用
        assertCallbackSuccess(callback(lockTask(orderId, 1)));

        assertThat(statusOf(orderId)).as("陈旧回调不得释放新一轮的锁").isEqualTo("LOCKED");
        assertThat(takerIdOf(orderId)).isEqualTo(takerB.userId());
        assertThat(lockSeqOf(orderId)).isEqualTo(2);
        assertThat(lockTimeoutEventCount(orderId)).as("未命中轮次守卫不落事件").isZero();
        assertThat(notificationCountFor(takerB.userId(), orderId)).as("未释放不发通知").isZero();

        // 新轮次的回调才生效
        assertCallbackSuccess(callback(lockTask(orderId, 2)));

        assertThat(statusOf(orderId)).isEqualTo("PUBLISHED");
        assertThat(takerIdOf(orderId)).as("释放清空接单人").isNull();
        assertThat(lockSeqOf(orderId)).as("释放不改 lock_seq（轮次只由抢单推进）").isEqualTo(2);
    }

    // ---------- 释放：回落 PUBLISHED、他人可抢、给被释放的接单方发通知 ----------

    @Test
    void 到点回调回落PUBLISHED且他人可再抢_并给被释放接单方发通知() {
        Session author = registerAndLogin("13800006006", "发单用户3");
        Session taker = registerAndLogin("13800006007", "接单用户4");
        Session other = registerAndLogin("13800006008", "接单用户5");
        String orderId = publishableOrder(author);
        grab(orderId, taker);

        assertCallbackSuccess(callback(lockTask(orderId, 1)));

        assertThat(statusOf(orderId)).as("到点自动回落").isEqualTo("PUBLISHED");
        assertThat(takerIdOf(orderId)).isNull();
        assertThat(lockSeqOf(orderId)).isEqualTo(1);
        // 事件：LOCK_TIMEOUT，LOCKED→PUBLISHED，actor SYSTEM（actor_id 允许 NULL）
        assertThat(eventRowOf(orderId, "LOCK_TIMEOUT"))
                .containsEntry("from_state", "LOCKED")
                .containsEntry("to_state", "PUBLISHED")
                .containsEntry("actor_role", "SYSTEM")
                .containsEntry("actor_id", null);
        // 锁单期还没开工（抢 ≠ 开工）：释放不扣信用分
        assertThat(creditScoreOf(taker)).isEqualTo(100);
        assertThat(creditDetailCount(taker, "ERRAND_TIMEOUT")).as("锁单超时不落在扣分路径").isZero();
        // 搜索侧零改动：释放回已发布，已发布单的搜索文档本就维持 PUBLISHED 态（不落新同步消息）
        assertThat(errandSearchSyncMessageCount()).isZero();

        // 释放后立刻回到公开列表、他人可抢先到先得
        assertThat(publicOrderIds()).as("回落 PUBLISHED 后重回公开列表").contains(orderId);
        grab(orderId, other);
        assertThat(takerIdOf(orderId)).isEqualTo(other.userId());
        assertThat(lockSeqOf(orderId)).as("二次锁单轮次 +1").isEqualTo(2);

        // 结果通知发给**被释放的接单方**（taker_id 已清空，通知靠释放前读出的 id）
        Map<String, Object> notice = notificationOf(taker.userId(), orderId, 1);
        assertThat(notice.get("scope")).isEqualTo("MARKET_ERRAND");
        assertThat(notice.get("type")).isEqualTo("ORDER_EVENT");
        assertThat(notice.get("ref_type")).isEqualTo("ERRAND");
        assertThat((String) notice.get("ref_id")).as("通知 ref_id 直带 UUID 字符串（uuid-biz-id 票 05）").isEqualTo(orderId);
        assertThat(notificationCountFor(other.userId(), orderId)).as("新接单人未被释放，不该收到通知").isZero();
    }

    @Test
    void 多轮释放各发各的结果通知_同轮重复回调不重复发() {
        Session author = registerAndLogin("13800006009", "发单用户4");
        Session taker = registerAndLogin("13800006010", "接单用户6");
        String orderId = publishableOrder(author);

        grab(orderId, taker);
        assertCallbackSuccess(callback(lockTask(orderId, 1)));
        assertThat(notificationCountFor(taker.userId(), orderId)).isEqualTo(1);

        // 同轮重投（中心 at-least-once）：未命中状态机 = 幂等成功，且不重复发通知
        assertCallbackSuccess(callback(lockTask(orderId, 1)));
        assertThat(notificationCountFor(taker.userId(), orderId)).as("同轮重投不重复发").isEqualTo(1);

        // 同一人再抢（新轮次）→ 释放 → 再发一条：dedupKey 含 lockSeq，多轮各发各的
        grab(orderId, taker);
        assertCallbackSuccess(callback(lockTask(orderId, 2)));

        assertThat(notificationCountFor(taker.userId(), orderId)).as("第二轮释放再发一条").isEqualTo(2);
        assertThat(notificationDedupKeysFor(taker.userId(), orderId))
                .containsExactlyInAnyOrder(lockDedupKey(orderId, 1), lockDedupKey(orderId, 2));
    }

    // ---------- 幂等：已确认 / 已取消 / 不存在 的单收到回调零副作用 ----------

    @Test
    void 已确认的单收到回调零副作用() {
        Session author = registerAndLogin("13800006011", "发单用户5");
        Session taker = registerAndLogin("13800006012", "接单用户7");
        String orderId = publishableOrder(author);
        grab(orderId, taker);
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/accept", null, taker), "确认接单");

        assertCallbackSuccess(callback(lockTask(orderId, 1)));

        assertThat(statusOf(orderId)).as("已确认离开 LOCKED，回调打不中").isEqualTo("CONFIRMED");
        assertThat(takerIdOf(orderId)).isEqualTo(taker.userId());
        assertThat(lockTimeoutEventCount(orderId)).isZero();
        assertThat(notificationCountFor(taker.userId(), orderId)).isZero();
    }

    @Test
    void 已取消与不存在的单收到回调零副作用() {
        Session author = registerAndLogin("13800006013", "发单用户6");
        Session taker = registerAndLogin("13800006014", "接单用户8");
        String orderId = publishableOrder(author);
        grab(orderId, taker);
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/cancel", null, author), "锁单期发布方取消");

        // 已取消 + 不存在的订单：同批回调都按成功回报（未命中状态机 = 幂等成功，不算失败）
        assertCallbackSuccess(callback(lockTask(orderId, 1), lockTask(UUID.randomUUID().toString(), 7)));

        assertThat(statusOf(orderId)).isEqualTo("CANCELLED");
        assertThat(lockTimeoutEventCount(orderId)).as("已取消单不落释放事件").isZero();
        assertThat(notificationCountFor(taker.userId(), orderId)).as("已取消单不发通知").isZero();
    }

    // ---------- 分发：复合 bizId 形状不合法按失败重投，不拖垮同批 ----------

    @Test
    void 复合bizId形状不合法按失败重投且不拖垮同批其他项() {
        Session author = registerAndLogin("13800006015", "发单用户7");
        Session taker = registerAndLogin("13800006016", "接单用户9");
        String orderId = publishableOrder(author);
        grab(orderId, taker);

        Set<String> failures = callback(
                lockTaskRaw("not-a-composite"),
                lockTaskRaw(orderId + ":"),
                lockTask(orderId, 1));

        assertThat(failures)
                .as("形状坏的复合 bizId 是注册方编程错误：按失败重投（16 次后进死信，不静默当成功）")
                .containsExactlyInAnyOrder(
                        Constants.ERRAND_LOCK_TIMEOUT + ":not-a-composite",
                        Constants.ERRAND_LOCK_TIMEOUT + ":" + orderId + ":");
        assertThat(statusOf(orderId)).as("同批的好项不受坏项影响，已释放").isEqualTo("PUBLISHED");
    }

    // ---------- 用例夹具 ----------

    /** 走到 PUBLISHED 的完整前置：发布 → 支付冻结 → 人工裁决通过 */
    private String publishableOrder(Session author) {
        JsonNode created = postJson("/api/errand/orders", Map.ofEntries(
                Map.entry("title", "帮忙取个快递"),
                Map.entry("type", "EXPRESS_PICKUP"),
                Map.entry("description", "帮忙取个快递"),
                Map.entry("pickupLocation", "菜鸟驿站"),
                Map.entry("deliveryLocation", "6号楼楼下"),
                Map.entry("reward", 10),
                Map.entry("deadline", LocalDateTime.now().plusHours(2).toString())), author);
        assertSuccess(created, "发布跑腿");
        String orderId = created.path("data").asText();
        assertUuidId(orderId);
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/pay", null, author), "支付");
        assertThat(statusOf(orderId)).as("前置：订单应已发布").isEqualTo("PUBLISHED");
        return orderId;
    }

    private void grab(String orderId, Session taker) {
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/grab", null, taker), "抢单");
        assertThat(statusOf(orderId)).isEqualTo("LOCKED");
    }

    // ---------- 回调工具 ----------

    private static TimeoutTaskEntity lockTask(String orderId, int lockSeq) {
        return lockTaskRaw(orderId + ":" + lockSeq);
    }

    /** 原始 bizId（含故意造坏形状的用例）：注册方/中心传来的字面值原样打进去 */
    private static TimeoutTaskEntity lockTaskRaw(String bizId) {
        return TimeoutCallbackFixture.dueTask(Constants.ERRAND_LOCK_TIMEOUT, bizId, 5);
    }

    private Set<String> callback(TimeoutTaskEntity... tasks) {
        return TimeoutCallbackFixture.callback(timeoutTaskCallback, tasks);
    }

    private static void assertCallbackSuccess(Set<String> failures) {
        TimeoutCallbackFixture.assertCallbackSuccess(failures);
    }

    // ---------- 可观察状态 ----------

    private String statusOf(String orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM errand_order WHERE errand_order_id = ?", String.class, orderId);
    }

    private String takerIdOf(String orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT taker_id FROM errand_order WHERE errand_order_id = ?", String.class, orderId);
    }

    private int lockSeqOf(String orderId) {
        Integer lockSeq = jdbcTemplate.queryForObject(
                "SELECT lock_seq FROM errand_order WHERE errand_order_id = ?", Integer.class, orderId);
        return lockSeq == null ? -1 : lockSeq;
    }

    private Map<String, Object> registerRowOf(String orderId, int lockSeq) {
        return jdbcTemplate.queryForMap(
                "SELECT action_time, status FROM timeout_task WHERE task_type = ? AND biz_id = ?",
                Constants.ERRAND_LOCK_TIMEOUT, orderId + ":" + lockSeq);
    }

    private LocalDateTime actionTimeOf(String taskType, String bizId) {
        return jdbcTemplate.queryForObject(
                "SELECT action_time FROM timeout_task WHERE task_type = ? AND biz_id = ?",
                LocalDateTime.class, taskType, bizId);
    }

    private int registerRowCount(String orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM timeout_task WHERE task_type = ? AND biz_id LIKE ?",
                Integer.class, Constants.ERRAND_LOCK_TIMEOUT, orderId + ":%");
        return count == null ? 0 : count;
    }

    private int lockTimeoutEventCount(String orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM errand_order_event WHERE order_id = ? AND event_type = 'LOCK_TIMEOUT'",
                Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private Map<String, Object> eventRowOf(String orderId, String eventType) {
        return jdbcTemplate.queryForMap(
                "SELECT from_state, to_state, actor_id, actor_role "
                        + "FROM errand_order_event WHERE order_id = ? AND event_type = ? ORDER BY id LIMIT 1",
                orderId, eventType);
    }

    private int creditScoreOf(Session user) {
        Integer score = jdbcTemplate.queryForObject(
                "SELECT credit_score FROM bs_user WHERE user_id = ?", Integer.class, user.userId());
        return score == null ? -1 : score;
    }

    private int creditDetailCount(Session user, String reasonType) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_credit_detail WHERE user_id = ? AND reason_type = ?",
                Integer.class, user.userId(), reasonType);
        return count == null ? 0 : count;
    }

    /** 该订单的搜索同步消息数（跑腿实体）；注册/释放都不该新增 */
    private int errandSearchSyncMessageCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_message WHERE destination = ? AND payload LIKE ?",
                Integer.class, Constants.SEARCH_SYNC_TOPIC, "%\"type\":\"errand\"%");
        return count == null ? 0 : count;
    }

    private static String lockDedupKey(String orderId, int lockSeq) {
        return "ORDER_EVENT:ERRAND_LOCK_TIMEOUT:" + orderId + ":" + lockSeq;
    }

    private Map<String, Object> notificationOf(String userId, String orderId, int lockSeq) {
        return jdbcTemplate.queryForMap(
                "SELECT scope, type, ref_type, ref_id FROM notification WHERE user_id = ? AND dedup_key = ?",
                userId, lockDedupKey(orderId, lockSeq));
    }

    private int notificationCountFor(String userId, String orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE user_id = ? AND type = 'ORDER_EVENT' AND ref_id = ?",
                Integer.class, userId, orderId);
        return count == null ? 0 : count;
    }

    private List<String> notificationDedupKeysFor(String userId, String orderId) {
        return jdbcTemplate.queryForList(
                "SELECT dedup_key FROM notification WHERE user_id = ? AND type = 'ORDER_EVENT' AND ref_id = ?",
                String.class, userId, orderId);
    }

    private List<String> publicOrderIds() {
        JsonNode response = getJson("/api/errand/orders?page=1&pageSize=20", null);
        assertSuccess(response, "公开跑腿列表");
        return StreamSupport.stream(response.path("data").path("list").spliterator(), false)
                .map(node -> node.path("orderId").asText()).toList();
    }

}
