package com.betterschool.marketing.infrastructure.raffle.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/** 抽奖活动行（draw_activity） */
@Data
public class DrawActivityPO {

    private Long id;
    private String activityId;
    private String activityName;
    private String strategyId;
    private LocalDateTime beginDateTime;
    private LocalDateTime endDateTime;
    private String state;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
