package com.betterschool.infrastructure.merge;

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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 引擎的五条不变量 + 两条附加断言（批上限、同分片保序）。
 *
 * <p>全部是纯内存测试：件是 {@link Job}（分片键 + 业务号），落库是 {@link RecordingFlush}，
 * 不起 Spring、不连库。这也说明通用组件的可测性——引擎不知道任何业务类型，
 * 策略在起引擎时绑一次，测它就只需要一个 record 加一个 lambda。
 */
class MergeEngineTest {

    private static final String KEY = "9_001";

    /** 测试件：分片键 + 业务号（业务号只用于保序断言） */
    record Job(String shardKey, int seq) {
    }

    private MergeEngine<Job, Boolean> engine;

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
            engine = null;
        }
    }

    /** 起引擎必须连策略一起给：件里不带策略，一个引擎只服务一个用途 */
    private MergeEngine<Job, Boolean> start(MergeConfig config, MergeStrategy<Job, Boolean> strategy) {
        engine = new MergeEngine<>(config, strategy);
        return engine;
    }

    private static MergeStrategy<Job, Boolean> strategyOf(RecordingFlush flush) {
        return new MergeStrategy<>("test", Job::shardKey, flush);
    }

    private static Job job(int seq) {
        return new Job(KEY, seq);
    }

    /** 单热点、窄窗口、够大的等待预算：A1/A2/A6 用 */
    private static MergeConfig hotConfig(int maxBatch) {
        return new MergeConfig(8, 1, maxBatch, 5_000, 5_000, 2_000);
    }

    /** 拿到业务结果且为 true 的件数 */
    private static long countTrue(List<MergeResult<Boolean>> outcomes) {
        return outcomes.stream().filter(o -> o.isCompleted() && Boolean.TRUE.equals(o.result())).count();
    }

    /** 落在某个终态的件数（{@code TIMEOUT} / {@code FAILED}） */
    private static long countState(List<MergeResult<Boolean>> outcomes, MergeResult.State state) {
        return outcomes.stream().filter(o -> o.state() == state).count();
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
     * 「只入队、不等结果」：本组件唯一的入口会等结果，只能在别的线程上等（虚拟线程足够便宜）。
     *
     * @return 那条线程；结果会进 {@code sink}
     */
    private Thread submitInBackground(Job item, List<MergeResult<Boolean>> sink) {
        return Thread.ofVirtual().start(() -> sink.add(engine.commit(item)));
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

    /** 等到落库函数被调用过 {@code expected} 次：确认「降级已经就地发起」，不必等它返回 */
    private static boolean awaitFlushCalls(RecordingFlush flush, int expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (flush.callCount() < expected) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            Thread.sleep(1);
        }
        return true;
    }

    // ── I1：不丢件、恰一个终态 ────────────────────────────────────────────────

    @Test
    @DisplayName("A1 8 线程并发提交：每件全部拿到终态，且每件只被落库一次")
    void everyAcceptedItemGetsExactlyOneTerminalState() throws Exception {
        int threads = 8;
        int perThread = 50;
        RecordingFlush flush = new RecordingFlush();
        MergeStrategy<Job, Boolean> strategy = strategyOf(flush);
        MergeEngine<Job, Boolean> buffer = start(hotConfig(20), strategy);

        List<MergeResult<Boolean>> results = Collections.synchronizedList(new ArrayList<>());
        runConcurrently(threads, threadNo -> {
            int base = threadNo * 1_000;
            for (int i = 0; i < perThread; i++) {
                results.add(buffer.commit(job(base + i)));
            }
        });

        assertEquals(threads * perThread, results.size(), "每个提交都必须有一个返回值，不能悬空");
        assertEquals(threads * perThread, countTrue(results), "等待预算 5s 足够，不该出现超时/失败");
        assertTrue(buffer.awaitIdle(5_000), "缓冲必须排空");
        assertEquals(0, buffer.pending());
        // I2 的推论（per-shard 线程模型）：提交线程全部返回 ⟹ 每件都已回填 ⟹ 所在批已摘走，
        // 且不再有新提交 ⟹ size 镜像必为 0，缓冲必空
        assertTrue(buffer.awaitIdle(0), "排空后必须立刻判定为空闲（没有在途 flush 链）");

        assertEquals(threads * perThread, flush.seenCount(), "入队的件数 == 落库函数看到的件数");
        assertEquals(0, flush.duplicateCount(), "每件至多回填一次（Pending 幂等）");
    }

    @Test
    @DisplayName("I1 补齐：落库函数漏回填时引擎补 FAILED，等待者不会悬到超时")
    void missingBackfillIsCompletedAsFailed() throws Exception {
        RecordingFlush flush = new RecordingFlush();
        flush.fillOnlyTotal = 1;
        MergeStrategy<Job, Boolean> strategy = strategyOf(flush);
        MergeEngine<Job, Boolean> buffer = start(new MergeConfig(8, 200, 10, 5_000, 5_000, 2_000), strategy);

        List<MergeResult<Boolean>> results = submitBurst(buffer, 5);

        assertEquals(1, countTrue(results));
        assertEquals(4, countState(results, MergeResult.State.FAILED), "漏回填的件必须补 FAILED");
    }

    @Test
    @DisplayName("I1 兜底：落库整组抛异常 → 该组全部 FAILED（不变量破坏就走这条）")
    void flushFailureMarksWholeGroupFailed() throws Exception {
        RecordingFlush flush = new RecordingFlush();
        flush.throwOnFlush = true;
        MergeStrategy<Job, Boolean> strategy = strategyOf(flush);
        MergeEngine<Job, Boolean> buffer = start(new MergeConfig(8, 200, 10, 5_000, 5_000, 2_000), strategy);

        List<MergeResult<Boolean>> results = submitBurst(buffer, 3);

        assertEquals(3, countState(results, MergeResult.State.FAILED),
                "事务回滚 ⇒ 组内每件都是 FAILED，不是 TIMEOUT");
    }

    /**
     * 让 {@code count} 个等待者挤进同一个窗口（同一分片、同一行）：窗口给到 200ms，
     * 线程在同一道闸门后起跑，落库前一定都已在缓冲区里——这样「一组 N 件」是确定性的，
     * 不靠「谁先被调度」的运气。
     */
    private List<MergeResult<Boolean>> submitBurst(MergeEngine<Job, Boolean> buffer, int count) throws Exception {
        List<MergeResult<Boolean>> results = Collections.synchronizedList(new ArrayList<>(count));
        runConcurrently(count, threadNo -> results.add(buffer.commit(job(threadNo))));
        assertEquals(count, results.size(), "每个等待者都必须拿到终态");
        return results;
    }

    // ── I2：单飞 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A2 落库变慢时，同分片任一时刻至多一条 flush 在执行，且件数一件不少")
    void singleFlightNeverTwoConcurrentFlushes() throws Exception {
        // 线程数必须明显大于 maxBatch：每个调用方线程同时只挂一件（都在等结果），
        // 只有并发调用方够多，落库期间 buffer 才攒得满——那正是「攒满提前触发」撞上「正在落库」的窗口，
        // 也是唯一能把单飞写坏的机会（用 Future.cancel() 判单飞在这里会稳定复现两条并发 flush）
        int threads = 16;
        int perThread = 20;
        int maxBatch = 10;
        RecordingFlush flush = new RecordingFlush();
        flush.delayMs = 5;
        MergeStrategy<Job, Boolean> strategy = strategyOf(flush);
        MergeEngine<Job, Boolean> buffer = start(hotConfig(maxBatch), strategy);

        List<MergeResult<Boolean>> results = Collections.synchronizedList(new ArrayList<>());
        runConcurrently(threads, threadNo -> {
            int base = threadNo * 1_000;
            for (int i = 0; i < perThread; i++) {
                results.add(buffer.commit(job(base + i)));
            }
        });

        assertEquals(1, flush.maxInFlight(),
                "同一分片两条 flush 同时跑 = 台账行锁重新排队，后一批看到的也不一定是已提交的状态");
        assertEquals(threads * perThread, countTrue(results));
        assertEquals(threads * perThread, flush.seenCount(), "单飞不等于丢件");
        assertTrue(buffer.awaitIdle(5_000));
    }

    // ── I3：锁不跨 DB ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("A3 落库阻塞期间提交仍毫秒级入队：锁只保护内存，不跨 DB 调用")
    void flushBlockingDoesNotBlockSubmit() throws Exception {
        RecordingFlush flush = new RecordingFlush();
        flush.entered = new CountDownLatch(1);
        flush.hold = new CountDownLatch(1);
        MergeStrategy<Job, Boolean> strategy = strategyOf(flush);
        MergeEngine<Job, Boolean> buffer = start(hotConfig(10), strategy);

        List<MergeResult<Boolean>> results = Collections.synchronizedList(new ArrayList<>());
        submitInBackground(job(1), results);
        assertTrue(flush.entered.await(5, TimeUnit.SECONDS), "第一次 flush 应该已经进入落库");

        // 落库线程此刻被闸门挡住，同一分片的锁必须已经放开：第二件要能立刻进缓冲区
        long startNanos = System.nanoTime();
        submitInBackground(job(2), results);
        boolean buffered = awaitBuffered(1, 2_000);
        long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertTrue(buffered, "落库阻塞期间提交必须仍能入队（锁跨 DB 就会卡在 offer 上）");
        assertTrue(costMs < 100, "入队必须毫秒级，实测 " + costMs + "ms");

        flush.hold.countDown();
        assertTrue(buffer.awaitIdle(5_000));
        assertEquals(2, flush.seenCount());
        assertEquals(1, flush.maxInFlight(), "期间只有那一条 flush 在跑");
    }

    // ── I4：停机三步（停收 → 排空 → 关池）──────────────────────────────────────

    @Test
    @DisplayName("A4 停机先排空：close() 时缓冲区里的件照样落库，一件不丢")
    void closeStillDrainsBufferedItems() throws Exception {
        RecordingFlush flush = new RecordingFlush();
        MergeStrategy<Job, Boolean> strategy = strategyOf(flush);
        // 窗口 1000ms 远超「攒件」所需：close() 之前不会有任何 flush 发生
        MergeEngine<Job, Boolean> buffer = start(new MergeConfig(8, 1_000, 200, 5_000, 2_000, 3_000), strategy);

        int total = 20;
        List<MergeResult<Boolean>> results = Collections.synchronizedList(new ArrayList<>());
        List<Thread> submitters = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            submitters.add(submitInBackground(job(i), results));
        }
        assertTrue(awaitBuffered(total), "20 件都该在缓冲里等窗口");

        buffer.close();
        // 结果由提交线程写进 sink：必须等它们跑完再数，否则数的是「已被唤醒的线程数」而不是终态
        for (Thread submitter : submitters) {
            submitter.join(5_000);
        }

        assertEquals(total, flush.seenCount(), "排空阶段必须把缓冲里的件全部落库");
        assertEquals(0, buffer.pending());
        assertTrue(buffer.awaitIdle(0), "close() 之后必须已排空（没有在途 flush）");
        assertEquals(total, countTrue(results), "等结果的调用方必须在停机前拿到成功，而不是 TIMEOUT");
    }

    @Test
    @DisplayName("A4 停收：close() 之后提交不进缓冲，改由调用线程一组一件就地落库")
    void closeRejectsNewSubmissions() {
        RecordingFlush flush = new RecordingFlush();
        MergeStrategy<Job, Boolean> strategy = strategyOf(flush);
        MergeEngine<Job, Boolean> buffer = start(hotConfig(200), strategy);
        buffer.close();

        MergeResult<Boolean> outcome = buffer.commit(job(1));

        assertEquals(MergeResult.State.COMPLETED, outcome.state(), "停机后提交照样有结果：降级成本线程落库，不是拒收");
        assertEquals(1, flush.callCount(), "不经缓冲：本线程直接落一组");
        assertEquals(1, flush.maxGroupSize(), "降级固定组大小 1，不攒批");
        assertEquals(0, buffer.pending(), "件从未进过缓冲区");
    }

    @Test
    @DisplayName("A4 排空超时：剩余件显式回填 FAILED（= 确定没落，可安全重试）")
    void drainFailureBackfillsFailed() throws Exception {
        RecordingFlush flush = new RecordingFlush();
        MergeStrategy<Job, Boolean> strategy = strategyOf(flush);
        // 窗口 1000ms 远超排空预算 20ms：close() 时件一定还在缓冲里
        MergeEngine<Job, Boolean> buffer = start(new MergeConfig(8, 1_000, 200, 5_000, 2_000, 20), strategy);

        int total = 3;
        List<MergeResult<Boolean>> results = Collections.synchronizedList(new ArrayList<>());
        for (int i = 0; i < total; i++) {
            submitInBackground(job(i), results);
        }
        assertTrue(awaitBuffered(total));

        buffer.close();

        assertEquals(0, flush.seenCount(), "回填不等于落库：这些件从未进入事务");
        assertEquals(0, buffer.pending());
        // 回填的件必须拿到「确定失败」（不是 TIMEOUT），调用方才能直接重试 / 同步降级
        assertEquals(total, countState(results, MergeResult.State.FAILED));
        // 停机之后，那条 1000ms 的窗口不该再跑出 flush
        Thread.sleep(150);
        assertEquals(0, flush.seenCount());
    }

    // ── I5：背压有界 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("A5 满即拒：不入队、不扩容、不动已入队件，被拒的件由调用线程就地落库")
    void rejectsWhenFullWithoutTouchingQueue() throws Exception {
        RecordingFlush flush = new RecordingFlush();
        flush.entered = new CountDownLatch(1);
        flush.hold = new CountDownLatch(1);
        MergeStrategy<Job, Boolean> strategy = strategyOf(flush);
        MergeEngine<Job, Boolean> buffer = start(new MergeConfig(8, 5, 8, 500, 8, 2_000), strategy);

        // 先让一条 flush 卡在落库上，后面的件就都只能待在缓冲里
        List<MergeResult<Boolean>> results = Collections.synchronizedList(new ArrayList<>());
        submitInBackground(job(0), results);
        assertTrue(flush.entered.await(5, TimeUnit.SECONDS));

        for (int i = 1; i <= 8; i++) {
            submitInBackground(job(i), results);
        }
        assertTrue(awaitBuffered(8), "容量 8 之内必须收下");

        // 第 9 件：缓冲已满 ⇒ 不进缓冲，就地同步落库（组大小 1）。它同样被 hold 闸门挡住，
        // 所以放到虚拟线程里发起；主线程要断言的是「进没进缓冲」与「有没有立刻就地落库」。
        Job overflow = job(99);
        List<MergeResult<Boolean>> overflowOutcome = Collections.synchronizedList(new ArrayList<>());
        Thread overflowThread = Thread.ofVirtual().start(() -> overflowOutcome.add(buffer.commit(overflow)));

        assertTrue(awaitFlushCalls(flush, 2, 5_000), "满了必须立刻就地落库，不是等窗口、也不是等超时");
        assertEquals(8, buffer.pending(), "拒收不许挤掉已入队的件，也不许扩容");
        assertTrue(overflowOutcome.isEmpty(), "件正卡在落库上，此时不该有终态");

        flush.hold.countDown();
        overflowThread.join(5_000);
        assertTrue(buffer.awaitIdle(5_000));

        assertEquals(MergeResult.State.COMPLETED, overflowOutcome.get(0).state(), "被拒的件最终仍落库——不丢件");
        assertTrue(flush.hasSeen(overflow), "同一件只经降级落一次");
        assertEquals(10, flush.seenCount(), "1 件先落库 + 1 件就地落库 + 8 件补落库");
        assertEquals(0, buffer.pending());
    }

    @Test
    @DisplayName("A5 背压不伤件：收下的 8 件与就地落库的 20 件各落一次，谁都不丢")
    void acceptedItemsSurviveRejection() throws Exception {
        RecordingFlush flush = new RecordingFlush();
        flush.entered = new CountDownLatch(1);
        flush.hold = new CountDownLatch(1);
        MergeStrategy<Job, Boolean> strategy = strategyOf(flush);
        MergeEngine<Job, Boolean> buffer = start(new MergeConfig(8, 5, 8, 500, 8, 2_000), strategy);

        List<MergeResult<Boolean>> results = Collections.synchronizedList(new ArrayList<>());
        submitInBackground(job(0), results);
        assertTrue(flush.entered.await(5, TimeUnit.SECONDS));

        List<Job> accepted = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            Job item = job(i);
            submitInBackground(item, results);
            accepted.add(item);
        }
        assertTrue(awaitBuffered(8));

        // 缓冲满了之后的 20 件：一件都进不了缓冲，各自在发起线程上就地落库（同样被闸门挡住）
        List<Job> overflow = new ArrayList<>();
        List<Thread> overflowThreads = new ArrayList<>();
        List<MergeResult<Boolean>> overflowOutcomes = Collections.synchronizedList(new ArrayList<>());
        for (int i = 100; i < 120; i++) {
            Job item = job(i);
            overflow.add(item);
            overflowThreads.add(Thread.ofVirtual().start(() -> overflowOutcomes.add(buffer.commit(item))));
        }
        assertTrue(awaitFlushCalls(flush, 21, 5_000), "20 件都该立刻就地落库，一件都不许挤进缓冲");
        assertEquals(8, buffer.pending(), "拒收不许扩容，也不许挤掉已入队的件");

        flush.hold.countDown();
        for (Thread thread : overflowThreads) {
            thread.join(5_000);
        }
        assertTrue(buffer.awaitIdle(5_000));
        assertEquals(20, countTrue(overflowOutcomes), "就地落库的 20 件都该拿到成功结果，不是超时/失败");

        for (Job item : accepted) {
            assertTrue(flush.hasSeen(item), "已入队的件必须落库");
        }
        for (Job item : overflow) {
            assertTrue(flush.hasSeen(item), "被拒的件由调用线程就地落库，不能无声消失");
        }
        assertEquals(1 + 8 + 20, flush.seenCount(), "先落库 1 件 + 收下 8 件 + 就地落库 20 件");
        assertEquals(0, flush.duplicateCount(), "一件只能落一次");
        assertEquals(0, buffer.pending(), "排空后无积压");
    }

    // ── 附加断言：单批上限 + 同分片保序 ────────────────────────────────────────

    @Test
    @DisplayName("A6 单批不超过 maxBatch")
    void batchNeverExceedsMaxBatch() throws Exception {
        int maxBatch = 15;
        int threads = 40;
        RecordingFlush flush = new RecordingFlush();
        MergeStrategy<Job, Boolean> strategy = strategyOf(flush);
        MergeEngine<Job, Boolean> buffer = start(new MergeConfig(8, 1, maxBatch, 5_000, 2_000, 2_000), strategy);

        List<MergeResult<Boolean>> results = Collections.synchronizedList(new ArrayList<>());
        runConcurrently(threads, threadNo -> results.add(buffer.commit(job(threadNo))));

        assertTrue(buffer.awaitIdle(5_000));
        assertTrue(buffer.awaitIdle(0), "排空后必须立刻判定为空闲");
        assertEquals(threads, flush.seenCount());
        assertTrue(flush.maxGroupSize() <= maxBatch, "单组上限 = maxBatch，实测 " + flush.maxGroupSize());
    }

    @Test
    @DisplayName("A6 同分片内保持入队顺序（跨分片不保证）")
    void sameShardKeepsSubmissionOrder() throws Exception {
        int total = 20;
        RecordingFlush flush = new RecordingFlush();
        MergeStrategy<Job, Boolean> strategy = strategyOf(flush);
        // 窗口给足，让 20 件在同一个窗口里排队
        MergeEngine<Job, Boolean> buffer = start(new MergeConfig(8, 1_000, 200, 5_000, 2_000, 6_000), strategy);

        List<MergeResult<Boolean>> results = Collections.synchronizedList(new ArrayList<>());
        for (int i = 1; i <= total; i++) {
            // 一个线程只能挂一件（都在等结果），所以用「上一件确认已入队」把顺序钉死
            submitInBackground(job(i), results);
            if (!awaitBuffered(i)) {
                fail("第 " + i + " 件没能入队");
            }
        }
        assertTrue(buffer.awaitIdle(5_000));

        List<Object> landed = flush.payloadsOf(KEY);
        assertEquals(total, landed.size(), "件数不能少");
        for (int i = 0; i < total; i++) {
            assertEquals(i + 1, ((Job) landed.get(i)).seq(), "同分片必须按入队序落库，第 " + i + " 件错位");
        }
    }

    // ── 隔离：不同用途 ⇒ 不同引擎 ───────────────────────────────────────────────

    @Test
    @DisplayName("两个用途各起一个引擎：同分片、同键、同窗口也各落各的组（不共享缓冲，也不共享事务）")
    void differentPurposesNeverShareAGroup() throws Exception {
        RecordingFlush deductFlush = new RecordingFlush();
        RecordingFlush claimFlush = new RecordingFlush();
        MergeConfig config = new MergeConfig(8, 200, 200, 5_000, 2_000, 2_000);
        MergeEngine<Job, Boolean> deductEngine = new MergeEngine<>(config,
                new MergeStrategy<>("deduct", Job::shardKey, deductFlush));
        MergeEngine<Job, Boolean> claimEngine = new MergeEngine<>(config,
                new MergeStrategy<>("claim", Job::shardKey, claimFlush));
        try {
            List<MergeResult<Boolean>> deductResults = Collections.synchronizedList(new ArrayList<>());
            List<MergeResult<Boolean>> claimResults = Collections.synchronizedList(new ArrayList<>());
            Thread deductThread = Thread.ofVirtual()
                    .start(() -> deductResults.add(deductEngine.commit(job(1))));
            Thread claimThread = Thread.ofVirtual()
                    .start(() -> claimResults.add(claimEngine.commit(job(2))));

            deductThread.join(10_000);
            claimThread.join(10_000);
            assertTrue(deductEngine.awaitIdle(5_000));
            assertTrue(claimEngine.awaitIdle(5_000));

            assertEquals(1, deductFlush.callCount(), "预扣一组");
            assertEquals(1, claimFlush.callCount(), "确认一组：两条不同表的 SQL 各有自己的事务，不是一条");
            assertEquals(1, deductFlush.seenCount());
            assertEquals(1, claimFlush.seenCount());
            assertEquals(1, countTrue(deductResults), "各拿各的结果，不串");
            assertEquals(1, countTrue(claimResults));
        } finally {
            deductEngine.close();
            claimEngine.close();
        }
    }

    @Test
    @DisplayName("业务上的「没成功」是结果不是丢失：原样回给调用方（它据此走业务分支）")
    void negativeBusinessResultIsNotALoss() throws Exception {
        RecordingFlush flush = new RecordingFlush();
        flush.negativeOnFlush = true;
        MergeStrategy<Job, Boolean> strategy = strategyOf(flush);
        MergeEngine<Job, Boolean> buffer = start(new MergeConfig(8, 200, 10, 5_000, 5_000, 2_000), strategy);

        List<MergeResult<Boolean>> results = submitBurst(buffer, 3);

        assertEquals(3, results.stream().filter(o -> o.isCompleted() && Boolean.FALSE.equals(o.result())).count(),
                "业务失败必须原样回给调用方，不能变成 FAILED");
    }

    @Test
    @DisplayName("等超时回 TIMEOUT（不是 FAILED）：件仍在途，之后大概率照样落库")
    void timeoutIsReportedAsUnknownResultNotFailure() throws Exception {
        RecordingFlush flush = new RecordingFlush();
        flush.entered = new CountDownLatch(1);
        flush.hold = new CountDownLatch(1);
        MergeStrategy<Job, Boolean> strategy = strategyOf(flush);
        // 等待预算 50ms 远小于闸门被按住的时间：一定先超时
        MergeEngine<Job, Boolean> buffer = start(new MergeConfig(8, 1, 10, 50, 2_000, 2_000), strategy);

        List<MergeResult<Boolean>> results = Collections.synchronizedList(new ArrayList<>());
        Thread caller = submitInBackground(job(1), results);
        assertTrue(flush.entered.await(5, TimeUnit.SECONDS), "第一次 flush 应该已经进入落库");
        caller.join(5_000);

        assertEquals(List.of(MergeResult.State.TIMEOUT), results.stream().map(MergeResult::state).toList(),
                "超时必须是「结果未知」那一态，不能当失败处理");

        flush.hold.countDown();
        assertTrue(buffer.awaitIdle(5_000));
        assertEquals(1, flush.seenCount(), "超时的件后来确实落库了 —— 这正是 TIMEOUT 存在的理由");
    }
}
