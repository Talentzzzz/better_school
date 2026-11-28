package com.betterschool.infrastructure.dao.wallet.po;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 支付单行（{@code payment_order}，钱包域）：模拟微信支付的统一下单产物。
 */
@Data
public class PaymentOrderPO {

    private Long id;
    /** 支付单号（UUID 字符串，对外支付凭据） */
    private String payNo;
    /** 商户订单业务 ID（一单一支付单） */
    private String bizOrderId;
    /** 付款人用户 ID */
    private String userId;
    /** 支付金额（商户订单总额快照） */
    private BigDecimal amount;
    /** 收银台展示标题 */
    private String subject;
    /** WAIT_PAY / PAID / CLOSED */
    private String state;
    private LocalDateTime payTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
