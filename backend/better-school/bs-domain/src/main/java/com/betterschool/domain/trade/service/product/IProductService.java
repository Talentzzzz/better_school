package com.betterschool.domain.trade.service.product;

import com.betterschool.domain.trade.model.entity.ProductEntity;
import com.betterschool.domain.trade.model.valobj.ProductFeedPageVO;

import java.math.BigDecimal;
import java.util.List;

/**
 * 二手商品领域服务：发布/列表/详情/删除/成交/收藏
 */
public interface IProductService {

    /** 发布（quantity = 挂单件数，null/<=0 按 1 件）：落库即可见 + 同事务建库存台账 */
    String publishProduct(String userId, String title, String description, List<String> images, BigDecimal price,
                        String tradeLocation, String category, Integer quantity);

    /** 保存草稿（字段可缺省，不做初筛，仅本人可见；quantity = 挂单件数可缺省），返回草稿 id */
    String saveProductDraft(String userId, String title, String description, List<String> images, BigDecimal price,
                          String tradeLocation, String category, Integer quantity);

    /** 编辑草稿（仅 DRAFT；quantity 非 null 时同事务覆盖台账件数） */
    void updateProductDraft(String userId, String productId, String title, String description, List<String> images,
                            BigDecimal price, String tradeLocation, String category, Integer quantity);

    /** 取本人的草稿（供用例层做完整性校验与初筛） */
    ProductEntity getOwnedProductDraft(String userId, String productId);

    /** 提交草稿：DRAFT → ON_SALE（完整性由用例层前置） */
    void submitProductDraft(String userId, String productId);

    /** 上架后主动下架进草稿箱：ON_SALE → DRAFT，同事务落搜索同步消息（退出对外可见态） */
    void takeDownProduct(String userId, String productId);

    /** 到期下架（扫描调用）：ON_SALE → DRAFT，同事务落搜索同步消息；非 ON_SALE 幂等跳过 */
    void expireProductToDraft(String productId);

    /** 到期扫描：ON_SALE 且 expire_at 已过的商品 id 列表 */
    List<String> listExpiredProductIds();

    /** 取本人可编辑的在售商品（用例层编辑守卫用） */
    ProductEntity getOwnedOnSaleProduct(String userId, String productId);

    /**
     * 上架后编辑（编辑语义）：全部变更立即生效（状态不变），同事务落搜索同步消息（消费方按状态更文档）。
     * 归属与状态守卫由 getOwnedOnSaleProduct 前置，此处靠条件 UPDATE 兜底竞态
     */
    void editOnSaleProduct(String productId, String title, String description, List<String> images, BigDecimal price);

    /**
     * 闲置推荐页（market-feed-uv 票 04）：无筛选走 product_feed_index 索引序扫描 + keyset 游标分页，
     * 带分类 / 价格区间筛选走旧直查（索引表无筛选列），索引异常 warn 降级旧直查。
     * 返回页内商品 + 续页游标 + 是否索引路径（索引路径 total 由调用方降口径为 -1）
     */
    ProductFeedPageVO listProducts(String category, BigDecimal minPrice, BigDecimal maxPrice, String sort,
                                   String cursor, int page, int pageSize);

    long countProducts(String category, BigDecimal minPrice, BigDecimal maxPrice);

    /**
     * feed 索引全量重建（market-feed-uv 票 06，XXL-Job 手动触发）：清空重灌 product_feed_index 并把
     * product_view_count 校准成 product_view_history 行数，一个事务（读者不见空窗）。
     * 常态无漂移——同步是同事务直写，重建只在出现漂移信号（浏览旁路命中 0 行的 warn、窄表被绕过直改）时手动执行
     */
    void rebuildFeedIndex();

    /**
     * 当前用户在给定商品集合里浏览过的商品 id（推荐列表已读灰显，market-feed-uv 票 05）：
     * 一页一次批量回查，未登录或集合为空返回空集
     */
    List<String> listViewedProductIds(String userId, List<String> productIds);

    /** 详情：浏览计数 +1，登录时返回是否已收藏 */
    ProductEntity productDetail(String productId, String currentUserId);

    void deleteProduct(String productId, String currentUserId);

    /** 标记已出（作者本人） */
    void markSold(String productId, String currentUserId);

    /** 重新上架（作者本人；台账 remaining = 0 视为真卖完，不可重上架） */
    void relistProduct(String productId, String currentUserId);

    /** 卖家加货（作者本人，仅 ON_SALE）：台账 quantity/remaining 同涨 + RESTOCK 流水（同事务） */
    void restockProduct(String userId, String productId, int quantity);

    void favoriteProduct(String productId, String userId);

    void unfavoriteProduct(String productId, String userId);

    List<ProductEntity> listFavorites(String userId, int page, int pageSize);

    /** 我发布的商品（卖家视角）：含已售/已下架，排除软删，按发布时间倒序 */
    List<ProductEntity> listMyProducts(String userId, int page, int pageSize);

    long countMyProducts(String userId);

    /** 某用户的在售商品（作者主页「闲置」tab，访客口径）：仅 ON_SALE，按发布时间倒序 */
    List<ProductEntity> listUserOnSaleProducts(String userId, int page, int pageSize);

    long countUserOnSaleProducts(String userId);
}
