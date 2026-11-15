package cn.inventory.domain.stock.adapter.repository;

import cn.inventory.domain.stock.model.entity.StockEntity;
import cn.inventory.domain.stock.service.merge.MergeTask;

import java.util.List;

/**
 * 库存仓储（一域一仓储，读写同一接口）
 *
 * <p>合并引擎把整批待落库请求直接交给 {@link #deductBatch}，SKU 分组、失败二分拆批、
 * 售罄短路、唯一键幂等裁决与事务落库全部在实现内完成——域侧不自持事务边界、
 * 也不注入 TransactionTemplate。
 *
 * <p><b>事务落位在实现里</b>：合并落库的「流水先行插入 + 条件扣减」
 * 必须收在一个本地事务里，由实现用编程式事务（传播恒 REQUIRED）保证。
 */
public interface IStockRepository {

    /**
     * 合并落库一批（可能混多个 SKU），并把结果回填到每个任务的 future。
     *
     * <p>同一 SKU 的整批压成一次事务：批量写流水（唯一键幂等）+ 一条 UPDATE 扣 N 件。
     * 批内混多个 SKU（分片哈希碰撞）时先按 SKU 分组，保证一次事务只碰一行；
     * 整批扣不动时二分拆批、先到先得，库存连批内最小请求量都不够则整批判死。
     *
     * @param batch 按到达顺序排列的待落库请求（取自同一分片缓冲区）
     */
    void deductBatch(List<MergeTask> batch);

    /**
     * 只读查库存实体（库存查询用；售罄短路在实现内部调用 {@code query}），事务外读、不加锁。
     *
     * @return 库存实体；库存行不存在时 {@code qty} 为 null（不返回 null，免得调用方到处判空）
     */
    StockEntity query(long skuId);

    /** 重置库存并清空该 SKU 流水（两轮压测之间使用） */
    void reset(long skuId, int qty);
}
