package com.betterschool.domain.idle.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

/** 支付动作结果：携带需冻结的货款总额（两段式 ADR-0016：冻结由 case 层在 prepare 与 confirm 之间顺序执行） */
@Data
@AllArgsConstructor
public class ProductPayResult {

    /** 需冻结金额（成交单价 × 件数） */
    private BigDecimal amount;

    /** 支付幂等：订单已越过待支付（PAID/DELIVERED/COMPLETED，上次支付已提交成功），本次跳过冻结与翻转 */
    private boolean alreadyPaid;
}
