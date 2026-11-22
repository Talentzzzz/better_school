package com.betterschool.infrastructure.dao.raffle.po;

import lombok.Data;

import java.time.LocalDateTime;

/** 抽奖策略权重档行（draw_strategy_weight，票 11 自设计稿 strategy_weight 规范化） */
@Data
public class DrawStrategyWeightPO {

    private Long id;
    private String strategyId;
    /** 该档生效的抽数阈值 */
    private Integer count;
    /** 该档保底奖品池（逗号分隔的 award_id） */
    private String weights;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
