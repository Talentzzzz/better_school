package cn.inventory.infrastructure.adapter.repository;

import cn.inventory.domain.stock.adapter.repository.IStockRepository;
import cn.inventory.domain.stock.model.entity.StockEntity;
import cn.inventory.domain.stock.model.valobj.DeductRequestVO;
import cn.inventory.domain.stock.model.valobj.MergedDeductResult;
import cn.inventory.infrastructure.dao.IStockDao;
import cn.inventory.infrastructure.dao.po.StockDetailPO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 库存仓储实现（一域一仓储，实现内事务落位）
 *
 * <h2>一次合并落库 = 一个原子用例</h2>
 * <pre>
 *   BEGIN
 *     INSERT IGNORE INTO stock_detail (...) VALUES (...),(...)   -- 流水先行，唯一键裁决幂等
 *     UPDATE stock SET qty = qty - N WHERE sku_id = ? AND qty >= N
 *   COMMIT
 * </pre>
 * 两条 SQL 的关系不能颠倒：「流水先行 + 同事务」保证了<b>不会出现「记了流水没扣库存」</b>——
 * 扣不动就整体回滚，流水一并消失。反过来先扣库存再写流水，一旦写流水失败就成了「扣了没记账」，
 * 对账无从下手。
 *
 * <p>幂等：{@code INSERT IGNORE} 撞 {@code uk_biz_no} 时返回 0 行。整批都是重放 → 扣减量为 0、
 * 直接提交；只重放了一部分 → 把已扣的份额从本次扣减量里剔除，其余照扣。
 * 唯一键是<b>唯一</b>裁决者，内存里不做去重——多实例、重启、重放语义都一样。
 *
 * <p>事务用编程式 {@link TransactionTemplate}（传播恒 REQUIRED）而不是 {@code @Transactional}：
 * 仓储方法自己就是事务边界，域服务只调一次、不感知事务，也不会出现「域服务套事务」的层级混乱。
 */
@Slf4j
@Repository
public class StockRepository implements IStockRepository {

    /** 清流水的单批行数：一轮 20k TPS × 几分钟 = 百万行，单事务全删会失败 */
    private static final int CLEAR_BATCH_SIZE = 5_000;

    private final IStockDao stockDao;
    private final TransactionTemplate transactionTemplate;

    public StockRepository(IStockDao stockDao, TransactionTemplate stockTransactionTemplate) {
        this.stockDao = stockDao;
        this.transactionTemplate = stockTransactionTemplate;
    }

    @Override
    public MergedDeductResult deductMerged(long skuId, List<DeductRequestVO> batch) {
        if (batch == null || batch.isEmpty()) {
            return MergedDeductResult.OK;
        }
        int totalQty = 0;
        List<StockDetailPO> details = new ArrayList<>(batch.size());
        for (DeductRequestVO request : batch) {
            totalQty += request.getQty();
            details.add(StockDetailPO.builder()
                    .bizNo(request.getBizNo())
                    .skuId(skuId)
                    .qty(request.getQty())
                    .status(1)
                    .build());
        }
        final int batchQty = totalQty;
        final int batchSize = batch.size();

        MergedDeductResult result = transactionTemplate.execute(status -> {
            int inserted = stockDao.batchInsertDetail(details);
            int deductQty = batchQty;
            Set<String> duplicates = Set.of();

            if (inserted < batchSize) {
                // 批内混进了已处理过的 bizNo：它们的 qty 之前已经扣过，必须从本次扣减量里剔除。
                // （流水存在 ⟹ 首次扣减已成功：流水与 UPDATE 同事务，失败会一起回滚，不会留残痕）
                List<String> bizNos = new ArrayList<>(batchSize);
                for (DeductRequestVO request : batch) {
                    bizNos.add(request.getBizNo());
                }
                List<StockDetailPO> existed = stockDao.listDetailsByBizNos(bizNos);
                int existedQty = 0;
                Set<String> existedBizNos = new HashSet<>(Math.max(4, existed.size() * 2));
                for (StockDetailPO po : existed) {
                    existedQty += po.getQty();
                    existedBizNos.add(po.getBizNo());
                }
                deductQty = batchQty - existedQty;
                duplicates = existedBizNos;
            }

            if (deductQty <= 0) {
                // 整批都是重放：无需扣减，提交即可（新增流水 0 行，等于什么都没发生）
                return new MergedDeductResult(true, duplicates);
            }
            int affected = stockDao.deduct(skuId, deductQty);
            if (affected == 0) {
                // 库存不够：整体回滚，先插的流水一并消失
                status.setRollbackOnly();
                return MergedDeductResult.INSUFFICIENT;
            }
            return new MergedDeductResult(true, duplicates);
        });
        return result == null ? MergedDeductResult.INSUFFICIENT : result;
    }

    @Override
    public StockEntity query(long skuId) {
        return new StockEntity(skuId, stockDao.getQty(skuId));
    }

    @Override
    public void reset(long skuId, int qty) {
        int clearedTotal = 0;
        int cleared;
        do {
            Integer affected = transactionTemplate.execute(status -> stockDao.clearDetailBatch(skuId, CLEAR_BATCH_SIZE));
            cleared = affected == null ? 0 : affected;
            clearedTotal += cleared;
        } while (cleared >= CLEAR_BATCH_SIZE);

        transactionTemplate.executeWithoutResult(status -> stockDao.upsertStock(skuId, qty));
        log.info("库存重置完成 skuId={} qty={} 清理流水={} 行", skuId, qty, clearedTotal);
    }
}
