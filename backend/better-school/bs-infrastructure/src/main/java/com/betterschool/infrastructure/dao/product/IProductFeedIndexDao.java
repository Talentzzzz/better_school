package com.betterschool.infrastructure.dao.product;

import com.betterschool.domain.trade.model.valobj.ProductFeedCursorVO;
import com.betterschool.domain.trade.model.valobj.ProductFeedIndexItemVO;
import com.betterschool.infrastructure.dao.product.po.ProductFeedIndexRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

/**
 * 闲置商品 feed 异构索引 Mapper（product_feed_index 单表）：SQL 定义在 app 模块 resources/mybatis/mapper/ProductFeedIndexMapper.xml
 */
@Mapper
public interface IProductFeedIndexDao {

    /**
     * 闲置推荐页索引序扫描（market-feed-uv 票 04）：只取 ON_SALE，排序键 / 游标谓词 / 排序方向随 sort 分支，
     * 走 (state, 排序键, product_id) 索引的 range scan。部分列直映域 VO（游标锚点要排序键随行带出）
     */
    List<ProductFeedIndexItemVO> selectFeedIndex(@Param("sort") String sort,
                                                 @Param("cursor") ProductFeedCursorVO cursor,
                                                 @Param("limit") int limit);

    /** 审核通过：行诞生 / 翻回 ON_SALE（INSERT...SELECT 取 price 快照；ODKU 刷新 state/feed_time/price） */
    int upsertFeedIndexOnApprove(@Param("productId") String productId);

    /** 状态快照同步：镜像 product 的新状态；行不存在（从未通过审）= 0 行静默，不 upsert */
    int updateFeedIndexState(@Param("productId") String productId, @Param("state") String state);

    /** 在售改价旁路同步（立即生效不进审核）：纯 UPDATE，价格排序键同事务跟上 */
    int updateFeedIndexPrice(@Param("productId") String productId, @Param("price") BigDecimal price);

    /** 浏览合并事务旁路 ±N：纯 UPDATE 不 upsert；0 行 = 索引行缺失，调用方落 warn 交重建 job */
    int updateFeedIndexViewCount(@Param("productId") String productId, @Param("delta") int delta);

    // ── feed 索引全量重建（market-feed-uv 票 06：兜底恢复手段，照帖子 IPostFeedIndexDao 同名先例）──
    // 各步由仓储收进一个事务（执行序：计数校准 → 清空索引 → 拼装重灌）：
    // 先算准计数窄表再抄进重灌行集，读者要么看到重建前、要么看到重建后的完整索引

    /** 清空索引表（DELETE 不用 TRUNCATE——TRUNCATE 隐式提交，会毁掉与重灌语句的原子性） */
    int clearFeedIndex();

    /** 重灌写回：仓储拼装好的行集批量 INSERT（feed_time = MAX(APPROVE) 兜底 create_time；view_count 抄计数窄表） */
    int insertFeedIndexRows(@Param("rows") List<ProductFeedIndexRow> feedIndexRowList);
}
