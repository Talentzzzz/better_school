package com.betterschool.marketing.infrastructure.raffle.dao.po;

import lombok.Data;

/** 奖品目录视图行（draw_strategy_award LEFT JOIN 两段库存台账的展示口径） */
@Data
public class DrawPrizeViewPO {

    private String awardId;
    private String awardName;
    private String awardType;
    private Integer level;
    /** 积分档面额（VIRTUAL + 库存 type=POINTS），其余为 null */
    private Integer pointsAmount;
    /** 台账剩余份数；不限量为 null */
    private Long remaining;
    /** 投放总量；不限量为 null */
    private Long total;
}
