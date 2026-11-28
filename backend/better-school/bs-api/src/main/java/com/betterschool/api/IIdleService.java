package com.betterschool.api;

import com.betterschool.api.dto.ProductDraftSaveRequestDTO;
import com.betterschool.api.dto.ProductEditRequestDTO;
import com.betterschool.api.dto.ProductOrderResponseDTO;
import com.betterschool.api.dto.ProductPublishRequestDTO;
import com.betterschool.api.dto.ProductResponseDTO;
import com.betterschool.api.response.PageResult;
import com.betterschool.api.response.Response;

import java.math.BigDecimal;

/**
 * 二手交易服务契约：HTTP 层由 Controller 实现，后续微服务化时由 Dubbo Provider 实现
 */
public interface IIdleService {

    /**
     * 闲置推荐页（market-feed-uv 票 04）：无筛选走索引序扫描 + keyset 续页（cursor 非空则忽略 page），
     * 带分类 / 价格区间筛选或索引查询降级时走旧直查（cursor 恒 null，按 page 续页）。
     * 索引路径 total 降口径为 -1（未知，不再精确 COUNT）
     */
    Response<PageResult<ProductResponseDTO>> listProducts(String category, BigDecimal minPrice, BigDecimal maxPrice,
                                                          String sort, String cursor, int page, int pageSize);

    Response<String> publishProduct(ProductPublishRequestDTO productPublishRequestDTO);

    /** 保存商品草稿（全部字段可缺省，不做初筛，仅本人可见） */
    Response<String> saveProductDraft(ProductDraftSaveRequestDTO productDraftSaveRequestDTO);

    /** 编辑商品草稿（仅 DRAFT；待审中不可编辑，需先撤回） */
    Response<Void> updateProductDraft(String productId, ProductDraftSaveRequestDTO productDraftSaveRequestDTO);

    /** 提交商品草稿：完整性校验，通过即上架可见 */
    Response<Void> submitProductDraft(String productId);

    /** 上架后主动下架商品进草稿箱（可编辑后重新提交上架） */
    Response<Void> takeDownProduct(String productId);

    /** 上架后编辑商品（编辑语义）：变更立即生效 */
    Response<Void> editProduct(String productId, ProductEditRequestDTO productEditRequestDTO);

    Response<ProductResponseDTO> productDetail(String productId);

    Response<Void> deleteProduct(String productId);

    Response<Void> markSold(String productId);

    Response<Void> relistProduct(String productId);

    /** 卖家加货（同款多件挂单）：台账 quantity/remaining 同涨，仅 ON_SALE 可加 */
    Response<Void> restockProduct(String productId, Integer quantity);

    Response<Void> favoriteProduct(String productId);

    Response<Void> unfavoriteProduct(String productId);

    Response<PageResult<ProductResponseDTO>> listFavorites(int page, int pageSize);

    /** 某用户的在售商品（作者主页「闲置」tab，免登录）：仅 ON_SALE */
    Response<PageResult<ProductResponseDTO>> listUserProducts(String userId, int page, int pageSize);

    /** 我发布的商品（卖家视角）：含已售/已下架，排除软删 */
    Response<PageResult<ProductResponseDTO>> listMyProducts(int page, int pageSize);

    /** 商品托管订单（quantity = 购买件数，1..999，可缺省默认 1） */
    Response<String> createProductOrder(String productId, Integer quantity);

    Response<Void> markDeliveredProduct(String orderId);

    Response<Void> confirmProductOrder(String orderId);

    Response<Void> cancelProductOrder(String orderId);

    Response<ProductOrderResponseDTO> productOrderDetail(String orderId);

    /** 我的商品订单：role=buy（我购买的）/ sell（我卖出的） */
    Response<PageResult<ProductOrderResponseDTO>> listMyProductOrders(String role, int page, int pageSize);
}
