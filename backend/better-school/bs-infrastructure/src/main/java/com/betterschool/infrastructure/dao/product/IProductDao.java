package com.betterschool.infrastructure.dao.product;

import com.betterschool.domain.trade.model.valobj.ProductFeedCursorVO;
import com.betterschool.domain.trade.model.valobj.ProductFeedIndexItemVO;
import com.betterschool.infrastructure.dao.product.po.ProductFeedIndexRowPO;
import com.betterschool.infrastructure.dao.product.po.ProductPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品 Mapper：SQL 定义在 app 模块 resources/mybatis/mapper/ProductMapper.xml
 */
@Mapper
public interface IProductDao {

    int insert(ProductPO productPO);

    /** 详情内容查询：纯 product 单表（view_count 与卖家昵称头像仓储回查拼装，SQL 层零 JOIN） */
    ProductPO selectById(@Param("productId") String productId);

    /** 浏览 UV 加速列批量读：按 id 集单表 IN 查询，仓储补 0 拼装（详情与列表共用） */
    List<ProductPO> selectViewCountsByProductIds(@Param("productIds") List<String> productIds);

    int softDelete(@Param("productId") String productId);

    /** 按 id 列表查询（不分状态，搜索同步反查/搜索结果组装用） */
    List<ProductPO> selectByIds(@Param("ids") List<String> productIds);

    /** 搜索索引全量重建的游标页：业务 ID 字典序，product_id > lastProductId 升序取 limit 条（uuid-biz-id 票 05） */
    List<String> selectIdsAfter(@Param("lastProductId") String lastProductId, @Param("limit") int limit);

    int markSold(@Param("productId") String productId);

    int relist(@Param("productId") String productId);

    /** 覆盖草稿内容：仅 DRAFT 行生效 */
    int updateDraft(ProductPO productPO);

    /** 提交草稿：DRAFT → PENDING_REVIEW */
    int submitDraft(@Param("productId") String productId);

    /** 撤回待审：PENDING_REVIEW → DRAFT */
    int withdraw(@Param("productId") String productId);

    /** 下架/到期汇入草稿箱：ON_SALE → DRAFT */
    int takeDown(@Param("productId") String productId);

    /** 到期扫描：ON_SALE 且 expire_at 早于 before 的商品 id 列表 */
    List<String> selectExpiredOnSaleIds(@Param("before") LocalDateTime before, @Param("limit") int limit);

    /** 24h 提醒扫描：PENDING_REVIEW 且 review_entered_at 早于 before 的商品 id 列表 */
    List<String> selectReviewTimeoutIds(@Param("before") LocalDateTime before, @Param("limit") int limit);

    /** 审核通过：PENDING_REVIEW → ON_SALE */
    int reviewApproveById(@Param("productId") String productId);

    /** 审核拒绝：PENDING_REVIEW → REJECTED */
    int reviewRejectById(@Param("productId") String productId);

    /** 被拒转草稿箱：REJECTED → DRAFT */
    int rejectToDraft(@Param("productId") String productId);

    /** 上架后改内容：ON_SALE → PENDING_REVIEW（轮次 +1，动态覆盖非 null 字段） */
    int editOnSaleContent(ProductPO productPO);

    /** 上架后仅改价：价格立即生效，状态不变 */
    int updateOnSalePrice(@Param("productId") String productId, @Param("price") BigDecimal price);

    /** 下单锁定：ON_SALE → LOCKED（0 行 = 已被交易/已下架） */
    int lock(@Param("productId") String productId);

    /** 解锁释放（下单取消/支付超时）：LOCKED → ON_SALE */
    int release(@Param("productId") String productId);

    /** 支付完成：LOCKED → SOLD */
    int lockToSold(@Param("productId") String productId);

    /** 旧直查降级路径 price/time 四档：ON_SALE SQL 分页（hot 档走 {@link #selectOnSaleRows} 内存排序） */
    List<ProductPO> selectPage(@Param("category") String category,
                               @Param("minPrice") BigDecimal minPrice,
                               @Param("maxPrice") BigDecimal maxPrice,
                               @Param("sort") String sort,
                               @Param("offset") int offset, @Param("limit") int limit);

    /** hot 档降级取数：ON_SALE 全集（筛选条件同 {@link #selectPage}，无 LIMIT），窄表批量回查后仓储内存排序切片 */
    List<ProductPO> selectOnSaleRows(@Param("category") String category,
                                     @Param("minPrice") BigDecimal minPrice,
                                     @Param("maxPrice") BigDecimal maxPrice);

    long count(@Param("category") String category,
               @Param("minPrice") BigDecimal minPrice,
               @Param("maxPrice") BigDecimal maxPrice);

    /**
     * 闲置推荐页索引序扫描（market-feed-uv 票 04）：只取 ON_SALE，排序键 / 游标谓词 / 排序方向随 sort 分支，
     * 走 (state, 排序键, product_id) 索引的 range scan。部分列直映域 VO（游标锚点要排序键随行带出）
     */
    List<ProductFeedIndexItemVO> selectFeedIndex(@Param("sort") String sort,
                                                 @Param("cursor") ProductFeedCursorVO cursor,
                                                 @Param("limit") int limit);

    int insertFavorite(@Param("productId") String productId, @Param("userId") String userId);

    int deleteFavorite(@Param("productId") String productId, @Param("userId") String userId);

    int existsFavorite(@Param("productId") String productId, @Param("userId") String userId);

    long countFavorites(@Param("productId") String productId);

    /** 我的收藏：收藏关系行主查（product_favorite 单表，物理 id DESC = 收藏先后倒序）；商品行与可见性仓储拼装 */
    List<String> selectFavoriteProductIdsByUser(@Param("userId") String userId);

    /** 我发布的商品分页（卖家视角）：含已售/已下架，排除软删 */
    List<ProductPO> selectPageBySeller(@Param("userId") String userId,
                                       @Param("offset") int offset, @Param("limit") int limit);

    long countBySeller(@Param("userId") String userId);

    /** 某用户在售商品分页（作者主页「闲置」tab，访客口径）：仅 ON_SALE */
    List<ProductPO> selectOnSalePageByUser(@Param("userId") String userId,
                                           @Param("offset") int offset, @Param("limit") int limit);

    long countOnSaleByUser(@Param("userId") String userId);

    // ── 商品浏览 UV 三步（market-feed-uv 票 02，照帖子 user_view_history / post_view_count 先例）──
    // 一组一个事务：INSERT IGNORE 明细（影响行数 = 真正新增的 UV 数）→ 全组刷 viewed_at → 加速列 += 新增数

    /** 第一步：UV 明细批量写，(user_id, product_id) 主键撞键裁决重复浏览 */
    int insertProductViewHistory(@Param("productId") String productId, @Param("userIds") List<String> userIds);

    /** 第二步：刷新本组全部键的浏览时刻（含刚插入的行）——重复浏览的唯一可见效果 */
    int touchProductViewedAt(@Param("productId") String productId, @Param("userIds") List<String> userIds);

    /** 第三步：加速列 upsert（行懒建 ODKU）；增量 = insertProductViewHistory 的影响行数 */
    int upsertProductViewCount(@Param("productId") String productId, @Param("delta") int delta);

    /** 当前用户浏览过的商品 id 集合（推荐列表已读灰显批量回显，走 (user_id, product_id) 主键前缀） */
    List<String> selectViewedProductIds(@Param("userId") String userId, @Param("productIds") List<String> productIds);

    // ── feed 异构索引同事务直写（market-feed-uv 票 03：索引行与 product 行同库，状态流转 / 改价 /
    //    浏览合并事务带写，照帖子 post_feed_index 先例）──

    /** 审核通过：行诞生 / 翻回 ON_SALE（INSERT...SELECT 取 price 快照；ODKU 刷新 state/feed_time/price） */
    int upsertFeedIndexOnApprove(@Param("productId") String productId);

    /** 状态快照同步：镜像 product 的新状态；行不存在（从未通过审）= 0 行静默，不 upsert */
    int updateFeedIndexState(@Param("productId") String productId, @Param("state") String state);

    /** 在售改价旁路同步（立即生效不进审核）：纯 UPDATE，价格排序键同事务跟上 */
    int updateFeedIndexPrice(@Param("productId") String productId, @Param("price") BigDecimal price);

    /** 浏览合并事务旁路 ±N：纯 UPDATE 不 upsert；0 行 = 索引行缺失，调用方落 warn 交重建 job */
    int updateFeedIndexViewCount(@Param("productId") String productId, @Param("delta") int delta);

    // ── feed 索引全量重建（market-feed-uv 票 06：兜底恢复手段，照帖子 IPostDao 同名先例）──
    // 各步由仓储收进一个事务（执行序：计数校准 → 清空索引 → 拼装重灌）：
    // 先算准计数窄表再抄进重灌行集，读者要么看到重建前、要么看到重建后的完整索引

    /** 清空索引表（DELETE 不用 TRUNCATE——TRUNCATE 隐式提交，会毁掉与重灌语句的原子性） */
    int clearFeedIndex();

    /** 重灌取数①：product 全量 (product_id, state, create_time, price)——行集 = ON_SALE ∪ 有过审批通过，仓储拼装 */
    List<ProductFeedIndexRowPO> selectRebuildRows();

    /** 重灌写回：仓储拼装好的行集批量 INSERT（feed_time = MAX(APPROVE) 兜底 create_time；view_count 抄计数窄表） */
    int insertFeedIndexRows(@Param("rows") List<ProductFeedIndexRowPO> rows);

    /** 浏览 UV 加速列校准收尾：清掉没有明细行的孤儿计数行（重投影由 {@link #recalibrateViewCounts} 完成） */
    int deleteViewCountsMissingHistory();

    /** 浏览 UV 加速列校准：product_view_count 重投影 = product_view_history 行数（ODKU 补缺行 / 刷漂移 / 幂等落回同值） */
    int recalibrateViewCounts();
}
