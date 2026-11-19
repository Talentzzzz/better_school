package com.betterschool.integration.errand;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跑腿取消资金收口（票 03）：发布方解冻退款与订单取消翻转经 IErrandFundPort 并入一个物理事务。
 * 只断言外显行为（HTTP 响应 + 数据库状态），不测事务内部编排：
 * <ul>
 *   <li>取消成功 ⇒ 订单已取消且解冻流水恰一条、冻结额归零、可用余额全额恢复——无悬置冻结；</li>
 *   <li>已取消订单重试 ⇒ 不重复退款，资金收敛不被破坏（终态守卫 + 流水 uk 双保险）；</li>
 *   <li>待支付取消（未冻结）⇒ 无解冻流水（无需退款分支保留）；</li>
 *   <li>接单方取消（回已发布）⇒ 悬赏继续托管不退款，同编号重放零副作用。</li>
 * </ul>
 * 失败注入点在端口委托（钱包动作）边界：解冻动作天然无「可注入失配」的条件守卫
 * （updateUnfreeze 无 0 行失败路径），故钱包侧的原子性由「取消成功 ⇒ 钱必到位」与
 * 「重放 ⇒ 不重复退款」两个方向外显覆盖，与票 02 余额不足注入同缝不同向。
 */
class ErrandCancelFundConsistencyIntegrationTest extends CommunityApiTestBase {

    /** 与用例一致的悬赏面额（≥10 下限之上取整好算的值） */
    private static final BigDecimal REWARD = new BigDecimal("10");
    /** 注册即建钱包的初始余额（WalletRepository.INITIAL_BALANCE 同口径）；
     *  freeze/unfreeze 只在 frozen_amount 与可用余额（balance - frozen）之间移动资金，balance 列不动 */
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("1000");

    @Test
    void 发布方取消成功_订单取消且解冻退款回余额无悬置冻结() {
        Session author = registerAndLogin("13800007201", "取消作者");
        String orderId = createPendingOrder(author);
        payOrder(orderId, author);
        assertThat(frozenAmountOf(author.userId())).as("前置：支付后悬赏已冻结").isEqualByComparingTo(REWARD);

        assertSuccess(postJson("/api/errand/orders/" + orderId + "/cancel", null, author), "取消");

        // 状态翻转：订单取消（终态）
        assertThat(statusOf(orderId)).as("取消成功应转已取消").isEqualTo("CANCELLED");
        // 解冻退款：UNFROZEN 流水恰一条（流水先行 + uk_order_type 幂等的物证）
        assertThat(unfrozenFlowCountOf(orderId)).as("取消成功应有一条解冻退款流水").isEqualTo(1);
        // 冻结额归零：钱回到可用余额，无悬置冻结
        assertThat(frozenAmountOf(author.userId())).as("取消后冻结额应归零").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balanceOf(author.userId())).as("取消后可用余额应全额恢复")
                .isEqualByComparingTo(INITIAL_BALANCE);
    }

    @Test
    void 已取消订单重试_不重复退款资金收敛不破坏() {
        Session author = registerAndLogin("13800007202", "重试作者");
        String orderId = createPendingOrder(author);
        payOrder(orderId, author);
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/cancel", null, author), "首次取消");

        // 同单再次取消：终态守卫拒绝（响应为失败），但资金侧必须零副作用
        JsonNode retryResponse = postJson("/api/errand/orders/" + orderId + "/cancel", null, author);
        assertThat(retryResponse.path("code").asText()).as("已取消订单重试应被终态守卫拒绝：%s", retryResponse)
                .isNotEqualTo("200");

        assertThat(unfrozenFlowCountOf(orderId)).as("重试不得重复退款（uk_order_type 幂等）").isEqualTo(1);
        assertThat(frozenAmountOf(author.userId())).as("重试后冻结额仍应归零").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balanceOf(author.userId())).as("重试不得重复入账").isEqualByComparingTo(INITIAL_BALANCE);
        assertThat(statusOf(orderId)).as("重试后订单仍已取消").isEqualTo("CANCELLED");
    }

    @Test
    void 待支付取消_无退款无解冻流水() {
        Session author = registerAndLogin("13800007203", "待支付取消作者");
        String orderId = createPendingOrder(author);

        assertSuccess(postJson("/api/errand/orders/" + orderId + "/cancel", null, author), "待支付取消");

        assertThat(statusOf(orderId)).as("待支付取消应转已取消").isEqualTo("CANCELLED");
        assertThat(unfrozenFlowCountOf(orderId)).as("从未冻结不应产生解冻流水").isEqualTo(0);
        assertThat(frozenAmountOf(author.userId())).as("冻结额应保持为零").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balanceOf(author.userId())).as("可用余额不应变化").isEqualByComparingTo(INITIAL_BALANCE);
    }

    @Test
    void 接单方取消_悬赏继续托管不退款() {
        Session author = registerAndLogin("13800007204", "托管作者");
        Session taker = registerAndLogin("13800007205", "托管接单方");
        String orderId = createPendingOrder(author);
        payOrder(orderId, author);
        takeAndConfirm(orderId, taker);

        assertSuccess(postJson("/api/errand/orders/" + orderId + "/cancel", null, taker), "接单方取消");

        // 订单回已发布重新可抢，悬赏继续托管给下一轮接单方——解冻退款只属于发布方取消
        assertThat(statusOf(orderId)).as("接单方取消应回已发布").isEqualTo("PUBLISHED");
        assertThat(unfrozenFlowCountOf(orderId)).as("接单方取消不应解冻退款").isEqualTo(0);
        assertThat(frozenAmountOf(author.userId())).as("悬赏应继续冻结托管").isEqualByComparingTo(REWARD);
    }

    @Test
    void 接单方取消同编号重放_回放成功且资金零副作用() {
        Session author = registerAndLogin("13800007206", "重放作者");
        Session taker = registerAndLogin("13800007207", "重放接单方");
        String orderId = createPendingOrder(author);
        payOrder(orderId, author);
        takeAndConfirm(orderId, taker);
        String requestId = "req-cancel-replay-01";
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/cancel?requestId=" + requestId, null, taker),
                "首次接单方取消");

        // 同编号重放：事件链幂等判定回放成功结果（接单人字段已清空，订单行无法辨认身份，
        // 判定事实源 = errand_order_event 的 TAKER_CANCELLED 事件）
        assertSuccess(postJson("/api/errand/orders/" + orderId + "/cancel?requestId=" + requestId, null, taker),
                "同编号重放");

        assertThat(unfrozenFlowCountOf(orderId)).as("重放不得产生解冻退款").isEqualTo(0);
        assertThat(frozenAmountOf(author.userId())).as("重放后悬赏应继续冻结托管").isEqualByComparingTo(REWARD);
        assertThat(creditDetailCount(taker, "ERRAND_TAKER_CANCEL")).as("重放扣分由流水 uk 去重只记一次").isEqualTo(1);
        assertThat(statusOf(orderId)).as("重放后订单仍已发布").isEqualTo("PUBLISHED");
    }

    // ---------- 工具 ----------

    /** 发布待支付订单（reward = 10，deadline 2 小时后），返回 orderId */
    private String createPendingOrder(Session author) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "帮忙取个快递");
        body.put("type", "EXPRESS_PICKUP");
        body.put("description", "取消资金收口测试订单");
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

    private int creditDetailCount(Session user, String reasonType) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM user_credit_detail WHERE user_id = ? AND reason_type = ?",
                Integer.class, user.userId(), reasonType);
        return count == null ? 0 : count;
    }
}
