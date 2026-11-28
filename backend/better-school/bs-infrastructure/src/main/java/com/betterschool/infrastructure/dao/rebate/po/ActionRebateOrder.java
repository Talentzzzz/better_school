package com.betterschool.infrastructure.dao.rebate.po;

import lombok.Data;

import java.time.LocalDateTime;

/** 行为返利订单行（action_rebate_order）：一行 = 一次行为 × 一个返利类型 × 一个限次周期 */
@Data
public class ActionRebateOrder {

    private Long id;
    private String orderId;
    private String userId;
    private String actionType;
    private String periodKey;
    /** 行为对象类型（ref_id 是谁的 ID）：post 等 */
    private String refType;
    private String refId;
    /** 返利类型：POINTS / DRAW_COUNT */
    private String rebateType;
    /** 领取时快照的返利值 */
    private int rebateValue;
    /** CLAIMED / GRANTED */
    private String state;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
