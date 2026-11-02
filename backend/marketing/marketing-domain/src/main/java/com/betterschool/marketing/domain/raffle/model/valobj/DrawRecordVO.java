package com.betterschool.marketing.domain.raffle.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 我的中奖记录的一条（读侧出参）：过滤谢谢参与由仓储保证。
 */
@Data
@AllArgsConstructor
public class DrawRecordVO {

    private String recordId;
    private String awardId;
    private String awardName;
    /** VIRTUAL / PHYSICAL / THANKS（列表恒不含 THANKS） */
    private String awardType;
    private Integer level;
    /** 积分档发放的积分数，其余为 null */
    private Integer pointsAmount;
    private LocalDateTime createTime;
}
