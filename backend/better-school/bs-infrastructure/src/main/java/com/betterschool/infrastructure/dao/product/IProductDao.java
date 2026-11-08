package com.betterschool.infrastructure.dao.product;

import com.betterschool.infrastructure.dao.product.po.ProductFeedIndexRow;
import com.betterschool.infrastructure.dao.product.po.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品 Mapper（product 单表）：SQL 定义在 app 模块 resources/mybatis/mapper/ProductMapper.xml
 * （收藏 / feed 索引 / 浏览明细 / 计数窄表拆分至 IProductFavoriteDao / IProductFeedIndexDao /
 * IProductViewHistoryDao / IProductViewCountDao 及同名 Mapper XML）
 */
@Mapper
public interface IProductDao {

    int insert(Product product);

    /** 详情内容查询：纯 product 单表（view_count 与卖家昵称头像仓储回查拼装，SQL 层零 JOIN） */
    Product selectById(@Param("productId") String productId);

    int softDelete(@Param("productId") String productId);

    /** 按 id 列表查询（不分状态，搜索同步反查/搜索结果组装用） */
    List<Product> selectByIds(@Param("ids") List<String> productIdList);

    /** 搜索索引全量重建的游标页：业务 ID 字典序，product_id > lastProductId 升序取 limit 条（uuid-biz-id 票 05） */
    List<String> selectIdsAfter(@Param("lastProductId") String lastProductId, @Param("limit") int limit);

    int markSold(@Param("productId") String productId);

    int relist(@Param("productId") String productId);

    /** 覆盖草稿内容：仅 DRAFT 行生效 */
    int updateDraft(Product product);

    /** 提交草稿：DRAFT → ON_SALE */
    int submitDraft(@Param("productId") String productId);

    /** 下架/到期汇入草稿箱：ON_SALE → DRAFT */
    int takeDown(@Param("productId") String productId);

    /** 到期扫描：ON_SALE 且 expire_at 早于 before 的商品 id 列表 */
    List<String> selectExpiredOnSaleIds(@Param("before") LocalDateTime before, @Param("limit") int limit);

    /** 上架后改内容：状态保持 ON_SALE 不变，动态覆盖非 null 字段 */
    int editOnSaleContent(Product product);

    /** 上架后仅改价：价格立即生效，状态不变 */
    int updateOnSalePrice(@Param("productId") String productId, @Param("price") BigDecimal price);

    /** 下单锁定：ON_SALE → LOCKED（0 行 = 已被交易/已下架） */
    int lock(@Param("productId") String productId);

    /** 解锁释放（下单取消/支付超时）：LOCKED → ON_SALE */
    int release(@Param("productId") String productId);

    /** 支付完成：LOCKED → SOLD */
    int lockToSold(@Param("productId") String productId);

    /** 旧直查降级路径 price/time 四档：ON_SALE SQL 分页（hot 档走 {@link #selectOnSaleRows} 内存排序） */
    List<Product> selectPage(@Param("category") String category,
                               @Param("minPrice") BigDecimal minPrice,
                               @Param("maxPrice") BigDecimal maxPrice,
                               @Param("sort") String sort,
                               @Param("offset") int offset, @Param("limit") int limit);

    /** hot 档降级取数：ON_SALE 全集（筛选条件同 {@link #selectPage}，无 LIMIT），窄表批量回查后仓储内存排序切片 */
    List<Product> selectOnSaleRows(@Param("category") String category,
                                     @Param("minPrice") BigDecimal minPrice,
                                     @Param("maxPrice") BigDecimal maxPrice);

    long count(@Param("category") String category,
               @Param("minPrice") BigDecimal minPrice,
               @Param("maxPrice") BigDecimal maxPrice);

    /** 我发布的商品分页（卖家视角）：含已售/已下架，排除软删 */
    List<Product> selectPageBySeller(@Param("userId") String userId,
                                       @Param("offset") int offset, @Param("limit") int limit);

    long countBySeller(@Param("userId") String userId);

    /** 某用户在售商品分页（作者主页「闲置」tab，访客口径）：仅 ON_SALE */
    List<Product> selectOnSalePageByUser(@Param("userId") String userId,
                                           @Param("offset") int offset, @Param("limit") int limit);

    long countOnSaleByUser(@Param("userId") String userId);

    // ── feed 索引全量重建（market-feed-uv 票 06）：重灌取数①（清空/写回在 IProductFeedIndexDao）──

    /** 重灌取数①：product 全量 (product_id, state, create_time, price)——行集 = ON_SALE，仓储拼装 */
    List<ProductFeedIndexRow> selectRebuildRows();
}
