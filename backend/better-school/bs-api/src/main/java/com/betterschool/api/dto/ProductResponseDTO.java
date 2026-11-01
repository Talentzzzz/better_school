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
    /** 拒绝理由（仅 REJECTED 且卖家本人可见） */
    private String rejectReason;
    private long viewCount;
    private long favoriteCount;
    private boolean favorited;
    /**
     * 当前用户是否浏览过（market-feed-uv 票 05 已读灰显标记）：仅推荐列表接口填充，详情与其他列表 null
     * （未登录 false——市集列表匿名可浏览，灰显只对登录用户有意义）
     */
    private Boolean viewed;
    private LocalDateTime createdAt;
}
