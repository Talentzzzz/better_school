package com.betterschool.domain.draw.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 抽奖活动（draw_activity）：活动 → 策略 1:1（uk_strategy_id），一个活动只有一个概率池。
 * state 生命周期 create/open/close 由后台配置维护，主链路开抽前的活动校验归票 13。
 */
@Data
public class DrawActivityEntity {

    private Long id;
    /** 业务 ID（对外唯一标识） */
    private String activityId;
    private String activityName;
    /** 绑定的抽奖策略 ID（draw_strategy.strategy_id） */
    private String strategyId;
    private LocalDateTime beginDateTime;
    private LocalDateTime endDateTime;
    /** create / open / close */
    private String state;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
