package com.betterschool.domain.idle.adapter.repository;

import com.betterschool.domain.idle.model.entity.ProductEntity;
import com.betterschool.domain.idle.model.entity.ProductOrderEntity;
import com.betterschool.domain.idle.model.valobj.ProductCategoryEnum;
import com.betterschool.domain.idle.model.valobj.ProductFeedCursorVO;
import com.betterschool.domain.idle.model.valobj.ProductFeedIndexItemVO;
import com.betterschool.domain.idle.model.valobj.ProductOrderEventVO;
import com.betterschool.types.common.SearchPageVO;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 交易仓储（闲置商品 + 商品托管订单合一，一域一仓储）：覆盖 idle 领域服务所需全部持久化能力，
 * 由 infrastructure 实现（实现内持 IProductDao / IProductOrderDao 及其拆分出的按表 DAO
 * （收藏 / feed 索引 / 浏览明细 / 计数窄表 / 订单事件）+ 共享 outbox store）。
 * 商品锁原语（lock/release/lockToSold）与订单状态变更同属本接口，故跨两表的改写收成一个方法、
 * 事务在实现内（编程式，默认 REQUIRED）。
 * 商品与订单都有同名能力（save/findById），按域限定名区分：订单侧带 Order。
 */
public interface IIdleRepository {

    // ---------- 商品 ----------

    /**
     * 落一行商品（uuid-biz-id 票 05：发号在实现内落库前完成，业务 id 随行写入并回填入参实体）；
     * 同事务建库存台账行（product_inventory，quantity 取实体入参，null = 1）并落 INIT 流水
     */
    void save(ProductEntity productEntity);

    ProductEntity findById(String productId);

    /** 覆盖草稿内容（仅 DRAFT 行生效；quantity 非 null 时同事务覆盖台账件数） */
    void updateDraft(ProductEntity productEntity);

    /** 提交草稿：DRAFT -> ON_SALE，返回 false = 状态已变化 */
    boolean submitDraft(String productId);

    /** 上架后改内容：仅非 null 字段覆盖、状态不变（立即生效），返回 false = 状态已变化 */
    boolean editOnSaleContent(ProductEntity productEntity);

    /** 上架后仅改价：价格立即生效，状态不变，返回 false = 状态已变化 */
    boolean updateOnSalePrice(String productId, BigDecimal price);

    /** 下架/到期汇入草稿箱：ON_SALE -> DRAFT，返回 false = 状态已变化 */
    boolean takeDown(String productId);

    /** 到期扫描：ON_SALE 且 expire_at 早于 before 的商品 id 列表 */
    List<String> listExpiredOnSaleIds(LocalDateTime before, int limit);

    /** 软删：status -> DELETED */
    void deleteById(String productId);

    /**
     * 标记已出：ON_SALE -> SOLD（同事务清零台账 remaining + 落 ADJUST 流水 + 索引行翻 SOLD）
     */
    void markSold(String productId);

    /**
     * 重新上架：SOLD -> ON_SALE（同事务索引行翻回 + 搜索同步消息）。
     * 台账 remaining = 0（真卖完）或台账行缺失返回 false，调用方报"无库存不可重上架"
     */
    boolean relist(String productId);

    /** 卖家加货：台账 quantity/remaining 同涨 + RESTOCK 流水，同一事务；仅 ON_SALE 可加货（0 行/状态不符抛错） */
    void restock(String productId, int quantity, String actorId);

    List<ProductEntity> pageList(ProductCategoryEnum productCategoryVO, BigDecimal minPrice, BigDecimal maxPrice,
                                 String sort, int offset, int limit);

    long count(ProductCategoryEnum productCategoryVO, BigDecimal minPrice, BigDecimal maxPrice);

    /**
     * 闲置推荐页索引序扫描（market-feed-uv 票 04）：product_feed_index 只取 ON_SALE，按
     * (排序键, product_id) 索引序出 id（hot = view_count、time / time_asc = feed_time、price_* = price），
     * 时间升序是同一索引（idx_state_feed）的正向扫描、降序反向；cursor 非 null 时按
     * {@code WHERE (排序键, product_id) < / > (?, ?)} 续页。排序键随行带出（游标锚点用）；
     * 查询异常向上抛，由调用方降级直查
     */
    List<ProductFeedIndexItemVO> scanFeedIndex(String sortType, ProductFeedCursorVO productFeedCursorVO, int limit);

    /**
     * feed 索引全量重建（market-feed-uv 票 06）：清空重灌 product_feed_index（数据源 = product +
     * product_view_count）并把 product_view_count 校准成 product_view_history 行数，
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
    List<ProductEntity> listByIds(List<String> productIdList);

    /** 搜索索引全量重建的游标页：业务 ID 字典序，product_id > lastProductId 升序取 limit 条（无界结果集一律游标化；uuid-biz-id 票 05） */
    List<String> listIdsAfter(String lastProductId, int limit);

    // ---------- 搜索索引（技术能力，落本域仓储：backend/CLAUDE.md §1） ----------

    /** 商品检索：ES 命中只回 id（相关度倒序）与近似总数，展示字段由领域服务回库组装 */
    SearchPageVO<String> searchProductIds(String keyword, int page, int pageSize);

    /** 按 id 反查商品刷新搜索文档：仍 ON_SALE 则写入、否则删除（幂等自愈，重复消息无副作用） */
    void syncProductSearchDoc(String productId);

    /** 商品索引全量重建（清空 + 游标分批回填），返回回填条数 */
    int rebuildProductSearchIndex();

    // ---------- 商品托管订单 ----------

    /** 落一行订单（uuid-biz-id 票 05：发号在实现内落库前完成，业务 id 随行写入并回填入参实体），并落 CREATED 状态流转事件 */
    void saveOrder(ProductOrderEntity productOrderEntity);

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

    // ---------- 跨商品、库存与订单的原子用例（实现内编程式事务，REQUIRED） ----------

    /**
     * 下单原子（multi-quantity-listing，替代原 createOrderWithLock）：订单落库（含 quantity）
     * + 台账条件 UPDATE 扣减（remaining >= quantity，0 行即抛 BUSINESS_ERROR 回滚——防超卖唯一闸门）
     * + DEDUCT 库存流水 + 扣到 0 时商品 ON_SALE → SOLD（索引行翻 SOLD + 搜索同步消息随行）
     * + 支付超时注册。全部一个事务，返回订单 id
     */
    String createOrderWithInventory(ProductOrderEntity productOrderEntity, String productId, int payTimeoutMinutes);

    /**
     * 买家取消：订单条件 UPDATE（PENDING_PAYMENT/PAID → CANCELLED）+ 台账回补（按订单 quantity）
     * + RELEASE 库存流水 + 回补后 remaining > 0 且商品 SOLD 时翻回 ON_SALE（索引行随行）
     * + 搜索同步消息，同一事务；返回 false = 0 行（状态已变化）
     */
    boolean cancelOrderWithSearchSync(String orderId, String buyerId);

    /**
     * 待支付超时取消：订单条件 UPDATE（PENDING_PAYMENT → CANCELLED）+ 台账回补 + RELEASE 流水
     * + 回补翻态 + 搜索同步消息，同一事务；返回 false = 0 行（已支付/已取消，幂等跳过）
     */
    boolean cancelPendingPaymentWithSearchSync(String orderId);

    /**
     * 孤儿扫描输入（ADR-0016 两段式）：近窗口进入终态的订单（按 update_time 升序），
     * 供扫描用例结合事件行裁决补解冻；窗口重扫幂等（资金动作全部流水唯一键吸收）
     */
    List<ProductOrderEntity> listRecentSettled(int windowMinutes, int limit);

    /** 订单最新一条状态流转事件（同订单 id 递增即状态变更顺序）：孤儿扫描的已支付取消判据事实源 */
    ProductOrderEventVO latestEventOf(String orderId);

    // ---------- 商品状态变更 + 搜索同步（实现内编程式事务，REQUIRED） ----------

    /** 在售退出可见态：条件 UPDATE ON_SALE → DRAFT + 搜索同步消息，同一事务；返回 false = 0 行 */
    boolean takeDownWithSearchSync(String productId);

    /** 上架后改内容（立即生效，价格快照同事务跟索引）：+ 搜索同步消息，同一事务；0 行即抛 BUSINESS_ERROR（提交前回滚） */
    void editOnSaleContentWithSearchSync(ProductEntity productEntity);

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
    List<String> selectViewedProductIds(String userId, List<String> productIdList);
}
