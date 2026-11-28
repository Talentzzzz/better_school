package com.betterschool.domain.idle.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

/** 取消动作结果：case 层据此决定是否平台退款（平台出账 + 买家入账） */
@Data
@AllArgsConstructor
public class ProductCancelResult {
    /** 已支付需平台退款 */
    private boolean needRefund;
    private String buyerId;
    /** 退款金额（成交单价 × 件数） */
    private BigDecimal amount;
}
