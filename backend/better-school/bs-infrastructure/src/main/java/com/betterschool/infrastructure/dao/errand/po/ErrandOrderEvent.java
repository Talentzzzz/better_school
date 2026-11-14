package com.betterschool.infrastructure.dao.errand.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 跑腿订单状态流转事件 PO（errand_order_event，只插入不修改）
 */
@Data
public class ErrandOrderEvent {

    private Long id;
    /** 跑腿订单业务 ID（UUID 字符串，uuid-biz-id 票 05） */
    private String orderId;
    /** 事件序号（业务顺序）：同订单内递增即状态变更顺序；id 仅内部键，无业务含义 */
    private Integer eventSeq;
    private String eventType;
    private String fromState;
    private String toState;
    private String actorId;
    private String actorRole;
    private String reasonType;
    private String reason;
    private String images;
    /** 触发本事件的请求编号（客户端每次点击生成、重试沿用；请求级幂等判定用，SYSTEM 事件为 null） */
    private String requestId;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
