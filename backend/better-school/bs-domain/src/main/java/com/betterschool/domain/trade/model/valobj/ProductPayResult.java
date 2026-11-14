package com.betterschool.domain.trade.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

/** 支付动作结果：case 层据此执行钱包冻结（PRODUCT_FROZEN） */
@Data
@AllArgsConstructor
public class ProductPayResult {
    /** 需冻结金额（成交单价 × 件数） */
    private BigDecimal amount;
}
