package com.betterschool.integration.idle;

import com.betterschool.integration.support.CommunityApiTestBase;
import com.betterschool.usecase.idle.ProductOrderCase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商品取消资金链路集成测试（资金一致性票 07，ADR-0016 两段化后口径同步）：在主缝（商品订单用例层
 * 公开取消方法 {@link ProductOrderCase#cancelOrder}）上断言两段式资金链路的外显行为——取消翻转段
 * （含台账回补 + 搜索同步，单仓储事务）先行，买家解冻退款 PRODUCT_UNFROZEN（uk_order_type 幂等）
 * 由 case 层随后执行；只断言外部可观察状态（订单状态 / 钱包流水 / 冻结额 / 可用余额），
 * 不断言段落编排。
 * <p>
 * 失败注入点说明（照票 03 维护者备注同缝不同向模式）：解冻动作（updateUnfreeze）无
 * 「0 行失败」的天然条件守卫可注入，故钱包侧原子性由「取消成功 ⇒ 钱必到位」与
 * 「幂等重放 ⇒ 不重复退款」两个方向外显覆盖；翻转未命中（状态已变化）抛业务异常、
 * 资金段尚未执行零残留，由条件 UPDATE 守卫保证，与票 06 支付的余额不足注入同缝不同向。
 */
class ProductCancelFundConsistencyIntegrationTest extends CommunityApiTestBase {

    @Autowired
    private ProductOrderCase productOrderCase;

    /** 注册赠送初始余额（WalletRepository.INITIAL_BALANCE）——资金注入基线 */
    private static final String INITIAL_BALANCE = "1000";

    // ---------- 取消成功：订单已取消 + 货款解冻回可用余额（两段式成功路径的外显三态） ----------

    @Test
    void 已支付订单取消成功_订单取消且解冻退款回可用余额无悬置冻结() {
        Session seller = registerAndLogin("13800011001", "取消卖家A");
        Session buyer = registerAndLogin("13800011002", "取消买家A");
        String productId = createProduct(seller, "九成新台灯", "19.90");
        String orderId = createProductOrder(buyer, productId, 2);
        productOrderCase.payOrder(orderId, buyer.userId());
        assertThat(frozenAmountOf(buyer.userId())).as("前置：支付后货款已冻结").isEqualByComparingTo("39.80");

        productOrderCase.cancelOrder(orderId, buyer.userId());

        // 外显一：订单取消（终态）
        assertThat(orderState(orderId)).as("取消成功应转已取消").isEqualTo("CANCELLED");
        // 外显二：解冻退款流水恰一条（PRODUCT_UNFROZEN，uk_order_type 幂等的物证）
        Map<String, Object> unfreezeFlow = unfreezeFlowRow(orderId);
        assertThat(unfreezeFlow).as("已支付订单取消必然带着解冻流水").isNotNull();
        assertThat(((BigDecimal) unfreezeFlow.get("amount"))).as("解冻金额 = 冻结口径同值（单价 × 件数）")
                .isEqualByComparingTo("39.80");
        // 外显三：货款回到可用余额——冻结额归零，balance 列全程不动（freeze/unfreeze 只在
        // frozen_amount 与可用余额之间移动资金），无悬置冻结
        assertThat(frozenAmountOf(buyer.userId())).as("取消后冻结额应归零").isEqualByComparingTo("0");
        assertThat(balanceOf(buyer.userId())).as("可用余额应全额恢复（balance - frozen 回到初始）")
                .isEqualByComparingTo(INITIAL_BALANCE);
    }

    // ---------- 幂等重放：终态短路同结果，无重复退款 ----------

    @Test
    void 已取消订单重放_终态短路同结果不重复退款() {
        Session seller = registerAndLogin("13800011003", "重放卖家B");
        Session buyer = registerAndLogin("13800011004", "重放买家B");
        String productId = createProduct(seller, "九成新吉他", "19.90");
        String orderId = createProductOrder(buyer, productId, 2);
        productOrderCase.payOrder(orderId, buyer.userId());
        productOrderCase.cancelOrder(orderId, buyer.userId());

        // 重放：终态短路返回原结果（不抛异常、订单保持已取消），uk_order_type 幂等吸收重复
        productOrderCase.cancelOrder(orderId, buyer.userId());

        assertThat(orderState(orderId)).as("重放后订单保持已取消").isEqualTo("CANCELLED");
        assertThat(unfreezeFlowCount(orderId)).as("重放不得重复退款（uk_order_type 幂等）").isEqualTo(1);
        assertThat(frozenAmountOf(buyer.userId())).as("重放后冻结额仍应归零").isEqualByComparingTo("0");
        assertThat(balanceOf(buyer.userId())).as("重放不得重复入账").isEqualByComparingTo(INITIAL_BALANCE);
    }

    // ---------- 待支付取消：未冻结无资金动作，单事务翻转 ----------

    @Test
    void 待支付订单取消_无解冻流水资金零副作用() {
        Session seller = registerAndLogin("13800011005", "待支付卖家C");
        Session buyer = registerAndLogin("13800011006", "待支付买家C");
        String productId = createProduct(seller, "九成新水杯", "19.90");
        String orderId = createProductOrder(buyer, productId, 1);

        productOrderCase.cancelOrder(orderId, buyer.userId());

        assertThat(orderState(orderId)).as("待支付取消应转已取消").isEqualTo("CANCELLED");
        assertThat(unfreezeFlowCount(orderId)).as("从未冻结不应产生解冻流水").isEqualTo(0);
        assertThat(frozenAmountOf(buyer.userId())).as("冻结额应保持为零").isEqualByComparingTo("0");
        assertThat(balanceOf(buyer.userId())).as("可用余额不应变化").isEqualByComparingTo(INITIAL_BALANCE);
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

    /** 该订单的解冻退款流水行数（uk_order_type 幂等的可观察事实源） */
    private int unfreezeFlowCount(String orderId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM wallet_detail WHERE order_id = ? AND type = 'PRODUCT_UNFROZEN'",
                Integer.class, orderId);
        return count == null ? 0 : count;
    }

    private Map<String, Object> unfreezeFlowRow(String orderId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT amount, biz_round FROM wallet_detail WHERE order_id = ? AND type = 'PRODUCT_UNFROZEN'", orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
