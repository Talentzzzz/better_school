package com.betterschool.domain.rebate.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 一次领取里按返利类型汇总的一项：返利类型 + 领取快照金额 */
@Getter
@AllArgsConstructor
public class RebateClaimItemVO {

    private final RebateTypeEnum rebateType;
    private final int rebateValue;
}
