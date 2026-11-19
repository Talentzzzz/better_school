package com.betterschool.domain.errand.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

/** 支付动作结果：携带需冻结的悬赏总额（冻结由 IErrandFundPort 在支付组合事务内执行，口径同退款） */
@Data
@AllArgsConstructor
public class ErrandPayResult {

    /** 需冻结的总额：即悬赏 */
    private BigDecimal amount;
}
