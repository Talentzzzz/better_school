package com.betterschool.infrastructure.dao.errand.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 跑腿订单状态流转事件 PO（errand_order_event，只插入不修改）
 */
@Data
public class ErrandOrderEventPO {

    private Long id;
    private Long orderId;
    private String eventType;
    private String fromState;
    private String toState;
    private String actorId;
    private String actorRole;
    private String reasonType;
    private String reason;
    private String images;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
