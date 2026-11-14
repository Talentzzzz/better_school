package com.betterschool.infrastructure.dao.productorder.po;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ProductOrder {

    private Long id;
    /** 业务 ID（对外唯一标识，UUID 字符串；uuid-biz-id 票 05 起落库前发号，与物理自增不再同值） */
    private String productOrderId;
    private String buyerId;
    private String sellerId;
    private String productId;
    private BigDecimal price;
    /** 成交件数（1..N，下单时原子扣减台账 remaining；订单总金额 = price × quantity） */
    private Integer quantity;
    /** 商品信息全部为下单时快照（此后不可变） */
    private String productTitle;
    private String productDescription;
    private String productImages;
    private String productTradeLocation;
    private String productCategory;
    private String state;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /** 查询冗余字段：买卖双方昵称头像（仓储查 bs_user 批量回查填充，SQL 层零 JOIN） */
    private String buyerNickname;
    private String buyerAvatar;
    private String sellerNickname;
    private String sellerAvatar;
    private String productImage;
}
