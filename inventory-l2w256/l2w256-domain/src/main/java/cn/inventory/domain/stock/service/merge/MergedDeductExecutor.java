package cn.inventory.domain.stock.service.merge;

import cn.inventory.domain.stock.adapter.repository.IStockRepository;
import cn.inventory.domain.stock.model.valobj.DeductRequestVO;
import cn.inventory.domain.stock.model.valobj.DeductResultVO;
import cn.inventory.domain.stock.model.valobj.MergeConfigVO;
import cn.inventory.domain.stock.model.valobj.MergedDeductResult;
import cn.inventory.types.enums.DeductStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 落库执行器：把「一批请求」变成「一到几次事务」，并把结果回填到每个等待者。
 *
 * <p>这是唯一碰数据库的地方，合并引擎（{@link StockMergeServiceImpl} / {@link ShardBuffer}）
 * 对它一无所知，只通过一个 {@code Consumer<List<MergeTask>>} 把批交过来。
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
 * <p>幂等<b>不在这里做</b>，也不在任何内存结构里做：唯一裁决者是
 * {@code stock_detail.uk_biz_no}，由仓储的落库事务承担。单实例、多实例、进程重启、重放，语义一致。
 */
@Slf4j
@Component
public class MergedDeductExecutor {

    private final IStockRepository stockRepository;
    private final boolean soldoutShortcut;

    public MergedDeductExecutor(IStockRepository stockRepository, MergeConfigVO mergeConfig) {
        this.stockRepository = stockRepository;
        this.soldoutShortcut = mergeConfig.isSoldoutShortcut();
    }

    /**
     * 落库一批（可能混多个 SKU）。
     *
     * <p>异常在这里兜住并<b>当场回填</b>给批内所有等待者：不兜的话它们只能等到 waitTimeout
     * 才知道失败，压测里表现为「莫名其妙的 100ms 超时」。
     */
    void flushBatch(List<MergeTask> batch) {
        if (batch.isEmpty()) {
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

        MergedDeductResult result = stockRepository.deductMerged(skuId, requests);
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
        if (stockRepository.query(skuId).isEnough(minQty)) {
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
}
