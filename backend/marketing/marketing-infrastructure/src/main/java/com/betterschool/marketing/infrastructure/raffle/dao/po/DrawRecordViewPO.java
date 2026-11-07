package com.betterschool.marketing.infrastructure.raffle.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/** 我的中奖记录视图行（draw_record 单表，恒过滤 THANKS；面额由仓储按 inv_id 集回查积分台账拼装） */
@Data
public class DrawRecordViewPO {

    private String recordId;
    private String awardId;
    private String awardName;
    private String awardType;
    private Integer level;
    /** 拼装中间列：该记录关联的库存桶业务 ID（面额回查锚点，不对外） */
    private String invId;
    private Integer pointsAmount;
    private LocalDateTime createTime;
}
