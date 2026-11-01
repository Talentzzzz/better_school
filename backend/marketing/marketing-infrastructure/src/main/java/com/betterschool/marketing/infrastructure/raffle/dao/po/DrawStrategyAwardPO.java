package com.betterschool.marketing.infrastructure.raffle.dao.po;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 抽奖策略奖品位行（draw_strategy_award） */
@Data
public class DrawStrategyAwardPO {

    private Long id;
    private Long strategyId;
    private Long awardId;
    private String awardName;
    private String awardDesc;
    private String awardType;
    private BigDecimal awardRate;
    private Integer level;
    private Long invId;
    private String type;
    private Integer sort;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
