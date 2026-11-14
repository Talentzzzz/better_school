package com.betterschool.domain.trade.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

/** 确认收货动作结果：case 层据此执行解冻退回 + 卖家入账 */
@Data
@AllArgsConstructor
public class ProductConfirmResult {
    private String buyerId;
    private String sellerId;
    /** 需解冻/入账金额（成交单价 × 件数） */
    private BigDecimal amount;
}
