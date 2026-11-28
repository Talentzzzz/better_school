package com.betterschool.api.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 收银台支付单（模拟微信支付）：用户按 payNo 支付，展示商品标题与金额。
 */
@Data
public class PaymentOrderResponseDTO {

    /** 支付单号（对外支付凭据） */
    private String payNo;
    /** 商户订单业务 ID（支付成功后跳回订单详情用） */
    private String bizOrderId;
    /** 收银台展示标题（商品标题快照） */
    private String subject;
    /** 支付金额 */
    private BigDecimal amount;
    /** WAIT_PAY / PAID / CLOSED */
    private String state;
    private LocalDateTime createdAt;
}
