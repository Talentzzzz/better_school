package cn.inventory.infrastructure.adapter.repository;

import cn.inventory.domain.stock.adapter.repository.IStockRepository;
import cn.inventory.domain.stock.model.entity.StockEntity;
import cn.inventory.domain.stock.model.valobj.DeductRequestVO;
import cn.inventory.domain.stock.model.valobj.DeductResultVO;
import cn.inventory.domain.stock.model.valobj.MergeConfigVO;
import cn.inventory.domain.stock.model.valobj.MergedDeductResult;
import cn.inventory.domain.stock.service.merge.MergeTask;
import cn.inventory.infrastructure.dao.IStockDao;
import cn.inventory.infrastructure.dao.po.StockDetailPO;
import cn.inventory.types.enums.DeductStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 库存仓储实现（一域一仓储，实现内事务落位）
 *
 * <p>合并引擎（缓冲区）把整批请求直接交到这里，一批 → 一到几次事务 → 回填每个等待者：
 * 这是唯一碰数据库的地方，引擎对落库细节一无所知，只通过一个
 * {@code Consumer<List<MergeTask>>} 把批交过来。
 *
 * <h2>两个层次的合并</h2>
 * <ol>
 *   <li><b>同 SKU 合并</b>：一条 {@code UPDATE stock SET qty = qty - N} 把 N 次
 *       「加锁-提交-解锁」压成 1 次——这是 20k TPS 的来源。批内若混了多个 SKU
 *       （分片哈希碰撞），先按 SKU 分组，保证一次事务只碰一行；</li>
 *   <li><b>失败二分拆批</b>：整批扣不动（affected=0）时分半重试，先到先得地让能成功的先成功。
 *       最坏情况退化成 N 次单条事务——这就是为什么需要售罄短路（见下）。</li>
 * </ol>
 *
 * <h2>售罄短路（默认开）</h2>
 * 库存为 0 时批量 UPDATE 影响 0 行，光看 affected 分不清「整批都不够」和「批里几条不够」，
 * 原逻辑只能二分到单条：200 件要 399 次 UPDATE / 399 次事务，单批耗时直接越过 100ms 等待上限，
 * 请求在排队途中集体过期，队列全是死件。开短路后先用一次 {@code SELECT qty} 探库存，
 * 剩余量连批内<b>最小</b>请求量都不够时整批判失败，399 次事务压成 1 次 SELECT（实测 4,611 → 21,060）。
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
    private final TransactionTemplate stockTransactionTemplate;
    private final boolean soldoutShortcut;

    public StockRepository(IStockDao stockDao,
                           TransactionTemplate stockTransactionTemplate,
                           MergeConfigVO mergeConfig) {
        this.stockDao = stockDao;
        this.stockTransactionTemplate = stockTransactionTemplate;
        this.soldoutShortcut = mergeConfig.isSoldoutShortcut();
    }

    @Override
    public void deductBatch(List<MergeTask> batch) {
        if (batch == null || batch.isEmpty()) {
            return;
        }
        try {
            if (isSingleSku(batch)) {
                // 单热点（绝大多数字况）：连分组都不做，省一个 Map 的分配与装箱
                flushGroup(batch);
                return;
            }
            // 分组用 LinkedHashMap：保持「先到先得」的组间顺序，避免后到的 SKU 插队
            Map<Long, List<MergeTask>> groups = new LinkedHashMap<>(8);
            for (MergeTask task : batch) {
                groups.computeIfAbsent(task.getRequest().getSkuId(), key -> new ArrayList<>(4)).add(task);
            }
            for (List<MergeTask> group : groups.values()) {
                flushGroup(group);
            }
        } catch (Throwable e) {
            log.error("合并落库异常，批内 {} 个请求回填 ERROR", batch.size(), e);
            for (MergeTask task : batch) {
                task.getFuture().complete(DeductResultVO.of(task.getRequest().getBizNo(), DeductStatus.ERROR));
            }
        }
    }

    /** 对同 SKU 的一组请求做一次合并落库，失败二分拆批，直到逐条 */
    private void flushGroup(List<MergeTask> group) {
        long skuId = group.get(0).getRequest().getSkuId();
        List<DeductRequestVO> requests = new ArrayList<>(group.size());
        for (MergeTask task : group) {
            requests.add(task.getRequest());
        }

        MergedDeductResult result = deductMerged(skuId, requests);
        if (result.isDeducted()) {
            completeDeducted(group, result);
            return;
        }
        if (group.size() == 1) {
            // 单条仍不足：库存确实不够，明确失败（不是异常，不进异常分桶）
            complete(group, DeductStatus.INSUFFICIENT);
            return;
        }
        if (soldoutShortcut && failAllIfSoldOut(group, skuId)) {
            return;
        }
        int mid = group.size() >>> 1;
        flushGroup(group.subList(0, mid));
        flushGroup(group.subList(mid, group.size()));
    }

    /** 扣成功（含整批重放）：命中的 bizNo 回 DUPLICATE，其余回 SUCCESS —— 否则成功笔数会虚高 */
    private void completeDeducted(List<MergeTask> group, MergedDeductResult result) {
        if (!result.hasDuplicate()) {
            complete(group, DeductStatus.SUCCESS);
            return;
        }
        Set<String> duplicates = result.getDuplicateBizNos();
        for (MergeTask task : group) {
            DeductStatus status = duplicates.contains(task.getRequest().getBizNo())
                    ? DeductStatus.DUPLICATE
                    : DeductStatus.SUCCESS;
            task.getFuture().complete(DeductResultVO.of(task.getRequest().getBizNo(), status));
        }
    }

    /**
     * 售罄判定：剩余库存比批内「最小请求量」还小 → 这一批里没有任何一条能成功，整批判失败。
     *
     * <p>用最小值而不是总量：批内 qty 可能不等（1 件和 10 件混在一批），
     * 只有「连最小的都放不下」才能断言全灭；否则放行去二分，让能成功的先成功（先到先得不变）。
     *
     * @return true = 已整批判失败，调用方不要再拆批
     */
    private boolean failAllIfSoldOut(List<MergeTask> group, long skuId) {
        int minQty = Integer.MAX_VALUE;
        for (MergeTask task : group) {
            minQty = Math.min(minQty, task.getRequest().getQty());
        }
        // 够不够扣是库存自己的判据（见 StockEntity#isEnough），执行器只负责「不够就整批判失败」。
        // 库存行不存在时 qty 为 null，isEnough 同样返回 false —— 再怎么拆也不可能扣成功。
        if (query(skuId).isEnough(minQty)) {
            return false;
        }
        complete(group, DeductStatus.INSUFFICIENT);
        return true;
    }

    private void complete(List<MergeTask> group, DeductStatus status) {
        for (MergeTask task : group) {
            task.getFuture().complete(DeductResultVO.of(task.getRequest().getBizNo(), status));
        }
    }

    /** 单 SKU 快路径判定：200 次 long 比较（纳秒级），比一次 HashMap 分配划算 */
    private static boolean isSingleSku(List<MergeTask> batch) {
        long skuId = batch.get(0).getRequest().getSkuId();
        for (int i = 1, n = batch.size(); i < n; i++) {
            if (batch.get(i).getRequest().getSkuId() != skuId) {
                return false;
            }
        }
        return true;
    }

    /**
     * 一个事务内完成「批量写流水（唯一键幂等）+ 一条 UPDATE 扣 N 件」。
     *
     * <p>包级可见：由 {@link #deductBatch} 分组后调用，单测用子类覆写模拟落库故障。
     *
     * @param skuId 本批全部请求同属一个 SKU（调用方已按 SKU 分组）
     * @param batch 按到达顺序排列的请求
     * @return {@code deducted=false} 表示库存不足、整批未动（事务已回滚，流水一并消失）
     */
    MergedDeductResult deductMerged(long skuId, List<DeductRequestVO> batch) {
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

        MergedDeductResult result = stockTransactionTemplate.execute(status -> {
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
            Integer affected = stockTransactionTemplate.execute(status -> stockDao.clearDetailBatch(skuId, CLEAR_BATCH_SIZE));
            cleared = affected == null ? 0 : affected;
            clearedTotal += cleared;
        } while (cleared >= CLEAR_BATCH_SIZE);

        stockTransactionTemplate.executeWithoutResult(status -> stockDao.upsertStock(skuId, qty));
        log.info("库存重置完成 skuId={} qty={} 清理流水={} 行", skuId, qty, clearedTotal);
    }
}
