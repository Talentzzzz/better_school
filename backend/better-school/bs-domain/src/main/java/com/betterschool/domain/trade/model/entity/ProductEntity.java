package com.betterschool.domain.trade.model.entity;

import com.betterschool.domain.trade.model.valobj.ProductCategoryVO;
import com.betterschool.domain.trade.model.valobj.ProductStatusVO;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ProductEntity {

    /** 业务 ID（UUID 字符串，uuid-biz-id 票 05） */
    private String productId;
    private String userId;
    private String title;
    private String description;
    private List<String> images;
    private BigDecimal price;
    private String tradeLocation;
    private ProductCategoryVO category;
    private ProductStatusVO status;
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
        // 发布即可见
        productEntity.status = ProductStatusVO.ON_SALE;
        productEntity.viewCount = 0;
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
        productEntity.viewCount = 0;
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
