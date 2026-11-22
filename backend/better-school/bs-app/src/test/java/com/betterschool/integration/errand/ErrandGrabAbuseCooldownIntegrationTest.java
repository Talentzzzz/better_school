package com.betterschool.integration.errand;

import com.betterschool.domain.timeout.model.entity.TimeoutTaskEntity;
import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.integration.support.TimeoutCallbackFixture;
import com.betterschool.types.common.Constants;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.usecase.timeout.TimeoutTaskCallbackDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RScoredSortedSet;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 防占单（票 05）集成测试：让「抢了不做」有代价，且代价与过错对等——用**限权（冷却）**而非扣信用分。
 * 滑动 24h 内主动放弃（{@code LOCK_ABANDONED}）达 3 次 → 该用户 2 小时内禁抢（{@code grab} 前置直接拒，403）；
 * 超时未确认不计（不可控）；冷却触发即重置计数窗口（否则冷却结束立刻再触发、实际封禁 ≈22h）。
 *
 * <p>Redis 计数/冷却是判定加速层，事实源仍是 {@code errand_order_event}——断言只看外部可观察状态
 * （HTTP 响应 + MySQL 事件表 + Redis 计数键）。
 */
@DisplayName("票 05：防占单（主动放弃滑动计数 24h + 抢单冷却 2h）")
class ErrandGrabAbuseCooldownIntegrationTest extends CommunityApiTestBase {

    @Autowired
    private TimeoutTaskCallbackDispatcher timeoutTaskCallback;

    // ---------- 主动放弃计数 → 冷却 → 拒抢 ----------

    @Test
    void 第三次主动放弃触发冷却_第四次抢单被拒且文案含到期时刻() {
        Session author = registerAndLogin("13800007001", "发单用户1");
        Session taker = registerAndLogin("13800007002", "接单用户1");

        // 前两次放弃：不到阈值，不冷却
        abandonTimes(author, taker, 2);
        assertThat(cooldownUntilOf(taker)).as("2 次放弃不到阈值，不冷却").isNull();

        // 第三次放弃：达阈值 → 触发冷却（并重置窗口）
        abandonOnce(author, taker);
        assertThat(cooldownUntilOf(taker)).as("第 3 次放弃触发冷却").isNotNull();

        // 第 4 次抢单被拒：403 权限类拒绝 + 文案（含到期时刻 HH:mm）；订单状态不动、无人持有
        String blockedOrder = publishableOrder(author);
        JsonNode rejected = postJson("/api/errand/orders/" + blockedOrder + "/grab", null, taker);
        assertThat(rejected.path("code").asText()).as("冷却期抢单被拒：%s", rejected)
                .isEqualTo(ResponseCode.FORBIDDEN.getCode());
        assertThat(rejected.path("info").asText()).as("文案含「限制接单」与到期时刻")
                .contains("限制接单").containsPattern("\\d{2}:\\d{2}");
        assertThat(statusOf(blockedOrder)).isEqualTo("PUBLISHED");
        assertThat(takerIdOf(blockedOrder)).isNull();

        // 冷却键是用户维度：他人不受影响（天然覆盖「该单」）
        Session other = registerAndLogin("13800007003", "接单用户2");
        assertSuccess(postJson("/api/errand/orders/" + blockedOrder + "/grab", null, other), "他人不受冷却影响");

        // 口径正交：放弃记冷却、不记信用分（信用分只记「已承诺后违约」）
        assertThat(creditScoreOf(taker)).as("放弃不扣信用分").isEqualTo(100);
        assertThat(creditDetailCount(taker, "ERRAND_TAKER_CANCEL")).isZero();
        assertThat(creditDetailCount(taker, "ERRAND_TIMEOUT")).isZero();
    }

    @Test
    void 冷却触发重置窗口_冷却结束后不会被旧计数立刻再触发() {
        Session author = registerAndLogin("13800007011", "发单用户3");
        Session taker = registerAndLogin("13800007012", "接单用户4");

        abandonTimes(author, taker, 3);
        assertThat(cooldownUntilOf(taker)).isNotNull();

        // 触发即重置窗口：ZSET 被清空（否则冷却结束那 3 次还在 ⇒ 立刻再触发 ⇒ 实际封禁 ≈22h）
        assertThat(abandonWindowSize(taker)).as("冷却触发后计数窗口被重置").isZero();

        // 模拟 2h 冷却到期（删冷却键，等价 TTL 到点）：窗口为空 → 可正常抢
        deleteCooldown(taker);
        String order = publishableOrder(author);
        assertSuccess(postJson("/api/errand/orders/" + order + "/grab", null, taker), "冷却结束可抢");
        assertThat(statusOf(order)).isEqualTo("LOCKED");

        // 新窗口重新起算：这一次放弃是第 1 次，不该立刻又冷却
        assertSuccess(postJson("/api/errand/orders/" + order + "/abandon", null, taker), "放弃");
        assertThat(cooldownUntilOf(taker)).as("重置后重新起算，1 次放弃不触发冷却").isNull();
        assertThat(abandonWindowSize(taker)).as("新窗口只有这一次放弃").isEqualTo(1);
    }

    // ---------- 超时未确认不计 ----------

    @Test
    void 锁单超时释放不计入主动放弃() {
        Session author = registerAndLogin("13800007021", "发单用户5");
        Session taker = registerAndLogin("13800007022", "接单用户6");

        // 三次「抢了不确认」由超时中心回调释放：超时不可控（掉线/没看到），不计入滥用
        for (int i = 0; i < 3; i++) {
            String order = publishableOrder(author);
            grab(order, taker);
            assertCallbackSuccess(callback(lockTask(order, 1)));
            assertThat(statusOf(order)).isEqualTo("PUBLISHED");
        }

        assertThat(abandonWindowSize(taker)).as("超时未确认不计入计数窗口").isZero();
        assertThat(cooldownUntilOf(taker)).as("三次超时不触发冷却").isNull();

        // 第 4 次仍可正常抢单
        String order = publishableOrder(author);
        assertSuccess(postJson("/api/errand/orders/" + order + "/grab", null, taker), "超时不冷却，仍可抢");
        assertThat(statusOf(order)).isEqualTo("LOCKED");
    }

    // ---------- 前置：走到 PUBLISHED 的完整链路 ----------

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

    /** 抢单 → 主动放弃 → 回已发布（一次完整「主动放弃」，每次用一张新单保证 member 唯一） */
    private void abandonOnce(Session author, Session taker) {
        String order = publishableOrder(author);
        grab(order, taker);
        assertSuccess(postJson("/api/errand/orders/" + order + "/abandon", null, taker), "放弃接单");
        assertThat(statusOf(order)).isEqualTo("PUBLISHED");
    }

    private void abandonTimes(Session author, Session taker, int times) {
        for (int i = 0; i < times; i++) {
            abandonOnce(author, taker);
        }
    }

    private void grab(String orderId, Session taker) {
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/grab", null, taker), "抢单");
        assertThat(statusOf(orderId)).isEqualTo("LOCKED");
    }

    // ---------- 超时回调工具（与 ErrandLockTimeoutIntegrationTest 同口径：进程内分发器，票 05） ----------

    private static TimeoutTaskEntity lockTask(String orderId, int lockSeq) {
        return TimeoutCallbackFixture.dueTask(Constants.ERRAND_LOCK_TIMEOUT, orderId + ":" + lockSeq, 5);
    }

    private Set<String> callback(TimeoutTaskEntity... tasks) {
        return TimeoutCallbackFixture.callback(timeoutTaskCallback, tasks);
    }

    private static void assertCallbackSuccess(Set<String> failures) {
        TimeoutCallbackFixture.assertCallbackSuccess(failures);
    }

    // ---------- Redis / MySQL 可观察状态 ----------

    private Long cooldownUntilOf(Session user) {
        return redissonClient.<Long>getBucket(Constants.ERRAND_GRAB_COOLDOWN_KEY + user.userId()).get();
    }

    private int abandonWindowSize(Session user) {
        RScoredSortedSet<String> window =
                redissonClient.getScoredSortedSet(Constants.ERRAND_GRAB_ABANDON_KEY + user.userId());
        return window.size();
    }

    private void deleteCooldown(Session user) {
        redissonClient.getBucket(Constants.ERRAND_GRAB_COOLDOWN_KEY + user.userId()).delete();
    }

    private String statusOf(String orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM errand_order WHERE errand_order_id = ?", String.class, orderId);
    }

    private String takerIdOf(String orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT taker_id FROM errand_order WHERE errand_order_id = ?", String.class, orderId);
    }

    private int creditScoreOf(Session user) {
        Integer score = jdbcTemplate.queryForObject(
                "SELECT credit_score FROM user_credit WHERE user_id = ?", Integer.class, user.userId());
        return score == null ? 100 : score;
    }

    private int creditDetailCount(Session user, String reasonType) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_credit_detail WHERE user_id = ? AND reason_type = ?",
                Integer.class, user.userId(), reasonType);
        return count == null ? 0 : count;
    }
}
