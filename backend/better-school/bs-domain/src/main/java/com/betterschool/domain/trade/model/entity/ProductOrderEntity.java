package com.betterschool.domain.trade.model.entity;

import com.betterschool.domain.trade.model.valobj.ProductCategoryVO;
import com.betterschool.domain.trade.model.valobj.ProductOrderStatusVO;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ProductOrderEntity {

    private Long orderId;
    private String buyerId;
    private String sellerId;
    private Long productId;
    private BigDecimal price;
    /** 商品信息全部为下单时快照（此后不可变）：展示不回 JOIN product（商品行可被编辑/软删，订单详情必须稳定） */
    private String productTitle;
    private String productDescription;
    private List<String> productImages;
    private String productTradeLocation;
    private ProductCategoryVO productCategory;
    private ProductOrderStatusVO status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    /** 查询冗余字段：买卖双方昵称头像（列表 JOIN 填充） */
    private String buyerNickname;
    private String buyerAvatar;
    private String sellerNickname;
    private String sellerAvatar;
    private String productImage;

    public static ProductOrderEntity create(String buyerId, ProductEntity product) {
        ProductOrderEntity orderEntity = new ProductOrderEntity();
        orderEntity.buyerId = buyerId;
        orderEntity.sellerId = product.getUserId();
        orderEntity.productId = product.getProductId();
        orderEntity.price = product.getPrice();
        orderEntity.productTitle = product.getTitle();
        orderEntity.productDescription = product.getDescription();
        orderEntity.productImages = product.getImages();
        orderEntity.productTradeLocation = product.getTradeLocation();
        orderEntity.productCategory = product.getCategory();
        orderEntity.status = ProductOrderStatusVO.PENDING_PAYMENT;
        orderEntity.createdAt = LocalDateTime.now();
        orderEntity.updatedAt = orderEntity.createdAt;
        return orderEntity;
    }

    public boolean isBuyer(String userId) {
        return buyerId != null && buyerId.equals(userId);
    }

    public boolean isSeller(String userId) {
        return sellerId != null && sellerId.equals(userId);
    }
}
