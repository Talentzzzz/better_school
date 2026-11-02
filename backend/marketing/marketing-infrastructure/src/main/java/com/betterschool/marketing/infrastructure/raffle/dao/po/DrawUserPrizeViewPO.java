package com.betterschool.marketing.infrastructure.raffle.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/** 我的奖品视图行（draw_record LEFT JOIN EXCHANGE 动作明细，恒过滤 THANKS） */
@Data
public class DrawUserPrizeViewPO {

    private String recordId;
    private String awardId;
    private String awardName;
    private String awardType;
    private Integer level;
    /** 待兑换 / 已兑换 / 已发货 / 已完成 */
    private String state;
    private LocalDateTime createTime;
    /** EXCHANGE 动作明细的落库时刻；还没兑换为 null */
    private LocalDateTime claimedAt;
}
