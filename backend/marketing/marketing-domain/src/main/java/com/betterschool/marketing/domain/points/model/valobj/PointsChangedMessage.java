package com.betterschool.marketing.domain.points.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 积分变动事件（topic {@code bs-points-changed}）：单体消费后落通知。
 * 只带「id + 消费方决策必需的值」——不带昵称头像等展示快照，消费方也不反查本服务。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PointsChangedMessage {

    private String userId;
    /** 变动量：入账为正、消费为负 */
    private Integer delta;
    private Integer balanceAfter;
    /** LOTTERY_WIN / REDEEM_DRAW_COUNT / REDEEM_PRIZE */
    private String reasonType;
    /** 来源业务 id（抽奖记录 id / 兑换单 id） */
    private String refId;
}
