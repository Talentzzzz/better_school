package com.betterschool.domain.errand.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

/** 支付动作结果：携带需冻结的悬赏总额（两段式 ADR-0016：冻结由 case 层在 prepare 与 confirm 之间顺序执行，口径同退款） */
@Data
@AllArgsConstructor
public class ErrandPayResult {

    /** 需冻结的总额：即悬赏 */
    private BigDecimal amount;

    /** 支付幂等：订单已在 PUBLISHED（上次支付已提交成功），本次跳过冻结与翻转 */
    private boolean alreadyPublished;
}
