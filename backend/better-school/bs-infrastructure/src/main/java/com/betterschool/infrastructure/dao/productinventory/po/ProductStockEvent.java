package com.betterschool.infrastructure.dao.productinventory.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品库存流水 PO（product_stock_event，只插入不修改；与 product_inventory 变更同事务双写，
 * 审计/对账用，不参与用户时间线）
 */
@Data
public class ProductStockEvent {

    private Long id;
    private String productId;
    /** INIT 发布建账 / DEDUCT 下单占用 / RELEASE 关单回补 / RESTOCK 卖家加货 / ADJUST 校正（含标记已出清零） */
    private String eventType;
    /** remaining 变动量（扣减为负、回补/加货为正、清零为负） */
    private Integer delta;
    /** 事件后 remaining 快照（对账锚点） */
    private Integer afterRemaining;
    /** 关联订单业务 ID（DEDUCT/RELEASE 必填，其余 NULL） */
    private String orderId;
    /** 操作人用户 ID（SYSTEM 事件可 NULL） */
    private String actorId;
    /** 操作方 BUYER/SELLER/SYSTEM/ADMIN */
    private String actorRole;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
