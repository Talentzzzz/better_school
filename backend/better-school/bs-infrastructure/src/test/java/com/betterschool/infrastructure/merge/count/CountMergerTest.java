package com.betterschool.infrastructure.merge.count;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

import static com.betterschool.infrastructure.merge.count.CountAction.POST_LIKE;
import static com.betterschool.infrastructure.merge.count.CountAction.POST_VIEW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A1–A6：引擎的五条不变量 + 两条附加断言（批上限、同分片保序）。
 *
 * <p>用例名与 {@code acceptance.md §2 A 组}逐字一致——两份实现（bs / marketing）照同一份名单写，
 * 漂移先在自己工程里红。
 *
 * <p>全部是<b>纯内存</b>测试：落库是 {@link RecordingFlushHandler}，不起 Spring、不连库，
 * 所以它们能进 {@code mvn -pl bs-infrastructure test} 的秒级回路。
 */
class CountMergerTest {

    private static final String POST_ID = "post-1001";

    private CountMerger merger;

    @AfterEach
    void tearDown() {
        if (merger != null) {
            merger.close();
            merger = null;
        }
    }

    private CountMerger start(MergeConfig config, ICountFlushHandler handler) {
        merger = new CountMerger(config, handler);
        return merger;
    }

    /** 单热点、窄窗口、够大的等待预算：A1/A2/A6 用 */
    private static MergeConfig hotConfig(int maxBatch) {
        return new MergeConfig(8, 1, maxBatch, 5_000, 5_000, 2_000);
    }

    private static CountEvent like(String userId) {
        return new CountEvent(POST_LIKE, POST_ID, userId);
    }

    private static CountEvent view(String postId, String userId) {
        return new CountEvent(POST_VIEW, postId, userId);
    }

    /**
     * 起 N 条线程，在同一道闸门后同时执行 body（收到线程号）——并发用例的公共骨架，
     * 避免每个用例各写一遍 latch/join 样板。
     */
    private static void runConcurrently(int threads, IntConsumer body) throws InterruptedException {
        CountDownLatch startGate = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>(threads);
        for (int t = 0; t < threads; t++) {
            int threadNo = t;
            Thread worker = new Thread(() -> {
                try {
                    startGate.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                body.accept(threadNo);
            }, "client-" + t);
            worker.start();
            workers.add(worker);
        }
        startGate.countDown();
        for (Thread worker : workers) {
            worker.join(30_000);
        }
    }

    // ── I1：不丢件、恰一个终态 ────────────────────────────────────────────────

    @Test
    @DisplayName("A1 8 线程并发提交：ACCEPTED 的件全部拿到终态，且每件只被落库一次")
    void everyAcceptedItemGetsExactlyOneTerminalState() throws Exception {
        int threads = 8;
        int perThread = 100;
        RecordingFlushHandler handler = new RecordingFlushHandler();
        CountMerger engine = start(hotConfig(20), handler);

        List<MergeResult<LikeState>> outcomes = Collections.synchronizedList(new ArrayList<>());
        runConcurrently(threads, threadNo -> {
            long base = threadNo * 1_000L;
            for (int i = 0; i < perThread; i++) {
                outcomes.add(engine.commit(POST_ID, like(String.valueOf(base + i))));
            }
        });

        assertEquals(threads * perThread, outcomes.size(), "每个提交都必须有一个返回值，不能悬空");
        assertEquals(threads * perThread,
                outcomes.stream().filter(MergeResult::isCompleted).count(),
                "等待预算 5s 足够，不该出现超时/失败");
        assertTrue(engine.awaitIdle(5_000), "缓冲必须排空");
        assertEquals(0, engine.pending());
        // 排空判据：全分片缓冲空（在途批由 close 的 join 兜底，测试里件已全部回填）
        assertTrue(engine.awaitIdle(0), "排空后必须立刻判定为空闲");

        assertEquals(threads * perThread, handler.seenCount(), "ACCEPTED 的件数 == 落库看到的件数");
        assertEquals(0, handler.duplicateCount(), "每件至多回填一次（Pending.complete 幂等）");

        MergeMetrics metrics = engine.metrics();
        assertEquals(threads * perThread, metrics.accepted());
        assertEquals(threads * perThread, metrics.flushed());
        assertEquals(0, metrics.failed(), "没有落库失败");
        assertEquals(0, metrics.drainedFailed(), "没有停机回填");
        assertEquals(0, metrics.timedOut(), "timedOut 必须是 0（唯一时延判据）");
        assertEquals(0, metrics.rejectedFull());
        assertEquals(0, metrics.rejectedClosed());
    }

    @Test
    @DisplayName("I1 补齐：落库方法漏回填时合并器补 FAILED，等待者不会悬到超时")
    void missingBackfillIsCompletedAsFailed() throws Exception {
        RecordingFlushHandler handler = new RecordingFlushHandler();
        handler.fillOnlyTotal = 1;
        CountMerger engine = start(new MergeConfig(8, 200, 10, 5_000, 5_000, 2_000), handler);

        List<MergeResult<LikeState>> outcomes = submitBurst(engine, 5);

        assertEquals(1, outcomes.stream().filter(MergeResult::isCompleted).count());
        assertEquals(4, outcomes.stream()
                .filter(o -> o.state() == MergeState.FAILED).count(), "漏回填的件必须补 FAILED");
        for (MergeResult<LikeState> outcome : outcomes) {
            if (outcome.state() == MergeState.FAILED) {
                assertNull(outcome.result(), "FAILED 不带业务结果");
            }
        }
        assertEquals(4, engine.metrics().failed());
        assertEquals(0, engine.metrics().timedOut(), "补 FAILED 就是为了不让调用方等超时");
    }

    @Test
    @DisplayName("I1 兜底：落库整组抛异常 → 该组全部 FAILED，计数不丢不重")
    void flushFailureMarksWholeGroupFailed() throws Exception {
        RecordingFlushHandler handler = new RecordingFlushHandler();
        handler.throwOnFlush = true;
        CountMerger engine = start(new MergeConfig(8, 200, 10, 5_000, 5_000, 2_000), handler);

        List<MergeResult<LikeState>> outcomes = submitBurst(engine, 3);

        assertEquals(3, outcomes.stream().filter(o -> o.state() == MergeState.FAILED).count(),
                "事务回滚 ⇒ 组内每件都是 FAILED，不是超时");
        assertEquals(1, engine.metrics().batches(), "一次落库调用 = 一组");
        assertEquals(3, engine.metrics().failed());
        assertEquals(0, engine.metrics().flushed());
    }

    /**
     * 让 {@code count} 个等待者挤进同一个窗口（同一分片、同一行）：窗口给到 200ms，
     * 线程在同一道闸门后起跑，落库前一定都已在缓冲区里——这样「一组 N 件」是确定性的，
     * 不靠「谁先被调度」的运气。
     */
    private List<MergeResult<LikeState>> submitBurst(CountMerger engine, int count) throws Exception {
        List<MergeResult<LikeState>> outcomes = Collections.synchronizedList(new ArrayList<>(count));
        runConcurrently(count, threadNo -> outcomes.add(engine.commit(POST_ID, like(String.valueOf(threadNo)))));
        assertEquals(count, outcomes.size(), "每个等待者都必须拿到终态");
        return outcomes;
    }

    // ── I2：单飞 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A2 落库变慢时，同分片任一时刻至多一条 flush 在执行，且件数一件不少")
    void singleFlightNeverTwoConcurrentFlushes() throws Exception {
        int threads = 4;
        int perThread = 50;
        RecordingFlushHandler handler = new RecordingFlushHandler();
        handler.delayMs = 5;
        CountMerger engine = start(hotConfig(10), handler);

        List<MergeResult<LikeState>> outcomes = Collections.synchronizedList(new ArrayList<>());
        runConcurrently(threads, threadNo -> {
            long base = threadNo * 1_000L;
            for (int i = 0; i < perThread; i++) {
                outcomes.add(engine.commit(POST_ID, like(String.valueOf(base + i))));
            }
        });

        assertEquals(1, handler.maxInFlight(), "同一分片两条 flush 同时跑 = 行锁重新排队，合并收益归零");
        assertEquals(threads * perThread, outcomes.stream().filter(MergeResult::isCompleted).count());
        assertEquals(threads * perThread, handler.seenCount(), "单飞不等于丢件");
        assertTrue(engine.awaitIdle(5_000));
    }

    // ── I3：锁不跨 DB ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("A3 落库阻塞期间提交仍毫秒级返回：锁只保护内存，不跨 DB 调用")
    void flushBlockingDoesNotBlockSubmit() throws Exception {
        RecordingFlushHandler handler = new RecordingFlushHandler();
        handler.entered = new CountDownLatch(1);
        handler.hold = new CountDownLatch(1);
        CountMerger engine = start(hotConfig(10), handler);

        assertEquals(SubmitOutcome.ACCEPTED, engine.submitAsync(POST_ID, view(POST_ID, "0")));
        assertTrue(handler.entered.await(5, TimeUnit.SECONDS), "第一次 flush 应该已经进入落库");

        // 落库线程此刻被闸门挡住，同一分片的锁必须已经放开
        long startNanos = System.nanoTime();
        SubmitOutcome blockedSubmit = engine.submitAsync(POST_ID, view(POST_ID, "1"));
        long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertEquals(SubmitOutcome.ACCEPTED, blockedSubmit);
        assertTrue(costMs < 100, "落库阻塞 300ms+ 期间提交必须毫秒级返回，实测 " + costMs + "ms");

        handler.hold.countDown();
        assertTrue(engine.awaitIdle(5_000));
        assertEquals(2, handler.seenCount());
    }

    // ── I4：停机三步（停收 → 排空 → 关池）──────────────────────────────────────

    @Test
    @DisplayName("A4 停机先排空：close() 时缓冲区里的件照样落库，一件不丢")
    void closeStillDrainsBufferedItems() {
        RecordingFlushHandler handler = new RecordingFlushHandler();
        CountMerger engine = start(new MergeConfig(8, 20, 200, 500, 2_000, 3_000), handler);

        int total = 50;
        for (int i = 0; i < total; i++) {
            assertEquals(SubmitOutcome.ACCEPTED, engine.submitAsync(POST_ID, view(POST_ID, String.valueOf(i))));
        }

        engine.close();

        assertEquals(total, handler.seenCount(), "排空阶段必须把缓冲里的件全部落库");
        assertEquals(total, engine.metrics().flushed());
        assertEquals(0, engine.metrics().drainedFailed(), "正常排空不该有回填");
        assertEquals(0, engine.pending());
        assertTrue(engine.awaitIdle(0), "close() 之后必须已排空（没有在途 flush）");
    }

    @Test
    @DisplayName("A4 停收：close() 之后不再入队 —— 等结果的走同步降级，不等的如实拿 CLOSED")
    void closeRejectsNewSubmissions() {
        RecordingFlushHandler handler = new RecordingFlushHandler();
        CountMerger engine = start(hotConfig(200), handler);
        engine.close();

        MergeResult<LikeState> waited = engine.commit(POST_ID, like("1"));
        SubmitOutcome async = engine.submitAsync(POST_ID, view(POST_ID, "2"));

        assertEquals(MergeState.COMPLETED, waited.state(), "停机后 commit 照样有结论：本线程就地落一组");
        assertEquals(LikeState.LIKED, waited.result());
        assertEquals(SubmitOutcome.CLOSED, async, "异步入口不降级，如实回 CLOSED 由 case 层丢弃");
        assertEquals(1, handler.seenCount(), "只有 commit 那一笔落库；浏览件从未入队");
        assertEquals(2, engine.metrics().rejectedClosed());
        assertEquals(0, engine.metrics().accepted(), "两笔都没进缓冲，accepted 保持 0");
    }

    @Test
    @DisplayName("A4 排空超时：剩余件显式回填并计入 drainedFailed（浏览件回填是空操作，但必须计数）")
    void drainFailureIsCounted() throws Exception {
        RecordingFlushHandler handler = new RecordingFlushHandler();
        // 窗口 100ms 远超排空预算 20ms：close() 时件一定还在缓冲里
        CountMerger engine = start(new MergeConfig(8, 100, 200, 500, 2_000, 20), handler);

        int total = 3;
        for (int i = 0; i < total; i++) {
            assertEquals(SubmitOutcome.ACCEPTED, engine.submitAsync(POST_ID, view(POST_ID, String.valueOf(i))));
        }

        engine.close();

        assertEquals(total, engine.metrics().drainedFailed(), "排空超时的件必须显式回填并计数");
        assertEquals(0, engine.metrics().flushed());
        assertEquals(0, handler.seenCount(), "回填不等于落库：这些件从未进入事务");
        assertEquals(0, engine.pending());
        // 停机收尾之后，那条 100ms 的窗口不该再把件送进落库（线程已随 closed 退出）
        Thread.sleep(150);
        assertEquals(0, handler.seenCount());
    }

    // ── I5：背压有界 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("A5 满即拒：offer 立刻 BUFFER_FULL，不阻塞、不扩容、不动已入队的件")
    void rejectsWhenFullWithoutTouchingQueue() throws Exception {
        RecordingFlushHandler handler = new RecordingFlushHandler();
        handler.entered = new CountDownLatch(1);
        handler.hold = new CountDownLatch(1);
        CountMerger engine = start(new MergeConfig(8, 5, 8, 500, 8, 2_000), handler);

        // 先让一条 flush 卡在落库上，后面的件就都只能待在缓冲里
        assertEquals(SubmitOutcome.ACCEPTED, engine.submitAsync(POST_ID, view(POST_ID, "0")));
        assertTrue(handler.entered.await(5, TimeUnit.SECONDS));

        for (int i = 1; i <= 8; i++) {
            assertEquals(SubmitOutcome.ACCEPTED, engine.submitAsync(POST_ID, view(POST_ID, String.valueOf(i))),
                    "容量 8 之内必须收下");
        }
        assertEquals(8, engine.pending(), "已入队的 8 件都在缓冲里");

        long startNanos = System.nanoTime();
        SubmitOutcome rejected = engine.submitAsync(POST_ID, view(POST_ID, "99"));
        long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertEquals(SubmitOutcome.BUFFER_FULL, rejected, "满了立即拒绝");
        assertTrue(costMs < 100, "背压必须是「立刻 false」，不是「等一会儿再 false」");
        assertEquals(8, engine.pending(), "拒收不许挤掉已入队的件，也不许扩容");
        assertFalse(handler.hasSeen(view(POST_ID, "99")), "被拒的件不该进落库方法");

        handler.hold.countDown();
        assertTrue(engine.awaitIdle(5_000));
        assertEquals(9, handler.seenCount(), "1 件先落库 + 8 件补落库");
        assertEquals(1, engine.metrics().rejectedFull());
    }

    @Test
    @DisplayName("A5 背压不伤已入队件：连续拒收之后，先被收下的件仍然全部落库且只落一次")
    void acceptedItemsSurviveRejection() throws Exception {
        RecordingFlushHandler handler = new RecordingFlushHandler();
        handler.entered = new CountDownLatch(1);
        handler.hold = new CountDownLatch(1);
        CountMerger engine = start(new MergeConfig(8, 5, 8, 500, 8, 2_000), handler);

        assertEquals(SubmitOutcome.ACCEPTED, engine.submitAsync(POST_ID, view(POST_ID, "0")));
        assertTrue(handler.entered.await(5, TimeUnit.SECONDS));

        List<CountEvent> accepted = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            CountEvent event = view(POST_ID, String.valueOf(i));
            assertEquals(SubmitOutcome.ACCEPTED, engine.submitAsync(POST_ID, event));
            accepted.add(event);
        }
        List<CountEvent> rejected = new ArrayList<>();
        for (int i = 100; i < 120; i++) {
            CountEvent event = view(POST_ID, String.valueOf(i));
            assertEquals(SubmitOutcome.BUFFER_FULL, engine.submitAsync(POST_ID, event));
            rejected.add(event);
        }

        handler.hold.countDown();
        assertTrue(engine.awaitIdle(5_000));

        for (CountEvent event : accepted) {
            assertTrue(handler.hasSeen(event), "已入队的件必须落库");
        }
        for (CountEvent event : rejected) {
            assertFalse(handler.hasSeen(event), "被拒的件必须留在调用方手里（由它降级）");
        }
        assertEquals(20, engine.metrics().rejectedFull());
        assertEquals(9, engine.metrics().accepted());
        assertEquals(9, engine.metrics().flushed());
        assertEquals(0, engine.pending(), "排空后无积压");
    }

    // ── 附加断言：单批上限 + 同分片保序 ────────────────────────────────────────

    @Test
    @DisplayName("A6 单批不超过 maxBatch")
    void batchNeverExceedsMaxBatch() throws Exception {
        int maxBatch = 15;
        int threads = 4;
        int perThread = 50;
        RecordingFlushHandler handler = new RecordingFlushHandler();
        CountMerger engine = start(new MergeConfig(8, 1, maxBatch, 5_000, 2_000, 2_000), handler);

        runConcurrently(threads, threadNo -> {
            String shardKey = "post-" + (2_000 + threadNo);
            for (int i = 0; i < perThread; i++) {
                engine.submitAsync(shardKey, view(shardKey, String.valueOf(i)));
            }
        });

        assertTrue(engine.awaitIdle(5_000));
        assertTrue(engine.awaitIdle(0), "排空后必须立刻判定为空闲");
        assertEquals(threads * perThread, handler.seenCount());
        assertTrue(handler.maxGroupSize() <= maxBatch,
                "单组上限 = maxBatch，实测 " + handler.maxGroupSize());
    }

    @Test
    @DisplayName("A6 同分片内保持入队顺序（跨分片不保证）")
    void sameShardKeepsSubmissionOrder() {
        int total = 100;
        RecordingFlushHandler handler = new RecordingFlushHandler();
        CountMerger engine = start(hotConfig(7), handler);

        AtomicLong sequence = new AtomicLong();
        for (int i = 0; i < total; i++) {
            engine.submitAsync(POST_ID, view(POST_ID, String.valueOf(sequence.incrementAndGet())));
        }
        assertTrue(engine.awaitIdle(5_000));

        List<CountEvent> landed = handler.eventsOf(POST_ID);
        assertEquals(total, landed.size(), "件数不能少");
        for (int i = 0; i < total; i++) {
            assertEquals(String.valueOf(i + 1), landed.get(i).userId(), "同分片必须按入队序落库，第 " + i + " 件错位");
        }
    }

    // ── 读数 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("读数快照：accepted / flushed / batches 能算出合并比，maxFlushNanos 有值")
    void metricsExposeMergeRatio() {
        RecordingFlushHandler handler = new RecordingFlushHandler();
        CountMerger engine = start(new MergeConfig(8, 1, 50, 5_000, 2_000, 2_000), handler);

        for (int i = 0; i < 120; i++) {
            assertEquals(SubmitOutcome.ACCEPTED, engine.submitAsync(POST_ID, view(POST_ID, String.valueOf(i))));
        }
        assertTrue(engine.awaitIdle(5_000));

        MergeMetrics metrics = engine.metrics();
        assertEquals(120, metrics.accepted());
        assertEquals(120, metrics.flushed());
        assertEquals(120, handler.seenCount());
        assertEquals(handler.callCount(), metrics.batches(), "batches = 落库方法被调用的组数");
        assertEquals(handler.seenCount() / (double) handler.callCount(),
                metrics.flushed() / (double) metrics.batches(), 1e-9,
                "合并比 flushed ÷ batches 必须等于实际「每组的件数」，否则调参依据是假的（≈1 说明长尾，合并器是净成本）");
        assertTrue(metrics.maxFlushNanos() > 0, "maxFlushNanos 必须被记录（对照 waitTimeoutMs 用）");
        assertEquals(0, metrics.pending());
    }
}
