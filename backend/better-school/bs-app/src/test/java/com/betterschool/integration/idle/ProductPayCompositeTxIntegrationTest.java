package com.betterschool.integration.idle;

import com.betterschool.integration.support.PostApiTestBase;
import com.betterschool.types.exception.BusinessException;
import com.betterschool.types.wallet.Constants;
import com.betterschool.usecase.idle.IdleCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 商品支付资金链路集成测试（平台代收口径；主缝 = 商品订单用例 {@link IdleCase#payOrder}）：
 * 支付链路的外显行为是「买家余额出账 + 平台代收入账」——买家 {@code PRODUCT_PAY} 与平台
 * {@code PLATFORM_IN} 双边流水、双边余额在钱包单仓储事务内完成（{@code uk_order_type} 幂等），
 * 提交后进程内回调把订单「待支付 → 已支付」；本类只断言外部可观察状态（订单状态 / 买家与平台余额 /
 * 流水行），不断言事务内部顺序与调用次数。
 * <p>
 * 失败注入点用天然条件「买家可用余额不足」（下单 2000 > 注册赠送 1000）：钱包事务整笔回滚，
 * 支付单留 WAIT_PAY、订单留待支付，双边流水与平台懒创建行一起消失——所以平台余额断言必须容忍
 * 「平台账户行不存在 = 0」，不能用 {@code queryForObject} 直取。
 */
class ProductPayCompositeTxIntegrationTest extends PostApiTestBase {

    @Autowired
    private IdleCase idleCase;

    /** 注册赠送初始余额（WalletRepository 注册路径口径）——低余额买家的资金注入基线 */
    private static final String INITIAL_BALANCE = "1000";

    // ---------- 支付成功：订单已支付 + 买家出账 / 平台代收双边落账（外显三态） ----------

    @Test
    void 支付成功_订单转已支付且买家出账平台入账() {
        Session seller = registerAndLogin("13800010001", "卖家A");
        Session buyer = registerAndLogin("13800010002", "买家A");
        String productId = createProduct(seller, "九成新台灯", "19.90");
        String orderId = createProductOrder(buyer, productId, 2);

        idleCase.payOrder(orderId, buyer.userId());

        // 外显一：订单翻转已支付
        assertThat(orderState(orderId)).as("订单翻转已支付").isEqualTo("PAID");
        // 外显二：双边流水恰两行（买家出账 + 平台代收，同一 order_id；uk_order_type 幂等的物证）
        assertThat(flowTypesOf(orderId)).as("支付只落买家出账 + 平台代收两行流水")
                .hasSize(2)
                .containsExactlyInAnyOrder("PRODUCT_PAY", "PLATFORM_IN");
        assertThat(flowAmountOf(orderId, "PRODUCT_PAY")).as("买家出账金额 = 单价 × 件数")
                .isNotNull().isEqualByComparingTo("39.80");
        assertThat(flowAmountOf(orderId, "PLATFORM_IN")).as("平台代收金额 = 订单总额")
                .isNotNull().isEqualByComparingTo("39.80");
        // 外显三：钱真的从买家余额离开、进平台代收账户；frozen_amount 列已退役，全程为 0
        assertThat(balanceOf(buyer.userId())).as("买家余额扣减货款").isEqualByComparingTo("960.20");
        assertThat(frozenAmountOf(buyer.userId())).as("支付不再写冻结额：frozen_amount 保持 0")
                .isEqualByComparingTo("0");
        assertThat(platformBalance()).as("平台代收账户收到货款").isEqualByComparingTo("39.80");
    }

    // ---------- 余额不足：钱包事务整笔回滚，翻转段未执行，订单留待支付 ----------

    @Test
    void 余额不足_支付整事务回滚订单留待支付() {
        Session seller = registerAndLogin("13800010003", "卖家B");
        Session buyer = registerAndLogin("13800010004", "买家B");
        // 标价 2000 > 注册初始余额 1000：买家出账守卫必败，这是钱包动作边界的真实失败注入
        String productId = createProduct(seller, "高价古董花瓶", "2000.00");
        String orderId = createProductOrder(buyer, productId, 1);

        assertThatThrownBy(() -> idleCase.payOrder(orderId, buyer.userId()))
                .as("余额不足支付直接失败").isInstanceOf(BusinessException.class);

        // 钱包事务整体回滚：双边流水与平台懒创建行都不留痕，订单翻转段也未执行
        assertThat(orderState(orderId)).as("订单留待支付").isEqualTo("PENDING_PAYMENT");
        assertThat(flowTypesOf(orderId)).as("失败支付不产生任何资金流水").isEmpty();
        assertThat(balanceOf(buyer.userId())).as("买家余额纹丝不动").isEqualByComparingTo(INITIAL_BALANCE);
        assertThat(frozenAmountOf(buyer.userId())).as("冻结额保持零").isEqualByComparingTo("0");
        assertThat(platformBalance()).as("平台账户行随事务回滚，按 0 处理").isEqualByComparingTo("0");
        assertThat(eventTypesOf(orderId)).as("支付事件随事务回滚").doesNotContain("PAID");
    }

    // ---------- 幂等重放：同结果，无重复出账 ----------

    @Test
    void 幂等重放_同结果无重复出账() {
        Session seller = registerAndLogin("13800010005", "卖家C");
        Session buyer = registerAndLogin("13800010006", "买家C");
        String productId = createProduct(seller, "九成新吉他", "19.90");
        String orderId = createProductOrder(buyer, productId, 2);

        idleCase.payOrder(orderId, buyer.userId());
        // 重放：订单已 PAID，支付入口幂等短路（不抛异常、不再动钱包），流水唯一键兜底
        idleCase.payOrder(orderId, buyer.userId());

        assertThat(orderState(orderId)).as("重放后订单保持已支付").isEqualTo("PAID");
        assertThat(flowTypesOf(orderId)).as("无重复出账流水")
                .hasSize(2)
                .containsExactlyInAnyOrder("PRODUCT_PAY", "PLATFORM_IN");
        assertThat(balanceOf(buyer.userId())).as("买家余额只扣一次").isEqualByComparingTo("960.20");
        assertThat(platformBalance()).as("平台代收只加一次").isEqualByComparingTo("39.80");
        assertThat(frozenAmountOf(buyer.userId())).as("冻结额始终为零").isEqualByComparingTo("0");
    }

    // ---------- 工具 ----------

    private String createProduct(Session seller, String title, String price) {
        var response = postJson("/api/idle/products", Map.ofEntries(
                Map.entry("title", title),
                Map.entry("description", "自用闲置，九成新"),
                Map.entry("images", List.of()),
                Map.entry("price", new BigDecimal(price)),
                Map.entry("tradeLocation", "图书馆门口"),
                Map.entry("category", "BOOKS"),
                // 发布即落台账（quantity 缺省 = 1）；用例下单 quantity=2，故夹具必须挂 2 件
                Map.entry("quantity", 2)), seller);
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

    /**
     * 平台代收余额：平台账户是懒创建的，行可能还不存在（如余额不足支付整笔回滚后），
     * 此时按 0 处理——直取 queryForObject 会抛 EmptyResultDataAccessException。
     */
    private BigDecimal platformBalance() {
        List<BigDecimal> balances = jdbcTemplate.queryForList(
                "SELECT balance FROM virtual_wallet WHERE user_id = ?", BigDecimal.class, Constants.PLATFORM_USER_ID);
        return balances.isEmpty() ? BigDecimal.ZERO : balances.get(0);
    }

    /** 该订单的全部流水类型（每行一条，按落表顺序；行数 = 是否有重复记账的物证） */
    private List<String> flowTypesOf(String orderId) {
        return jdbcTemplate.queryForList(
                "SELECT type FROM wallet_detail WHERE order_id = ? ORDER BY id", String.class, orderId);
    }

    /** 该订单指定类型的流水金额；没有该类型行返回 null（断言「不该有」时用） */
    private BigDecimal flowAmountOf(String orderId, String type) {
        List<BigDecimal> amounts = jdbcTemplate.queryForList(
                "SELECT amount FROM wallet_detail WHERE order_id = ? AND type = ?", BigDecimal.class, orderId, type);
        return amounts.isEmpty() ? null : amounts.get(0);
    }

    private List<String> eventTypesOf(String orderId) {
        return jdbcTemplate.queryForList(
                "SELECT event_type FROM product_order_event WHERE order_id = ? ORDER BY id", String.class, orderId);
    }
}
