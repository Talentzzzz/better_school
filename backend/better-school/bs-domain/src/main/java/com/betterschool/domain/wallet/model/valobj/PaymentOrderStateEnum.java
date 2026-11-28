package com.betterschool.domain.wallet.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 支付单状态机（{@code payment_order.state}）。
 * <p>
 * WAIT_PAY →(用户支付成功)→ PAID；WAIT_PAY →(订单取消/超时)→ CLOSED。
 * PAID 是终态：订单侧取消退款走钱包域的平台退款链路，支付单不再回退。
 */
@Getter
@AllArgsConstructor
public enum PaymentOrderStateEnum {

    /** 待支付：已预下单，等用户拿支付单号付款 */
    WAIT_PAY,

    /** 已支付：买家已出账、平台已入账，商户回调已发出或待重放 */
    PAID,

    /** 已关闭：订单在支付前取消/超时，支付单不再可用 */
    CLOSED;

    public static PaymentOrderStateEnum of(String state) {
        for (PaymentOrderStateEnum paymentOrderStateEnum : values()) {
            if (paymentOrderStateEnum.name().equals(state)) {
                return paymentOrderStateEnum;
            }
        }
        throw new IllegalArgumentException("未知支付单状态 state=" + state);
    }
}
