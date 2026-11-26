package com.betterschool.infrastructure.dao.rebate.po;

import lombok.Data;

import java.time.LocalDateTime;

/** 行为返利领取记录行（action_rebate_claim） */
@Data
public class ActionRebateClaim {

    private Long id;
    private String claimId;
    private String userId;
    private String actionType;
    private String periodKey;
    private String refId;
    private int pointsValue;
    private int drawCountValue;
    /** CLAIMED / GRANTED */
    private String state;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
