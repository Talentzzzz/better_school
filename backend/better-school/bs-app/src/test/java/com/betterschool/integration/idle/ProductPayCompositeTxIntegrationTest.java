package com.betterschool.integration.idle;

import com.betterschool.integration.support.PostApiTestBase;
import com.betterschool.usecase.idle.IdleCase;
import com.betterschool.types.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 商品支付资金链路集成测试（资金一致性票 06；钱包支付渠道化后主缝迁至商品订单用例
 * {@link IdleCase#payOrder}）：
 * 在主缝（订单用例层公开支付方法）上断言支付链路的外显行为——
 * 资金段「货款 PRODUCT_FROZEN 冻结」（流水 uk_order_type 幂等 + 余额条件扣减）与支付单 PAID
 * 在钱包单仓储事务内完成，提交后回调翻转订单「待支付→已支付」；只断言外部可观察状态
 * （订单状态 / 钱包流水 / 余额），不断言段落编排。失败注入点 = 钱包冻结动作边界
 * （余额不足即真实失败注入，支付单留 WAIT_PAY）。只断言外显行为：支付成功三态齐备、
 * 余额不足支付失败订单留待支付、幂等重放无害。
 */
class ProductPayCompositeTxIntegrationTest extends PostApiTestBase {

    @Autowired
    private IdleCase idleCase;

    /** 注册赠送初始余额（WalletRepository.INITIAL_BALANCE）——低余额买家的资金注入基线 */
    private static final String INITIAL_BALANCE = "1000";

    // ---------- 支付成功：订单已支付 + 货款真实冻结（两段式成功路径的外显三态） ----------

    @Test
    void 支付成功_订单转已支付且货款同事务冻结() {
        Session seller = registerAndLogin("13800010001", "卖家A");
        Session buyer = registerAndLogin("13800010002", "买家A");
        String productId = createProduct(seller, "九成新台灯", "19.90");
        String orderId = createProductOrder(buyer, productId, 2);

        idleCase.payOrder(orderId, buyer.userId());

        // 外显一：订单翻转已支付
        assertThat(orderState(orderId)).as("订单翻转已支付").isEqualTo("PAID");
        // 外显二：货款冻结流水诞生（PRODUCT_FROZEN，带订单幂等键）
        Map<String, Object> freezeFlow = freezeFlowRow(orderId);
        assertThat(freezeFlow).as("已支付订单必然带着冻结流水").isNotNull();
        assertThat(((BigDecimal) freezeFlow.get("amount"))).as("冻结金额 = 单价 × 件数")
                .isEqualByComparingTo("39.80");
        // 外显三：可用余额被冻结额占据（balance 扣减、frozen_amount 同额抬升）
        assertThat(balanceOf(buyer.userId())).as("余额扣减货款").isEqualByComparingTo("960.20");
        assertThat(frozenAmountOf(buyer.userId())).as("冻结额抬升货款").isEqualByComparingTo("39.80");
    }

    // ---------- 余额不足：资金段先行失败，翻转段未执行，订单留待支付 ----------

    @Test
    void 余额不足_支付整事务回滚订单留待支付() {
        Session seller = registerAndLogin("13800010003", "卖家B");
        Session buyer = registerAndLogin("13800010004", "买家B");
        // 标价 2000 > 注册初始余额 1000：冻结（资金段钱包动作）必败，即资金段边界的真实失败注入
        String productId = createProduct(seller, "高价古董花瓶", "2000.00");
        String orderId = createProductOrder(buyer, productId, 1);

        assertThatThrownBy(() -> idleCase.payOrder(orderId, buyer.userId()))
                .as("余额不足支付直接失败").isInstanceOf(BusinessException.class);

        // 资金段失败（流水与余额条件扣减随其本地事务回滚），翻转段尚未执行：订单停在待支付，
        // 冻结流水不诞生，余额纹丝不动，PAID 事件也未落
        assertThat(orderState(orderId)).as("订单留待支付").isEqualTo("PENDING_PAYMENT");
        assertThat(freezeFlowCount(orderId)).as("不产生冻结流水").isZero();
        assertThat(balanceOf(buyer.userId())).as("余额不变").isEqualByComparingTo(INITIAL_BALANCE);
        assertThat(frozenAmountOf(buyer.userId())).as("冻结额不变").isEqualByComparingTo("0");
        assertThat(eventTypesOf(orderId)).as("支付事件随事务回滚").doesNotContain("PAID");
    }

    // ---------- 幂等重放：同结果，无重复冻结 ----------

    @Test
    void 幂等重放_同结果无重复冻结() {
        Session seller = registerAndLogin("13800010005", "卖家C");
        Session buyer = registerAndLogin("13800010006", "买家C");
        String productId = createProduct(seller, "九成新吉他", "19.90");
        String orderId = createProductOrder(buyer, productId, 2);

        idleCase.payOrder(orderId, buyer.userId());
        // 重放：与首次同结果（不抛异常、订单保持已支付），uk_order_type 幂等吸收重复
        idleCase.payOrder(orderId, buyer.userId());

        assertThat(orderState(orderId)).as("重放后订单保持已支付").isEqualTo("PAID");
        assertThat(freezeFlowCount(orderId)).as("无重复冻结流水").isEqualTo(1);
        assertThat(balanceOf(buyer.userId())).as("余额只扣一次").isEqualByComparingTo("960.20");
        assertThat(frozenAmountOf(buyer.userId())).as("冻结额只抬一次").isEqualByComparingTo("39.80");
    }

    // ---------- 工具 ----------

    private String createProduct(Session seller, String title, String price) {
        var response = postJson("/api/idle/products", Map.ofEntries(
                Map.entry("title", title),
                Map.entry("description", "自用闲置，九成新"),
                Map.entry("images", List.of()),
                Map.entry("price", new BigDecimal(price)),
                Map.entry("tradeLocation", "图书馆门口"),
                Map.entry("category", "BOOKS")), seller);
        assertSuccess(response, "发布商品");
        return response.path("data").asText();
    }

    private String createProductOrder(Session buyer, String productId, int quantity) {
        var response = postJson("/api/idle/orders?productId=" + productId + "&quantity=" + quantity,
                null, buyer);
        assertSuccess(response, "商品下单");
        return response.path("data").asText();
    }

    private String orderState(String orderId) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM product_order WHERE product_order_id = ?", String.class, orderId);
    }

    private BigDecimal balanceOf(String userId) {
        return jdbcTemplate.queryForObject(
                "SELECT balance FROM virtual_wallet WHERE user_id = ?", BigDecimal.class, userId);
    }

    private BigDecimal frozenAmountOf(String userId) {
        return jdbcTemplate.queryForObject(
                "SELECT frozen_amount FROM virtual_wallet WHERE user_id = ?", BigDecimal.class, userId);
    }

    /** 该订单的货款冻结流水行数（uk_order_type 幂等的可观察事实源） */
    private int freezeFlowCount(String orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_detail WHERE order_id = ? AND type = 'PRODUCT_FROZEN'",
                Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private Map<String, Object> freezeFlowRow(String orderId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT amount, biz_round FROM wallet_detail WHERE order_id = ? AND type = 'PRODUCT_FROZEN'", orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<String> eventTypesOf(String orderId) {
        return jdbcTemplate.queryForList(
                "SELECT event_type FROM product_order_event WHERE order_id = ? ORDER BY id", String.class, orderId);
    }
}
