package cn.inventory.domain.stock.service.merge;

import cn.inventory.domain.stock.adapter.repository.IStockRepository;
import cn.inventory.domain.stock.model.entity.StockEntity;
import cn.inventory.domain.stock.model.valobj.DeductRequestVO;
import cn.inventory.domain.stock.model.valobj.MergedDeductResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存版库存仓储：用 CAS 模拟「条件扣减 + 唯一键幂等 + 事务回滚」，语义与真实实现对齐。
 *
 * <p>单测要锁住的是合并引擎的行为（分组、拆批、短路、幂等），不是 SQL——所以这里没有 DB。
 * 有了它，「不超卖」「不丢件」「幂等不重复扣」这三条都能在毫秒级验证。
 */
class FakeStockRepository implements IStockRepository {

    private final AtomicInteger remaining;
    private final Set<String> processedBizNos = ConcurrentHashMap.newKeySet();

    final AtomicInteger mergedCalls = new AtomicInteger();
    final AtomicInteger maxGroupSize = new AtomicInteger();
    final AtomicInteger queryCalls = new AtomicInteger();
    final AtomicInteger deductedTotal = new AtomicInteger();

    /** 单次落库的人为耗时，用来放大并发窗口 */
    volatile long flushDelayMs;

    FakeStockRepository(int remaining) {
        this.remaining = new AtomicInteger(remaining);
    }

    @Override
    public MergedDeductResult deductMerged(long skuId, List<DeductRequestVO> batch) {
        mergedCalls.incrementAndGet();
        maxGroupSize.accumulateAndGet(batch.size(), Math::max);
        sleep(flushDelayMs);

        Set<String> duplicates = new HashSet<>();
        List<String> inserted = new ArrayList<>(batch.size());
        int newQty = 0;
        for (DeductRequestVO request : batch) {
            if (processedBizNos.add(request.getBizNo())) {
                inserted.add(request.getBizNo());
                newQty += request.getQty();
            } else {
                duplicates.add(request.getBizNo());
            }
        }
        if (newQty == 0) {
            // 整批重放：无需扣减
            return new MergedDeductResult(true, duplicates);
        }
        // 条件扣减：扣不动就整体回滚（流水一并撤销），与真实事务语义一致
        while (true) {
            int current = remaining.get();
            if (current < newQty) {
                inserted.forEach(processedBizNos::remove);
                return MergedDeductResult.INSUFFICIENT;
            }
            if (remaining.compareAndSet(current, current - newQty)) {
                break;
            }
        }
        deductedTotal.addAndGet(newQty);
        return new MergedDeductResult(true, duplicates);
    }

    @Override
    public StockEntity query(long skuId) {
        queryCalls.incrementAndGet();
        return new StockEntity(skuId, remaining.get());
    }

    @Override
    public void reset(long skuId, int qty) {
        remaining.set(qty);
        processedBizNos.clear();
    }

    int remainingQty() {
        return remaining.get();
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
