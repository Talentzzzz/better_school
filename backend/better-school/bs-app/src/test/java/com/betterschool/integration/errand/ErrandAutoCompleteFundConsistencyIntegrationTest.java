package com.betterschool.integration.errand;

import com.betterschool.domain.timeout.model.entity.TimeoutTaskEntity;
import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.integration.support.TimeoutCallbackFixture;
import com.betterschool.integration.support.WalletIncomeFailureProbe;
import com.betterschool.types.common.Constants;
import com.betterschool.usecase.timeout.TimeoutTaskCallbackDispatcher;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跑腿送达 24h 自动完成资金收口（票 05）：发布方解冻退款 + 接单方悬赏收入与
 * 「已送达 → 已完成」翻转经 IErrandFundPort 并入一个物理事务。只断言外显行为
 * （回调结果 + 数据库状态），不测事务内部编排：
 * <ul>
 *   <li>自动完成成功 ⇒ 订单已完成、发布方解冻退款恰一条、接单方 INCOME 入账恰一条、
 *       接单方余额增加——悬赏收入不再永久不结算；</li>
 *   <li>回调重放 ⇒ 终态短路安全返回，不重复退款、不重复入账（重投永远无害）；</li>
 *   <li>失败注入在端口委托的钱包动作边界（接单方入账抛异常）⇒ 整事务回滚：订单不翻转、
 *       事务内已执行的解冻随之回滚零残留；重投（桩撤掉后）收敛。</li>
 * </ul>
 * 结算口径与确认收货/仲裁的正面样板同源（发布方全额解冻、接单方得悬赏），
 * 但本链路靠物理事务消灭中间态，不动那两条已收敛路径。
 */
class ErrandAutoCompleteFundConsistencyIntegrationTest extends CommunityApiTestBase {

    /** 与用例一致的悬赏面额（≥10 下限之上取整好算的值） */
    private static final BigDecimal REWARD = new BigDecimal("10");
    /** 注册即建钱包的初始余额（WalletRepository.INITIAL_BALANCE 同口径）；
     *  freeze/unfreeze 只在 frozen_amount 与可用余额（balance - frozen）之间移动资金，balance 列不动 */
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("1000");

    @Autowired
    private TimeoutTaskCallbackDispatcher timeoutTaskCallback;
    @Autowired
    private WalletIncomeFailureProbe walletIncomeFailureProbe;

    @BeforeEach
    void resetProbe() {
        walletIncomeFailureProbe.reset();
    }

    @Test
    void 自动完成成功_订单完成且发布方解冻接单方悬赏入账() {
        Session author = registerAndLogin("13800007401", "自动完成作者");
        Session taker = registerAndLogin("13800007402", "自动完成接单方");
        String orderId = createPendingOrder(author);
        payOrder(orderId, author);
        takeAndDeliver(orderId, taker);
        assertThat(statusOf(orderId)).as("前置：接单方已送达").isEqualTo("DELIVERED");
        assertThat(frozenAmountOf(author.userId())).as("前置：悬赏已冻结托管").isEqualByComparingTo(REWARD);
        assertThat(balanceOf(taker.userId())).as("前置：接单方初始余额").isEqualByComparingTo(INITIAL_BALANCE);

        Set<String> failures = callbackAutoComplete(orderId);

        TimeoutCallbackFixture.assertCallbackSuccess(failures);
        // 状态翻转：订单完成（终态）
        assertThat(statusOf(orderId)).as("自动完成应转已完成").isEqualTo("COMPLETED");
        // 发布方解冻退款：UNFROZEN 流水恰一条（流水先行 + uk_order_type 幂等的物证）
        assertThat(unfrozenFlowCountOf(orderId)).as("自动完成应有一条发布方解冻退款流水").isEqualTo(1);
        assertThat(frozenAmountOf(author.userId())).as("完成后冻结额应归零").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balanceOf(author.userId())).as("完成后可用余额应全额恢复")
                .isEqualByComparingTo(INITIAL_BALANCE);
        // 接单方悬赏收入：INCOME 流水恰一条、余额入账——与订单终态同一时刻生效，无永久丢失
        assertThat(incomeFlowCountOf(orderId, taker.userId()))
                .as("接单方应恰有一条悬赏收入流水").isEqualTo(1);
        assertThat(balanceOf(taker.userId())).as("接单方余额应增加悬赏")
                .isEqualByComparingTo(INITIAL_BALANCE.add(REWARD));
    }

    @Test
    void 回调重放_终态短路无重复退款无重复入账() {
        Session author = registerAndLogin("13800007403", "重放作者");
        Session taker = registerAndLogin("13800007404", "重放接单方");
        String orderId = createPendingOrder(author);
        payOrder(orderId, author);
        takeAndDeliver(orderId, taker);
        TimeoutCallbackFixture.assertCallbackSuccess(callbackAutoComplete(orderId));
        BigDecimal authorBalanceAfterFirst = balanceOf(author.userId());
        BigDecimal takerBalanceAfterFirst = balanceOf(taker.userId());

        // 中心 at-least-once 重投：同一条已处理过的自动完成任务再回调一次
        Set<String> replayFailures = callbackAutoComplete(orderId);

        TimeoutCallbackFixture.assertCallbackSuccess(replayFailures);
        assertThat(statusOf(orderId)).as("重放后订单仍已完成").isEqualTo("COMPLETED");
        assertThat(unfrozenFlowCountOf(orderId)).as("重放不得重复退款（终态短路）").isEqualTo(1);
        assertThat(incomeFlowCountOf(orderId, taker.userId()))
                .as("重放不得重复入账（uk_order_type 幂等）").isEqualTo(1);
        assertThat(balanceOf(author.userId())).as("重放后发布方余额不变").isEqualByComparingTo(authorBalanceAfterFirst);
        assertThat(balanceOf(taker.userId())).as("重放后接单方余额不变").isEqualByComparingTo(takerBalanceAfterFirst);
    }

    @Test
    void 入账失败注入_整事务回滚订单不翻转_重投收敛() {
        Session author = registerAndLogin("13800007405", "注入作者");
        Session taker = registerAndLogin("13800007406", "注入接单方");
        String orderId = createPendingOrder(author);
        payOrder(orderId, author);
        takeAndDeliver(orderId, taker);

        // 失败注入（端口委托的钱包动作边界）：接单方入账那一刻抛异常
        walletIncomeFailureProbe.armIncomeFailure();
        Set<String> failures = callbackAutoComplete(orderId);

        assertThat(failures).as("入账失败应按失败重投").contains(Constants.ERRAND_AUTO_COMPLETE + ":" + orderId);
        // 整事务回滚的外显：订单不翻转；物理事务内已执行的发布方解冻随之回滚——零残留
        assertThat(statusOf(orderId)).as("入账失败订单应留在已送达").isEqualTo("DELIVERED");
        assertThat(unfrozenFlowCountOf(orderId)).as("回滚后不应有解冻流水").isZero();
        assertThat(incomeFlowCountOf(orderId, taker.userId())).as("回滚后不应有入账流水").isZero();
        assertThat(frozenAmountOf(author.userId())).as("悬赏应继续冻结托管").isEqualByComparingTo(REWARD);
        assertThat(balanceOf(author.userId())).as("发布方可用余额不应变化").isEqualByComparingTo(INITIAL_BALANCE);
        assertThat(balanceOf(taker.userId())).as("接单方余额不应变化").isEqualByComparingTo(INITIAL_BALANCE);

        // 崩溃窗口收敛：撤桩后的重投把单正常关掉、钱正常结清
        Set<String> retryFailures = callbackAutoComplete(orderId);

        TimeoutCallbackFixture.assertCallbackSuccess(retryFailures);
        assertThat(statusOf(orderId)).as("重投后订单应完成").isEqualTo("COMPLETED");
        assertThat(unfrozenFlowCountOf(orderId)).as("重投后发布方解冻退款恰一条").isEqualTo(1);
        assertThat(incomeFlowCountOf(orderId, taker.userId())).as("重投后接单方入账恰一条").isEqualTo(1);
        assertThat(frozenAmountOf(author.userId())).as("重投后冻结额应归零").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balanceOf(author.userId())).as("重投后发布方可用余额应全额恢复")
                .isEqualByComparingTo(INITIAL_BALANCE);
        assertThat(balanceOf(taker.userId())).as("重投后接单方余额应增加悬赏")
                .isEqualByComparingTo(INITIAL_BALANCE.add(REWARD));
    }

    // ---------- 超时回调驱动（TimeoutCallbackFixture 直驱真分发器，超时中心本体零改动） ----------

    private Set<String> callbackAutoComplete(String orderId) {
        TimeoutTaskEntity taskEntity =
                TimeoutCallbackFixture.dueTask(Constants.ERRAND_AUTO_COMPLETE, orderId, 15);
        return TimeoutCallbackFixture.callback(timeoutTaskCallback, taskEntity);
    }

    // ---------- 用例夹具（照同包票 02/03/04 既有用例的工具形状） ----------

    /** 发布待支付订单（reward = 10，deadline 2 小时后），返回 orderId */
    private String createPendingOrder(Session author) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "帮忙取个快递");
        body.put("type", "EXPRESS_PICKUP");
        body.put("description", "自动完成资金收口测试订单");
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

    /** 接单方走完整履约链：抢 → 确认接单 → 取货 → 送达（送达后订单进 DELIVERED，等 24h 自动完成） */
    private void takeAndDeliver(String orderId, Session taker) {
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/grab", null, taker), "抢单");
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/accept", null, taker), "确认接单");
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/pickup", null, taker), "取货");
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/deliver", null, taker), "送达");
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

    private int incomeFlowCountOf(String orderId, String takerId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_detail WHERE order_id = ? AND type = 'INCOME' AND user_id = ?",
                Integer.class, orderId, takerId);
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
}
