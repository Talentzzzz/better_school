package com.betterschool.infrastructure.dao.productorder.po;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ProductOrderPO {

    private Long id;
    /** 业务 ID（对外唯一标识；与物理自增同值，插入后同事务回写） */
    private Long productOrderId;
    private String buyerId;
    private String sellerId;
    private Long productId;
    private BigDecimal price;
    /** 商品信息全部为下单时快照（此后不可变） */
    private String productTitle;
    private String productDescription;
    private String productImages;
    private String productTradeLocation;
    private String productCategory;
    private String state;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 查询冗余字段：买卖双方昵称头像（JOIN 填充） */
    private String buyerNickname;
    private String buyerAvatar;
    private String sellerNickname;
    private String sellerAvatar;
    private String productImage;
}
