package com.betterschool.domain.wallet.service;

import com.betterschool.domain.wallet.model.entity.PaymentOrderEntity;
import com.betterschool.domain.wallet.model.valobj.PaymentPayResult;

import java.math.BigDecimal;

/**
 * 支付渠道服务（钱包域对商户暴露的「微信支付」语义）：
 * 预下单 → 按支付单号支付 → 回调商户 → 关闭支付单。
 * <p>
 * 资金仍从钱包余额扣（冻结托管），但用户不再直接看到钱包；对用户只暴露支付单号与收银台。
 */
public interface IWalletPaymentService {

    /** 预下单（统一下单）：按 bizOrderId 幂等，已存在则原样返回；返回支付单号 */
    PaymentOrderEntity createPaymentOrder(String userId, String bizOrderId, BigDecimal amount, String subject);

    /**
     * 预下单（best-effort）：失败只记日志并返回 null，不抛异常——商户主流程（下单 / 订单详情）
     * 不因支付单缺失而失败；后续支付前会按同一 bizOrderId 幂等补齐。
     */
    PaymentOrderEntity createPaymentOrderQuietly(String userId, String bizOrderId, BigDecimal amount, String subject);

    /** 收银台查询：支付单不存在抛 404，非付款人抛 403 */
    PaymentOrderEntity getPaymentOrder(String payNo, String userId);

    /** 按支付单号支付：钱包事务扣冻 + 支付单置 PAID + 回调商户；重复支付幂等 */
    PaymentPayResult pay(String payNo, String userId);

    /** 关闭支付单（订单取消/超时）：仅 WAIT_PAY 生效；已支付是空操作（退款走订单解冻链路） */
    void closePaymentOrder(String bizOrderId);

    /**
     * 关闭支付单（best-effort）：失败只记日志——旧支付单若仍可支付，回调会按订单已取消/超时拒绝，
     * 已冻结的货款由资金孤儿扫描收敛退款。
     */
    void closePaymentOrderQuietly(String bizOrderId);

    /** 按商户订单号查支付单（回调重放用；不存在返回 null） */
    PaymentOrderEntity getPaymentOrderByBizOrderId(String bizOrderId);
}
