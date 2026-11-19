package com.betterschool.integration.errand;

import com.betterschool.domain.timeout.model.entity.TimeoutTaskEntity;
import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.integration.support.TimeoutCallbackFixture;
import com.betterschool.types.common.Constants;
import com.betterschool.usecase.timeout.TimeoutTaskCallbackDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跑腿超时取消资金收口（票 04）：订单超时取消翻转 + 发布方解冻退款（开工场景另含接单方
 * 信用分扣减）经 IErrandFundPort 并入一个物理事务。只断言外显行为（回调结果 + 数据库状态），
 * 不测事务内部编排：
 * <ul>
 *   <li>超时取消成功 ⇒ 订单已取消、解冻流水恰一条、冻结额归零、可用余额恢复——无悬置冻结；</li>
 *   <li>开工场景（已确认接单）超时 ⇒ 解冻退款与接单方信用分扣减一起生效，任何一侧不单独生效；</li>
 *   <li>锁单未开工超时 ⇒ 解冻照常但零扣分（「抢 ≠ 开工」committed 判据的外显）；</li>
 *   <li>回调重放 ⇒ 终态短路安全返回，不重复退款、不重复扣分（重投永远无害）；</li>
 *   <li>早到回调按失败重投 ⇒ 解冻与扣分零残留（整事务回滚的外显），deadline 越过后重投收敛。</li>
 * </ul>
 * 失败注入点在端口委托（订单条件翻转）边界：早到守卫让回调按失败重投——此刻物理事务内
 * 已执行的解冻/扣分必须随翻转失败整体回滚，不留半截资金动作。
 */
class ErrandTimeoutFundConsistencyIntegrationTest extends CommunityApiTestBase {

    /** 与用例一致的悬赏面额（≥10 下限之上取整好算的值） */
    private static final BigDecimal REWARD = new BigDecimal("10");
    /** 注册即建钱包的初始余额（WalletRepository.INITIAL_BALANCE 同口径）；
     *  freeze/unfreeze 只在 frozen_amount 与可用余额（balance - frozen）之间移动资金，balance 列不动 */
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("1000");
    /** 信用分初始值（注册默认 100），开工超时扣 ERRAND_TIMEOUT = -20 */
    private static final int INITIAL_CREDIT_SCORE = 100;
    private static final int TIMEOUT_CREDIT_DELTA = -20;

    @Autowired
    private TimeoutTaskCallbackDispatcher timeoutTaskCallback;

    @Test
    void 超时取消成功_订单取消且解冻退款回余额无悬置冻结() {
        Session author = registerAndLogin("13800007301", "超时作者");
        String orderId = createPendingOrder(author);
        payOrder(orderId, author);
        assertThat(frozenAmountOf(author.userId())).as("前置：支付后悬赏已冻结").isEqualByComparingTo(REWARD);

        expireDeadline(orderId);
        Set<String> failures = callbackTimeout(orderId);

        TimeoutCallbackFixture.assertCallbackSuccess(failures);
        // 状态翻转：订单取消（终态）
        assertThat(statusOf(orderId)).as("超时回调应转已取消").isEqualTo("CANCELLED");
        // 解冻退款：UNFROZEN 流水恰一条（流水先行 + uk_order_type 幂等的物证）
        assertThat(unfrozenFlowCountOf(orderId)).as("超时取消应有一条解冻退款流水").isEqualTo(1);
        assertThat(frozenAmountOf(author.userId())).as("取消后冻结额应归零").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balanceOf(author.userId())).as("取消后可用余额应全额恢复")
                .isEqualByComparingTo(INITIAL_BALANCE);
        // 大厅单无人接单：无接单方信用分动作
        assertThat(creditDetailCountOf(author.userId(), "ERRAND_TIMEOUT"))
                .as("无接单方不应有超时扣分流水").isZero();
    }

    @Test
    void 开工场景超时_解冻退款与接单方信用分扣减一起生效() {
        Session author = registerAndLogin("13800007302", "开工作者");
        Session taker = registerAndLogin("13800007303", "开工接单方");
        String orderId = createPendingOrder(author);
        payOrder(orderId, author);
        takeAndConfirm(orderId, taker);
        assertThat(creditScoreOf(taker.userId())).as("前置：初始信用分").isEqualTo(INITIAL_CREDIT_SCORE);

        expireDeadline(orderId);
        Set<String> failures = callbackTimeout(orderId);

        TimeoutCallbackFixture.assertCallbackSuccess(failures);
        // 订单取消与解冻退款同一生效
        assertThat(statusOf(orderId)).as("开工单超时应转已取消").isEqualTo("CANCELLED");
        assertThat(unfrozenFlowCountOf(orderId)).as("开工单超时应解冻退款").isEqualTo(1);
        assertThat(frozenAmountOf(author.userId())).as("冻结额应归零").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balanceOf(author.userId())).as("可用余额应全额恢复").isEqualByComparingTo(INITIAL_BALANCE);
        // 信用分扣减与解冻同事务一起生效：流水恰一条 + 分数扣减落地，任何一侧不单独生效
        assertThat(creditDetailCountOf(taker.userId(), "ERRAND_TIMEOUT"))
                .as("开工接单方超时应恰有一条扣分流水").isEqualTo(1);
        assertThat(creditScoreOf(taker.userId()))
                .as("开工接单方信用分应扣 " + TIMEOUT_CREDIT_DELTA)
                .isEqualTo(INITIAL_CREDIT_SCORE + TIMEOUT_CREDIT_DELTA);
    }

    @Test
    void 锁单未开工超时_解冻照常但零扣分() {
        Session author = registerAndLogin("13800007304", "锁单作者");
        Session taker = registerAndLogin("13800007305", "锁单接单方");
        String orderId = createPendingOrder(author);
        payOrder(orderId, author);
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/grab", null, taker), "抢单");
        assertThat(statusOf(orderId)).as("前置：锁单中（抢 ≠ 开工）").isEqualTo("LOCKED");

        expireDeadline(orderId);
        Set<String> failures = callbackTimeout(orderId);

        TimeoutCallbackFixture.assertCallbackSuccess(failures);
        assertThat(statusOf(orderId)).as("锁单单超时应转已取消").isEqualTo("CANCELLED");
        assertThat(unfrozenFlowCountOf(orderId)).as("解冻退款照常生效").isEqualTo(1);
        assertThat(frozenAmountOf(author.userId())).as("冻结额应归零").isEqualByComparingTo(BigDecimal.ZERO);
        // 锁单期未开工＝未产生承诺：与「放弃不扣分」同口径，超时也不扣接单人分
        assertThat(creditDetailCountOf(taker.userId(), "ERRAND_TIMEOUT"))
                .as("锁单未开工不应扣信用分").isZero();
        assertThat(creditScoreOf(taker.userId())).as("接单方信用分应保持不变").isEqualTo(INITIAL_CREDIT_SCORE);
    }

    @Test
    void 回调重放_终态短路无重复退款无重复扣分() {
        Session author = registerAndLogin("13800007306", "重放作者");
        Session taker = registerAndLogin("13800007307", "重放接单方");
        String orderId = createPendingOrder(author);
        payOrder(orderId, author);
        takeAndConfirm(orderId, taker);
        expireDeadline(orderId);
        TimeoutCallbackFixture.assertCallbackSuccess(callbackTimeout(orderId));

        // 中心 at-least-once 重投：同一条已处理过的超时任务再回调一次
        Set<String> replayFailures = callbackTimeout(orderId);

        TimeoutCallbackFixture.assertCallbackSuccess(replayFailures);
        assertThat(statusOf(orderId)).as("重放后订单仍已取消").isEqualTo("CANCELLED");
        assertThat(unfrozenFlowCountOf(orderId)).as("重放不得重复退款（终态短路）").isEqualTo(1);
        assertThat(frozenAmountOf(author.userId())).as("重放后冻结额仍应归零").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balanceOf(author.userId())).as("重放不得重复入账").isEqualByComparingTo(INITIAL_BALANCE);
        assertThat(creditDetailCountOf(taker.userId(), "ERRAND_TIMEOUT"))
                .as("重放不得重复扣分（uk_user_reason_ref 幂等）").isEqualTo(1);
        assertThat(creditScoreOf(taker.userId()))
                .as("重放后信用分只扣一次")
                .isEqualTo(INITIAL_CREDIT_SCORE + TIMEOUT_CREDIT_DELTA);
    }

    @Test
    void 早到回调按失败重投资金零残留_deadline越过后重投收敛() {
        Session author = registerAndLogin("13800007308", "早到作者");
        String orderId = createPendingOrder(author);
        payOrder(orderId, author);

        // deadline 还没到（时钟偏差窗口）：回调必须按失败重投，绝不静默成功
        Set<String> failures = callbackTimeout(orderId);

        assertThat(failures).as("早到回调应按失败重投").contains(Constants.ERRAND_ORDER_TIMEOUT + ":" + orderId);
        // 失败重投 = 整事务回滚的外显：订单未翻转、资金零残留（解冻/扣分半截动作无处存活）
        assertThat(statusOf(orderId)).as("早到回调不得翻转订单").isEqualTo("PUBLISHED");
        assertThat(unfrozenFlowCountOf(orderId)).as("早到回调不得解冻").isZero();
        assertThat(frozenAmountOf(author.userId())).as("悬赏应继续冻结托管").isEqualByComparingTo(REWARD);
        assertThat(balanceOf(author.userId())).as("可用余额不应变化").isEqualByComparingTo(INITIAL_BALANCE);

        // 崩溃窗口收敛：deadline 越过后的重投把单正常关掉、钱正常退回
        expireDeadline(orderId);
        Set<String> retryFailures = callbackTimeout(orderId);

        TimeoutCallbackFixture.assertCallbackSuccess(retryFailures);
        assertThat(statusOf(orderId)).as("重投后订单应取消").isEqualTo("CANCELLED");
        assertThat(unfrozenFlowCountOf(orderId)).as("重投后解冻退款恰一条").isEqualTo(1);
        assertThat(frozenAmountOf(author.userId())).as("重投后冻结额应归零").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balanceOf(author.userId())).as("重投后可用余额应全额恢复")
                .isEqualByComparingTo(INITIAL_BALANCE);
    }

    // ---------- 超时回调驱动（TimeoutCallbackFixture 直驱真分发器，超时中心本体零改动） ----------

    private Set<String> callbackTimeout(String orderId) {
        TimeoutTaskEntity taskEntity =
                TimeoutCallbackFixture.dueTask(Constants.ERRAND_ORDER_TIMEOUT, orderId, 15);
        return TimeoutCallbackFixture.callback(timeoutTaskCallback, taskEntity);
    }

    /** 业务行 deadline 回拨到过去（过 markTimeout 的 deadline 校验与早到守卫；回调由夹具直驱，不真等） */
    private void expireDeadline(String orderId) {
        jdbcTemplate.update(
                "UPDATE errand_order SET deadline = DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE errand_order_id = ?",
                orderId);
    }

    // ---------- 用例夹具（照同包票 02/03 既有用例的工具形状） ----------

    /** 发布待支付订单（reward = 10，deadline 2 小时后），返回 orderId */
    private String createPendingOrder(Session author) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "帮忙取个快递");
        body.put("type", "EXPRESS_PICKUP");
        body.put("description", "超时资金收口测试订单");
        body.put("pickupLocation", "菜鸟驿站");
        body.put("deliveryLocation", "6号楼楼下");
        body.put("reward", REWARD.intValue());
        body.put("deadline", LocalDateTime.now().plusHours(2).toString());
        JsonNode createResponse = postJson("/api/errand/orders", body, author);
        assertSuccess(createResponse, "发布跑腿");
        return createResponse.path("data").asText();
    }

    private void payOrder(String orderId, Session author) {
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/pay", null, author), "支付");
    }

    private void takeAndConfirm(String orderId, Session taker) {
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/grab", null, taker), "抢单");
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/accept", null, taker), "确认接单");
    }

    // ---------- 可观察状态 ----------

    private String statusOf(String orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM errand_order WHERE errand_order_id = ?", String.class, orderId);
    }

    private int unfrozenFlowCountOf(String orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_detail WHERE order_id = ? AND type = 'UNFROZEN'",
                Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private BigDecimal balanceOf(String userId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM virtual_wallet WHERE user_id = ?", BigDecimal.class, userId);
    }

    private BigDecimal frozenAmountOf(String userId) {
        return jdbcTemplate.queryForObject(
                "SELECT frozen_amount FROM virtual_wallet WHERE user_id = ?", BigDecimal.class, userId);
    }

    private int creditDetailCountOf(String userId, String reasonType) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_credit_detail WHERE user_id = ? AND reason_type = ?",
                Integer.class, userId, reasonType);
        return count == null ? 0 : count;
    }

    private int creditScoreOf(String userId) {
        Integer score = jdbcTemplate.queryForObject(
                "SELECT credit_score FROM bs_user WHERE user_id = ?", Integer.class, userId);
        return score == null ? -1 : score;
    }
}
