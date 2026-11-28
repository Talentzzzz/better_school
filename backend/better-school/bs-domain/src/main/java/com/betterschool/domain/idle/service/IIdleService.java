package com.betterschool.domain.idle.service;

import com.betterschool.domain.idle.model.entity.ProductEntity;
import com.betterschool.domain.idle.model.entity.ProductOrderEntity;
import com.betterschool.domain.idle.model.valobj.ProductCancelResult;
import com.betterschool.domain.idle.model.valobj.ProductConfirmResult;
import com.betterschool.domain.idle.model.valobj.ProductFeedPageVO;
import com.betterschool.domain.idle.model.valobj.ProductOrderEventVO;
import com.betterschool.types.common.SearchPageVO;

import java.math.BigDecimal;
import java.util.List;

/**
 * 闲置域服务：闲置商品生命周期 + 商品托管订单状态机，一个域一个服务。
 *
 * <p>不再拆商品 / 商品订单两个服务：两者共用唯一仓储 {@code IIdleRepository}，库存台账是同一个
 * 不变量——下单扣减、取消回补、售罄翻态、加货都落在台账上，"谁能改台账"这条规则只该有一处出口；
 * 两个聚合的状态机在服务层零相互调用，合并后按方法分节读即可。
 *
 * <p>跨域资金动作（钱包冻结/解冻/入账）不在此处：域服务只做判据与状态翻转，需求通过结果对象
 * 交回 case 层顺序执行（ADR-0016，资金段与翻转段各自仓储本地事务，事务边界 = 仓储）。
 * 支付为资金段先行、取消为翻转段先行，宕机窗口的长款中间态由 {@code productFundOrphanScan} 收敛。
 */
public interface IIdleService {

    // ==================== 商品生命周期：发布 / 草稿 / 上下架 / 编辑 / 浏览 / 收藏 ====================

    /** 发布（quantity = 挂单件数，null/<=0 按 1 件）：落库即可见 + 同事务建库存台账 */
    String publishProduct(String userId, String title, String description, List<String> images, BigDecimal price,
                          String tradeLocation, String category, Integer quantity);

    /** 保存草稿（字段可缺省，不做初筛，仅本人可见；quantity = 挂单件数可缺省），返回草稿 id */
    String saveProductDraft(String userId, String title, String description, List<String> images, BigDecimal price,
                            String tradeLocation, String category, Integer quantity);

    /** 编辑草稿（仅 DRAFT；quantity 非 null 时同事务覆盖台账件数） */
    void updateProductDraft(String userId, String productId, String title, String description, List<String> images,
                            BigDecimal price, String tradeLocation, String category, Integer quantity);

    /** 取本人的草稿（归属/状态守卫） */
    ProductEntity getOwnedProductDraft(String userId, String productId);

    /** 提交草稿：DRAFT → ON_SALE（域内做归属/状态守卫与完整性校验） */
    void submitProductDraft(String userId, String productId);

    /** 上架后主动下架进草稿箱：ON_SALE → DRAFT，同事务落搜索同步消息（退出对外可见态） */
    void takeDownProduct(String userId, String productId);

    /** 到期下架（扫描调用）：ON_SALE → DRAFT，同事务落搜索同步消息；非 ON_SALE 幂等跳过 */
    void expireProductToDraft(String productId);

    /** 到期扫描：ON_SALE 且 expire_at 已过的商品 id 列表 */
    List<String> listExpiredProductIds();

    /** 取本人可编辑的在售商品（编辑/加货守卫用） */
    ProductEntity getOwnedOnSaleProduct(String userId, String productId);

    /**
     * 上架后编辑（编辑语义）：域内先做归属与在售守卫，全部变更立即生效（状态不变），
     * 同事务落搜索同步消息（消费方按状态更文档）；守卫与落库之间的竞态由条件 UPDATE 兜底
     */
    void editOnSaleProduct(String userId, String productId, String title, String description, List<String> images,
                           BigDecimal price);

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

    /** 读单条商品（无副作用不计数，任何状态可读；跨域只读场景用） */
    ProductEntity getProductEntity(String productId);

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

    // ==================== 商品托管订单：下单 / 支付 / 交付 / 确认 / 取消 / 超时 ====================

    /**
     * 下单：自购校验 → 生成 PENDING_PAYMENT 订单（含件数）+ 库存台账条件 UPDATE 扣减
     * （remaining >= quantity，0 行即库存不足回滚；扣到 0 售罄随行翻 SOLD）+ 超时注册（域内事务；
     * 信用分门槛由 case 层前置校验）
     */
    String createOrder(String buyerId, String productId, int quantity);

    /**
     * 支付确认准备（两段式 ADR-0016 第一段，纯校验无副作用）：买家校验 + 可支付态判定
     * （待支付，或已支付及其后态的重试回放），通过则返回订单实体；
     * 冻结金额由实体 {@code totalAmount()} 给出，资金冻结由 case 层在 prepare 与 confirm 之间顺序执行
     */
    ProductOrderEntity preparePayOrder(String orderId, String buyerId);

    /**
     * 支付确认翻转（两段式 ADR-0016 第二段）：「待支付 → 已支付」条件翻转（守卫买家 + 前态，
     * PAID 事件随行落表，单仓储事务）；未命中且订单已是已支付及其后态 = 重试回放，幂等返回；
     * 其余未命中抛业务异常——此刻货款可能已冻结（订单留待支付的长款中间态），
     * 孤儿由 {@code productFundOrphanScan} 扫描解冻收敛
     */
    void confirmPayOrder(String orderId, String buyerId);

    /** 卖家标记交付：PAID → DELIVERED */
    void markDelivered(String orderId, String sellerId);

    /** 买家确认收货：DELIVERED → COMPLETED（域内事务），返回解冻/入账需求（单价 × 件数） */
    ProductConfirmResult confirmReceived(String orderId, String buyerId);

    /**
     * 买家取消（两段式 ADR-0016 翻转段先行）：PENDING_PAYMENT/PAID → CANCELLED + 库存台账回补 +
     * 搜索同步（单仓储事务，未命中抛异常零残留——资金段尚未执行）；返回退款需求（单价 × 件数），
     * 解冻退款（needRefund=true 时）由 case 层随后执行
     */
    ProductCancelResult cancelOrder(String orderId, String buyerId);

    ProductOrderEntity orderDetail(String orderId, String currentUserId);

    List<ProductOrderEntity> listMineOrders(String role, String userId, int page, int pageSize);

    long countMineOrders(String role, String userId);

    // ==================== 资金补偿扫描读口（支付回调重放 / 孤儿扫描的跨域编排取数） ====================

    /** 按 id 读订单（补偿扫描 / 支付回调重放用，无权限守卫）；不存在返回 null */
    ProductOrderEntity getOrderEntity(String orderId);

    /** 近窗口终态订单（资金孤儿扫描第二段输入：窗口分钟数 + 单轮上限） */
    List<ProductOrderEntity> listRecentSettled(int windowMinutes, int limit);

    /** 订单最新事件行（资金补偿判据）；无事件返回 null */
    ProductOrderEventVO latestEventOf(String orderId);

    /** 待支付超时（超时中心回调触发）：PENDING_PAYMENT → CANCELLED + 商品回 ON_SALE（幂等忽略） */
    void handlePayTimeout(String orderId);

    // ==================== 站内搜索：索引增量同步 / 全量重建 ====================

    /**
     * 站内搜索：关键词命中本域仓储的倒排索引后回库组装成实体（相关度顺序保持），
     * 只返回 ON_SALE 商品——索引侧状态过滤是冗余兜底，这里才是最终判据
     */
    SearchPageVO<ProductEntity> searchProducts(String keyword, int page, int pageSize);

    /** 搜索文档同步：按 id 反查，仍在售则写入索引、否则删除（幂等自愈，重复消息无副作用） */
    void syncSearchDoc(String productId);

    /** 商品索引全量重建（清空 + 游标分批回填），返回回填条数 */
    int rebuildSearchIndex();
}
