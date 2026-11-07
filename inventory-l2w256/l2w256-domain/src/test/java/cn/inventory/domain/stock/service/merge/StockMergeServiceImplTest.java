package cn.inventory.domain.stock.service.merge;

import cn.inventory.domain.stock.model.valobj.DeductRequestVO;
import cn.inventory.domain.stock.model.valobj.DeductResultVO;
import cn.inventory.domain.stock.model.valobj.MergeConfigVO;
import cn.inventory.types.enums.DeductStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合并引擎端到端（内存仓储）：不丢件 / 不超卖 / 超时语义 / 停机拒绝
 */
class StockMergeServiceImplTest {

    private StockMergeServiceImpl mergeService;

    @AfterEach
    void tearDown() {
        if (mergeService != null) {
            mergeService.destroy();
        }
    }

    private static MergeConfigVO config(int waitTimeoutMs) {
        return new MergeConfigVO(8, 1, 20, waitTimeoutMs, 2_000, 4, true, 2_000);
    }

    private StockMergeServiceImpl start(FakeStockRepository repository, MergeConfigVO config) {
        mergeService = new StockMergeServiceImpl(new MergedDeductExecutor(repository, config), config);
        return mergeService;
    }

    @Test
    @DisplayName("并发扣减：一笔不丢、一毫不差、批不超上限")
    void concurrentDeductNoLoss() throws Exception {
        FakeStockRepository repository = new FakeStockRepository(100_000);
        StockMergeServiceImpl service = start(repository, config(5_000));

        int threads = 8;
        int perThread = 250;
        List<DeductResultVO> results = Collections.synchronizedList(new ArrayList<>(threads * perThread));
        CountDownLatch startGate = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>(threads);
        for (int t = 0; t < threads; t++) {
            final int threadNo = t;
            Thread worker = new Thread(() -> {
                try {
                    startGate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int i = 0; i < perThread; i++) {
                    results.add(service.submit(new DeductRequestVO(1001L, 1, "t" + threadNo + "-" + i)));
                }
            }, "client-" + t);
            worker.start();
            workers.add(worker);
        }
        startGate.countDown();
        for (Thread worker : workers) {
            worker.join(30_000);
        }

        assertEquals(threads * perThread, results.size());
        assertEquals(threads * perThread, results.stream().filter(DeductResultVO::isSuccess).count(),
                "全部成功：等待上限给到 5s，不该出现超时");
        assertEquals(threads * perThread, repository.deductedTotal.get(), "扣减总量必须精确等于请求总量");
        assertTrue(repository.maxGroupSize.get() <= 20, "单批不能超过 maxBatch");
        assertTrue(service.awaitIdle(2_000));
        assertEquals(0, service.pendingCount());
    }

    @Test
    @DisplayName("库存不足：精确扣到 0，一件不超卖")
    void noOversell() {
        FakeStockRepository repository = new FakeStockRepository(50);
        StockMergeServiceImpl service = start(repository, config(5_000));

        List<DeductStatus> statuses = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            statuses.add(service.submit(new DeductRequestVO(1001L, 1, "order-" + i)).getStatus());
        }

        long success = statuses.stream().filter(s -> s == DeductStatus.SUCCESS).count();
        long insufficient = statuses.stream().filter(s -> s == DeductStatus.INSUFFICIENT).count();
        assertEquals(50, success);
        assertEquals(50, insufficient);
        assertEquals(0, repository.remainingQty());
        assertEquals(50, repository.deductedTotal.get());
    }

    @Test
    @DisplayName("落库比等待慢：返回 TIMEOUT_UNKNOWN（处理中），且这笔最终仍然会扣")
    void slowFlushReturnsProcessingButStillDeducts() {
        FakeStockRepository repository = new FakeStockRepository(1_000);
        repository.flushDelayMs = 300;
        StockMergeServiceImpl service = start(repository, config(50));

        DeductResultVO result = service.submit(new DeductRequestVO(1001L, 1, "slow-1"));

        assertEquals(DeductStatus.TIMEOUT_UNKNOWN, result.getStatus(), "超时必须表达为「结果未知」，不能是失败");
        assertTrue(service.awaitIdle(3_000));
        assertEquals(1, repository.deductedTotal.get(), "超时的请求仍在缓冲区里，之后照样落库");
    }

    @Test
    @DisplayName("停机后提交：直接 REJECTED（确定没扣，可安全重试）")
    void rejectAfterShutdown() {
        FakeStockRepository repository = new FakeStockRepository(1_000);
        StockMergeServiceImpl service = start(repository, config(1_000));
        service.destroy();

        DeductResultVO result = service.submit(new DeductRequestVO(1001L, 1, "after-shutdown"));

        assertEquals(DeductStatus.REJECTED, result.getStatus());
        assertEquals(0, repository.deductedTotal.get());
    }

    @Test
    @DisplayName("空闲时 awaitIdle 立刻返回，不空转")
    void awaitIdleWhenIdle() {
        FakeStockRepository repository = new FakeStockRepository(1_000);
        StockMergeServiceImpl service = start(repository, config(1_000));

        long start = System.currentTimeMillis();
        assertTrue(service.awaitIdle(1_000));
        assertTrue(System.currentTimeMillis() - start < 50, "空闲时不该等满超时");
    }

    @Test
    @DisplayName("同一 bizNo 重复提交：第二次是 DUPLICATE，库存只扣一次")
    void idempotentByBizNo() throws Exception {
        FakeStockRepository repository = new FakeStockRepository(1_000);
        StockMergeServiceImpl service = start(repository, config(5_000));

        DeductResultVO first = service.submit(new DeductRequestVO(1001L, 1, "same-biz-no"));
        // 第一笔落库后，再提交同一个 bizNo（模拟客户端重试）
        assertTrue(service.awaitIdle(2_000));
        DeductResultVO second = service.submit(new DeductRequestVO(1001L, 1, "same-biz-no"));

        assertEquals(DeductStatus.SUCCESS, first.getStatus());
        assertEquals(DeductStatus.DUPLICATE, second.getStatus());
        assertEquals(1, repository.deductedTotal.get());
    }

    @Test
    @DisplayName("多热点：不同 SKU 各自成批，互不阻塞")
    void multiHotspot() {
        FakeStockRepository repository = new FakeStockRepository(100_000);
        StockMergeServiceImpl service = start(repository, config(5_000));

        List<DeductStatus> statuses = new ArrayList<>();
        for (long skuId = 1; skuId <= 32; skuId++) {
            for (int i = 0; i < 10; i++) {
                statuses.add(service.submit(new DeductRequestVO(skuId, 1, "sku" + skuId + "-" + i)).getStatus());
            }
        }

        assertEquals(320, statuses.stream().filter(s -> s == DeductStatus.SUCCESS).count());
        assertEquals(320, repository.deductedTotal.get());
        assertTrue(service.awaitIdle(2_000));
    }
}
