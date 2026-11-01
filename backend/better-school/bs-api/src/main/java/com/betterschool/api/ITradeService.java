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
public interface ITradeService {

    /**
     * 闲置推荐页（market-feed-uv 票 04）：无筛选走索引序扫描 + keyset 续页（cursor 非空则忽略 page），
     * 带分类 / 价格区间筛选或索引查询降级时走旧直查（cursor 恒 null，按 page 续页）。
     * 索引路径 total 降口径为 -1（未知，不再精确 COUNT）
     */
    Response<PageResult<ProductResponseDTO>> listProducts(String category, BigDecimal minPrice, BigDecimal maxPrice,
                                                          String sort, String cursor, int page, int pageSize);

    Response<Long> publishProduct(ProductPublishRequestDTO request);

    /** 保存商品草稿（全部字段可缺省，不做初筛，仅本人可见） */
    Response<Long> saveProductDraft(ProductDraftSaveRequestDTO request);

    /** 编辑商品草稿（仅 DRAFT；待审中不可编辑，需先撤回） */
    Response<Void> updateProductDraft(Long productId, ProductDraftSaveRequestDTO request);

    /** 提交商品草稿：完整性校验 + 同步初筛（命中停留草稿）→ 待审 */
    Response<Void> submitProductDraft(Long productId);

    /** 撤回待审商品进草稿箱 */
    Response<Void> withdrawProduct(Long productId);

    /** 上架后主动下架商品进草稿箱（可编辑后重新提交回待审） */
    Response<Void> takeDownProduct(Long productId);

    /** 上架后编辑商品（编辑语义）：仅改价立即生效；改标题/描述/图片回待审（期间他人不可见、卖家本人可见） */
    Response<Void> editProduct(Long productId, ProductEditRequestDTO request);

    /** 被拒商品转入草稿箱（自救路径）：修改后重新提交产生第二次审核 */
    Response<Void> moveRejectedProductToDraft(Long productId);

    Response<ProductResponseDTO> productDetail(Long productId);

    Response<Void> deleteProduct(Long productId);

    Response<Void> markSold(Long productId);

    Response<Void> relistProduct(Long productId);

    Response<Void> favoriteProduct(Long productId);

    Response<Void> unfavoriteProduct(Long productId);

    Response<PageResult<ProductResponseDTO>> listFavorites(int page, int pageSize);

    /** 某用户的在售商品（作者主页「闲置」tab，免登录）：仅 ON_SALE */
    Response<PageResult<ProductResponseDTO>> listUserProducts(String userId, int page, int pageSize);

    /** 我发布的商品（卖家视角）：含已售/已下架，排除软删 */
    Response<PageResult<ProductResponseDTO>> listMyProducts(int page, int pageSize);

    /** 商品托管订单 */
    Response<Long> createProductOrder(Long productId);

    Response<Void> payProductOrder(Long orderId);

    Response<Void> markDeliveredProduct(Long orderId);

    Response<Void> confirmProductOrder(Long orderId);

    Response<Void> cancelProductOrder(Long orderId);

    Response<ProductOrderResponseDTO> productOrderDetail(Long orderId);

    /** 我的商品订单：role=buy（我购买的）/ sell（我卖出的） */
    Response<PageResult<ProductOrderResponseDTO>> listMyProductOrders(String role, int page, int pageSize);
}
