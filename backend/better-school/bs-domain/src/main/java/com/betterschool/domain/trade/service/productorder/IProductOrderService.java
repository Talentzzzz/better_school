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
     * 下单：自购校验 → 生成 PENDING_PAYMENT 订单（含件数）+ 库存台账条件 UPDATE 扣减
     * （remaining >= quantity，0 行即库存不足回滚；扣到 0 售罄随行翻 SOLD）+ 超时注册（域内事务；
     * 信用分门槛由 case 层前置校验）
     */
    String createOrder(String buyerId, String productId, int quantity);

    /** 支付确认：PENDING_PAYMENT → PAID（幂等：已支付静默返回），返回需冻结金额（单价 × 件数） */
    ProductPayResult payOrder(String orderId, String buyerId);

    /** 卖家标记交付：PAID → DELIVERED */
    void markDelivered(String orderId, String sellerId);

    /** 买家确认收货：DELIVERED → COMPLETED（域内事务），返回解冻/入账需求（单价 × 件数） */
    ProductConfirmResult confirmReceived(String orderId, String buyerId);

    /** 买家取消：PENDING_PAYMENT/PAID → CANCELLED + 库存台账回补（域内事务），返回退款需求（单价 × 件数） */
    ProductCancelResult cancelOrder(String orderId, String buyerId);

    ProductOrderEntity orderDetail(String orderId, String currentUserId);

    List<ProductOrderEntity> listMineOrders(String role, String userId, int page, int pageSize);

    long countMineOrders(String role, String userId);

    /** 待支付超时（超时中心回调触发）：PENDING_PAYMENT → CANCELLED + 商品回 ON_SALE（幂等忽略） */
    void handlePayTimeout(String orderId);
}
