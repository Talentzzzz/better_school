package com.betterschool.domain.errand.model.valobj;

import lombok.Data;

import java.math.BigDecimal;

/** 订单超时处理结果（null = 幂等忽略）：case 层据此执行解冻退款与接单方扣分 */
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
