package com.betterschool.api.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class ProductOrderResponseDTO {

    private Long orderId;
    private Long productId;
    /** 商品标题快照 */
    private String productTitle;
    private String productImage;

    private String buyerId;
    private String buyerNickname;
    private String buyerAvatar;

    private String sellerId;
    private String sellerNickname;
    private String sellerAvatar;

    /** 成交价快照 */
    private BigDecimal price;

    /** PENDING_PAYMENT/PAID/DELIVERED/COMPLETED/CANCELLED */
    private String status;
    private LocalDateTime createdAt;
}
