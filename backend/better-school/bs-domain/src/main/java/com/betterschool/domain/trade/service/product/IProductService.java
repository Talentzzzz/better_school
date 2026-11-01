package com.betterschool.domain.trade.service.product;

import com.betterschool.domain.trade.model.entity.ProductEntity;
import com.betterschool.domain.trade.model.valobj.ProductFeedPageVO;

import java.math.BigDecimal;
import java.util.List;

/**
 * 二手商品领域服务：发布/列表/详情/删除/成交/收藏
 */
public interface IProductService {

    Long publishProduct(String userId, String title, String description, List<String> images, BigDecimal price,
                        String tradeLocation, String category);

    /** 保存草稿（字段可缺省，不做初筛，仅本人可见），返回草稿 id */
    Long saveProductDraft(String userId, String title, String description, List<String> images, BigDecimal price,
                          String tradeLocation, String category);

    /** 编辑草稿（仅 DRAFT；待审中不可编辑，需先撤回） */
    void updateProductDraft(String userId, Long productId, String title, String description, List<String> images,
                            BigDecimal price, String tradeLocation, String category);

    /** 取本人的草稿（供用例层做完整性校验与初筛） */
    ProductEntity getOwnedProductDraft(String userId, Long productId);

    /** 提交草稿：DRAFT → PENDING_REVIEW（初筛由用例层前置） */
    void submitProductDraft(String userId, Long productId);

    /** 撤回待审商品进草稿箱：PENDING_REVIEW → DRAFT */
    void withdrawProduct(String userId, Long productId);

    /** 上架后主动下架进草稿箱：ON_SALE → DRAFT，同事务落搜索同步消息（退出对外可见态） */
    void takeDownProduct(String userId, Long productId);

    /** 到期下架（扫描调用）：ON_SALE → DRAFT，同事务落搜索同步消息；非 ON_SALE 幂等跳过 */
    void expireProductToDraft(Long productId);

    /** 到期扫描：ON_SALE 且 expire_at 已过的商品 id 列表 */
    List<Long> listExpiredProductIds();

    /** 24h 待审超时提醒扫描：PENDING_REVIEW 且进入待审超时的商品 id 列表 */
    List<Long> listReviewTimeoutProductIds();

    /** 取本人可编辑的在售商品（供用例层做初筛）：待审中不可编辑（先撤回转草稿）、被拒引导转草稿箱 */
    ProductEntity getOwnedOnSaleProduct(String userId, Long productId);

    /**
     * 上架后编辑（编辑语义）：仅改价格立即生效（状态不变、不进审核、无通知）；
     * 改标题/描述/图片则回待审（轮次 +1，期间他人不可见、卖家本人可见），同事务落搜索同步消息（消费方按状态删/更文档）。
     * 归属与状态守卫由 getOwnedOnSaleProduct 前置，此处靠条件 UPDATE 兜底竞态
     */
    void editOnSaleProduct(Long productId, String title, String description, List<String> images, BigDecimal price);

    /** 被拒商品转入草稿箱：REJECTED → DRAFT（修改后重新提交走草稿提交流程，产生第二次审核） */
    void moveRejectedProductToDraft(String userId, Long productId);

    /** 审核通过（统一裁决用例调用）：PENDING_REVIEW → ON_SALE，事务内落搜索同步消息；已 ON_SALE 幂等跳过 */
    void reviewApproveProduct(Long productId);

    /** 审核拒绝（统一裁决用例调用）：PENDING_REVIEW → REJECTED，无资金环节；已 REJECTED 幂等跳过 */
    void reviewRejectProduct(Long productId);

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
    List<Long> listViewedProductIds(String userId, List<Long> productIds);

    /** 详情：浏览计数 +1，登录时返回是否已收藏 */
    ProductEntity productDetail(Long productId, String currentUserId);

    void deleteProduct(Long productId, String currentUserId);

    /** 标记已出（作者本人） */
    void markSold(Long productId, String currentUserId);

    /** 重新上架（作者本人） */
    void relistProduct(Long productId, String currentUserId);

    void favoriteProduct(Long productId, String userId);

    void unfavoriteProduct(Long productId, String userId);

    List<ProductEntity> listFavorites(String userId, int page, int pageSize);

    /** 我发布的商品（卖家视角）：含已售/已下架，排除软删，按发布时间倒序 */
    List<ProductEntity> listMyProducts(String userId, int page, int pageSize);

    long countMyProducts(String userId);

    /** 某用户的在售商品（作者主页「闲置」tab，访客口径）：仅 ON_SALE，按发布时间倒序 */
    List<ProductEntity> listUserOnSaleProducts(String userId, int page, int pageSize);

    long countUserOnSaleProducts(String userId);
}
