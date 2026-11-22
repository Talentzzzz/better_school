package com.betterschool.marketing.infrastructure.raffle.dao.po;

import lombok.Data;

/**
 * 反向配对缺失候选行（drawReconScan 只读聚合，返回行即差异）：中奖记录反查预扣明细未命中。
 */
@Data
public class RecordMissingDeductPO {

    private String recordId;
    /** 发放来源快照（= 预扣明细应落在的奖品库存账户 award_id） */
    private String invId;
    private String userId;
}
