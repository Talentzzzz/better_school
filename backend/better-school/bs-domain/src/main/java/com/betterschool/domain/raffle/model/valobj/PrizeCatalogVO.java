package com.betterschool.domain.raffle.model.valobj;

import lombok.Data;

/**
 * 奖品目录的一格（读侧出参，SQL 视图组装）：奖品位 + 库存台账的展示口径。
 * 库存只暴露 {@code remaining}（台账 surplus_qty）与 {@code total}（投放总量），
 * 不暴露桶与在途预扣的实现细节；inv_id=0 的不限量档给 {@code unlimited=true}、两个库存字段为 null。
 */
@Data
public class PrizeCatalogVO {

    /** 奖品 ID（draw_strategy_award.award_id，策略内唯一） */
    private String awardId;
    private String awardName;
    /** VIRTUAL / PHYSICAL / THANKS */
    private String awardType;
    /** 奖项层级（1 等奖 = 1） */
    private Integer level;
    /** 积分档发放的积分数（VIRTUAL 且库存 type=POINTS 的面额），其余为 null */
    private Integer pointsAmount;
    /** 剩余可中奖份数（台账 surplus_qty）；不限量为 null */
    private Long remaining;
    /** 投放总量（surplus + 在途/已发放）；不限量为 null */
    private Long total;
    private Boolean unlimited;
}
