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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A1–A6：引擎的五条不变量 + 附加断言（批上限、同 key 保序、跨 key 并发、动作不混组）。
 *
 * <p>用例名与 {@code acceptance.md §2 A 组}逐字一致——两份实现照同一份名单写，漂移先在自己工程里红。
 *
 * <p>全部是<b>纯内存</b>测试：落库是 {@link RecordingFlushHandler}，不起 Spring、不连库。
 * per-invId leader 模型下每一条 commit 都等终态（浏览也是），所以「只入队不等结果」的骨架
 * 改用虚拟线程后台提交。
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
     * 「只入队、不等结果」的测试骨架：commit 会等终态，只能在别的线程上等（虚拟线程足够便宜）。
     *
     * @return 那条线程；结果会进 {@code sink}
     */
    private Thread submitInBackground(String shardKey, CountEvent event, List<MergeResult<LikeState>> sink) {
        return Thread.ofVirtual().start(() -> sink.add(merger.commit(shardKey, event)));
    }

    /** 等到队列里至少攒到 {@code expected} 件：这就是「入队成功」的判据（提交线程在等终态） */
    private boolean awaitBuffered(int expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (merger.pending() < expected) {
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
        assertTrue(engine.awaitIdle(0), "排空后必须立刻判定为空闲");
        // per-invId 模型的推论：全部排空后没有活跃热点组（长尾 invId 不留常驻内存）
        assertEquals(0, engine.metrics().activeGroups(), "排空后不应有活跃热点组");

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
     * 让 {@code count} 个等待者挤进同一个窗口（同一 key）：窗口给到 200ms，
     * 线程在同一道闸门后起跑，落库前一定都已在队列里——这样「一组 N 件」是确定性的，
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
    @DisplayName("A2 落库变慢时，同 key 任一时刻至多一条 flush 在执行，且件数一件不少")
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

        assertEquals(1, handler.maxInFlightOf(POST_LIKE, POST_ID),
                "同 key 两条 flush 同时跑 = 行锁重新排队，合并收益归零");
        assertEquals(threads * perThread, outcomes.stream().filter(MergeResult::isCompleted).count());
        assertEquals(threads * perThread, handler.seenCount(), "单飞不等于丢件");
        assertTrue(engine.awaitIdle(5_000));
    }

    // ── 跨 key 并发 + 动作不混组（per-invId 模型的钉子）─────────────────────────

    @Test
    @DisplayName("不同帖子（key）可并发 flush：一个热帖的落库卡住，另一个帖子照常发车")
    void differentKeysFlushConcurrently() throws Exception {
        RecordingFlushHandler handler = new RecordingFlushHandler();
        handler.entered = new CountDownLatch(1);
        handler.hold = new CountDownLatch(1);
        CountMerger engine = start(hotConfig(10), handler);

        List<MergeResult<LikeState>> blockedKey = Collections.synchronizedList(new ArrayList<>());
        Thread blockedThread = submitInBackground("post-A", like("1"), blockedKey);
        assertTrue(handler.entered.await(5, TimeUnit.SECONDS), "post-A 的第一次 flush 应该已经进入落库");

        long startNanos = System.nanoTime();
        List<MergeResult<LikeState>> otherKey = Collections.synchronizedList(new ArrayList<>());
        Thread otherThread = submitInBackground("post-B", like("1"), otherKey);
        otherThread.join(5_000);
        long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertEquals(1, otherKey.size());
        assertEquals(MergeState.COMPLETED, otherKey.get(0).state(),
                "post-A 卡死期间 post-B 必须独立完成：并行度随热点行数自伸缩，不被别的热点拖住");
        assertTrue(costMs < 1_000, "post-B 独立发车（窗口 1ms），实测 " + costMs + "ms");
        assertEquals(2, handler.maxDistinctKeysInFlight(), "两个不同 key 的 flush 并发在途");

        handler.hold.countDown();
        blockedThread.join(5_000);
        assertTrue(engine.awaitIdle(5_000));
        assertEquals(1, handler.maxInFlightOf(POST_LIKE, "post-A"), "同 key 单飞不受跨 key 并发影响");
        assertEquals(1, handler.maxInFlightOf(POST_LIKE, "post-B"), "同 key 单飞不受跨 key 并发影响");
    }

    @Test
    @DisplayName("动作不混组：同一帖子的点赞与浏览是两个组，各落各的事务，浏览也等终态")
    void actionsNeverMixIntoOneGroup() throws Exception {
        RecordingFlushHandler handler = new RecordingFlushHandler();
        CountMerger engine = start(hotConfig(200), handler);

        List<MergeResult<LikeState>> likeOutcome = Collections.synchronizedList(new ArrayList<>());
        List<MergeResult<LikeState>> viewOutcome = Collections.synchronizedList(new ArrayList<>());
        Thread likeThread = submitInBackground(POST_ID, like("1"), likeOutcome);
        Thread viewThread = submitInBackground(POST_ID, view(POST_ID, "1"), viewOutcome);
        likeThread.join(10_000);
        viewThread.join(10_000);
        assertTrue(engine.awaitIdle(5_000));

        assertEquals(1, likeOutcome.size());
        assertEquals(MergeState.COMPLETED, likeOutcome.get(0).state());
        assertEquals(LikeState.LIKED, likeOutcome.get(0).result());
        assertEquals(1, viewOutcome.size());
        assertEquals(MergeState.COMPLETED, viewOutcome.get(0).state(), "浏览同样等终态（统一等待语义）");
        assertEquals(LikeState.APPLIED, viewOutcome.get(0).result(), "浏览件的占位结果原样回给调用方");

        assertEquals(2, handler.callCount(), "两个动作两组，各自一个事务");
        assertEquals(2, handler.callsSnapshot().stream().map(RecordingFlushHandler.Group::action).distinct().count(),
                "两组动作不同：组身份 = 动作 × 行主键");
    }

    // ── I3：锁不跨 DB ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("A3 落库阻塞期间提交仍毫秒级入队：锁只保护内存，不跨 DB 调用")
    void flushBlockingDoesNotBlockSubmit() throws Exception {
        RecordingFlushHandler handler = new RecordingFlushHandler();
        handler.entered = new CountDownLatch(1);
        handler.hold = new CountDownLatch(1);
        CountMerger engine = start(hotConfig(10), handler);

        List<MergeResult<LikeState>> first = Collections.synchronizedList(new ArrayList<>());
        submitInBackground(POST_ID, like("0"), first);
        assertTrue(handler.entered.await(5, TimeUnit.SECONDS), "第一次 flush 应该已经进入落库");

        // 落库线程此刻被闸门挡住，同一组的锁必须已经放开
        long startNanos = System.nanoTime();
        List<MergeResult<LikeState>> second = Collections.synchronizedList(new ArrayList<>());
        Thread blockedSubmit = submitInBackground(POST_ID, like("1"), second);
        boolean buffered = awaitBuffered(1, 2_000);
        long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertTrue(buffered, "落库阻塞期间提交必须仍能入队（锁跨 DB 就会卡在入队上）");
        assertTrue(costMs < 100, "入队必须毫秒级，实测 " + costMs + "ms");

        handler.hold.countDown();
        blockedSubmit.join(5_000);
        assertTrue(engine.awaitIdle(5_000));
        assertEquals(2, handler.seenCount());
        assertEquals(1, handler.maxInFlightOf(POST_LIKE, POST_ID), "期间只有那一条 flush 在跑");
    }

    // ── I4：停机三步（停收 → 排空 → 残余回填 FAILED）──────────────────────────

    @Test
    @DisplayName("A4 停机先排空：close() 时队列里的件照样落库，一件不丢")
    void closeStillDrainsBufferedItems() throws Exception {
        RecordingFlushHandler handler = new RecordingFlushHandler();
        CountMerger engine = start(new MergeConfig(8, 20, 200, 500, 2_000, 3_000), handler);

        int total = 50;
        List<MergeResult<LikeState>> outcomes = Collections.synchronizedList(new ArrayList<>());
        List<Thread> submitters = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            submitters.add(submitInBackground(POST_ID, view(POST_ID, String.valueOf(i)), outcomes));
        }
        assertTrue(awaitBuffered(total), "50 件都该在队列里等窗口");

        engine.close();
        for (Thread submitter : submitters) {
            submitter.join(5_000);
        }

        assertEquals(total, handler.seenCount(), "排空阶段必须把队列里的件全部落库");
        assertEquals(total, engine.metrics().flushed());
        assertEquals(0, engine.metrics().drainedFailed(), "正常排空不该有回填");
        assertEquals(0, engine.pending());
        assertTrue(engine.awaitIdle(0), "close() 之后必须已排空（没有在途 flush）");
        assertEquals(total, outcomes.stream().filter(MergeResult::isCompleted).count(),
                "等结果的调用方必须在停机前拿到成功，而不是 TIMEOUT");
    }

    @Test
    @DisplayName("A4 停收：close() 之后提交不进队列，改由调用线程一组一件就地落库")
    void closeRejectsNewSubmissions() {
        RecordingFlushHandler handler = new RecordingFlushHandler();
        CountMerger engine = start(hotConfig(200), handler);
        engine.close();

        MergeResult<LikeState> waited = engine.commit(POST_ID, like("1"));
        MergeResult<LikeState> viewOutcome = engine.commit(POST_ID, view(POST_ID, "2"));

        assertEquals(MergeState.COMPLETED, waited.state(), "停机后 commit 照样有结论：本线程就地落一组");
        assertEquals(LikeState.LIKED, waited.result());
        assertEquals(MergeState.COMPLETED, viewOutcome.state(), "浏览件同样就地降级落库（统一等待语义）");
        assertEquals(2, handler.seenCount(), "两笔都由调用线程就地落库");
        assertEquals(2, engine.metrics().rejectedClosed());
        assertEquals(0, engine.metrics().accepted(), "两笔都没进队列，accepted 保持 0");
    }

    @Test
    @DisplayName("A4 排空超时：没人认领的残余件显式回填 FAILED 并计入 drainedFailed")
    void drainFailureIsCounted() throws Exception {
        RecordingFlushHandler handler = new RecordingFlushHandler();
        handler.entered = new CountDownLatch(1);
        handler.hold = new CountDownLatch(1);
        // 窗口 1ms：第一件立刻发车并卡在落库；排空预算 20ms 远小于闸门被按住的时间
        CountMerger engine = start(new MergeConfig(8, 1, 200, 5_000, 2_000, 20), handler);

        List<MergeResult<LikeState>> leaderOutcome = Collections.synchronizedList(new ArrayList<>());
        List<MergeResult<LikeState>> followerOutcome = Collections.synchronizedList(new ArrayList<>());
        Thread leader = submitInBackground(POST_ID, like("0"), leaderOutcome);
        assertTrue(handler.entered.await(5, TimeUnit.SECONDS), "第一件应该已经进入落库");
        Thread follower = submitInBackground(POST_ID, like("1"), followerOutcome);
        assertTrue(awaitBuffered(1), "第二件该在队列里等落库权");

        engine.close();
        follower.join(5_000);

        assertEquals(1, followerOutcome.size());
        assertEquals(MergeState.FAILED, followerOutcome.get(0).state(),
                "排空超时的残余件 = 确定没落（从未进入事务），可安全重试");
        assertEquals(1, engine.metrics().drainedFailed(), "排空超时的件必须显式回填并计数");
        assertEquals(0, engine.metrics().flushed());
        assertEquals(0, engine.pending());

        handler.hold.countDown();
        leader.join(5_000);
        assertEquals(MergeState.COMPLETED, leaderOutcome.get(0).state(),
                "在途 flush 由它自己的线程收尾，不受停机回填影响");
        // 停机之后不该再有 flush 跑出来
        Thread.sleep(150);
        assertEquals(1, handler.seenCount());
    }

    // ── I5：背压有界 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("A5 满即拒：不入队、不扩容、不动已入队件，被拒的件由调用线程就地落库")
    void rejectsWhenFullWithoutTouchingQueue() throws Exception {
        RecordingFlushHandler handler = new RecordingFlushHandler();
        handler.entered = new CountDownLatch(1);
        handler.hold = new CountDownLatch(1);
        CountMerger engine = start(new MergeConfig(8, 5, 8, 500, 8, 2_000), handler);

        // 先让一条 flush 卡在落库上，后面的件就都只能待在队列里
        List<MergeResult<LikeState>> results = Collections.synchronizedList(new ArrayList<>());
        submitInBackground(POST_ID, view(POST_ID, "0"), results);
        assertTrue(handler.entered.await(5, TimeUnit.SECONDS));

        for (int i = 1; i <= 8; i++) {
            submitInBackground(POST_ID, view(POST_ID, String.valueOf(i)), results);
        }
        assertTrue(awaitBuffered(8), "容量 8 之内必须收下");

        // 第 9 件：队列已满 ⇒ 不进队列，就地同步落库（组大小 1），同样被闸门挡住
        List<MergeResult<LikeState>> overflowOutcome = Collections.synchronizedList(new ArrayList<>());
        Thread overflowThread = Thread.ofVirtual().start(
                () -> overflowOutcome.add(engine.commit(POST_ID, view(POST_ID, "99"))));

        assertTrue(awaitOverflowDegraded(handler, 2, 5_000),
                "满了必须立刻就地落库，不是等窗口、也不是等超时");
        assertEquals(8, engine.pending(), "拒收不许挤掉已入队的件，也不许扩容");
        assertTrue(overflowOutcome.isEmpty(), "件正卡在落库上，此时不该有终态");

        handler.hold.countDown();
        overflowThread.join(5_000);
        assertTrue(engine.awaitIdle(5_000));

        assertEquals(MergeState.COMPLETED, overflowOutcome.get(0).state(), "被拒的件最终仍落库——不丢件");
        assertEquals(10, handler.seenCount(), "1 件先落库 + 1 件就地落库 + 8 件补落库");
        assertEquals(1, engine.metrics().rejectedFull());
        assertEquals(0, engine.pending());
    }

    /** 等到降级那次落库发生（handler 记到的第 {@code expected} 次调用） */
    private static boolean awaitOverflowDegraded(RecordingFlushHandler handler, int expected, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (handler.callCount() < expected) {
            if (System.currentTimeMillis() > deadline) {
                return false;
            }
            Thread.sleep(1);
        }
        return true;
    }

    @Test
    @DisplayName("A5 背压不伤已入队件：连续拒收之后，先被收下的件仍然全部落库且只落一次")
    void acceptedItemsSurviveRejection() throws Exception {
        RecordingFlushHandler handler = new RecordingFlushHandler();
        handler.entered = new CountDownLatch(1);
        handler.hold = new CountDownLatch(1);
        CountMerger engine = start(new MergeConfig(8, 5, 8, 500, 8, 2_000), handler);

        List<MergeResult<LikeState>> results = Collections.synchronizedList(new ArrayList<>());
        submitInBackground(POST_ID, view(POST_ID, "0"), results);
        assertTrue(handler.entered.await(5, TimeUnit.SECONDS));

        List<CountEvent> accepted = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            CountEvent event = view(POST_ID, String.valueOf(i));
            submitInBackground(POST_ID, event, results);
            accepted.add(event);
        }
        assertTrue(awaitBuffered(8));

        // 队列满了之后的 20 件：一件都进不了队列，各自在发起线程上就地落库（同样被闸门挡住）
        List<CountEvent> rejected = new ArrayList<>();
        List<Thread> overflowThreads = new ArrayList<>();
        List<MergeResult<LikeState>> overflowOutcomes = Collections.synchronizedList(new ArrayList<>());
        for (int i = 100; i < 120; i++) {
            CountEvent event = view(POST_ID, String.valueOf(i));
            rejected.add(event);
            overflowThreads.add(Thread.ofVirtual().start(
                    () -> overflowOutcomes.add(engine.commit(POST_ID, event))));
        }
        assertTrue(awaitOverflowDegraded(handler, 21, 5_000), "20 件都该立刻就地落库，一件都不许挤进队列");
        assertEquals(8, engine.pending(), "拒收不许扩容，也不许挤掉已入队的件");

        handler.hold.countDown();
        for (Thread thread : overflowThreads) {
            thread.join(5_000);
        }
        assertTrue(engine.awaitIdle(5_000));

        for (CountEvent event : accepted) {
            assertTrue(handler.hasSeen(event), "已入队的件必须落库");
        }
        for (CountEvent event : rejected) {
            assertTrue(handler.hasSeen(event), "被拒的件由调用线程就地落库，不能无声消失");
        }
        assertEquals(20, engine.metrics().rejectedFull());
        assertEquals(9, engine.metrics().accepted());
        assertEquals(29, handler.seenCount(), "先落库 1 件 + 收下 8 件 + 就地落库 20 件");
        assertEquals(0, engine.pending(), "排空后无积压");
    }

    // ── 附加断言：单批上限 + 同 key 保序 ────────────────────────────────────────

    @Test
    @DisplayName("A6 单批不超过 maxBatch")
    void batchNeverExceedsMaxBatch() throws Exception {
        int maxBatch = 15;
        int threads = 4;
        int perThread = 50;
        RecordingFlushHandler handler = new RecordingFlushHandler();
        CountMerger engine = start(new MergeConfig(8, 1, maxBatch, 5_000, 2_000, 2_000), handler);

        runConcurrently(threads, threadNo -> {
            String rowKey = "post-" + (2_000 + threadNo);
            for (int i = 0; i < perThread; i++) {
                engine.commit(rowKey, view(rowKey, String.valueOf(i)));
            }
        });

        assertTrue(engine.awaitIdle(5_000));
        assertTrue(engine.awaitIdle(0), "排空后必须立刻判定为空闲");
        assertEquals(threads * perThread, handler.seenCount());
        assertTrue(handler.maxGroupSize() <= maxBatch,
                "单组上限 = maxBatch，实测 " + handler.maxGroupSize());
    }

    @Test
    @DisplayName("A6 同 key 内保持入队顺序（台账守卫式 UPDATE 的语义可推理）")
    void sameKeyKeepsSubmissionOrder() throws Exception {
        int total = 100;
        RecordingFlushHandler handler = new RecordingFlushHandler();
        CountMerger engine = start(hotConfig(7), handler);

        AtomicLong sequence = new AtomicLong();
        List<MergeResult<LikeState>> outcomes = Collections.synchronizedList(new ArrayList<>());
        List<Thread> submitters = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            // 一个线程只能挂一件（都在等结果），所以用「上一件确认已入队」把顺序钉死
            submitters.add(submitInBackground(POST_ID,
                    view(POST_ID, String.valueOf(sequence.incrementAndGet())), outcomes));
            if (!awaitBuffered(i + 1)) {
                fail("第 " + (i + 1) + " 件没能入队");
            }
        }
        assertTrue(engine.awaitIdle(5_000));
        for (Thread submitter : submitters) {
            submitter.join(5_000);
        }

        List<CountEvent> landed = handler.eventsOf(POST_ID);
        assertEquals(total, landed.size(), "件数不能少");
        for (int i = 0; i < total; i++) {
            assertEquals(String.valueOf(i + 1), landed.get(i).userId(), "同 key 必须按入队序落库，第 " + i + " 件错位");
        }
    }

    // ── 读数 ─────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("读数快照：合并比 flushed ÷ batches 可算，activeGroups / handovers 有值")
    void metricsExposeMergeRatioAndLeaderObservables() throws Exception {
        RecordingFlushHandler handler = new RecordingFlushHandler();
        CountMerger engine = start(new MergeConfig(8, 1, 50, 5_000, 2_000, 2_000), handler);

        int total = 120;
        List<MergeResult<LikeState>> outcomes = Collections.synchronizedList(new ArrayList<>());
        List<Thread> submitters = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            submitters.add(submitInBackground(POST_ID, view(POST_ID, String.valueOf(i)), outcomes));
            awaitBuffered(i + 1);
        }
        assertTrue(engine.awaitIdle(5_000));
        for (Thread submitter : submitters) {
            submitter.join(5_000);
        }

        MergeMetrics metrics = engine.metrics();
        assertEquals(total, metrics.accepted());
        assertEquals(total, metrics.flushed());
        assertEquals(total, handler.seenCount());
        assertEquals(handler.callCount(), metrics.batches(), "batches = 落库方法被调用的组数");
        assertEquals(handler.seenCount() / (double) handler.callCount(),
                metrics.flushed() / (double) metrics.batches(), 1e-9,
                "合并比 flushed ÷ batches 必须等于实际「每组的件数」，否则调参依据是假的");
        assertTrue(metrics.maxFlushNanos() > 0, "maxFlushNanos 必须被记录（对照 waitTimeoutMs 用）");
        assertEquals(0, metrics.pending());
        assertEquals(0, metrics.activeGroups(), "排空后不应有活跃热点组");
        assertTrue(metrics.handovers() > 0,
                "多批发车必然产生接班（前任 leader 发过车后由等待者接管收集），接班次数应被记录");
    }
}
