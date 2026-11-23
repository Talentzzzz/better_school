package com.betterschool.infrastructure.dao.inventory.po;

import lombok.Data;

/**
 * B3 预扣守恒对账读数行（drawReconScan 只读聚合）：台账 waiting+issued 与
 * Σ(flow_type = 1 预扣明细) 同一条查询返回，差异判断在域侧。
 */
@Data
public class InventoryPrereserveConservationPO {

    private String awardId;
    /** 台账 waiting_qty（VIRTUAL 恒 0） */
    private Long waitingQty;
    /** 台账 issued_qty */
    private Long issuedQty;
    /** Σ(flow_type = 1 预扣明细 quantity)，窗口内 */
    private Long deductedQty;
}
