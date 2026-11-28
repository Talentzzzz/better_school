package com.betterschool.domain.wallet.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * 支付单支付结果：钱包事务提交后交给回调编排层。
 * <p>
 * {@code alreadyPaid = true} 表示本次是重复支付（支付单已是 PAID），钱包未再动资金；
 * 回调仍是幂等的，重复支付也会重新通知一次，用于修复上次回调丢失。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentPayResult {

    private String payNo;
    private String bizOrderId;
    private String payerId;
    private BigDecimal amount;
    private boolean alreadyPaid;
}
