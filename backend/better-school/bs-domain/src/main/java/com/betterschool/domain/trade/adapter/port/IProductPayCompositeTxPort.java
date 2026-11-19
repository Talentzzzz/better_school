package com.betterschool.domain.trade.adapter.port;

import com.betterschool.domain.trade.model.valobj.ProductPayResult;

import java.math.BigDecimal;

/**
 * 商品支付组合事务端口（资金一致性票 06）：把「订单『待支付 → 已支付』条件翻转」与
 * 「货款 PRODUCT_FROZEN 冻结（流水先行 + 余额条件扣减，uk_order_type 幂等）」收进一个
 * REQUIRED 物理事务——「订单已支付 ⇒ 货款已真实冻结」由这一个物理事务保证，
 * 余额不足 / 翻转失败任一步抛异常整事务回滚、订单留待支付。
 * <p>
 * 判据（买家校验 / 终态幂等回放）留在消费方域（{@code ProductOrderServiceImpl}），
 * 本端口只承载事务内的跨域动作编排；trade 域不写 wallet 表，wallet 动作经本端口委托钱包域。
 */
public interface IProductPayCompositeTxPort {

    /**
     * 在一个物理事务内完成商品订单支付：条件翻转订单状态 + 冻结货款。
     *
     * @param amount  货款总额（成交单价 × 件数，调用方按订单快照算出）
     * @return 支付结果（语义与既有 ProductPayResult 一致：amount = 冻结金额）
     */
    ProductPayResult payOrder(String orderId, String buyerId, BigDecimal amount);
}
