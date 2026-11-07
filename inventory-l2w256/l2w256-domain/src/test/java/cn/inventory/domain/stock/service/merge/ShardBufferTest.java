package cn.inventory.domain.stock.service.merge;

import cn.inventory.domain.stock.model.valobj.DeductRequestVO;
import cn.inventory.domain.stock.model.valobj.DeductResultVO;
import cn.inventory.domain.stock.model.valobj.MergeConfigVO;
import cn.inventory.types.enums.DeductStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分片缓冲区：窗口触发 / 攒满提前触发 / 单飞 / 背压 / 批上限
 */
class ShardBufferTest {

    private ScheduledExecutorService scheduler;
    private final AtomicInteger concurrentFlushes = new AtomicInteger();
    private final AtomicInteger maxConcurrentFlushes = new AtomicInteger();
    private final List<Integer> batchSizes = Collections.synchronizedList(new ArrayList<>());

    @BeforeEach
    void setUp() {
        scheduler = Executors.newScheduledThreadPool(2);
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
    }

    private static MergeConfigVO config(int windowMs, int maxBatch, int capacity) {
        // 容量必须 >= 批上限（配置本身的硬约束），这里顺手兜一下，免得每个用例都写一遍
        // 等待上限给足（>= 1s）：单测关心的是触发时机，不希望请求先超时
        return new MergeConfigVO(8, windowMs, maxBatch, Math.max(1_000, windowMs * 2),
                Math.max(capacity, maxBatch), 2, true, 2_000);
    }

    /** 记录并发度与批大小的 flush 动作；{@code gate} 非空时先阻塞，用来制造「flush 进行中」的时序 */
    private ShardBuffer newBuffer(MergeConfigVO config, CountDownLatch gate) {
        return new ShardBuffer(0, config, scheduler, batch -> {
            int now = concurrentFlushes.incrementAndGet();
            maxConcurrentFlushes.accumulateAndGet(now, Math::max);
            batchSizes.add(batch.size());
            try {
                if (gate != null) {
                    gate.await(2, TimeUnit.SECONDS);
                }
                Thread.sleep(3);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                concurrentFlushes.decrementAndGet();
            }
            for (MergeTask task : batch) {
                task.getFuture().complete(DeductResultVO.of(task.getRequest().getBizNo(), DeductStatus.SUCCESS));
            }
        }, () -> {
        });
    }

    private static MergeTask task(int seq) {
        return new MergeTask(new DeductRequestVO(1001L, 1, "no-" + seq), new CompletableFuture<>());
    }

    @Test
    @DisplayName("窗口到点自动 flush（没有新请求也能走）")
    void flushAfterWindow() throws Exception {
        ShardBuffer buffer = newBuffer(config(20, 200, 100), null);
        MergeTask task = task(1);
        assertTrue(buffer.offer(task));

        // 提交线程不参与攒批：offer 立刻返回，结果由定时器驱动
        assertEquals(DeductStatus.SUCCESS, task.getFuture().get(1, TimeUnit.SECONDS).getStatus());
        assertEquals(1, batchSizes.size());
    }

    @Test
    @DisplayName("攒满提前触发：窗口 300ms，第 5 个请求一到就立刻 flush")
    void fullBatchTriggersEarly() throws Exception {
        ShardBuffer buffer = newBuffer(config(300, 5, 100), null);
        long start = System.currentTimeMillis();
        MergeTask last = null;
        for (int i = 0; i < 5; i++) {
            last = task(i);
            assertTrue(buffer.offer(last));
        }
        assertEquals(DeductStatus.SUCCESS, last.getFuture().get(2, TimeUnit.SECONDS).getStatus());
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 250, "攒满应立刻触发，实际耗时 " + elapsed + "ms（说明还在等窗口）");
    }

    @Test
    @DisplayName("缓冲区满了直接拒绝（入口背压，不占调用方等待时间）")
    void rejectsWhenBufferFull() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        ShardBuffer buffer = newBuffer(config(1_000, 2, 2), gate);

        assertTrue(buffer.offer(task(1)));
        assertTrue(buffer.offer(task(2)));
        // 前两个攒满触发 flush，被 gate 卡在落库中途；此后缓冲区空出，可以继续进两个
        long deadline = System.currentTimeMillis() + 2_000;
        while (buffer.pendingSize() != 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(2);
        }
        assertTrue(buffer.offer(task(3)));
        assertTrue(buffer.offer(task(4)));
        assertFalse(buffer.offer(task(5)), "容量 2 再塞第 3 个必须被拒绝");
        gate.countDown();
    }

    @Test
    @DisplayName("单飞：同一分片永远不会有两个 flush 并发（否则热点行重新排队）")
    void singleFlight() throws Exception {
        // 回归用例：曾经用「cancel(false) 失败 ⟹ flush 已在执行」来判定单飞，
        // 但 FutureTask 在 callable 执行期间 state 仍是 NEW，cancel 会返回 true，
        // 于是攒满提前触发把「正在落库的那一批」cancel 掉、又排了一条新的 → 并发度 = 2。
        // 现在靠 running 标志 + 代际号保证，这个用例会稳定失败/通过。
        ShardBuffer buffer = newBuffer(config(1, 10, 1_000), null);
        int threads = 2;
        int perThread = 300;
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    buffer.offer(task(i));
                }
            });
            worker.start();
            workers.add(worker);
        }
        for (Thread worker : workers) {
            worker.join(10_000);
        }
        long deadline = System.currentTimeMillis() + 5_000;
        while (!buffer.isIdle() && System.currentTimeMillis() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(buffer.isIdle(), "最终必须排空");
        assertEquals(1, maxConcurrentFlushes.get(), "同一分片出现并发 flush = 单飞被破坏");
        int total = batchSizes.stream().mapToInt(Integer::intValue).sum();
        assertEquals(threads * perThread, total, "一个请求都不能丢");
    }

    @Test
    @DisplayName("批上限：一次 flush 最多取 maxBatch 个，剩余的在后续批次里走")
    void batchCappedAtMaxBatch() throws Exception {
        ShardBuffer buffer = newBuffer(config(5, 4, 100), null);
        List<MergeTask> tasks = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            MergeTask task = task(i);
            tasks.add(task);
            assertTrue(buffer.offer(task));
        }
        for (MergeTask task : tasks) {
            assertEquals(DeductStatus.SUCCESS, task.getFuture().get(2, TimeUnit.SECONDS).getStatus());
        }
        assertEquals(10, batchSizes.stream().mapToInt(Integer::intValue).sum());
        assertTrue(batchSizes.stream().allMatch(size -> size <= 4), "单批不能超过 maxBatch：" + batchSizes);
        assertTrue(batchSizes.contains(4), "10 个请求、上限 4，必然出现满批：" + batchSizes);
    }
}
