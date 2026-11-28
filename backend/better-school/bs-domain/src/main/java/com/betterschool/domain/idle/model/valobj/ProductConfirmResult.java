package com.betterschool.domain.idle.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

/** 确认收货动作结果：case 层据此执行平台结算（平台出账 + 卖家入账） */
@Data
@AllArgsConstructor
public class ProductConfirmResult {
    private String buyerId;
    private String sellerId;
    /** 结算金额（成交单价 × 件数） */
    private BigDecimal amount;
}
