package com.betterschool.domain.errand.model.valobj;

import lombok.Data;

import java.math.BigDecimal;

/** 订单超时处理结果（null = 幂等忽略）：取消翻转已在本域仓储事务内完成（翻转段先行），
 *  资金动作（解冻退款 + 开工场景扣分）由 case 层按本结果对象顺序执行（两段式 ADR-0016），committed 口径等随行 */
@Data
public class ErrandTimeoutResult {

    private String publisherId;

    private String takerId;

    private BigDecimal reward;

    /** 是否「已承诺后违约」（CONFIRMED 起）：只有已开工的接单方超时才扣信用分；
     *  锁单期（LOCKED）还没开工——抢 ≠ 开工——与「放弃不扣分」同口径，故为 false */
    private boolean committed;

    public ErrandTimeoutResult(String publisherId, String takerId, BigDecimal reward, boolean committed) {
        this.publisherId = publisherId;
        this.takerId = takerId;
        this.reward = reward;
        this.committed = committed;
    }
}
