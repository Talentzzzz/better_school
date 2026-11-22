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

    /**
     * 支付确认准备（两段式 ADR-0016 第一段，纯校验无副作用）：买家校验 + 幂等判定
     * （已越过待支付视为上次支付已提交成功，alreadyPaid=true 零副作用），返回需冻结金额（单价 × 件数）；
     * 资金冻结由 case 层在 prepare 与 confirm 之间顺序执行
     */
    ProductPayResult preparePayOrder(String orderId, String buyerId);

    /**
     * 支付确认翻转（两段式 ADR-0016 第二段）：「待支付 → 已支付」条件翻转（守卫买家 + 前态，
     * PAID 事件随行落表，单仓储事务）；翻转未命中抛业务异常——此刻货款可能已冻结
     * （订单留待支付的长款中间态），孤儿由 {@code productFundOrphanScan} 扫描解冻收敛
     */
    void confirmPayOrder(String orderId, String buyerId);

    /** 卖家标记交付：PAID → DELIVERED */
    void markDelivered(String orderId, String sellerId);

    /** 买家确认收货：DELIVERED → COMPLETED（域内事务），返回解冻/入账需求（单价 × 件数） */
    ProductConfirmResult confirmReceived(String orderId, String buyerId);

    /**
     * 买家取消（两段式 ADR-0016 翻转段先行）：PENDING_PAYMENT/PAID → CANCELLED + 库存台账回补 +
     * 搜索同步（单仓储事务，未命中抛异常零残留——资金段尚未执行）；返回退款需求（单价 × 件数），
     * 解冻退款（needRefund=true 时）由 case 层随后执行
     */
    ProductCancelResult cancelOrder(String orderId, String buyerId);

    ProductOrderEntity orderDetail(String orderId, String currentUserId);

    List<ProductOrderEntity> listMineOrders(String role, String userId, int page, int pageSize);

    long countMineOrders(String role, String userId);

    /** 待支付超时（超时中心回调触发）：PENDING_PAYMENT → CANCELLED + 商品回 ON_SALE（幂等忽略） */
    void handlePayTimeout(String orderId);
}
