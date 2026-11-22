package com.betterschool.infrastructure.dao.raffle.po;

import lombok.Data;

import java.time.LocalDateTime;

/** 抽奖记录行（draw_record；写入方归票 13 主链路，本票只备五层） */
@Data
public class DrawRecordPO {

    private Long id;
    private String recordId;
    private String userId;
    private String activityId;
    private String strategyId;
    private String awardId;
    private String awardName;
    private String awardDesc;
    private String awardType;
    private Integer level;
    private String invId;
    private String state;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
