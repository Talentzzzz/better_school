package com.betterschool.marketing.infrastructure.inventory.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/** 实物营销配额行（physical_marketing_quota） */
@Data
public class PhysicalMarketingQuotaPO {

    private Long id;
    private Long invId;
    private String name;
    private String decr;
    private Long surplusQty;
    private Long waitingQty;
    private Long issuedQty;
    private String state;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
