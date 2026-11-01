package com.betterschool.infrastructure.dao.productorder.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品订单状态流转事件 PO（product_order_event，只插入不修改）
 */
@Data
public class ProductOrderEventPO {

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
