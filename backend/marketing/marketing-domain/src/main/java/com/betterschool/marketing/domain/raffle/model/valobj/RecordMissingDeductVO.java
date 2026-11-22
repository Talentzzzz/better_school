package com.betterschool.marketing.domain.raffle.model.valobj;

import lombok.Builder;
import lombok.Data;

/**
 * 反向配对的数据破坏级缺失候选（返回行即差异）：数据窗口内 {@code draw_record}
 * （{@code invId} 非空且 {@code award_type != 'THANKS'}）不存在对应预扣明细
 * （{@code award_id = invId} 发放来源快照、{@code biz_id = recordId}）。
 * 记录在、预扣明细丢 = 数据破坏级，只告警人工不自动修（spec 平账分流）。
 * THANKS 是谢谢参与占位（{@code inv_id = '0'} 无库存来源），无预扣明细是正常态，查询已排除。
 */
@Data
@Builder
public class RecordMissingDeductVO {

    /** 抽奖记录 id（{@code draw_record.record_id}，差错定位键） */
    private String recordId;
    /** 发放来源快照（= 预扣明细应落在的奖品库存账户 {@code award_id}） */
    private String invId;
    /** 用户 id（差错快照上下文） */
    private String userId;
}
