package com.betterschool.loadtest.inventory.strategy;

import com.betterschool.loadtest.inventory.metrics.RunStats;
import com.betterschool.loadtest.inventory.repository.InventoryRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 纯 DB 行锁扣减（基线）。
 * <pre>
 *   BEGIN
 *     UPDATE inventory_ledger SET surplus_qty = surplus_qty - 1 WHERE sku_id = 1001 AND surplus_qty >= 1;
 *     INSERT INTO inventory_order ...
 *   COMMIT
 * </pre>
 * 单行热点 = 所有请求排同一把行锁，吞吐上限由「一次提交的 redo 落盘 + 锁传递」决定。
 * 这是最直观、也是故障时最容易雪崩的一种：锁等待会把 Tomcat 线程池一起拖死。
 */
@Component
public class DbRowLockStrategy implements DeductStrategy {

    private final InventoryRepository repository;
    private final RunStats stats;
    private final TransactionTemplate tx;

    public DbRowLockStrategy(InventoryRepository repository, RunStats stats, TransactionTemplate tx) {
        this.repository = repository;
        this.stats = stats;
        this.tx = tx;
    }

    @Override
    public String name() {
        return "db";
    }

    @Override
    public DeductResult deduct(long skuId, int qty, String orderNo) {
        long begin = System.nanoTime();
        try {
            Boolean accepted = tx.execute(status -> {
                if (!repository.deductLedgerRow(skuId, qty)) {
                    return false;
                }
                repository.insertOrder(orderNo, skuId, qty, name());
                return true;
            });
            if (Boolean.TRUE.equals(accepted)) {
                stats.inc("ok:" + name());
                return DeductResult.ok();
            }
            stats.inc("soldout:" + name());
            return DeductResult.soldOut();
        } catch (RuntimeException e) {
            stats.inc("error:" + name());
            throw e;
        } finally {
            stats.add("cost_nanos:" + name(), System.nanoTime() - begin);
            stats.inc("calls:" + name());
        }
    }
}
