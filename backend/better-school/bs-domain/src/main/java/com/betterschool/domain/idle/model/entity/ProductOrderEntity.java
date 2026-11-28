package com.betterschool.domain.idle.model.entity;

import com.betterschool.domain.idle.model.valobj.ProductCategoryEnum;
import com.betterschool.domain.idle.model.valobj.ProductOrderStatusEnum;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ProductOrderEntity {

    /** 订单业务 ID（UUID 字符串，uuid-biz-id 票 05） */
    private String orderId;
    private String buyerId;
    private String sellerId;
    private String productId;
    /** 成交单价快照（订单总金额 = price × quantity） */
    private BigDecimal price;
    /** 成交件数（1..N，下单时原子扣减台账 remaining） */
    private Integer quantity;
    /** 商品信息全部为下单时快照（此后不可变）：展示不回 JOIN product（商品行可被编辑/软删，订单详情必须稳定） */
    private String productTitle;
    private String productDescription;
    private List<String> productImages;
    private String productTradeLocation;
    private ProductCategoryEnum productCategory;
    private ProductOrderStatusEnum status;
    private LocalDateTime createdAt;

    /** 查询冗余字段：买卖双方昵称头像（列表 JOIN 填充） */
    private String buyerNickname;
    private String buyerAvatar;
    private String sellerNickname;
    private String sellerAvatar;
    private String productImage;

    public static ProductOrderEntity create(String buyerId, ProductEntity productEntity, int quantity) {
        ProductOrderEntity productOrderEntity = new ProductOrderEntity();
        productOrderEntity.buyerId = buyerId;
        productOrderEntity.sellerId = productEntity.getUserId();
        productOrderEntity.productId = productEntity.getProductId();
        productOrderEntity.price = productEntity.getPrice();
        productOrderEntity.quantity = quantity;
        productOrderEntity.productTitle = productEntity.getTitle();
        productOrderEntity.productDescription = productEntity.getDescription();
        productOrderEntity.productImages = productEntity.getImages();
        productOrderEntity.productTradeLocation = productEntity.getTradeLocation();
        productOrderEntity.productCategory = productEntity.getCategory();
        productOrderEntity.status = ProductOrderStatusEnum.PENDING_PAYMENT;
        return productOrderEntity;
    }

    public boolean isBuyer(String userId) {
        return buyerId != null && buyerId.equals(userId);
    }

    public boolean isSeller(String userId) {
        return sellerId != null && sellerId.equals(userId);
    }

    /** 订单总金额 = 成交单价快照 × 成交件数（钱包冻结/解冻/入账统一按总额走） */
    public BigDecimal totalAmount() {
        int quantity = this.quantity == null ? 1 : this.quantity;
        return this.price.multiply(BigDecimal.valueOf(quantity));
    }
}
