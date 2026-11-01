package com.betterschool.domain.errand.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * 审核裁决结果：向 case 层声明的资金动作需求（拒绝 = 解冻退款，通过 = 无资金动作），
 * 退款幂等由 wallet_detail uk_order_type 保证
 */
@Getter
@AllArgsConstructor
public class ErrandReviewResult {

    private final String publisherId;

    private final BigDecimal reward;
}
