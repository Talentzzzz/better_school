package com.betterschool.domain.errand.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.math.BigDecimal;

/** 取消动作结果：case 层据此执行退款（发布方取消）与信用扣分（接单方取消） */
@Data
@AllArgsConstructor
public class ErrandCancelResult {

    /** 发布方取消且已冻结：需解冻退款给发布方 */
    private boolean needRefund;

    private String publisherId;

    private BigDecimal reward;

    /** 接单方取消（CONFIRMED 回 PUBLISHED）：需对 taker 扣信用分 */
    private boolean takerCanceled;

    private String takerId;
}
