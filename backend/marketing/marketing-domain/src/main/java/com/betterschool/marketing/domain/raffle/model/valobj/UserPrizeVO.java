package com.betterschool.marketing.domain.raffle.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 「我的奖品」的一条（读侧出参）：draw_record 中 award_type != THANKS 的行。
 * state 是四段状态的直接映射（待兑换/已兑换/已发货/已完成，中文库值为域口径，
 * HTTP 面的英文映射归 trigger 层）；claimedAt = EXCHANGE 动作明细的落库时刻。
 */
@Data
@AllArgsConstructor
public class UserPrizeVO {

    private String recordId;
    private String awardId;
    private String awardName;
    private String awardType;
    private Integer level;
    /** 待兑换 / 已兑换 / 已发货 / 已完成 */
    private String state;
    private LocalDateTime createTime;
    private LocalDateTime claimedAt;
}
