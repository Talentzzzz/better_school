package cn.inventory.infrastructure.adapter.repository;

import cn.inventory.domain.stock.model.valobj.DeductRequestVO;
import cn.inventory.domain.stock.model.valobj.DeductResultVO;
import cn.inventory.domain.stock.model.valobj.MergeConfigVO;
import cn.inventory.domain.stock.model.valobj.MergedDeductResult;
import cn.inventory.domain.stock.service.merge.MergeTask;
import cn.inventory.types.enums.DeductStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 仓储合并落库：分组 / 二分拆批 / 售罄短路 / 幂等标记 / 异常兜底
 * （原 MergedDeductExecutor 职责并入 StockRepository 后，这套行为单测随逻辑一起迁过来）
 */
class StockRepositoryDeductBatchTest {

    private static MergeConfigVO config(boolean soldoutShortcut) {
        return new MergeConfigVO(8, 5, 200, 100, 1_000, 2, soldoutShortcut, 2_000);
    }

    private static StockRepository repository(FakeStockDao dao, boolean soldoutShortcut) {
        TransactionTemplate template = new TransactionTemplate(new FakeTransactionManager(dao));
        return new StockRepository(dao, template, config(soldoutShortcut));
    }

    private static List<MergeTask> tasks(long skuId, int count, int qty) {
        List<MergeTask> tasks = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            DeductRequestVO request = new DeductRequestVO(skuId, qty, skuId + "-" + i);
            tasks.add(new MergeTask(request, new CompletableFuture<>()));
        }
        return tasks;
    }

    private static List<DeductStatus> statuses(List<MergeTask> tasks) {
        List<DeductStatus> statuses = new ArrayList<>(tasks.size());
        for (MergeTask task : tasks) {
            DeductResultVO result = task.getFuture().getNow(null);
            assertNotNull(result, "每个请求都必须被回填，否则调用方只能等到超时");
            statuses.add(result.getStatus());
        }
        return statuses;
    }

    private static long count(List<DeductStatus> statuses, DeductStatus status) {
        return statuses.stream().filter(s -> s == status).count();
    }

    @Test
    @DisplayName("整批够扣：一次事务搞定，不回查库存")
    void successInOneTransaction() {
        FakeStockDao dao = new FakeStockDao(1_000);
        StockRepository repository = repository(dao, true);

        List<MergeTask> batch = tasks(1L, 10, 3);
        repository.deductBatch(batch);

        assertEquals(10, count(statuses(batch), DeductStatus.SUCCESS));
        assertEquals(1, dao.deductCalls.get(), "同 SKU 的批必须合并成一次事务");
        assertEquals(0, dao.qtyCalls.get(), "扣成功时不该多查一次库存");
        assertEquals(30, dao.deductedTotal.get());
    }

    @Test
    @DisplayName("单条库存不足：明确失败，不进异常分桶")
    void singleInsufficient() {
        FakeStockDao dao = new FakeStockDao(0);
        StockRepository repository = repository(dao, true);

        List<MergeTask> batch = tasks(1L, 1, 5);
        repository.deductBatch(batch);

        assertEquals(DeductStatus.INSUFFICIENT, statuses(batch).get(0));
        assertEquals(1, dao.deductCalls.get());
    }

    @Test
    @DisplayName("部分够扣 + 关短路：二分拆批，先到先得")
    void binarySplitKeepsArrivalOrder() {
        FakeStockDao dao = new FakeStockDao(2);
        StockRepository repository = repository(dao, false);

        List<MergeTask> batch = tasks(1L, 4, 1);
        repository.deductBatch(batch);

        List<DeductStatus> statuses = statuses(batch);
        assertEquals(DeductStatus.SUCCESS, statuses.get(0));
        assertEquals(DeductStatus.SUCCESS, statuses.get(1));
        assertEquals(DeductStatus.INSUFFICIENT, statuses.get(2));
        assertEquals(DeductStatus.INSUFFICIENT, statuses.get(3));
        assertTrue(dao.deductCalls.get() > 3, "关短路时必须退化成多次单条事务（这正是要开短路的原因）");
    }

    @Test
    @DisplayName("售罄短路：整批都不够时只探一次库存就整批判死，不进二分")
    void soldoutShortcutSkipsBinarySplit() {
        FakeStockDao dao = new FakeStockDao(0);
        StockRepository repository = repository(dao, true);

        List<MergeTask> batch = tasks(1L, 200, 1);
        repository.deductBatch(batch);

        assertEquals(200, count(statuses(batch), DeductStatus.INSUFFICIENT));
        assertEquals(1, dao.deductCalls.get(), "整批只探一次库存就判死，绝不该退化成 399 次事务");
        assertEquals(1, dao.qtyCalls.get(), "整批判死只探一次库存");
    }

    @Test
    @DisplayName("短路不误杀：库存还够最小请求量时，放行去二分让能成功的先成功")
    void shortcutDoesNotKillPartialSuccess() {
        FakeStockDao dao = new FakeStockDao(4);
        StockRepository repository = repository(dao, true);

        List<MergeTask> batch = tasks(1L, 8, 1);
        repository.deductBatch(batch);

        List<DeductStatus> statuses = statuses(batch);
        assertEquals(4, count(statuses, DeductStatus.SUCCESS));
        assertEquals(4, count(statuses, DeductStatus.INSUFFICIENT));
        assertEquals(4, dao.deductedTotal.get(), "精确扣 4 件，不超卖也不漏扣");
    }

    @Test
    @DisplayName("分片里混了多个 SKU：按 SKU 分组，一次只碰一行")
    void groupsBySku() {
        FakeStockDao dao = new FakeStockDao(1_000);
        StockRepository repository = repository(dao, true);

        List<MergeTask> batch = new ArrayList<>();
        batch.addAll(tasks(1L, 3, 1));
        batch.addAll(tasks(2L, 2, 1));
        repository.deductBatch(batch);

        assertEquals(2, dao.deductCalls.get(), "两个 SKU = 两次事务，不能混在一行上");
        assertEquals(5, count(statuses(batch), DeductStatus.SUCCESS));
    }

    @Test
    @DisplayName("重复 bizNo：命中幂等回 DUPLICATE，成功笔数不能虚高")
    void duplicateReturnsDuplicateStatus() {
        FakeStockDao dao = new FakeStockDao(1_000);
        StockRepository repository = repository(dao, true);

        List<MergeTask> first = tasks(1L, 3, 1);
        repository.deductBatch(first);
        assertEquals(3, count(statuses(first), DeductStatus.SUCCESS));

        // 同一批 bizNo 再走一次（模拟客户端超时重试）
        List<MergeTask> replay = tasks(1L, 3, 1);
        repository.deductBatch(replay);

        assertEquals(3, count(statuses(replay), DeductStatus.DUPLICATE));
        assertEquals(3, dao.deductedTotal.get(), "重放绝不能二次扣减");
    }

    @Test
    @DisplayName("落库异常：当场回填 ERROR，不能让等待者干等到超时")
    void exceptionCompletesError() {
        FakeStockDao dao = new FakeStockDao(1_000);
        StockRepository failing = new StockRepository(dao,
                new TransactionTemplate(new FakeTransactionManager(dao)), config(true)) {
            @Override
            MergedDeductResult deductMerged(long skuId, List<DeductRequestVO> batch) {
                throw new IllegalStateException("数据库炸了");
            }
        };

        List<MergeTask> batch = tasks(1L, 5, 1);
        failing.deductBatch(batch);

        assertEquals(5, count(statuses(batch), DeductStatus.ERROR));
    }
}
