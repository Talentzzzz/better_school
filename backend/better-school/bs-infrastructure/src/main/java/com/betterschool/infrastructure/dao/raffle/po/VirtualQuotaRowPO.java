package com.betterschool.infrastructure.dao.raffle.po;

import lombok.Data;

/** 虚拟库存台账行（virtual_inventory_quota 单表批量回查）：奖品目录 / 积分面额拼装用 */
@Data
public class VirtualQuotaRowPO {

    private String invId;
    /** 库存型（POINTS / DRAW_COUNT） */
    private String type;
    /** 面额（仅 type=POINTS 有意义） */
    private Integer value;
    /** 剩余份数（-1 = 不限量） */
    private Long surplusQty;
    /** 已发放份数（total = surplus + issued） */
    private Long issuedQty;
}
