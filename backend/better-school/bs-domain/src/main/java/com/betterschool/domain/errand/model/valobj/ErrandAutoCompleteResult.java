package com.betterschool.domain.errand.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 送达超时自动完成结果（null = 幂等忽略）：case 层据此执行解冻 + 入账（与发布方确认同一结算口径） */
@Data
@AllArgsConstructor
public class ErrandAutoCompleteResult {

    private String publisherId;

    /** 解冻总额：即悬赏 */
    private BigDecimal reward;

    /** 入账明细：接单方单条（悬赏） */
    private List<ErrandIncomeVO> incomes;
}
