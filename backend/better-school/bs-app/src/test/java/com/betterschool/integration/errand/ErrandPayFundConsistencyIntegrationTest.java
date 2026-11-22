package com.betterschool.integration.errand;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 跑腿支付资金收口（票 02，ADR-0016 两段式）：case 层先冻结悬赏（资金段）再翻转「待支付 → 已发布」（翻转段）。
 * 只断言外显行为（HTTP 响应 + 数据库状态），不测事务内部编排：
 * <ul>
 *   <li>支付成功 ⇒ 订单已发布（对外可见）且悬赏已冻结托管（frozen_amount 增加、balance 不动，
 *       可用 = balance − frozen）——无「发布成功但钱没冻」；</li>
 *   <li>余额不足 ⇒ 支付失败，整事务回滚：订单留待支付、无冻结流水、余额不动；</li>
 *   <li>同凭证幂等重放 ⇒ 返回与首次相同结果，不重复冻结。</li>
 * </ul>
 * 失败注入点在端口委托（钱包动作）边界：直接把可用余额压到悬赏之下，
 * 让冻结的可用余额条件 UPDATE 自然失配（不 mock 任何内部 Bean）。
 */
class ErrandPayFundConsistencyIntegrationTest extends CommunityApiTestBase {

    /** 与用例一致的悬赏面额（≥10 下限之上取整好算的值） */
    private static final BigDecimal REWARD = new BigDecimal("10");
    /** 注册即建钱包的初始余额（WalletRepository.INITIAL_BALANCE 同口径） */
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("1000");

    @Test
    void 支付成功_订单已发布且悬赏已冻结托管() {
        Session author = registerAndLogin("13800007101", "支付作者");
        String orderId = createPendingOrder(author);

        assertSuccess(postJson("/api/errand/orders/" + orderId + "/pay", null, author), "支付");

        // 状态翻转：订单进大厅对外可见
        assertThat(statusOf(orderId)).as("支付成功应转已发布").isEqualTo("PUBLISHED");
        // 悬赏冻结托管：FROZEN 流水恰一条（金额 = 悬赏）；冻结只加 frozen_amount，
        // balance 不动（可用 = balance − frozen，WalletMapper.updateFreeze 口径）
        assertThat(frozenFlowCountOf(orderId)).as("支付成功应有一条悬赏冻结流水").isEqualTo(1);
        assertThat(frozenFlowAmountOf(orderId)).as("冻结流水金额应为悬赏").isEqualByComparingTo(REWARD);
        assertThat(balanceOf(author.userId())).as("balance 不应变化（冻结只加 frozen_amount）")
                .isEqualByComparingTo(INITIAL_BALANCE);
        assertThat(frozenAmountOf(author.userId())).as("冻结额应增加悬赏").isEqualByComparingTo(REWARD);
    }

    @Test
    void 余额不足_支付失败订单留待支付且无冻结流水() {
        Session author = registerAndLogin("13800007102", "余额不足作者");
        String orderId = createPendingOrder(author);

        // 失败注入（钱包动作边界）：可用余额压到悬赏之下，冻结的条件扣减必失配
        jdbcTemplate.update("UPDATE virtual_wallet SET balance = 5 WHERE user_id = ?", author.userId());

        JsonNode payResponse = postJson("/api/errand/orders/" + orderId + "/pay", null, author);
        assertThat(payResponse.path("code").asText()).as("余额不足支付应失败：%s", payResponse).isNotEqualTo("200");

        // 整事务回滚：订单不翻转、无冻结流水、钱包不动、不对外可见
        assertThat(statusOf(orderId)).as("支付失败订单应留在待支付").isEqualTo("PENDING_PAYMENT");
        assertThat(frozenFlowCountOf(orderId)).as("回滚后不应有冻结流水").isEqualTo(0);
        assertThat(balanceOf(author.userId())).as("失败支付不应扣余额").isEqualByComparingTo("5");
        assertThat(frozenAmountOf(author.userId())).as("失败支付不应产生冻结额").isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(listPublicOrderIds()).as("待支付订单不应对外可见").doesNotContain(orderId);
    }

    @Test
    void 幂等重放_返回原结果且不重复冻结() {
        Session author = registerAndLogin("13800007103", "重放作者");
        String orderId = createPendingOrder(author);

        JsonNode firstPayResponse = postJson("/api/errand/orders/" + orderId + "/pay", null, author);
        assertSuccess(firstPayResponse, "首次支付");
        BigDecimal frozenAfterFirstPay = frozenAmountOf(author.userId());

        // 同一凭证（同订单同发布方）重试：PUBLISHED 短路重放，返回与首次完全相同的结果
        //（支付端点回 Response.ok(null)，重放必须拿到同样的成功响应而非错误）
        JsonNode replayResponse = postJson("/api/errand/orders/" + orderId + "/pay", null, author);
        assertSuccess(replayResponse, "重放支付");
        assertThat(replayResponse).as("重放响应应与首次支付响应完全一致：%s", replayResponse)
                .isEqualTo(firstPayResponse);

        assertThat(frozenFlowCountOf(orderId)).as("重放不得重复冻结（uk_order_type 幂等）").isEqualTo(1);
        assertThat(balanceOf(author.userId())).as("重放不得重复扣款").isEqualByComparingTo(INITIAL_BALANCE);
        assertThat(frozenAmountOf(author.userId())).as("重放不得重复累加冻结额")
                .isEqualByComparingTo(frozenAfterFirstPay);
        assertThat(statusOf(orderId)).as("重放后订单仍已发布").isEqualTo("PUBLISHED");
    }

    // ---------- 工具 ----------

    /** 发布待支付订单（reward = 10，deadline 2 小时后），返回 orderId */
    private String createPendingOrder(Session author) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "帮忙取个快递");
        body.put("type", "EXPRESS_PICKUP");
        body.put("description", "资金收口测试订单");
        body.put("pickupLocation", "菜鸟驿站");
        body.put("deliveryLocation", "6号楼楼下");
        body.put("reward", REWARD.intValue());
        body.put("deadline", LocalDateTime.now().plusHours(2).toString());
        JsonNode createResponse = postJson("/api/errand/orders", body, author);
        assertSuccess(createResponse, "发布跑腿");
        return createResponse.path("data").asText();
    }

    /** 公开列表当前可见的订单 id 集（GET /api/errand/orders 大厅口径，供可见性断言用） */
    private List<String> listPublicOrderIds() {
        JsonNode data = getJson("/api/errand/orders?status=PUBLISHED", null).path("data");
        List<String> orderIds = new ArrayList<>();
        for (JsonNode order : data.path("orders")) {
            orderIds.add(order.path("orderId").asText());
        }
        return orderIds;
    }

    private String statusOf(String orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM errand_order WHERE errand_order_id = ?", String.class, orderId);
    }

    private int frozenFlowCountOf(String orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_detail WHERE order_id = ? AND type = 'FROZEN'",
                Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private BigDecimal frozenFlowAmountOf(String orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT amount FROM wallet_detail WHERE order_id = ? AND type = 'FROZEN' LIMIT 1",
                BigDecimal.class, orderId);
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
