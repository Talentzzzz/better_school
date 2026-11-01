package com.betterschool.domain.errand.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

/** 支付动作结果：case 层据此执行钱包冻结 */
@Data
@AllArgsConstructor
public class ErrandPayResult {

    /** 需冻结的总额：即悬赏 */
    private BigDecimal amount;
}
