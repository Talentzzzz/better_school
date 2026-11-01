package com.betterschool.marketing.domain.inventory.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 虚拟资产类型（virtual_inventory_quota.type） */
@Getter
@AllArgsConstructor
public enum InventoryTypeVO {

    POINTS("POINTS"),
    DRAW_COUNT("DRAW_COUNT");

    private final String code;
}
