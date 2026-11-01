package com.betterschool.infrastructure.dao.product.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ProductFavoritePO {

    private Long id;
    private String productId;
    private String userId;
    private LocalDateTime createdAt;
}
