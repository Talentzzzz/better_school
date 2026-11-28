package com.betterschool.integration.idle;

import com.betterschool.integration.support.PostApiTestBase;
import com.betterschool.types.common.Constants;
import com.betterschool.usecase.idle.IdleCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商品取消资金链路集成测试（平台代收口径，ADR-0016 两段化）：在主缝（商品订单用例层
 * 公开取消方法 {@link IdleCase#cancelOrder}）上断言外显行为——取消翻转段（含台账回补 + 搜索同步，
 * 单仓储事务）先行，平台退款（平台 {@code PLATFORM_OUT} + 买家 {@code PRODUCT_REFUND} 双边流水，
 * {@code uk_order_type} 幂等）由 case 层随后顺序执行。只断言外部可观察状态（订单状态 / 三方余额 /
 * 流水行），不断言事务内部顺序与调用次数。
 * <p>
 * 失败注入点说明：平台退款不依赖外部注入，天然的不变量守卫是「平台余额 ≥ 退款额」——
 * 正常取消路径下平台已代收该笔货款，守卫必过；异常路径（平台未收款却要退款）会整笔回滚、
 * 由资金孤儿扫描兜底，不在本类覆盖。本类用「取消成功 ⇒ 钱必由平台退回」与
 * 「幂等重放 ⇒ 不重复退款」两个方向外显覆盖平台退款事务的双边一致性。
 * <p>
 * 待支付订单取消时平台从未收款：无任何资金流水，且平台账户行可能尚不存在——平台余额断言
 * 一律用容忍「行缺失 = 0」的辅助方法。
 */
class ProductCancelFundConsistencyIntegrationTest extends PostApiTestBase {

    @Autowired
    private IdleCase idleCase;

    /** 注册赠送初始余额（WalletRepository 注册路径口径）——资金注入基线 */
    private static final String INITIAL_BALANCE = "1000";

    // ---------- 取消成功：订单已取消 + 平台退款回买家余额（外显三态） ----------

    @Test
    void 已支付订单取消_订单取消且平台退款买家余额复原() {
        Session seller = registerAndLogin("13800011001", "取消卖家A");
        Session buyer = registerAndLogin("13800011002", "取消买家A");
        String productId = createProduct(seller, "九成新台灯", "19.90");
        String orderId = createProductOrder(buyer, productId, 2);
        idleCase.payOrder(orderId, buyer.userId());
        // 前置：支付已让钱离开买家、进平台代收
        assertThat(balanceOf(buyer.userId())).as("前置：支付已扣买家余额").isEqualByComparingTo("960.20");
        assertThat(platformBalance()).as("前置：平台已代收货款").isEqualByComparingTo("39.80");

        idleCase.cancelOrder(orderId, buyer.userId());

        // 外显一：订单取消（终态）
        assertThat(orderState(orderId)).as("取消成功应转已取消").isEqualTo("CANCELLED");
        // 外显二：退款动作恰一对流水（买家退款入账 + 平台出账，金额 = 订单总额）
        assertThat(flowAmountOf(orderId, "PRODUCT_REFUND")).as("买家退款入账金额 = 单价 × 件数")
                .isNotNull().isEqualByComparingTo("39.80");
        assertThat(flowAmountOf(orderId, "PLATFORM_OUT")).as("平台出账金额 = 订单总额")
                .isNotNull().isEqualByComparingTo("39.80");
        // 支付那两条流水不因退款消失：整单恰四条，且退款没有重复落账
        assertThat(flowTypesOf(orderId)).as("整单恰四条流水：支付一对 + 退款一对")
                .hasSize(4)
                .containsExactlyInAnyOrder("PRODUCT_PAY", "PLATFORM_IN", "PRODUCT_REFUND", "PLATFORM_OUT");
        // 外显三：钱由平台退回买家余额，平台代收余额归零
        assertThat(balanceOf(buyer.userId())).as("买家余额回到初始（平台退回货款）")
                .isEqualByComparingTo(INITIAL_BALANCE);
        assertThat(frozenAmountOf(buyer.userId())).as("frozen_amount 已退役，全程保持 0")
                .isEqualByComparingTo("0");
        assertThat(platformBalance()).as("平台代收余额出账后归零").isEqualByComparingTo("0");
    }

    // ---------- 幂等重放：终态短路同结果，无重复退款 ----------

    @Test
    void 已取消订单重放_终态短路同结果不重复退款() {
        Session seller = registerAndLogin("13800011003", "重放卖家B");
        Session buyer = registerAndLogin("13800011004", "重放买家B");
        String productId = createProduct(seller, "九成新吉他", "19.90");
        String orderId = createProductOrder(buyer, productId, 2);
        idleCase.payOrder(orderId, buyer.userId());
        idleCase.cancelOrder(orderId, buyer.userId());

        // 重放：已取消是终态，域服务短路返回 needRefund=false（不抛异常、不再动钱包）
        idleCase.cancelOrder(orderId, buyer.userId());

        assertThat(orderState(orderId)).as("重放后订单保持已取消").isEqualTo("CANCELLED");
        assertThat(flowTypesOf(orderId)).as("重放不得重复退款（整单仍四条流水）")
                .hasSize(4)
                .containsExactlyInAnyOrder("PRODUCT_PAY", "PLATFORM_IN", "PRODUCT_REFUND", "PLATFORM_OUT");
        assertThat(balanceOf(buyer.userId())).as("重放不得重复入账").isEqualByComparingTo(INITIAL_BALANCE);
        assertThat(platformBalance()).as("平台余额不得被重复出账").isEqualByComparingTo("0");
        assertThat(frozenAmountOf(buyer.userId())).as("冻结额仍为零").isEqualByComparingTo("0");
    }

    // ---------- 待支付取消：平台从未收款，无资金动作 ----------

    @Test
    void 待支付订单取消_无流水资金零副作用() {
        Session seller = registerAndLogin("13800011005", "待支付卖家C");
        Session buyer = registerAndLogin("13800011006", "待支付买家C");
        String productId = createProduct(seller, "九成新水杯", "19.90");
        String orderId = createProductOrder(buyer, productId, 1);

        idleCase.cancelOrder(orderId, buyer.userId());

        assertThat(orderState(orderId)).as("待支付取消应转已取消").isEqualTo("CANCELLED");
        assertThat(flowTypesOf(orderId)).as("平台从未收款，取消不产生任何资金流水").isEmpty();
        assertThat(balanceOf(buyer.userId())).as("买家余额不应变化").isEqualByComparingTo(INITIAL_BALANCE);
        assertThat(frozenAmountOf(buyer.userId())).as("冻结额应保持为零").isEqualByComparingTo("0");
        assertThat(platformBalance()).as("平台账户行很可能还没创建，按 0 处理").isEqualByComparingTo("0");
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
     * 平台代收余额：平台账户是懒创建的，待支付取消等用例下可能尚无该行，
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
