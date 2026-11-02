package com.betterschool.marketing.domain.raffle.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 一抽的结果（主链路出参）：奖品信息来自 draw_record 的落库快照，重放与本抽返回同一份。
 */
@Data
@AllArgsConstructor
public class DrawResultVO {

    /** 抽奖记录业务 ID（draw_record.record_id，幂等键的落点） */
    private String recordId;
    private String awardId;
    private String awardName;
    /** VIRTUAL / PHYSICAL / THANKS */
    private String awardType;
    private Integer level;
    /** 积分档发放的积分数（VIRTUAL 且来源库存 type=POINTS 时取自台账面额），其余为 null */
    private Integer pointsAmount;
}
