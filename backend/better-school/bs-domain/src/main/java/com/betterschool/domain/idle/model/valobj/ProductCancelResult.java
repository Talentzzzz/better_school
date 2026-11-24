package com.betterschool.domain.idle.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

/** 取消动作结果：case 层据此决定是否解冻退款 */
@Data
@AllArgsConstructor
public class ProductCancelResult {
    /** 已支付需解冻退回 */
    private boolean needRefund;
    private String buyerId;
    /** 需解冻金额（成交单价 × 件数） */
    private BigDecimal amount;
}
