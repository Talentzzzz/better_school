package com.betterschool.api.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ProductOrderResponseDTO {

    private String orderId;
    private String productId;
    /** 商品标题快照 */
    private String productTitle;
    private String productImage;

    private String buyerId;
    private String buyerNickname;
    private String buyerAvatar;

    private String sellerId;
    private String sellerNickname;
    private String sellerAvatar;

    /** 成交单价快照（订单总金额 = price × quantity） */
    private BigDecimal price;

    /** 成交件数（1..N） */
    private Integer quantity;

    /** PENDING_PAYMENT/PAID/DELIVERED/COMPLETED/CANCELLED */
    private String status;

    /** 支付单号（仅买家 + 待支付时有值；收银台支付凭据） */
    private String payNo;

    private LocalDateTime createdAt;
}
