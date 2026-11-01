package com.betterschool.domain.trade.model.entity;

import com.betterschool.domain.trade.model.valobj.ProductCategoryVO;
import com.betterschool.domain.trade.model.valobj.ProductStatusVO;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ProductEntity {

    private Long productId;
    private String userId;
    private String title;
    private String description;
    private List<String> images;
    private BigDecimal price;
    private String tradeLocation;
    private ProductCategoryVO category;
    private ProductStatusVO status;
    /** 审核轮次：累计进入待审次数（直接发布=1，草稿创建=0，草稿提交/重新上架/在售改内容回审再 +1） */
    private int reviewRound;
    /** 最近一次进入待审时间（24h 待审超时提醒扫描用；草稿行为 null，提交时由 SQL 刷新） */
    private LocalDateTime reviewEnteredAt;
    private long viewCount;
    private LocalDateTime createdAt;
    private LocalDateTime expireAt;

    /** 查询冗余字段：卖家昵称/头像（列表 JOIN 填充） */
    private String sellerNickname;
    private String sellerAvatar;

    /** 查询冗余字段：收藏数（详情接口填充） */
    private long favoriteCount;

    /** 是否已收藏（当前用户，详情接口填充） */
    private boolean favorited;

    public static ProductEntity create(String userId, String title, String description, List<String> images,
                                       BigDecimal price, String tradeLocation,
                                       ProductCategoryVO category) {
        ProductEntity productEntity = new ProductEntity();
        productEntity.userId = userId;
        productEntity.title = title;
        productEntity.description = description;
        productEntity.images = images == null ? List.of() : images;
        productEntity.price = price;
        productEntity.tradeLocation = tradeLocation;
        productEntity.category = category;
        // 先审后售：提交即待审，审核通过才转 ON_SALE（对外可见）
        productEntity.status = ProductStatusVO.PENDING_REVIEW;
        productEntity.reviewRound = 1;
        productEntity.viewCount = 0;
        productEntity.createdAt = LocalDateTime.now();
        productEntity.reviewEnteredAt = productEntity.createdAt;
        productEntity.expireAt = productEntity.createdAt.plusDays(30);
        return productEntity;
    }

    /** 草稿：字段可缺省（未填完就存），仅本人可见，可编辑后重新提交回发布流程 */
    public static ProductEntity createDraft(String userId, String title, String description, List<String> images,
                                            BigDecimal price, String tradeLocation,
                                            ProductCategoryVO category) {
        ProductEntity productEntity = new ProductEntity();
        productEntity.userId = userId;
        productEntity.title = title;
        productEntity.description = description;
        productEntity.images = images == null ? List.of() : images;
        productEntity.price = price;
        productEntity.tradeLocation = tradeLocation;
        productEntity.category = category;
        productEntity.status = ProductStatusVO.DRAFT;
        productEntity.reviewRound = 0;
        productEntity.viewCount = 0;
        productEntity.createdAt = LocalDateTime.now();
        return productEntity;
    }

    /** 覆盖草稿内容（全量编辑：文字、图片、价格等全部可改） */
    public void applyDraftContent(String title, String description, List<String> images, BigDecimal price,
                                  String tradeLocation, ProductCategoryVO category) {
        this.title = title;
        this.description = description;
        this.images = images == null ? List.of() : images;
        this.price = price;
        this.tradeLocation = tradeLocation;
        this.category = category;
    }

    public boolean isOwner(String userId) {
        return this.userId != null && this.userId.equals(userId);
    }
}
