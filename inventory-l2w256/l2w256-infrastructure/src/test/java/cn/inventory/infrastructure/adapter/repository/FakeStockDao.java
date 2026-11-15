package cn.inventory.infrastructure.adapter.repository;

import cn.inventory.infrastructure.dao.IStockDao;
import cn.inventory.infrastructure.dao.po.StockDetailPO;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存版 DAO：按 MyBatis 映射 SQL 的语义模拟——INSERT IGNORE 唯一键幂等、
 * 「WHERE qty &gt;= n」条件扣减、事务可见性（未提交的流水回滚后不可见）。
 *
 * <p>事务边界由 {@link FakeTransactionManager} 驱动：提交时把在途流水落进已提交集合，
 * 回滚时直接丢弃。测试单线程跑 flush，集合无需并发容器。
 */
final class FakeStockDao implements IStockDao {

    private final int initialStock;

    /** 各 SKU 剩余库存（懒初始化，初值统一为构造参数） */
    private final Map<Long, AtomicInteger> stockBySku = new HashMap<>();

    /** 已提交的流水：bizNo → PO（唯一键 uk_biz_no 的内存对应物） */
    private final Map<String, StockDetailPO> committed = new HashMap<>();

    /** 当前在途事务里插入的流水 */
    private final List<StockDetailPO> inflight = new ArrayList<>();

    final AtomicInteger deductCalls = new AtomicInteger();
    final AtomicInteger qtyCalls = new AtomicInteger();
    final AtomicInteger deductedTotal = new AtomicInteger();

    FakeStockDao(int initialStock) {
        this.initialStock = initialStock;
    }

    @Override
    public int deduct(long skuId, int qty) {
        deductCalls.incrementAndGet();
        AtomicInteger stock = stockBySku.computeIfAbsent(skuId, key -> new AtomicInteger(initialStock));
        while (true) {
            int current = stock.get();
            if (current < qty) {
                return 0;
            }
            if (stock.compareAndSet(current, current - qty)) {
                deductedTotal.addAndGet(qty);
                return 1;
            }
        }
    }

    @Override
    public int batchInsertDetail(List<StockDetailPO> details) {
        int inserted = 0;
        for (StockDetailPO detail : details) {
            if (committed.containsKey(detail.getBizNo())) {
                continue;
            }
            boolean alreadyInflight = false;
            for (StockDetailPO po : inflight) {
                if (po.getBizNo().equals(detail.getBizNo())) {
                    alreadyInflight = true;
                    break;
                }
            }
            if (!alreadyInflight) {
                inflight.add(detail);
                inserted++;
            }
        }
        return inserted;
    }

    @Override
    public List<StockDetailPO> listDetailsByBizNos(List<String> bizNos) {
        List<StockDetailPO> result = new ArrayList<>(bizNos.size());
        for (String bizNo : bizNos) {
            StockDetailPO po = committed.get(bizNo);
            if (po != null) {
                result.add(po);
            }
        }
        return result;
    }

    @Override
    public Integer getQty(long skuId) {
        qtyCalls.incrementAndGet();
        AtomicInteger stock = stockBySku.get(skuId);
        return stock == null ? null : stock.get();
    }

    @Override
    public int upsertStock(long skuId, int qty) {
        stockBySku.put(skuId, new AtomicInteger(qty));
        return 1;
    }

    @Override
    public int clearDetailBatch(long skuId, int limit) {
        int removed = 0;
        Iterator<StockDetailPO> it = committed.values().iterator();
        while (it.hasNext() && removed < limit) {
            if (it.next().getSkuId() == skuId) {
                it.remove();
                removed++;
            }
        }
        return removed;
    }

    // ---- 事务边界（由 FakeTransactionManager 驱动）----

    void beginTx() {
        inflight.clear();
    }

    void commitTx() {
        for (StockDetailPO po : inflight) {
            committed.put(po.getBizNo(), po);
        }
        inflight.clear();
    }

    void rollbackTx() {
        inflight.clear();
    }
}
