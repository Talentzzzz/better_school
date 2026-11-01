package com.betterschool.domain.errand.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 确认完成动作结果：case 层据此执行解冻总额 + 入账（流水 uk 幂等） */
@Data
@AllArgsConstructor
public class ErrandConfirmResult {

    private String publisherId;

    /** 解冻总额：即悬赏 */
    private BigDecimal reward;

    /** 入账明细：接单方单条（悬赏） */
    private List<ErrandIncomeVO> incomes;
}
