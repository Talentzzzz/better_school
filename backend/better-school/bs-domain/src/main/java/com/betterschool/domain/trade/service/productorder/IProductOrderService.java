package com.betterschool.domain.trade.service.productorder;

import com.betterschool.domain.trade.model.entity.ProductOrderEntity;
import com.betterschool.domain.trade.model.valobj.ProductCancelResult;
import com.betterschool.domain.trade.model.valobj.ProductConfirmResult;
import com.betterschool.domain.trade.model.valobj.ProductPayResult;

import java.util.List;

/**
 * 商品托管订单服务：域内只做订单状态机与商品锁（同域仓储），跨域动作（钱包冻结/解冻/入账）通过结果对象返回，
 * 由 case 层编排执行（domain 不依赖其他域 service）
 */
public interface IProductOrderService {

    /**
     * 下单：自购校验 → 生成 PENDING_PAYMENT 订单 + 商品 ON_SALE→LOCKED + 落 15min 延迟消息（域内事务；
     * 信用分门槛由 case 层前置校验）
     */
    Long createOrder(String buyerId, Long productId);

    /** 支付确认：PENDING_PAYMENT → PAID（幂等：已支付静默返回），返回冻结金额 */
    ProductPayResult payOrder(Long orderId, String buyerId);

    /** 卖家标记交付：PAID → DELIVERED */
    void markDelivered(Long orderId, String sellerId);

    /** 买家确认收货：DELIVERED → COMPLETED + 商品 LOCKED→SOLD（域内事务），返回解冻/入账需求 */
    ProductConfirmResult confirmReceived(Long orderId, String buyerId);

    /** 买家取消：PENDING_PAYMENT/PAID → CANCELLED + 商品 LOCKED→ON_SALE（域内事务），返回退款需求 */
    ProductCancelResult cancelOrder(Long orderId, String buyerId);

    ProductOrderEntity orderDetail(Long orderId, String currentUserId);

    List<ProductOrderEntity> listMineOrders(String role, String userId, int page, int pageSize);

    long countMineOrders(String role, String userId);

    /** 待支付超时（超时中心回调触发）：PENDING_PAYMENT → CANCELLED + 商品回 ON_SALE（幂等忽略） */
    void handlePayTimeout(Long orderId);
}
