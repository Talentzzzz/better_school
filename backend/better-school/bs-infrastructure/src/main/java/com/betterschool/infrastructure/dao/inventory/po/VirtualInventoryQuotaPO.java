package com.betterschool.infrastructure.dao.inventory.po;

import lombok.Data;

import java.time.LocalDateTime;

/** 虚拟库存行（virtual_inventory_quota） */
@Data
public class VirtualInventoryQuotaPO {

    private Long id;
    private String invId;
    private String name;
    private String type;
    private Integer value;
    private Long surplusQty;
    private Long issuedQty;
    private String state;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
