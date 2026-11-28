package com.betterschool.domain.draw.model.valobj;

import lombok.Builder;
import lombok.Data;

/**
 * 正向配对的漏结候选（返回行即差异）：数据窗口内预扣明细（{@code flow_type = 1}、
 * {@code biz_type = LOTTERY_DRAW}）的 {@code bizId}（= {@code draw_record.record_id}）
 * 既不命中 {@code draw_record.record_id} 也不命中 {@code draw_orphan_prereserve_order.record_id}
 * ——预扣已提交、记录没落库也没种上孤儿对账单，种单环节失效。
 */
@Data
@Builder
public class UnmatchedDeductDetailVO {

    /** 奖品位 id（预扣明细落在的奖品库存账户） */
    private String awardId;
    /** 明细业务 ID（= 抽奖记录 {@code record_id}） */
    private String bizId;
    /** 预扣数量 */
    private Long quantity;
}
