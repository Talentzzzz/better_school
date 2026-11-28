package com.betterschool.domain.wallet.model.entity;

import com.betterschool.domain.wallet.model.valobj.PaymentOrderStateEnum;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付单（{@code payment_order}，钱包域）：模拟微信支付的「统一下单」产物。
 * <p>
 * 商户下单时向钱包要一个支付单号，用户拿支付单号进收银台支付；钱包扣款成功后回调商户。
 * 支付单与商户订单一一对应（{@code bizOrderId} 唯一），金额、标题均为下单时快照。
 */
@Data
public class PaymentOrderEntity {

    /** 支付单号（UUID 字符串，对外支付凭据） */
    private String payNo;
    /** 商户订单业务 ID（二手商品订单 orderId，一单一支付单） */
    private String bizOrderId;
    /** 付款人用户 ID */
    private String userId;
    /** 支付金额（商户订单总额快照） */
    private BigDecimal amount;
    /** 收银台展示标题（商品标题快照） */
    private String subject;
    private PaymentOrderStateEnum state;
    private LocalDateTime payTime;
    private LocalDateTime createdAt;
}
