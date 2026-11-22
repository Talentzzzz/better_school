package com.betterschool.marketing.infrastructure.inventory.dao.po;

import lombok.Data;

/**
 * B1 台账守恒对账读数行（drawReconScan 只读聚合）：台账行与 Σ(flow_type = 0 分配转入明细)
 * 同一条查询返回，差异判断在域侧（限量户比三列、不限量户跳过 surplus 项）。
 */
@Data
public class InventoryLedgerConservationPO {

    private String awardId;
    /** 台账 surplus_qty（-1 = 不限量） */
    private Long surplusQty;
    /** 台账 waiting_qty（VIRTUAL 恒 0） */
    private Long waitingQty;
    /** 台账 issued_qty */
    private Long issuedQty;
    /** Σ(flow_type = 0 分配转入明细 quantity)，窗口内 */
    private Long allocatedQty;
}
