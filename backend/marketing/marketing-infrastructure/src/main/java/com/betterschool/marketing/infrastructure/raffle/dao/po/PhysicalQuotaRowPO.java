package com.betterschool.marketing.infrastructure.raffle.dao.po;

import lombok.Data;

/** 实物营销台账行（physical_marketing_quota 单表批量回查）：奖品目录 total 拼装用 */
@Data
public class PhysicalQuotaRowPO {

    private String invId;
    /** 剩余份数（-1 = 不限量） */
    private Long surplusQty;
    /** 待发货份数（total = surplus + waiting + issued） */
    private Long waitingQty;
    /** 已发放份数 */
    private Long issuedQty;
}
