package com.betterschool.domain.trade.adapter.repository;

import com.betterschool.domain.trade.model.entity.ProductEntity;
import com.betterschool.domain.trade.model.entity.ProductOrderEntity;
import com.betterschool.domain.trade.model.valobj.ProductCategoryVO;
import com.betterschool.domain.trade.model.valobj.ProductFeedCursorVO;
import com.betterschool.domain.trade.model.valobj.ProductFeedIndexItemVO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 交易仓储（闲置商品 + 商品托管订单合一，一域一仓储）：覆盖 trade 领域服务所需全部持久化能力，
 * 由 infrastructure 实现（实现内持 IProductDao + IProductOrderDao + 共享 outbox store）。
 * 商品锁原语（lock/release/lockToSold）与订单状态变更同属本接口，故跨两表的改写收成一个方法、
 * 事务在实现内（编程式，默认 REQUIRED）。
 * 商品与订单都有同名能力（save/findById），按域限定名区分：订单侧带 Order。
 */
public interface ITradeRepository {

    // ---------- 商品 ----------

    /** 落一行商品（uuid-biz-id 票 05：发号在实现内落库前完成，业务 id 随行写入并回填入参实体） */
    void save(ProductEntity productEntity);

    ProductEntity findById(String productId);

    /** 覆盖草稿内容（仅 DRAFT 行生效） */
    void updateDraft(ProductEntity productEntity);

    /** 提交草稿：DRAFT -> PENDING_REVIEW，返回 false = 状态已变化 */
    boolean submitDraft(String productId);

    /** 撤回待审：PENDING_REVIEW -> DRAFT，返回 false = 状态已变化 */
    boolean withdraw(String productId);

    /** 被拒转草稿箱：REJECTED -> DRAFT，返回 false = 状态已变化 */
    boolean rejectToDraft(String productId);

    /** 上架后改内容：ON_SALE -> PENDING_REVIEW（轮次 +1，仅非 null 字段覆盖），返回 false = 状态已变化 */
    boolean editOnSaleContent(ProductEntity productEntity);

    /** 上架后仅改价：价格立即生效，状态不变，返回 false = 状态已变化 */
    boolean updateOnSalePrice(String productId, BigDecimal price);

    /** 下架/到期汇入草稿箱：ON_SALE -> DRAFT，返回 false = 状态已变化 */
    boolean takeDown(String productId);

    /** 到期扫描：ON_SALE 且 expire_at 早于 before 的商品 id 列表 */
    List<String> listExpiredOnSaleIds(LocalDateTime before, int limit);

    /** 24h 提醒扫描：PENDING_REVIEW 且 review_entered_at 早于 before 的商品 id 列表 */
    List<String> listReviewTimeoutIds(LocalDateTime before, int limit);

    /** 审核通过：PENDING_REVIEW -> ON_SALE，返回 false = 状态已变化 */
    boolean reviewApproveById(String productId);

    /** 审核拒绝：PENDING_REVIEW -> REJECTED，返回 false = 状态已变化 */
    boolean reviewRejectById(String productId);

    /** 软删：status -> DELETED */
    void deleteById(String productId);

    /** 标记已出：ON_SALE -> SOLD */
    void markSold(String productId);

    /** 重新上架：SOLD -> ON_SALE */
    void relist(String productId);

    /** 下单锁定：ON_SALE -> LOCKED（条件 UPDATE，0 行 = 已被交易） */
    boolean lock(String productId);

    /** 解锁释放（下单后取消/支付超时）：LOCKED -> ON_SALE */
    boolean release(String productId);

    /** 支付完成转已出：LOCKED -> SOLD */
    boolean lockToSold(String productId);

    List<ProductEntity> pageList(ProductCategoryVO category, BigDecimal minPrice, BigDecimal maxPrice,
                                 String sort, int offset, int limit);

    long count(ProductCategoryVO category, BigDecimal minPrice, BigDecimal maxPrice);

    /**
     * 闲置推荐页索引序扫描（market-feed-uv 票 04）：product_feed_index 只取 ON_SALE，按
     * (排序键, product_id) 索引序出 id（hot = view_count、time / time_asc = feed_time、price_* = price），
     * 时间升序是同一索引（idx_state_feed）的正向扫描、降序反向；cursor 非 null 时按
     * {@code WHERE (排序键, product_id) < / > (?, ?)} 续页。排序键随行带出（游标锚点用）；
     * 查询异常向上抛，由调用方降级直查
     */
    List<ProductFeedIndexItemVO> scanFeedIndex(String sortType, ProductFeedCursorVO cursor, int limit);

    /**
     * feed 索引全量重建（market-feed-uv 票 06）：清空重灌 product_feed_index（数据源 = product +
     * content_review + product_view_count）并把 product_view_count 校准成 product_view_history 行数，
     * 四条语句一个事务——读者要么看到重建前、要么看到重建后的完整索引，不见空窗。
     * 兜底场景 = 状态流转/改价/浏览旁路的同事务直写留下漂移（索引行缺失、计数窄表被绕过直改）
     */
    void rebuildFeedIndex();

    /** 收藏记录入库，user+product 唯一冲突返回 false */
    boolean addFavorite(String productId, String userId);

    boolean removeFavorite(String productId, String userId);

    boolean isFavorited(String productId, String userId);

    long countFavorites(String productId);

    List<ProductEntity> listFavorites(String userId, int offset, int limit);

    /** 我发布的商品分页（卖家视角）：含已售/已下架，排除软删 */
    List<ProductEntity> pageBySeller(String userId, int offset, int limit);

    long countBySeller(String userId);

    /** 某用户在售商品分页（作者主页「闲置」tab，访客口径）：仅 ON_SALE */
    List<ProductEntity> pageOnSaleByUser(String userId, int offset, int limit);

    long countOnSaleByUser(String userId);

    /** 按 id 列表查询（不分状态，搜索同步/搜索命中反查用；返回顺序与入参无关，调用方按 id 重排） */
    List<ProductEntity> listByIds(List<String> productIds);

    /** 搜索索引全量重建的游标页：业务 ID 字典序，product_id > lastProductId 升序取 limit 条（无界结果集一律游标化；uuid-biz-id 票 05） */
    List<String> listIdsAfter(String lastProductId, int limit);

    /** 落一条"搜索索引同步"待发送消息（必须与商品状态变更同事务调用，实现层写 outbox 表） */
    void saveSearchSyncMessage(String productId);

    // ---------- 商品托管订单 ----------

    /** 落一行订单（uuid-biz-id 票 05：发号在实现内落库前完成，业务 id 随行写入并回填入参实体），并落 CREATED 状态流转事件 */
    void saveOrder(ProductOrderEntity orderEntity);

    ProductOrderEntity findOrderById(String orderId);

    /** 支付：PENDING_PAYMENT → PAID */
    boolean payOrderById(String orderId, String buyerId);

    /** 卖家标记交付：PAID → DELIVERED */
    boolean markDeliveredOrderById(String orderId, String sellerId);

    /** 买家确认收货：DELIVERED → COMPLETED */
    boolean confirmOrderById(String orderId, String buyerId);

    /** 取消：PENDING_PAYMENT/PAID → CANCELLED */
    boolean cancelOrderById(String orderId, String buyerId);

    /** 待支付超时取消：PENDING_PAYMENT → CANCELLED（未冻结无退款） */
    boolean cancelPendingPaymentOrder(String orderId);

    List<ProductOrderEntity> listMineByBuyer(String userId, int offset, int limit);

    long countMineByBuyer(String userId);

    List<ProductOrderEntity> listMineBySeller(String userId, int offset, int limit);

    long countMineBySeller(String userId);

    /**
     * 登记"15 分钟未支付"超时任务（必须与下单同事务调用，实现层直插 timeout_task 分表）；
     * actionTime = 理论到期时刻，超时行随业务事务同生同死（内嵌超时中心票 03）
     */
    void schedulePayTimeout(String orderId, long delaySeconds);

    // ---------- 跨商品与订单的原子用例（实现内编程式事务，REQUIRED） ----------

    /**
     * 下单四写原子：订单落库 + 商品锁定（条件 UPDATE，0 行即抛 BUSINESS_ERROR 并回滚）
     * + 搜索同步消息（锁定后商品退出搜索）+ 支付超时注册（到期时刻在事务内按 payTimeoutMinutes 算）。
     * 返回订单 id
     */
    String createOrderWithLock(ProductOrderEntity orderEntity, String productId, int payTimeoutMinutes);

    /**
     * 支付：订单 PAID + 搜索同步消息（保持文档不存在），同一事务；返回 false = 0 行（状态已变化）；
     * 商品 id 在实现内按 orderId 反查（与改造前 payAndSync 同口径）
     */
    boolean payOrderWithSearchSync(String orderId, String buyerId);

    /**
     * 确认收货：订单 DELIVERED → COMPLETED + 商品 LOCKED → SOLD（幂等，0 行无妨）
     * + 搜索同步消息，同一事务；返回 false = 0 行（状态已变化）
     */
    boolean confirmOrderWithSearchSync(String orderId, String buyerId);

    /**
     * 买家取消：订单条件 UPDATE（PENDING_PAYMENT/PAID → CANCELLED）+ 商品释放回在售
     * + 搜索同步消息，同一事务；返回 false = 0 行（状态已变化）
     */
    boolean cancelOrderWithSearchSync(String orderId, String buyerId);

    /**
     * 待支付超时取消：订单条件 UPDATE（PENDING_PAYMENT → CANCELLED）+ 商品释放
     * + 搜索同步消息，同一事务；返回 false = 0 行（已支付/已取消，幂等跳过）
     */
    boolean cancelPendingPaymentWithSearchSync(String orderId);

    // ---------- 商品状态变更 + 搜索同步（实现内编程式事务，REQUIRED） ----------

    /** 在售退出可见态：条件 UPDATE ON_SALE → DRAFT + 搜索同步消息，同一事务；返回 false = 0 行 */
    boolean takeDownWithSearchSync(String productId);

    /** 上架后改内容回待审：条件 UPDATE（轮次 +1）+ 搜索同步消息，同一事务；0 行即抛 BUSINESS_ERROR（提交前回滚） */
    void editOnSaleContentWithSearchSync(ProductEntity productEntity);

    /** 审核通过：条件 UPDATE PENDING_REVIEW → ON_SALE + 搜索同步消息，同一事务；0 行即抛 BUSINESS_ERROR（提交前回滚） */
    void reviewApproveWithSearchSync(String productId);

    /** 软删 + 搜索同步消息（在售商品对外可见过才需同步），同一事务 */
    void deleteWithSearchSync(String productId, boolean publiclyVisible);

    /** 标记已出 ON_SALE → SOLD + 搜索同步消息，同一事务 */
    void markSoldWithSearchSync(String productId);

    /**
     * 商品浏览（UV 口径）：fire-and-forget，入队即返回，窗口过后一组一个事务——
     * {@code product_view_history} 明细 INSERT IGNORE + 全组刷 viewed_at + {@code product_view_count} 加速列
     * += 新增数（market-feed-uv 票 02，与帖子浏览同构）。
     *
     * <p><b>匿名（userId 为 null）不计入</b>：明细主键的一半是 user_id，无主无处可落——按
     * 「INSERT 成功才算一次浏览」的口径直接跳过（商品语义变更：旧口径匿名也累加 PV）。
     * 落库<b>允许丢</b>：失败只计数、不告警、不回补，也<b>不影响请求结果</b>（spec §1.2 / §3.2）。
     * HTTP 时延与合并窗口无关。
     */
    void recordProductView(String productId, String userId);

    /**
     * 当前用户在给定商品集合里浏览过的商品 id（闲置推荐页已读灰显批量回显，market-feed-uv 票 05）：
     * 走 {@code product_view_history} 主键 {@code (user_id, product_id)} 前缀，一页一次 IN 查询。
     * userId 为 null（未登录）或集合为空返回空集
     */
    List<String> selectViewedProductIds(String userId, List<String> productIds);
}
