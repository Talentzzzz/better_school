package com.betterschool.domain.errand.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 仲裁结案结果：case 层据此执行解冻（cancel 退发布方）与入账（complete 划转接单方，口径同确认完成） */
@Data
@AllArgsConstructor
public class ErrandArbitrateResult {

    private String publisherId;

    private String takerId;

    private BigDecimal reward;

    /** 仲裁结果 cancel / complete */
    private String result;

    /** 入账明细（result=complete 时非空；cancel 为空——全额退发布方，接单方不结算） */
    private List<ErrandIncomeVO> incomes;

    public ErrandArbitrateResult(String publisherId, String takerId, BigDecimal reward, String result) {
        this(publisherId, takerId, reward, result, List.of());
    }
}
