package com.betterschool.api.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ProductResponseDTO {

    private String productId;
    private String userId;
    private String sellerNickname;
    private String sellerAvatar;
    private String title;
    private String description;
    private List<String> images;
    private BigDecimal price;
    private String tradeLocation;
    private String category;
    private String status;
    private long viewCount;
    /** 剩余可售件数（product_inventory 台账回查填充；多件挂单展示"还剩 N 件"） */
    private Integer remainingQuantity;
    private long favoriteCount;
    private boolean favorited;
    /**
     * 当前用户是否浏览过（market-feed-uv 票 05 已读灰显标记）：仅推荐列表接口填充，详情与其他列表 null
     * （未登录 false——市集列表匿名可浏览，灰显只对登录用户有意义）
     */
    private Boolean viewed;
    private LocalDateTime createdAt;
}
