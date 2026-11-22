package com.betterschool.marketing.infrastructure.inventory.dao.po;

import lombok.Data;

/**
 * 正向配对漏结候选行（drawReconScan 只读聚合，返回行即差异）：预扣明细的 biz_id
 * 反查 draw_record 与孤儿对账单都未命中。
 */
@Data
public class UnmatchedDeductDetailPO {

    private String awardId;
    /** 明细业务 ID（= draw_record.record_id） */
    private String bizId;
    /** 预扣数量 */
    private Long quantity;
}
