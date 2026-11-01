package com.betterschool.marketing.domain.inventory.model.valobj;

/**
 * 带兜底的配额预扣结果（票 12 预扣面，调用方票 13 责任链）。
 * <p>
 * DEDUCTED = 配额桶扣减成功（配额段落了明细，等合并进 waiting_qty）；
 * FALLBACK = 配额不足，改发兜底奖品（兜底虚拟权益扣减成功，配额段零写入）；
 * FAILED = 连兜底奖品也扣不出来（调用方按发放失败处理，本次不落任何库存）。
 */
public enum DeductOutcomeVO {

    DEDUCTED,
    FALLBACK,
    FAILED
}
