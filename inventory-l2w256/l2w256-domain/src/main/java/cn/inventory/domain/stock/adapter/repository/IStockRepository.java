package cn.inventory.domain.stock.adapter.repository;

import cn.inventory.domain.stock.model.entity.StockEntity;
import cn.inventory.domain.stock.model.valobj.DeductRequestVO;
import cn.inventory.domain.stock.model.valobj.MergedDeductResult;

import java.util.List;

/**
 * 库存仓储（一域一仓储，读写同一接口）
 *
 * <p><b>事务落位在实现里</b>：{@link #deductMerged} 的「流水先行插入 + 条件扣减」
 * 必须收在一个本地事务里，由实现用编程式事务（传播恒 REQUIRED）保证。
 * 域服务只调一次仓储方法，不自持事务边界、也不注入 TransactionTemplate。
 */
public interface IStockRepository {

    /**
     * 合并落库：一个事务内完成「批量写流水（唯一键幂等）+ 一条 UPDATE 扣 N 件」。
     *
     * @param skuId 本批全部请求同属一个 SKU（调用方已按 SKU 分组）
     * @param batch 按到达顺序排列的请求
     * @return {@code deducted=false} 表示库存不足、整批未动（事务已回滚，流水一并消失）
     */
    MergedDeductResult deductMerged(long skuId, List<DeductRequestVO> batch);

    /**
     * 只读查库存实体（售罄短路 + 库存查询共用），事务外读、不加锁。
     * <p>批量 UPDATE 影响 0 行时，用它区分「整批都不够」和「批里几条不够」：
     * 前者整批判失败，省掉二分拆批的 399 次事务。
     *
     * @return 库存实体；库存行不存在时 {@code qty} 为 null（不返回 null，免得调用方到处判空）
     */
    StockEntity query(long skuId);

    /** 重置库存并清空该 SKU 流水（两轮压测之间使用） */
    void reset(long skuId, int qty);
}
