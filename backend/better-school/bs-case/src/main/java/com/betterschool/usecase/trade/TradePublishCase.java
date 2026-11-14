package com.betterschool.usecase.trade;

import com.betterschool.domain.credit.service.ICreditAccountService;
import com.betterschool.domain.trade.model.entity.ProductEntity;
import com.betterschool.domain.trade.service.product.IProductService;
import com.betterschool.types.concurrent.VirtualThreadFanout;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/**
 * 二手交易发布用例编排：信用分门槛前置校验 + trade 域发布（商品，落库即可见）。
 * 草稿同轨：保存/编辑私有内容不做校验，提交与直接发布同门槛建检
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradePublishCase {

    private final IProductService productService;
    private final ICreditAccountService creditAccountService;
    private final VirtualThreadFanout fanout;

    public String publishProduct(String userId, String title, String description, List<String> images, BigDecimal price,
                               String tradeLocation, String category, Integer quantity) {
        creditAccountService.ensureOperable(userId);
        return productService.publishProduct(userId, title, description, images, price, tradeLocation, category, quantity);
    }

    /** 保存商品草稿：不做信用分门槛，字段可缺省 */
    public String saveProductDraft(String userId, String title, String description, List<String> images, BigDecimal price,
                                 String tradeLocation, String category, Integer quantity) {
        return productService.saveProductDraft(userId, title, description, images, price, tradeLocation, category, quantity);
    }

    /** 编辑商品草稿：仅 DRAFT 可编辑 */
    public void updateProductDraft(String userId, String productId, String title, String description, List<String> images,
                                   BigDecimal price, String tradeLocation, String category, Integer quantity) {
        productService.updateProductDraft(userId, productId, title, description, images, price,
                tradeLocation, category, quantity);
    }

    /** 提交商品草稿：与直接发布同门槛校验，通过即上架可见 */
    public void submitProductDraft(String userId, String productId) {
        ProductEntity draftEntity = productService.getOwnedProductDraft(userId, productId);
        creditAccountService.ensureOperable(userId);
        validateProductDraftComplete(draftEntity);
        productService.submitProductDraft(userId, productId);
    }

    /**
     * 上架后编辑商品（编辑语义）：先做归属与状态守卫，再由域层落编辑、立即生效
     */
    public void editOnSaleProduct(String userId, String productId, String title, String description, List<String> images,
                                  BigDecimal price) {
        productService.getOwnedOnSaleProduct(userId, productId);
        productService.editOnSaleProduct(productId, title, description, images, price);
    }

    /**
     * 卖家加货（multi-quantity-listing）：台账 quantity/remaining 同涨 + RESTOCK 流水（同事务）。
     * 归属与在售守卫由域层 getOwnedOnSaleProduct 前置
     */
    public void restockProduct(String userId, String productId, int quantity) {
        productService.restockProduct(userId, productId, quantity);
    }

    /**
     * 上架商品主动下架：ON_SALE → DRAFT 汇入草稿箱，可编辑后重新提交上架；同事务退出搜索可见
     */
    public void takeDownProduct(String userId, String productId) {
        productService.takeDownProduct(userId, productId);
    }

    /**
     * 到期下架扫描（XXL-Job 每分钟）：上架满 30 天（expire_at，重提/改内容时刷新）的商品汇入草稿箱而非终态。
     * 条件 UPDATE 幂等，与下架并发无害。单发一批、批内扇出（宽度 = SCAN_BATCH_SIZE），项内失败只影响该项（下轮扫描补跑）
     */
    public void scanExpiredListings() {
        fanout.forEach("trade-expire-product-", productService.listExpiredProductIds(),
                productService::expireProductToDraft);
    }

    private void validateProductDraftComplete(ProductEntity draftEntity) {
        if (isBlank(draftEntity.getTitle()) || draftEntity.getPrice() == null
                || isBlank(draftEntity.getTradeLocation()) || draftEntity.getCategory() == null) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "草稿内容不完整，请补全标题、价格、交易地点与分类后再提交");
        }
    }

    private boolean isBlank(String text) {
        return text == null || text.isBlank();
    }
}
