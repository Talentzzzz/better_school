package cn.inventory.infrastructure.dao;

import cn.inventory.infrastructure.dao.po.StockDetailPO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 库存 DAO（MyBatis Mapper）
 *
 * <p>SQL 全部在 {@code l2w256-app/src/main/resources/mybatis/mapper/StockDao.xml}。
 * 接口里只保留「一次调用 = 一条 SQL」，事务边界一律由仓储持有——DAO 不知道自己是否在事务里，
 * 这样同一条 SQL 既能被单笔扣减复用，也能被合并落库复用。
 */
public interface IStockDao {

    /** 单语句条件扣减：affected=1 成功，=0 库存不足（DB 兜底 qty >= n，绝不扣负） */
    int deduct(@Param("skuId") long skuId, @Param("qty") int qty);

    /** 批量写流水（INSERT IGNORE）：返回实际新增行数，&lt; 入参大小即说明有 bizNo 命中幂等 */
    int batchInsertDetail(@Param("list") List<StockDetailPO> details);

    /** 按 bizNo 集合捞已存在的流水（仅落库事务内发现重复时调用，用来剔除已扣份额） */
    List<StockDetailPO> listDetailsByBizNos(@Param("bizNos") List<String> bizNos);

    /** 只读剩余库存（售罄短路 / 库存查询用，不加锁） */
    Integer getQty(@Param("skuId") long skuId);

    /** upsert 库存：SKU 不存在则创建，存在则改数 */
    int upsertStock(@Param("skuId") long skuId, @Param("qty") int qty);

    /** 分批清理流水：一轮高吞吐会产生数十万行，单事务全删会撞行锁上限并产生长事务 */
    int clearDetailBatch(@Param("skuId") long skuId, @Param("limit") int limit);
}
