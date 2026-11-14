package com.betterschool.infrastructure.dao.productinventory.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商品库存台账 PO（product_inventory，商品 1:1，行随发布/存草稿诞生）
 * remaining 是防超卖唯一真源；流水见 product_stock_event（同事务双写）
 */
@Data
public class ProductInventory {

    private String productId;
    /** 挂单总件数（发布时定，草稿可改，上架后仅能经加货上调） */
    private Integer quantity;
    /** 剩余可售件数（下单扣减、关单回补；恒 <= quantity） */
    private Integer remaining;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
