package com.betterschool.api;

import com.betterschool.api.dto.PaymentOrderResponseDTO;
import com.betterschool.api.response.Response;

/**
 * 支付收银台契约（模拟微信支付）：用户按支付单号查询 / 支付。
 * 钱包本身不再有用户端接口，余额与流水只作为支付渠道内部状态。
 */
public interface IPaymentService {

    /** 收银台查询：仅付款人可见，返回支付单号 / 标题 / 金额 / 状态 */
    Response<PaymentOrderResponseDTO> getPaymentOrder(String payNo);

    /** 按支付单号支付：钱包扣冻成功后回调商户翻转订单；重复支付幂等 */
    Response<Void> payOrder(String payNo);
}
