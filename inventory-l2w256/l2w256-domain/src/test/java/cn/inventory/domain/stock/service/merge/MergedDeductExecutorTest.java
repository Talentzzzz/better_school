package cn.inventory.domain.stock.service.merge;

import cn.inventory.domain.stock.model.valobj.DeductRequestVO;
import cn.inventory.domain.stock.model.valobj.DeductResultVO;
import cn.inventory.domain.stock.model.valobj.MergeConfigVO;
import cn.inventory.domain.stock.model.valobj.MergedDeductResult;
import cn.inventory.types.enums.DeductStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 落库执行器：分组 / 二分拆批 / 售罄短路 / 幂等标记 / 异常兜底
 */
class MergedDeductExecutorTest {

    private static MergeConfigVO config(boolean soldoutShortcut) {
        return new MergeConfigVO(8, 5, 200, 100, 1_000, 2, soldoutShortcut, 2_000);
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
        FakeStockRepository repository = new FakeStockRepository(1_000);
        MergedDeductExecutor executor = new MergedDeductExecutor(repository, config(true));

        List<MergeTask> batch = tasks(1L, 10, 3);
        executor.flushBatch(batch);

        assertEquals(10, count(statuses(batch), DeductStatus.SUCCESS));
        assertEquals(1, repository.mergedCalls.get(), "同 SKU 的批必须合并成一次事务");
        assertEquals(0, repository.queryCalls.get(), "扣成功时不该多查一次库存");
        assertEquals(30, repository.deductedTotal.get());
    }

    @Test
    @DisplayName("单条库存不足：明确失败，不进异常分桶")
    void singleInsufficient() {
        FakeStockRepository repository = new FakeStockRepository(0);
        MergedDeductExecutor executor = new MergedDeductExecutor(repository, config(true));

        List<MergeTask> batch = tasks(1L, 1, 5);
        executor.flushBatch(batch);

        assertEquals(DeductStatus.INSUFFICIENT, statuses(batch).get(0));
        assertEquals(1, repository.mergedCalls.get());
    }

    @Test
    @DisplayName("部分够扣 + 关短路：二分拆批，先到先得")
    void binarySplitKeepsArrivalOrder() {
        FakeStockRepository repository = new FakeStockRepository(2);
        MergedDeductExecutor executor = new MergedDeductExecutor(repository, config(false));

        List<MergeTask> batch = tasks(1L, 4, 1);
        executor.flushBatch(batch);

        List<DeductStatus> statuses = statuses(batch);
        assertEquals(DeductStatus.SUCCESS, statuses.get(0));
        assertEquals(DeductStatus.SUCCESS, statuses.get(1));
        assertEquals(DeductStatus.INSUFFICIENT, statuses.get(2));
        assertEquals(DeductStatus.INSUFFICIENT, statuses.get(3));
        assertTrue(repository.mergedCalls.get() > 3, "关短路时必须退化成多次单条事务（这正是要开短路的原因）");
    }

    @Test
    @DisplayName("售罄短路：整批都不够时只探一次库存就整批判死，不进二分")
    void soldoutShortcutSkipsBinarySplit() {
        FakeStockRepository repository = new FakeStockRepository(0);
        MergedDeductExecutor executor = new MergedDeductExecutor(repository, config(true));

        List<MergeTask> batch = tasks(1L, 200, 1);
        executor.flushBatch(batch);

        assertEquals(200, count(statuses(batch), DeductStatus.INSUFFICIENT));
        assertEquals(1, repository.mergedCalls.get(), "整批只探一次库存就判死，绝不该退化成 399 次事务");
        assertEquals(1, repository.queryCalls.get(), "整批判死只探一次库存");
    }

    @Test
    @DisplayName("短路不误杀：库存还够最小请求量时，放行去二分让能成功的先成功")
    void shortcutDoesNotKillPartialSuccess() {
        FakeStockRepository repository = new FakeStockRepository(4);
        MergedDeductExecutor executor = new MergedDeductExecutor(repository, config(true));

        List<MergeTask> batch = tasks(1L, 8, 1);
        executor.flushBatch(batch);

        List<DeductStatus> statuses = statuses(batch);
        assertEquals(4, count(statuses, DeductStatus.SUCCESS));
        assertEquals(4, count(statuses, DeductStatus.INSUFFICIENT));
        assertEquals(4, repository.deductedTotal.get(), "精确扣 4 件，不超卖也不漏扣");
    }

    @Test
    @DisplayName("分片里混了多个 SKU：按 SKU 分组，一次只碰一行")
    void groupsBySku() {
        FakeStockRepository repository = new FakeStockRepository(1_000);
        MergedDeductExecutor executor = new MergedDeductExecutor(repository, config(true));

        List<MergeTask> batch = new ArrayList<>();
        batch.addAll(tasks(1L, 3, 1));
        batch.addAll(tasks(2L, 2, 1));
        executor.flushBatch(batch);

        assertEquals(2, repository.mergedCalls.get(), "两个 SKU = 两次事务，不能混在一行上");
        assertEquals(5, count(statuses(batch), DeductStatus.SUCCESS));
    }

    @Test
    @DisplayName("重复 bizNo：命中幂等回 DUPLICATE，成功笔数不能虚高")
    void duplicateReturnsDuplicateStatus() {
        FakeStockRepository repository = new FakeStockRepository(1_000);
        MergedDeductExecutor executor = new MergedDeductExecutor(repository, config(true));

        List<MergeTask> first = tasks(1L, 3, 1);
        executor.flushBatch(first);
        assertEquals(3, count(statuses(first), DeductStatus.SUCCESS));

        // 同一批 bizNo 再走一次（模拟客户端超时重试）
        List<MergeTask> replay = tasks(1L, 3, 1);
        executor.flushBatch(replay);

        assertEquals(3, count(statuses(replay), DeductStatus.DUPLICATE));
        assertEquals(3, repository.deductedTotal.get(), "重放绝不能二次扣减");
    }

    @Test
    @DisplayName("落库异常：当场回填 ERROR，不能让等待者干等到超时")
    void exceptionCompletesError() {
        FakeStockRepository failing = new FakeStockRepository(1_000) {
            @Override
            public MergedDeductResult deductMerged(long skuId, List<DeductRequestVO> batch) {
                throw new IllegalStateException("数据库炸了");
            }
        };
        MergedDeductExecutor executor = new MergedDeductExecutor(failing, config(true));

        List<MergeTask> batch = tasks(1L, 5, 1);
        executor.flushBatch(batch);

        assertEquals(5, count(statuses(batch), DeductStatus.ERROR));
    }
}
