package com.betterschool.marketing.infrastructure.raffle.dao.po;

import lombok.Data;

import java.time.LocalDateTime;

/** 抽奖次数购买单行（draw_count_purchase_order，ADR-0014 订单兼 outbox） */
@Data
public class DrawCountPurchaseOrderPO {

    private Long id;
    private String purchaseOrderId;
    private String userId;
    private Integer quantity;
    private Integer pointsCost;
    private Integer state;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime finishTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
