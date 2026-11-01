package com.betterschool.marketing.infrastructure.raffle.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/** 我的中奖记录视图行（draw_record，恒过滤 THANKS；面额按 inv_id 关联积分台账带出） */
@Data
public class DrawRecordViewPO {

    private Long recordId;
    private Long awardId;
    private String awardName;
    private String awardType;
    private Integer level;
    private Integer pointsAmount;
    private LocalDateTime createTime;
}
