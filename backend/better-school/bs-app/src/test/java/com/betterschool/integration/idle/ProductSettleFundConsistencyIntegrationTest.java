package com.betterschool.integration.idle;

import com.betterschool.integration.support.PostApiTestBase;
import com.betterschool.types.common.Constants;
import com.betterschool.types.exception.BusinessException;
import com.betterschool.usecase.idle.IdleCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 商品结算资金链路集成测试（平台代收口径新增的最大行为）：买家确认收货后，钱由平台代收账户
 * 出账、结算进卖家余额——平台 {@code PLATFORM_OUT} + 卖家 {@code PRODUCT_INCOME} 双边流水与
 * 双边余额在钱包单仓储事务内完成（{@code uk_order_type} 幂等），买家余额在支付时已扣、此步不再变化。
 * 主缝 = 商品订单用例的交付 / 确认收货 {@link IdleCase#markDelivered} / {@link IdleCase#confirmReceived}；
 * 只断言外部可观察状态（订单状态 / 买家、平台、卖家三方余额 / 流水行），不断言事务内部顺序与调用次数。
 * <p>
 * 三个外显方向：正常结算（卖家 1000 + 39.80 = 1039.80、平台 39.80 − 39.80 = 0）、
 * 重复确认幂等（不重复入账、不重复出账）、已取消订单永不结算（确认收货被状态守卫拒绝，
 * 卖家未入账）。结算与退款共用平台的 {@code PLATFORM_OUT}，同一订单只能走一条终态资金路，
 * 所以本类不构造「既结算又退款」的同单场景——那会被平台流水唯一键判为状态不一致并整笔回滚。
 */
class ProductSettleFundConsistencyIntegrationTest extends PostApiTestBase {

    @Autowired
    private IdleCase idleCase;

    /** 注册赠送初始余额（WalletRepository 注册路径口径）——买家 / 卖家 / 平台余额基线的共同起点 */
    private static final String INITIAL_BALANCE = "1000";

    // ---------- 正常结算：确认收货 → 平台出账 + 卖家入账，订单完成 ----------

    @Test
    void 确认收货_平台结算卖家入账订单完成() {
        Session seller = registerAndLogin("13800012001", "结算卖家A");
        Session buyer = registerAndLogin("13800012002", "结算买家A");
        String productId = createProduct(seller, "九成新台灯", "19.90");
        String orderId = createProductOrder(buyer, productId, 2);
        idleCase.payOrder(orderId, buyer.userId());
        // 前置：支付已扣买家、平台已代收；卖家尚未入账
        assertThat(balanceOf(buyer.userId())).as("前置：支付已扣买家余额").isEqualByComparingTo("960.20");
        assertThat(platformBalance()).as("前置：平台已代收货款").isEqualByComparingTo("39.80");
        assertThat(balanceOf(seller.userId())).as("前置：卖家尚未收到货款").isEqualByComparingTo(INITIAL_BALANCE);

        idleCase.markDelivered(orderId, seller.userId());
        idleCase.confirmReceived(orderId, buyer.userId());

        // 外显一：订单完成（终态）
        assertThat(orderState(orderId)).as("确认收货后订单完成").isEqualTo("COMPLETED");
        // 外显二：结算恰一对流水（卖家入账 + 平台出账，金额 = 订单总额）
        assertThat(flowAmountOf(orderId, "PRODUCT_INCOME")).as("卖家结算入账金额 = 单价 × 件数")
                .isNotNull().isEqualByComparingTo("39.80");
        assertThat(flowAmountOf(orderId, "PLATFORM_OUT")).as("平台出账金额 = 订单总额")
                .isNotNull().isEqualByComparingTo("39.80");
        // 支付那两条流水不因结算消失：整单恰四条，且结算没有重复落账
        assertThat(flowTypesOf(orderId)).as("整单恰四条流水：支付一对 + 结算一对")
                .hasSize(4)
                .containsExactlyInAnyOrder("PRODUCT_PAY", "PLATFORM_IN", "PRODUCT_INCOME", "PLATFORM_OUT");
        // 外显三：钱从平台代收账户进卖家余额；买家余额此步不再变化
        assertThat(balanceOf(seller.userId())).as("卖家余额收到货款（1000 + 39.80）")
                .isEqualByComparingTo("1039.80");
        assertThat(balanceOf(buyer.userId())).as("确认收货不再动买家余额").isEqualByComparingTo("960.20");
        assertThat(frozenAmountOf(buyer.userId())).as("frozen_amount 已退役，全程保持 0")
                .isEqualByComparingTo("0");
        assertThat(platformBalance()).as("平台出账后余额归零").isEqualByComparingTo("0");
    }

    // ---------- 重复确认：终态短路同结果，无重复结算 ----------

    @Test
    void 重复确认_幂等不重复结算() {
        Session seller = registerAndLogin("13800012003", "重复卖家B");
        Session buyer = registerAndLogin("13800012004", "重复买家B");
        String productId = createProduct(seller, "九成新吉他", "19.90");
        String orderId = createProductOrder(buyer, productId, 2);
        idleCase.payOrder(orderId, buyer.userId());
        idleCase.markDelivered(orderId, seller.userId());
        idleCase.confirmReceived(orderId, buyer.userId());

        // 重放：已完成是终态，域服务短路返回原结果，钱包结算按卖家入账流水唯一键幂等吸收
        idleCase.confirmReceived(orderId, buyer.userId());

        assertThat(orderState(orderId)).as("重放后订单保持已完成").isEqualTo("COMPLETED");
        assertThat(flowTypesOf(orderId)).as("重放不得重复结算（整单仍四条流水）")
                .hasSize(4)
                .containsExactlyInAnyOrder("PRODUCT_PAY", "PLATFORM_IN", "PRODUCT_INCOME", "PLATFORM_OUT");
        assertThat(balanceOf(seller.userId())).as("卖家只能入账一次").isEqualByComparingTo("1039.80");
        assertThat(platformBalance()).as("平台余额不得被重复出账").isEqualByComparingTo("0");
        assertThat(balanceOf(buyer.userId())).as("买家余额不受重复确认影响").isEqualByComparingTo("960.20");
    }

    // ---------- 已取消订单：永不结算 ----------

    @Test
    void 已取消订单_永不结算() {
        Session seller = registerAndLogin("13800012005", "取消结算卖家C");
        Session buyer = registerAndLogin("13800012006", "取消结算买家C");
        String productId = createProduct(seller, "九成新水壶", "19.90");
        String orderId = createProductOrder(buyer, productId, 2);
        idleCase.payOrder(orderId, buyer.userId());
        idleCase.cancelOrder(orderId, buyer.userId());
        // 前置：取消已把货款由平台退回买家，平台代收归零
        assertThat(balanceOf(buyer.userId())).as("前置：取消已退款给买家").isEqualByComparingTo(INITIAL_BALANCE);
        assertThat(platformBalance()).as("前置：平台退款后余额归零").isEqualByComparingTo("0");

        // 已取消订单不是 DELIVERED，确认收货被状态守卫拒绝（失败发生在钱包动作之前）
        assertThatThrownBy(() -> idleCase.confirmReceived(orderId, buyer.userId()))
                .as("已取消订单不可确认收货").isInstanceOf(BusinessException.class);

        assertThat(orderState(orderId)).as("订单保持已取消").isEqualTo("CANCELLED");
        assertThat(flowAmountOf(orderId, "PRODUCT_INCOME")).as("取消订单永不结算：无卖家入账流水").isNull();
        assertThat(flowTypesOf(orderId)).as("整单只有支付与退款两对流水，无结算腿")
                .hasSize(4)
                .containsExactlyInAnyOrder("PRODUCT_PAY", "PLATFORM_IN", "PRODUCT_REFUND", "PLATFORM_OUT");
        assertThat(balanceOf(seller.userId())).as("卖家未收到货款").isEqualByComparingTo(INITIAL_BALANCE);
        assertThat(platformBalance()).as("平台余额仍为 0（退款已出账，无二次出账）").isEqualByComparingTo("0");
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

    /**
     * 平台代收余额：平台账户是懒创建的，行可能还不存在，
     * 缺失按 0 处理（queryForObject 会抛 EmptyResultDataAccessException）。
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
}
