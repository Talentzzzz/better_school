package com.betterschool.marketing.infrastructure.inventory.merge;

import com.betterschool.marketing.domain.inventory.model.valobj.ClaimItem;
import com.betterschool.marketing.domain.inventory.model.valobj.ClaimOutcome;
import com.betterschool.marketing.domain.inventory.model.valobj.DeductResult;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryDeductRequestVO;
import com.betterschool.marketing.types.inventory.InventoryBizTypes;
import com.betterschool.marketing.types.inventory.InventorySegmentVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A1–A6：引擎的五条不变量 + 两条附加断言（批上限、同分片保序），库存侧那一份。
 *
 * <p>用例名与 {@code acceptance.md §2 A 组}、以及 better-school 侧 {@code CountMergerTest} 的用例名
 * <b>逐字一致</b>——两份实现各自在自己工程里先红，是「漂移能被自己发现」的唯一防线（票 06 §9）。
 *
 * <p>全部是<b>纯内存</b>测试：落库是 {@link RecordingQuotaFlushHandler}，不起 Spring、不连库。
 *
 * <p>与计数侧唯一的形式差别：库存侧没有 fire-and-forget 入口（{@code submitAsync} 不存在），
 * 所以「只想入队、不想等」的用例改成另起一条虚拟线程跑 {@code submitAndWait}（它 park 在自己的
 * future 上），「已入队」的判据是 {@code pending()} 涨到预期件数——这正是「提交侧只多一次 add」的形状。
 */
class QuotaMergeBufferTest {

    private static final long INV_ID = 9_001L;
    private static final InventorySegmentVO SEGMENT = InventorySegmentVO.QUOTA;

    private QuotaMergeBuffer engine;

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }

    private QuotaMergeBuffer start(MergeConfig config, IQuotaMergeFlushHandler handler) {
        engine = new QuotaMergeBuffer(config, handler);
        return engine;
    }

    /** 单热点、窄窗口、够大的等待预算：A1/A2/A6 用 */
    private static MergeConfig hotConfig(int maxBatch) {
        return new MergeConfig(8, 1, maxBatch, 5_000, 5_000, 4, 2_000);
    }

    private static InventoryDeductRequestVO deduct(long bizId) {
        return new InventoryDeductRequestVO(SEGMENT, INV_ID, 1, InventoryBizTypes.LOTTERY_DRAW, bizId);
    }

    private static ClaimItem claim(long bizId) {
        return new ClaimItem(InventoryBizTypes.LOTTERY_CLAIM, bizId, 1);
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

    /**
     * 「只入队、不等结果」：库存侧没有 fire-and-forget 入口，只能在别的线程上等（虚拟线程足够便宜）。
     *
     * @return 那条线程；结果会进 {@code sink}
     */
    private Thread submitDeductInBackground(InventoryDeductRequestVO request, List<DeductResult> sink) {
        return Thread.ofVirtual().start(() -> sink.add(engine.submitAndWait(INV_ID, request)));
    }

    private Thread submitClaimInBackground(ClaimItem item, List<ClaimOutcome> sink) {
        return Thread.ofVirtual().start(() -> sink.add(engine.submitAndWait(INV_ID, item)));
    }

    /** 等到缓冲里至少攒到 {@code expected} 件：这就是「入队成功」的判据（提交线程会 park 在 future 上） */
    private boolean awaitBuffered(int expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (engine.pending() < expected) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            Thread.sleep(1);
        }
        return true;
    }

    private boolean awaitBuffered(int expected) throws InterruptedException {
        return awaitBuffered(expected, 5_000);
    }

    // ── I1：不丢件、恰一个终态 ────────────────────────────────────────────────

    @Test
    @DisplayName("A1 8 线程并发提交：ACCEPTED 的件全部拿到终态，且每件只被落库一次")
    void everyAcceptedItemGetsExactlyOneTerminalState() throws Exception {
        int threads = 8;
        int perThread = 50;
        RecordingQuotaFlushHandler handler = new RecordingQuotaFlushHandler();
        QuotaMergeBuffer buffer = start(hotConfig(20), handler);

        List<DeductResult> results = Collections.synchronizedList(new ArrayList<>());
        runConcurrently(threads, threadNo -> {
            long base = threadNo * 1_000L;
            for (int i = 0; i < perThread; i++) {
                results.add(buffer.submitAndWait(INV_ID, deduct(base + i)));
            }
        });

        assertEquals(threads * perThread, results.size(), "每个提交都必须有一个返回值，不能悬空");
        assertEquals(threads * perThread,
                results.stream().filter(r -> r == DeductResult.DEDUCTED).count(),
                "等待预算 5s 足够，不该出现超时/失败");
        assertTrue(buffer.awaitIdle(5_000), "缓冲必须排空");
        assertEquals(0, buffer.pending());
        // I2 的推论：scheduled == false（且无在途 flush）⟹ 缓冲必空
        assertTrue(buffer.awaitIdle(0), "排空后必须立刻判定为空闲（没有在途 flush 链）");

        assertEquals(threads * perThread, handler.seenCount(), "ACCEPTED 的件数 == 落库看到的件数");
        assertEquals(0, handler.duplicateCount(), "每件至多回填一次（Pending.complete 幂等）");

        MergeMetrics metrics = buffer.metrics();
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
        RecordingQuotaFlushHandler handler = new RecordingQuotaFlushHandler();
        handler.fillOnlyTotal = 1;
        QuotaMergeBuffer buffer = start(new MergeConfig(8, 200, 10, 5_000, 5_000, 4, 2_000), handler);

        List<DeductResult> results = submitBurst(buffer, 5);

        assertEquals(1, results.stream().filter(r -> r == DeductResult.DEDUCTED).count());
        assertEquals(4, results.stream().filter(r -> r == DeductResult.FAILED).count(),
                "漏回填的件必须补 FAILED");
        assertEquals(4, buffer.metrics().failed());
        assertEquals(0, buffer.metrics().timedOut(), "补 FAILED 就是为了不让调用方等超时");
    }

    @Test
    @DisplayName("I1 兜底：落库整组抛异常 → 该组全部 FAILED（确认路径的 waiting 不足就走这条）")
    void flushFailureMarksWholeGroupFailed() throws Exception {
        RecordingQuotaFlushHandler handler = new RecordingQuotaFlushHandler();
        handler.throwOnFlush = true;
        QuotaMergeBuffer buffer = start(new MergeConfig(8, 200, 10, 5_000, 5_000, 4, 2_000), handler);

        List<DeductResult> results = submitBurst(buffer, 3);

        assertEquals(3, results.stream().filter(r -> r == DeductResult.FAILED).count(),
                "事务回滚 ⇒ 组内每件都是 FAILED，不是 TIMEOUT_UNKNOWN");
        assertEquals(1, buffer.metrics().batches(), "一次落库调用 = 一组");
        assertEquals(3, buffer.metrics().failed());
        assertEquals(0, buffer.metrics().flushed());
    }

    /**
     * 让 {@code count} 个等待者挤进同一个窗口（同一分片、同一行）：窗口给到 200ms，
     * 线程在同一道闸门后起跑，落库前一定都已在缓冲区里——这样「一组 N 件」是确定性的，
     * 不靠「谁先被调度」的运气。
     */
    private List<DeductResult> submitBurst(QuotaMergeBuffer buffer, int count) throws Exception {
        List<DeductResult> results = Collections.synchronizedList(new ArrayList<>(count));
        runConcurrently(count, threadNo -> results.add(buffer.submitAndWait(INV_ID, deduct(threadNo))));
        assertEquals(count, results.size(), "每个等待者都必须拿到终态");
        return results;
    }

    // ── I2：单飞 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A2 落库变慢时，同分片任一时刻至多一条 flush 在执行，且件数一件不少")
    void singleFlightNeverTwoConcurrentFlushes() throws Exception {
        // 线程数必须明显大于 maxBatch：库存侧每个调用方线程同时只挂一件（都在等结果），
        // 只有并发调用方够多，落库期间 buffer 才攒得满——那正是「攒满提前触发」撞上「正在落库」的窗口，
        // 也是唯一能把单飞写坏的机会（用 Future.cancel() 判单飞在这里会稳定复现两条并发 flush）
        int threads = 16;
        int perThread = 20;
        int maxBatch = 10;
        RecordingQuotaFlushHandler handler = new RecordingQuotaFlushHandler();
        handler.delayMs = 5;
        QuotaMergeBuffer buffer = start(hotConfig(maxBatch), handler);

        List<DeductResult> results = Collections.synchronizedList(new ArrayList<>());
        runConcurrently(threads, threadNo -> {
            long base = threadNo * 1_000L;
            for (int i = 0; i < perThread; i++) {
                results.add(buffer.submitAndWait(INV_ID, deduct(base + i)));
            }
        });

        assertEquals(1, handler.maxInFlight(),
                "同一分片两条 flush 同时跑 = 台账行锁重新排队，确认看到的也不一定是已落账的 waiting");
        assertEquals(threads * perThread,
                results.stream().filter(r -> r == DeductResult.DEDUCTED).count());
        assertEquals(threads * perThread, handler.seenCount(), "单飞不等于丢件");
        assertTrue(buffer.awaitIdle(5_000));
    }

    // ── I3：锁不跨 DB ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("A3 落库阻塞期间提交仍毫秒级入队：锁只保护内存，不跨 DB 调用")
    void flushBlockingDoesNotBlockSubmit() throws Exception {
        RecordingQuotaFlushHandler handler = new RecordingQuotaFlushHandler();
        handler.entered = new CountDownLatch(1);
        handler.hold = new CountDownLatch(1);
        QuotaMergeBuffer buffer = start(hotConfig(10), handler);

        List<DeductResult> results = Collections.synchronizedList(new ArrayList<>());
        submitDeductInBackground(deduct(1), results);
        assertTrue(handler.entered.await(5, TimeUnit.SECONDS), "第一次 flush 应该已经进入落库");

        // 落库线程此刻被闸门挡住，同一分片的锁必须已经放开：第二件要能立刻进缓冲区
        long startNanos = System.nanoTime();
        submitDeductInBackground(deduct(2), results);
        boolean buffered = awaitBuffered(1, 2_000);
        long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertTrue(buffered, "落库阻塞期间提交必须仍能入队（锁跨 DB 就会卡在 offer 上）");
        assertTrue(costMs < 100, "入队必须毫秒级，实测 " + costMs + "ms");

        handler.hold.countDown();
        assertTrue(buffer.awaitIdle(5_000));
        assertEquals(2, handler.seenCount());
        assertEquals(1, handler.maxInFlight(), "期间只有那一条 flush 在跑");
    }

    // ── I4：停机三步（停收 → 排空 → 关池）──────────────────────────────────────

    @Test
    @DisplayName("A4 停机先排空：close() 时缓冲区里的件照样落库，一件不丢")
    void closeStillDrainsBufferedItems() throws Exception {
        RecordingQuotaFlushHandler handler = new RecordingQuotaFlushHandler();
        // 窗口 1000ms 远超「攒件」所需：close() 之前不会有任何 flush 发生
        QuotaMergeBuffer buffer = start(new MergeConfig(8, 1_000, 200, 5_000, 2_000, 4, 3_000), handler);

        int total = 20;
        List<DeductResult> results = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < total; i++) {
            submitDeductInBackground(deduct(i), results);
        }
        assertTrue(awaitBuffered(total), "20 件都该在缓冲里等窗口");

        buffer.close();

        assertEquals(total, handler.seenCount(), "排空阶段必须把缓冲里的件全部落库");
        assertEquals(total, buffer.metrics().flushed());
        assertEquals(0, buffer.metrics().drainedFailed(), "正常排空不该有回填");
        assertEquals(0, buffer.pending());
        assertTrue(buffer.awaitIdle(0), "close() 之后必须已排空（没有在途 flush）");
        assertEquals(total, results.stream().filter(r -> r == DeductResult.DEDUCTED).count(),
                "等结果的调用方必须在停机前拿到成功，而不是 TIMEOUT_UNKNOWN");
    }

    @Test
    @DisplayName("A4 停收：close() 之后提交拿 CLOSED，且件从未入队")
    void closeRejectsNewSubmissions() {
        RecordingQuotaFlushHandler handler = new RecordingQuotaFlushHandler();
        QuotaMergeBuffer buffer = start(hotConfig(200), handler);
        buffer.close();

        assertEquals(DeductResult.CLOSED, buffer.submitAndWait(INV_ID, deduct(1)));
        assertEquals(ClaimOutcome.CLOSED, buffer.submitAndWait(INV_ID, claim(2)));
        assertEquals(0, handler.seenCount(), "停机后提交的件不该被落库");
        assertEquals(2, buffer.metrics().rejectedClosed());
        assertEquals(0, buffer.metrics().accepted());
    }

    @Test
    @DisplayName("A4 排空超时：剩余件显式回填并计入 drainedFailed（库存侧 = 确定没扣，可安全重试）")
    void drainFailureIsCounted() throws Exception {
        RecordingQuotaFlushHandler handler = new RecordingQuotaFlushHandler();
        // 窗口 1000ms 远超排空预算 20ms：close() 时件一定还在缓冲里
        QuotaMergeBuffer buffer = start(new MergeConfig(8, 1_000, 200, 5_000, 2_000, 4, 20), handler);

        int total = 3;
        List<DeductResult> results = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < total; i++) {
            submitDeductInBackground(deduct(i), results);
        }
        assertTrue(awaitBuffered(total));

        buffer.close();

        assertEquals(total, buffer.metrics().drainedFailed(), "排空超时的件必须显式回填并计数");
        assertEquals(0, buffer.metrics().flushed());
        assertEquals(0, handler.seenCount(), "回填不等于落库：这些件从未进入事务");
        assertEquals(0, buffer.pending());
        // 回填的件必须拿到「确定失败」（不是 TIMEOUT_UNKNOWN），调用方才能直接重试 / 同步降级
        assertEquals(total, results.stream().filter(r -> r == DeductResult.FAILED).count());
        // 关池之后，那条 1000ms 的定时任务不该再跑出来
        Thread.sleep(150);
        assertEquals(0, handler.seenCount());
    }

    // ── I5：背压有界 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("A5 满即拒：offer 立刻 BUFFER_FULL，不阻塞、不扩容、不动已入队的件")
    void rejectsWhenFullWithoutTouchingQueue() throws Exception {
        RecordingQuotaFlushHandler handler = new RecordingQuotaFlushHandler();
        handler.entered = new CountDownLatch(1);
        handler.hold = new CountDownLatch(1);
        QuotaMergeBuffer buffer = start(new MergeConfig(8, 5, 8, 500, 8, 4, 2_000), handler);

        // 先让一条 flush 卡在落库上，后面的件就都只能待在缓冲里
        List<DeductResult> results = Collections.synchronizedList(new ArrayList<>());
        submitDeductInBackground(deduct(0), results);
        assertTrue(handler.entered.await(5, TimeUnit.SECONDS));

        for (int i = 1; i <= 8; i++) {
            submitDeductInBackground(deduct(i), results);
        }
        assertTrue(awaitBuffered(8), "容量 8 之内必须收下");

        long startNanos = System.nanoTime();
        DeductResult rejected = buffer.submitAndWait(INV_ID, deduct(99));
        long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertEquals(DeductResult.BUFFER_FULL, rejected, "满了立即拒绝（调用方据此同步降级，不丢奖）");
        assertTrue(costMs < 100, "背压必须是「立刻 false」，不是「等一会儿再 false」，实测 " + costMs + "ms");
        assertEquals(8, buffer.pending(), "拒收不许挤掉已入队的件，也不许扩容");
        assertFalse(handler.hasSeen(deduct(99)), "被拒的件不该进落库方法");

        handler.hold.countDown();
        assertTrue(buffer.awaitIdle(5_000));
        assertEquals(9, handler.seenCount(), "1 件先落库 + 8 件补落库");
        assertEquals(1, buffer.metrics().rejectedFull());
    }

    @Test
    @DisplayName("A5 背压不伤已入队件：连续拒收之后，先被收下的件仍然全部落库且只落一次")
    void acceptedItemsSurviveRejection() throws Exception {
        RecordingQuotaFlushHandler handler = new RecordingQuotaFlushHandler();
        handler.entered = new CountDownLatch(1);
        handler.hold = new CountDownLatch(1);
        QuotaMergeBuffer buffer = start(new MergeConfig(8, 5, 8, 500, 8, 4, 2_000), handler);

        List<DeductResult> results = Collections.synchronizedList(new ArrayList<>());
        submitDeductInBackground(deduct(0), results);
        assertTrue(handler.entered.await(5, TimeUnit.SECONDS));

        List<InventoryDeductRequestVO> accepted = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            InventoryDeductRequestVO request = deduct(i);
            submitDeductInBackground(request, results);
            accepted.add(request);
        }
        assertTrue(awaitBuffered(8));

        List<InventoryDeductRequestVO> rejected = new ArrayList<>();
        for (int i = 100; i < 120; i++) {
            InventoryDeductRequestVO request = deduct(i);
            assertEquals(DeductResult.BUFFER_FULL, buffer.submitAndWait(INV_ID, request));
            rejected.add(request);
        }

        handler.hold.countDown();
        assertTrue(buffer.awaitIdle(5_000));

        for (InventoryDeductRequestVO request : accepted) {
            assertTrue(handler.hasSeen(request), "已入队的件必须落库");
        }
        for (InventoryDeductRequestVO request : rejected) {
            assertFalse(handler.hasSeen(request), "被拒的件必须留在调用方手里（由它同步降级）");
        }
        assertEquals(20, buffer.metrics().rejectedFull());
        assertEquals(9, buffer.metrics().accepted());
        assertEquals(9, buffer.metrics().flushed());
        assertEquals(0, buffer.pending(), "排空后无积压");
    }

    // ── 附加断言：单批上限 + 同分片保序 ────────────────────────────────────────

    @Test
    @DisplayName("A6 单批不超过 maxBatch")
    void batchNeverExceedsMaxBatch() throws Exception {
        int maxBatch = 15;
        int threads = 40;
        RecordingQuotaFlushHandler handler = new RecordingQuotaFlushHandler();
        QuotaMergeBuffer buffer = start(new MergeConfig(8, 1, maxBatch, 5_000, 2_000, 4, 2_000), handler);

        List<DeductResult> results = Collections.synchronizedList(new ArrayList<>());
        runConcurrently(threads, threadNo -> results.add(buffer.submitAndWait(INV_ID, deduct(threadNo))));

        assertTrue(buffer.awaitIdle(5_000));
        assertTrue(buffer.awaitIdle(0), "排空后必须立刻判定为空闲");
        assertEquals(threads, handler.seenCount());
        assertTrue(handler.maxGroupSize() <= maxBatch, "单组上限 = maxBatch，实测 " + handler.maxGroupSize());
    }

    @Test
    @DisplayName("A6 同分片内保持入队顺序（跨分片不保证）")
    void sameShardKeepsSubmissionOrder() throws Exception {
        int total = 20;
        RecordingQuotaFlushHandler handler = new RecordingQuotaFlushHandler();
        // 窗口给足，让 20 件在同一个窗口里排队
        QuotaMergeBuffer buffer = start(new MergeConfig(8, 1_000, 200, 5_000, 2_000, 4, 6_000), handler);

        List<DeductResult> results = Collections.synchronizedList(new ArrayList<>());
        for (int i = 1; i <= total; i++) {
            // 一个线程只能挂一件（库存侧都在等结果），所以用「上一件确认已入队」把顺序钉死
            submitDeductInBackground(deduct(i), results);
            if (!awaitBuffered(i)) {
                fail("第 " + i + " 件没能入队");
            }
        }
        assertTrue(buffer.awaitIdle(5_000));

        List<Object> landed = handler.payloadsOf(INV_ID);
        assertEquals(total, landed.size(), "件数不能少");
        for (int i = 0; i < total; i++) {
            assertEquals(i + 1, ((InventoryDeductRequestVO) landed.get(i)).bizId(),
                    "同分片必须按入队序落库，第 " + i + " 件错位");
        }
    }

    // ── 分组：动作不同 ⇒ 两个各自成组的落库方法 ─────────────────────────────────

    @Test
    @DisplayName("预扣与确认同分片、同窗口，但落在两个组里（两个失败域不合成一条 SQL）")
    void deductAndClaimNeverShareAGroup() throws Exception {
        RecordingQuotaFlushHandler handler = new RecordingQuotaFlushHandler();
        QuotaMergeBuffer buffer = start(new MergeConfig(8, 200, 200, 5_000, 2_000, 4, 2_000), handler);

        List<DeductResult> deductResults = Collections.synchronizedList(new ArrayList<>());
        List<ClaimOutcome> claimResults = Collections.synchronizedList(new ArrayList<>());
        Thread deductThread = submitDeductInBackground(deduct(1), deductResults);
        Thread claimThread = submitClaimInBackground(claim(2), claimResults);

        deductThread.join(10_000);
        claimThread.join(10_000);
        assertTrue(buffer.awaitIdle(5_000));

        assertEquals(List.of(DeductResult.DEDUCTED), deductResults);
        assertEquals(List.of(ClaimOutcome.CONFIRMED), claimResults);
        assertEquals(2, handler.callCount(), "预扣一组、确认一组：两条 SQL 各有自己的事务，不是一条");
        assertEquals(2, handler.seenCount(), "两件各被落库一次");
        assertEquals(0, handler.duplicateCount(), "没有一件被落两次（组边界没把它们弄重复）");
        assertEquals(2, buffer.metrics().batches());
    }

    @Test
    @DisplayName("shardKey 必须等于 request.invId：同一行给两个 key 会让两个分片各自合并同一行")
    void rejectsShardKeyThatDiffersFromRequestInvId() {
        RecordingQuotaFlushHandler handler = new RecordingQuotaFlushHandler();
        QuotaMergeBuffer buffer = start(hotConfig(200), handler);

        assertThrows(IllegalArgumentException.class,
                () -> buffer.submitAndWait(INV_ID + 1, deduct(1)));
        assertEquals(0, buffer.metrics().accepted(), "不一致的件不许入队");
        assertEquals(0, handler.seenCount());
    }

    @Test
    @DisplayName("INSUFFICIENT 是业务结果不是丢失：计入 flushed 而不是 failed（三出口里的第二条）")
    void insufficientIsABusinessResultNotALoss() throws Exception {
        RecordingQuotaFlushHandler handler = new RecordingQuotaFlushHandler();
        handler.insufficientOnFlush = true;
        QuotaMergeBuffer buffer = start(new MergeConfig(8, 200, 10, 5_000, 5_000, 4, 2_000), handler);

        List<DeductResult> results = submitBurst(buffer, 3);

        assertEquals(3, results.stream().filter(r -> r == DeductResult.INSUFFICIENT).count(),
                "余量不足必须原样回给调用方（它据此切兜底档），不能变成 FAILED");
        MergeMetrics metrics = buffer.metrics();
        assertEquals(3, metrics.flushed(), "INSUFFICIENT 是落库方法给出的业务结果 ⇒ 算 flushed");
        assertEquals(0, metrics.failed(), "没扣成不等于丢件");
    }

    @Test
    @DisplayName("等超时回 TIMEOUT_UNKNOWN（不是 FAILED）：件仍在途，之后大概率照样落库")
    void timeoutIsReportedAsUnknownResultNotFailure() throws Exception {
        RecordingQuotaFlushHandler handler = new RecordingQuotaFlushHandler();
        handler.entered = new CountDownLatch(1);
        handler.hold = new CountDownLatch(1);
        // 等待预算 50ms 远小于闸门被按住的时间：一定先超时
        QuotaMergeBuffer buffer = start(new MergeConfig(8, 1, 10, 50, 2_000, 4, 2_000), handler);

        List<DeductResult> results = Collections.synchronizedList(new ArrayList<>());
        Thread caller = submitDeductInBackground(deduct(1), results);
        assertTrue(handler.entered.await(5, TimeUnit.SECONDS), "第一次 flush 应该已经进入落库");
        caller.join(5_000);

        assertEquals(List.of(DeductResult.TIMEOUT_UNKNOWN), results,
                "超时必须是第三态：调用方据此「不切兜底、抛可重试」，当失败处理会扣两份库存");
        assertEquals(1, buffer.metrics().timedOut());

        handler.hold.countDown();
        assertTrue(buffer.awaitIdle(5_000));
        assertEquals(1, buffer.metrics().flushed(), "超时的件后来确实落库了 —— 这正是第三态存在的理由");
        assertEquals(0, buffer.metrics().failed());
    }

    // ── 读数 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("读数快照：accepted / flushed / batches 能算出合并比，maxFlushNanos 有值")
    void metricsExposeMergeRatio() throws Exception {
        int total = 60;
        RecordingQuotaFlushHandler handler = new RecordingQuotaFlushHandler();
        QuotaMergeBuffer buffer = start(new MergeConfig(8, 1, 50, 5_000, 2_000, 2, 2_000), handler);

        List<DeductResult> results = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch submitted = new CountDownLatch(total);
        for (int i = 0; i < total; i++) {
            InventoryDeductRequestVO request = deduct(i);
            Thread.ofVirtual().start(() -> {
                results.add(buffer.submitAndWait(INV_ID, request));
                submitted.countDown();
            });
        }
        assertTrue(submitted.await(5, TimeUnit.SECONDS), "60 件都该提交完（每件都拿到返回值）");
        assertTrue(buffer.awaitIdle(5_000));

        MergeMetrics metrics = buffer.metrics();
        assertEquals(total, metrics.accepted());
        assertEquals(total, metrics.flushed());
        assertEquals(total, handler.seenCount());
        assertEquals(handler.callCount(), metrics.batches(), "batches = 落库方法被调用的组数");
        assertEquals(handler.seenCount() / (double) handler.callCount(),
                metrics.flushed() / (double) metrics.batches(), 1e-9,
                "合并比 flushed ÷ batches 必须等于实际「每组的件数」，否则调参依据是假的");
        assertTrue(metrics.maxFlushNanos() > 0, "maxFlushNanos 必须被记录（对照 waitTimeoutMs 用）");
        assertEquals(0, metrics.pending());
    }
}
