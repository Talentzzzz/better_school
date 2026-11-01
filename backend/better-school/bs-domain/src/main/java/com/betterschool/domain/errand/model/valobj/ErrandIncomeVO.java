package com.betterschool.domain.errand.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

/** 完成结算的入账明细：接单方单条（得悬赏） */
@Data
@AllArgsConstructor
public class ErrandIncomeVO {

    private String userId;

    private BigDecimal amount;
}
