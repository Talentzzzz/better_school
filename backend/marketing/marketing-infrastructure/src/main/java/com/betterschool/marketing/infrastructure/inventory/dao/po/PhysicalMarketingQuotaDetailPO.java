package com.betterschool.marketing.infrastructure.inventory.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/** 实物营销配额流转明细行（physical_marketing_quota_detail） */
@Data
public class PhysicalMarketingQuotaDetailPO {

    private Long id;
    private Long invId;
    private Integer bucketId;
    private Long quantity;
    /** 1-预扣结转（进 waiting）2-兑换确认（waiting → issued） */
    private Integer type;
    /** 业务类型：LOTTERY_DRAW（预扣）/ LOTTERY_CLAIM（兑换确认） */
    private String bizType;
    /** 业务 ID：draw_record.record_id（预扣与确认同值，靠 type 区分） */
    private Long bizId;
    private Integer state;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
