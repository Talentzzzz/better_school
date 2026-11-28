package com.betterschool.infrastructure.dao.draw.po;

import lombok.Data;

import java.time.LocalDateTime;

/** 抽奖策略行（draw_strategy） */
@Data
public class DrawStrategyPO {

    private Long id;
    private String strategyId;
    private String strategyDesc;
    /** 规则链节点码，逗号分隔（rule_blacklist,rule_weight） */
    private String rule;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
