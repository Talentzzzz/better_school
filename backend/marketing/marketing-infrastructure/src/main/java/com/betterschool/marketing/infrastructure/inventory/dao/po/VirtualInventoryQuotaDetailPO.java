package com.betterschool.marketing.infrastructure.inventory.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/** 虚拟库存配额扣减明细行（virtual_inventory_quota_detail） */
@Data
public class VirtualInventoryQuotaDetailPO {

    private Long id;
    private Long invId;
    private Integer bucketId;
    private Long quantity;
    /** 业务类型：LOTTERY_DRAW（抽奖预扣） */
    private String bizType;
    /** 业务 ID：draw_record.record_id */
    private Long bizId;
    private Integer state;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
