package cn.inventory.domain.stock.service.merge;

import cn.inventory.domain.stock.adapter.repository.IStockRepository;
import cn.inventory.domain.stock.model.entity.StockEntity;
import cn.inventory.domain.stock.model.valobj.DeductRequestVO;
import cn.inventory.domain.stock.model.valobj.DeductResultVO;
import cn.inventory.types.enums.DeductStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存版库存仓储：模拟「条件扣减 + 唯一键幂等 + 事务回滚」的最终语义，逐条结算。
 *
 * <p>单测要锁住的是合并引擎的行为（攒批、窗口、单飞、停机），不是落库的拆批策略——
 * 分组 / 二分 / 售罄短路已在 infrastructure 的 {@code StockRepositoryDeductBatchTest}
 * 里由 FakeStockDao 那套单测覆盖。这里逐条条件扣减即可与真实实现的
 * 「先到先得」结算结果对齐，让「不超卖」「不丢件」「幂等不重复扣」都能在毫秒级验证。
 */
class FakeStockRepository implements IStockRepository {

    private final int initialStock;

    /** 各 SKU 剩余库存（懒初始化，初值统一为构造参数） */
    private final Map<Long, AtomicInteger> stockBySku = new HashMap<>();

    private final Set<String> processedBizNos = ConcurrentHashMap.newKeySet();

    final AtomicInteger deductedTotal = new AtomicInteger();

    /** 引擎交给仓储的最大批大小，用来断言「单批不能超过 maxBatch」 */
    final AtomicInteger maxBatchSize = new AtomicInteger();

    /** 单次落库的人为耗时，用来放大并发窗口 */
    volatile long flushDelayMs;

    FakeStockRepository(int remaining) {
        this.initialStock = remaining;
    }

    @Override
    public void deductBatch(List<MergeTask> batch) {
        if (batch.isEmpty()) {
            return;
        }
        try {
            maxBatchSize.accumulateAndGet(batch.size(), Math::max);
            sleep(flushDelayMs);
            // 按 SKU 分组结算，与真实实现的「一次事务只碰一行」对齐
            Map<Long, List<MergeTask>> groups = new LinkedHashMap<>(4);
            for (MergeTask task : batch) {
                groups.computeIfAbsent(task.getRequest().getSkuId(), key -> new ArrayList<>(4)).add(task);
            }
            for (List<MergeTask> group : groups.values()) {
                flushGroup(group);
            }
        } catch (Throwable e) {
            for (MergeTask task : batch) {
                task.getFuture().complete(DeductResultVO.of(task.getRequest().getBizNo(), DeductStatus.ERROR));
            }
        }
    }

    /** 逐条条件扣减：扣得动成功、扣不动明确失败，先到先得（与真实二分拆批的结算结果一致） */
    private void flushGroup(List<MergeTask> group) {
        for (MergeTask task : group) {
            DeductRequestVO request = task.getRequest();
            DeductStatus status;
            if (!processedBizNos.add(request.getBizNo())) {
                status = DeductStatus.DUPLICATE;
            } else if (tryDeduct(request.getSkuId(), request.getQty())) {
                status = DeductStatus.SUCCESS;
            } else {
                // 扣不动：回滚流水，语义与真实实现的「事务回滚、流水一并消失」一致
                processedBizNos.remove(request.getBizNo());
                status = DeductStatus.INSUFFICIENT;
            }
            task.getFuture().complete(DeductResultVO.of(request.getBizNo(), status));
        }
    }

    private boolean tryDeduct(long skuId, int qty) {
        AtomicInteger stock = stockBySku.computeIfAbsent(skuId, key -> new AtomicInteger(initialStock));
        while (true) {
            int current = stock.get();
            if (current < qty) {
                return false;
            }
            if (stock.compareAndSet(current, current - qty)) {
                deductedTotal.addAndGet(qty);
                return true;
            }
        }
    }

    @Override
    public StockEntity query(long skuId) {
        return new StockEntity(skuId, stock(skuId).get());
    }

    @Override
    public void reset(long skuId, int qty) {
        stockBySku.put(skuId, new AtomicInteger(qty));
        processedBizNos.clear();
    }

    int remainingQty() {
        return stock.values().iterator().next().get();
    }

    private AtomicInteger stock(long skuId) {
        return stockBySku.computeIfAbsent(skuId, key -> new AtomicInteger(initialStock));
    }

    private static void sleep(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
