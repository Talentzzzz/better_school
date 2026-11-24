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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 引擎的五条不变量 + 附加断言（批上限、同 key 保序、跨 key 并发、队列非空即发）。
 *
 * <p><b>没有收集窗口</b>：发车条件只有「队列非空」一条（单批上界 {@code maxBatch} 不是发车条件）。
 * 所以本类用意是「单件立即落库」「队列有货就接着发下一批」；需要「件留在队列里」的用例一律用
 * <b>闸门后落库</b>（{@link RecordingFlush#hold}）制造确定的在途/排队，不靠调度运气。
 *
 * <p>全部是纯内存测试：件是 {@link Job}（业务键 + 业务号），落库是 {@link RecordingFlush}，
 * 不起 Spring、不连库。这也说明通用组件的可测性——引擎不知道任何业务类型，
 * 策略在起引擎时绑一次，测它就只需要一个 record 加一个 lambda。
 */
class MergeEngineTest {

    private static final String KEY = "9_001";

    /** 测试件：业务键（= invId）+ 业务号（业务号只用于保序断言） */
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

    /** 单热点、够大的缓冲：A1/A2/A6 用（第 2 个参数是退役的窗口键，不参与发车判定） */
    private static MergeConfig hotConfig(int maxBatch) {
        return new MergeConfig(8, 0, maxBatch, 5_000, 2_000);
    }

    /** 拿到业务结果且为 true 的件数 */
    private static long countTrue(List<MergeResult<Boolean>> outcomes) {
        return outcomes.stream().filter(o -> o.isCompleted() && Boolean.TRUE.equals(o.result())).count();
    }

    /** 落在某个终态的件数（{@code COMPLETED} / {@code FAILED}） */
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

    /** 等到队列里至少攒到 {@code expected} 件：这就是「入队成功」的判据（提交线程会等终态） */
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
        // I2 的推论（leader 模型）：提交线程全部返回 ⟹ 每件都已回填 ⟹ 所在批已摘走，
        // 且不再有新提交 ⟹ size 镜像必为 0，队列必空
        assertTrue(buffer.awaitIdle(0), "排空后必须立刻判定为空闲（没有在途 flush）");

        assertEquals(threads * perThread, flush.seenCount(), "入队的件数 == 落库函数看到的件数");
        assertEquals(0, flush.duplicateCount(), "每件至多回填一次（MergeItem 幂等）");
    }

    @Test
    @DisplayName("I1 补齐：落库函数漏回填时引擎补 FAILED，等待者不会悬着")
    void missingBackfillIsCompletedAsFailed() throws Exception {
        RecordingFlush flush = new RecordingFlush();
        flush.fillOnlyTotal = 1;
        MergeStrategy<Job, Boolean> strategy = strategyOf(flush);
        MergeEngine<Job, Boolean> buffer = start(new MergeConfig(8, 200, 10, 5_000, 2_000), strategy);

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
        MergeEngine<Job, Boolean> buffer = start(new MergeConfig(8, 200, 10, 5_000, 2_000), strategy);

        List<MergeResult<Boolean>> results = submitBurst(buffer, 3);

        assertEquals(3, countState(results, MergeResult.State.FAILED),
                "事务回滚 ⇒ 组内每件都是 FAILED（没有「结果未知」这一态）");
    }

    /**
     * 让 {@code count} 个等待者在同一道闸门后同时提交（同一 key）。
     *
     * <p>没有收集窗口之后「一组 N 件」不再是确定性事实（先到的件立刻发车，后到的进下一批），
     * 所以调用方只能断言「终态 / 件数」，不能断言批次形状。要断言批次形状的用例自己用闸门造。
     */
    private List<MergeResult<Boolean>> submitBurst(MergeEngine<Job, Boolean> buffer, int count) throws Exception {
        List<MergeResult<Boolean>> results = Collections.synchronizedList(new ArrayList<>(count));
        runConcurrently(count, threadNo -> results.add(buffer.commit(job(threadNo))));
        assertEquals(count, results.size(), "每个等待者都必须拿到终态");
        return results;
    }

    // ── I2：单飞 ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("A2 落库变慢时，同 key 任一时刻至多一条 flush 在执行，且件数一件不少")
    void singleFlightNeverTwoConcurrentFlushes() throws Exception {
        // 线程数必须明显大于 maxBatch：每个调用方线程同时只挂一件（都在等结果），
        // 只有并发调用方够多，落库期间队列才持续进件——那正是「下一批发车撞上上一批落库」的时刻，
        // 也是唯一能把单飞写坏的机会
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

        assertEquals(1, flush.maxInFlightOf(KEY),
                "同 key 两条 flush 同时跑 = 台账行锁重新排队，后一批看到的也不一定是已提交的状态");
        assertEquals(threads * perThread, countTrue(results));
        assertEquals(threads * perThread, flush.seenCount(), "单飞不等于丢件");
        assertTrue(buffer.awaitIdle(5_000));
    }

    // ── 跨 key 并发（per-invId 模型的钉子：撞片串行消除）────────────────────────

    @Test
    @DisplayName("不同 key 可并发 flush：一个 key 的落库卡住，另一个 key 照常发车")
    void differentKeysFlushConcurrently() throws Exception {
        RecordingFlush flush = new RecordingFlush();
        flush.entered = new CountDownLatch(1);
        flush.hold = new CountDownLatch(1);
        // 闸门只拦 key-A：key-B 的落库必须能照常走完，这才叫「不被别的热点拖住」
        flush.gatedKey = "key-A";
        MergeStrategy<Job, Boolean> strategy = strategyOf(flush);
        MergeEngine<Job, Boolean> buffer = start(hotConfig(10), strategy);

        List<MergeResult<Boolean>> blockedKey = Collections.synchronizedList(new ArrayList<>());
        Thread blockedThread = submitInBackground(new Job("key-A", 1), blockedKey);
        assertTrue(flush.entered.await(5, TimeUnit.SECONDS), "key-A 的第一次 flush 应该已经进入落库");

        // key-B 与 key-A 不共享任何组：不等 key-A 的落库权，独立发车
        long startNanos = System.nanoTime();
        List<MergeResult<Boolean>> otherKey = Collections.synchronizedList(new ArrayList<>());
        Thread otherThread = submitInBackground(new Job("key-B", 1), otherKey);
        otherThread.join(5_000);
        long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertEquals(1, otherKey.size());
        assertEquals(MergeResult.State.COMPLETED, otherKey.get(0).state(),
                "key-A 卡死期间 key-B 必须独立完成，不能被别的热点拖住");
        assertTrue(costMs < 1_000, "key-B 独立发车（队列非空即发），实测 " + costMs + "ms");
        assertEquals(2, flush.maxDistinctKeysInFlight(), "两个不同 key 的 flush 并发在途");

        flush.hold.countDown();
        blockedThread.join(5_000);
        assertTrue(buffer.awaitIdle(5_000));
        assertEquals(1, flush.maxInFlightOf("key-A"), "同 key 单飞不受跨 key 并发影响");
        assertEquals(1, flush.maxInFlightOf("key-B"), "同 key 单飞不受跨 key 并发影响");
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

        // 落库线程此刻被闸门挡住，同一组的锁必须已经放开：第二件要能立刻进队列
        long startNanos = System.nanoTime();
        submitInBackground(job(2), results);
        boolean buffered = awaitBuffered(1, 2_000);
        long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertTrue(buffered, "落库阻塞期间提交必须仍能入队（锁跨 DB 就会卡在入队上）");
        assertTrue(costMs < 100, "入队必须毫秒级，实测 " + costMs + "ms");

        flush.hold.countDown();
        assertTrue(buffer.awaitIdle(5_000));
        assertEquals(2, flush.seenCount());
        assertEquals(1, flush.maxInFlightOf(KEY), "期间只有那一条 flush 在跑");
    }

    // ── 发车时机：单件立即落库 / 队列有货就接着发（批上限只管单批）──────────────────

    @Test
    @DisplayName("单件立即落库：首件即发车，返回时间 ≈ 一次 flush（窗口参数 3s 也一分不等）")
    void singleItemFlushesImmediatelyWithoutWindow() throws Exception {
        RecordingFlush flush = new RecordingFlush();
        MergeStrategy<Job, Boolean> strategy = strategyOf(flush);
        // 退役的窗口键给到 3000ms：如果它还参与发车判定，这条断言必红
        MergeEngine<Job, Boolean> buffer = start(new MergeConfig(8, 3_000, 200, 2_000, 2_000),
                strategy);

        List<MergeResult<Boolean>> results = Collections.synchronizedList(new ArrayList<>());
        long startNanos = System.nanoTime();
        Thread submitter = submitInBackground(job(1), results);
        submitter.join(5_000);
        long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertEquals(1, results.size());
        assertEquals(MergeResult.State.COMPLETED, results.get(0).state());
        assertTrue(costMs < 500, "队列非空即发：单件不许等任何窗口，实测 " + costMs + "ms");
        assertEquals(1, flush.callCount(), "单件自成一批，一次落库");
        assertTrue(buffer.awaitIdle(5_000));
        assertEquals(0, buffer.pending());
    }

    @Test
    @DisplayName("队列有货就接着发：在途一批期间进件的，落库一结束立即发下一批，单批不超过 maxBatch")
    void queuedItemsDepartRightAfterPreviousFlush() throws Exception {
        int maxBatch = 5;
        RecordingFlush flush = new RecordingFlush();
        flush.entered = new CountDownLatch(1);
        flush.hold = new CountDownLatch(1);
        MergeStrategy<Job, Boolean> strategy = strategyOf(flush);
        MergeEngine<Job, Boolean> buffer = start(new MergeConfig(8, 1_000, maxBatch, 2_000, 5_000),
                strategy);

        List<MergeResult<Boolean>> results = Collections.synchronizedList(new ArrayList<>());
        List<Thread> submitters = new ArrayList<>(maxBatch + 4);
        long startNanos = System.nanoTime();
        submitters.add(submitInBackground(job(0), results));
        assertTrue(flush.entered.await(5, TimeUnit.SECONDS), "首件应已发车并卡在落库上");

        // 落库在途：后面的件只能待在队列里（8 件 = maxBatch + 3）
        for (int i = 1; i <= maxBatch + 3; i++) {
            submitters.add(submitInBackground(job(i), results));
        }
        assertTrue(awaitBuffered(maxBatch + 3), "在途 flush 期间进件必须能入队");

        flush.hold.countDown();
        assertTrue(buffer.awaitIdle(5_000));
        // 结果由提交线程写进 sink：必须等它们跑完再数，否则数的是「已被唤醒的线程数」而不是终态
        for (Thread submitter : submitters) {
            submitter.join(5_000);
        }
        long costMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertEquals(maxBatch + 4, countTrue(results));
        assertEquals(maxBatch, flush.maxGroupSize(), "单批上界 = maxBatch");
        assertTrue(flush.callCount() >= 2, "队列有货就接着发，不等任何窗口");
        assertTrue(costMs < 1_000, "发车不依赖窗口（退役键给的 1000ms），实测 " + costMs + "ms");
    }

    // ── I4：停机三步（停收 → 排空 → 残余回填 FAILED）──────────────────────────

    @Test
    @DisplayName("A4 停机先排空：close() 时队列里的件照样落库，一件不丢")
    void closeStillDrainsBufferedItems() throws Exception {
        RecordingFlush flush = new RecordingFlush();
        // 闸门造出「落库在途 + 队列有货」：没有窗口之后，件不会被窗口扣在队列里，只能靠慢落库制造排队
        flush.entered = new CountDownLatch(1);
        flush.hold = new CountDownLatch(1);
        MergeStrategy<Job, Boolean> strategy = strategyOf(flush);
        MergeEngine<Job, Boolean> buffer = start(new MergeConfig(8, 0, 200, 2_000, 10_000), strategy);

        int total = 20;
        List<MergeResult<Boolean>> results = Collections.synchronizedList(new ArrayList<>());
        List<Thread> submitters = new ArrayList<>(total);
        submitters.add(submitInBackground(job(0), results));
        assertTrue(flush.entered.await(5, TimeUnit.SECONDS), "首件应已发车并卡在落库上");
        for (int i = 1; i < total; i++) {
            submitters.add(submitInBackground(job(i), results));
        }
        assertTrue(awaitBuffered(total - 1), "在途 flush 期间，其余件都该在队列里");

        Thread closer = Thread.ofVirtual().start(buffer::close);
        // close 已进入「停收 + 等排空」：给它一点时间落到 awaitIdle 上，再放开闸门
        Thread.sleep(100);
        flush.hold.countDown();
        closer.join(10_000);
        // 结果由提交线程写进 sink：必须等它们跑完再数，否则数的是「已被唤醒的线程数」而不是终态
        for (Thread submitter : submitters) {
            submitter.join(10_000);
        }

        assertEquals(total, flush.seenCount(), "排空阶段必须把队列里的件全部落库");
        assertEquals(0, buffer.pending());
        assertTrue(buffer.awaitIdle(0), "close() 之后必须已排空（没有在途 flush）");
        assertEquals(total, countTrue(results), "等结果的调用方必须在停机前拿到确定的结果");
    }

    @Test
    @DisplayName("A4 停收：close() 之后提交不进队列，直接返回 FAILED（件从未入队，重试安全）")
    void closeRejectsNewSubmissions() {
        RecordingFlush flush = new RecordingFlush();
        MergeStrategy<Job, Boolean> strategy = strategyOf(flush);
        MergeEngine<Job, Boolean> buffer = start(hotConfig(200), strategy);
        buffer.close();

        MergeResult<Boolean> outcome = buffer.commit(job(1));

        assertEquals(MergeResult.State.FAILED, outcome.state(), "停机后提交直接失败：件从未入队、必未落库");
        assertEquals(0, flush.callCount(), "既不进队列，也不就地落库");
        assertEquals(0, buffer.pending(), "件从未进过队列");
    }

    @Test
    @DisplayName("A4 排空超时：没人认领的残余件显式回填 FAILED（= 确定没落，可安全重试）")
    void drainFailureBackfillsFailed() throws Exception {
        RecordingFlush flush = new RecordingFlush();
        flush.entered = new CountDownLatch(1);
        flush.hold = new CountDownLatch(1);
        MergeStrategy<Job, Boolean> strategy = strategyOf(flush);
        // 队列非空即发：第一件立刻发车并卡在落库；排空预算 20ms 远小于闸门被按住的时间
        MergeEngine<Job, Boolean> buffer = start(new MergeConfig(8, 1, 200, 2_000, 20), strategy);

        List<MergeResult<Boolean>> leaderOutcome = Collections.synchronizedList(new ArrayList<>());
        List<MergeResult<Boolean>> followerOutcome = Collections.synchronizedList(new ArrayList<>());
        Thread leader = submitInBackground(job(0), leaderOutcome);
        assertTrue(flush.entered.await(5, TimeUnit.SECONDS), "第一件应该已经进入落库");
        Thread follower = submitInBackground(job(1), followerOutcome);
        assertTrue(awaitBuffered(1), "第二件该在队列里等落库权");

        buffer.close();
        follower.join(5_000);

        assertEquals(1, followerOutcome.size());
        assertEquals(MergeResult.State.FAILED, followerOutcome.get(0).state(),
                "排空超时的残余件 = 确定没落（从未进入事务），可安全重试");
        assertEquals(1, flush.seenCount(), "只有 leader 的那件进过落库函数");

        flush.hold.countDown();
        leader.join(5_000);
        assertEquals(MergeResult.State.COMPLETED, leaderOutcome.get(0).state(),
                "在途 flush 由它自己的线程收尾，不受停机回填影响");
        assertEquals(0, buffer.pending());
        // 停机之后不该再有 flush 跑出来
        Thread.sleep(150);
        assertEquals(1, flush.seenCount());
    }

    // ── I5：背压有界 ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("A5 满即拒：不入队、不扩容、不动已入队件，被拒的件直接失败")
    void rejectsWhenFullWithoutTouchingQueue() throws Exception {
        RecordingFlush flush = new RecordingFlush();
        flush.entered = new CountDownLatch(1);
        flush.hold = new CountDownLatch(1);
        MergeStrategy<Job, Boolean> strategy = strategyOf(flush);
        MergeEngine<Job, Boolean> buffer = start(new MergeConfig(8, 5, 8, 8, 2_000), strategy);

        // 先让一条 flush 卡在落库上，后面的件就都只能待在队列里
        List<MergeResult<Boolean>> results = Collections.synchronizedList(new ArrayList<>());
        submitInBackground(job(0), results);
        assertTrue(flush.entered.await(5, TimeUnit.SECONDS));

        for (int i = 1; i <= 8; i++) {
            submitInBackground(job(i), results);
        }
        assertTrue(awaitBuffered(8), "容量 8 之内必须收下");

        // 第 9 件：队列已满 ⇒ 不进队列，也不就地落库，立刻失败（调用方稍后重试）
        MergeResult<Boolean> rejected = buffer.commit(job(99));

        assertEquals(MergeResult.State.FAILED, rejected.state(), "满即拒：直接失败，不抢连接落库");
        assertEquals(8, buffer.pending(), "拒收不许挤掉已入队的件，也不许扩容");
        assertEquals(1, flush.callCount(), "只有卡住的那条 flush，被拒的件没有触发落库");

        flush.hold.countDown();
        assertTrue(buffer.awaitIdle(5_000));

        assertEquals(9, flush.seenCount(), "1 件先落库 + 8 件补落库；被拒的那件不在其中");
        assertEquals(0, buffer.pending());
    }

    @Test
    @DisplayName("A5 背压不伤件：收下的 8 件照常落库，被拒件直接失败且不挤队列")
    void acceptedItemsSurviveRejection() throws Exception {
        RecordingFlush flush = new RecordingFlush();
        flush.entered = new CountDownLatch(1);
        flush.hold = new CountDownLatch(1);
        MergeStrategy<Job, Boolean> strategy = strategyOf(flush);
        MergeEngine<Job, Boolean> buffer = start(new MergeConfig(8, 5, 8, 8, 2_000), strategy);

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

        // 队列满了之后的 20 件：一件都进不了队列，各自立刻失败（不抢连接、不挤队列）
        List<Job> rejected = new ArrayList<>();
        List<MergeResult<Boolean>> rejectedOutcomes = new ArrayList<>();
        for (int i = 100; i < 120; i++) {
            Job item = job(i);
            rejected.add(item);
            rejectedOutcomes.add(buffer.commit(item));
        }
        assertEquals(20, countState(rejectedOutcomes, MergeResult.State.FAILED), "满即拒：20 件全部直接失败");
        assertEquals(8, buffer.pending(), "拒收不许扩容，也不许挤掉已入队的件");
        assertEquals(1, flush.callCount(), "只有卡住的那条 flush");

        flush.hold.countDown();
        assertTrue(buffer.awaitIdle(5_000));

        for (Job item : accepted) {
            assertTrue(flush.hasSeen(item), "已入队的件必须落库");
        }
        for (Job item : rejected) {
            assertFalse(flush.hasSeen(item), "被拒的件从未入队，不许落库");
        }
        assertEquals(1 + 8, flush.seenCount(), "先落库 1 件 + 收下 8 件；被拒的 20 件不在其中");
        assertEquals(0, flush.duplicateCount(), "一件只能落一次");
        assertEquals(0, buffer.pending(), "排空后无积压");
    }

    // ── 附加断言：单批上限 + 同 key 保序 ────────────────────────────────────────

    @Test
    @DisplayName("A6 单批不超过 maxBatch")
    void batchNeverExceedsMaxBatch() throws Exception {
        int maxBatch = 15;
        int threads = 40;
        RecordingFlush flush = new RecordingFlush();
        MergeStrategy<Job, Boolean> strategy = strategyOf(flush);
        MergeEngine<Job, Boolean> buffer = start(new MergeConfig(8, 1, maxBatch, 2_000, 2_000), strategy);

        List<MergeResult<Boolean>> results = Collections.synchronizedList(new ArrayList<>());
        runConcurrently(threads, threadNo -> results.add(buffer.commit(job(threadNo))));

        assertTrue(buffer.awaitIdle(5_000));
        assertTrue(buffer.awaitIdle(0), "排空后必须立刻判定为空闲");
        assertEquals(threads, flush.seenCount());
        assertTrue(flush.maxGroupSize() <= maxBatch, "单组上限 = maxBatch，实测 " + flush.maxGroupSize());
    }

    @Test
    @DisplayName("A6 同 key 内保持入队顺序（台账守卫式 UPDATE 的语义可推理）")
    void sameKeyKeepsSubmissionOrder() throws Exception {
        int total = 20;
        RecordingFlush flush = new RecordingFlush();
        MergeStrategy<Job, Boolean> strategy = strategyOf(flush);
        // 首件发车并被闸门按在落库上：此后进件只能排队，入队顺序即落库顺序
        flush.entered = new CountDownLatch(1);
        flush.hold = new CountDownLatch(1);
        MergeEngine<Job, Boolean> buffer = start(new MergeConfig(8, 0, 200, 2_000, 10_000), strategy);

        List<MergeResult<Boolean>> results = Collections.synchronizedList(new ArrayList<>());
        submitInBackground(job(1), results);
        assertTrue(flush.entered.await(5, TimeUnit.SECONDS), "首件应已发车并卡在落库上");
        for (int i = 2; i <= total; i++) {
            // 一个线程只能挂一件（都在等结果），所以用「上一件确认已入队」把顺序钉死
            submitInBackground(job(i), results);
            if (!awaitBuffered(i - 1)) {
                fail("第 " + i + " 件没能入队");
            }
        }
        flush.hold.countDown();
        assertTrue(buffer.awaitIdle(5_000));

        List<Object> landed = flush.payloadsOf(KEY);
        assertEquals(total, landed.size(), "件数不能少");
        for (int i = 0; i < total; i++) {
            assertEquals(i + 1, ((Job) landed.get(i)).seq(), "同 key 必须按入队序落库，第 " + i + " 件错位");
        }
    }

    // ── 隔离：不同用途 ⇒ 不同引擎 ───────────────────────────────────────────────

    @Test
    @DisplayName("两个用途各起一个引擎：同键、同配置也各落各的组（不共享缓冲，也不共享事务）")
    void differentPurposesNeverShareAGroup() throws Exception {
        RecordingFlush deductFlush = new RecordingFlush();
        RecordingFlush claimFlush = new RecordingFlush();
        MergeConfig config = new MergeConfig(8, 200, 200, 2_000, 2_000);
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
        MergeEngine<Job, Boolean> buffer = start(new MergeConfig(8, 200, 10, 5_000, 2_000), strategy);

        List<MergeResult<Boolean>> results = submitBurst(buffer, 3);

        assertEquals(3, results.stream().filter(o -> o.isCompleted() && Boolean.FALSE.equals(o.result())).count(),
                "业务失败必须原样回给调用方，不能变成 FAILED");
    }

    @Test
    @DisplayName("没有等待上限：follower 一直等到落库权空出来、自己接班落库，拿 COMPLETED")
    void followerWaitsUntilItsOwnItemLands() throws Exception {
        RecordingFlush flush = new RecordingFlush();
        flush.entered = new CountDownLatch(1);
        flush.hold = new CountDownLatch(1);
        MergeStrategy<Job, Boolean> strategy = strategyOf(flush);
        // 单件立刻发车，然后被闸门按在落库上——此时 follower 只能等
        MergeEngine<Job, Boolean> buffer = start(new MergeConfig(8, 1, 10, 2_000, 2_000), strategy);

        List<MergeResult<Boolean>> leaderOutcome = Collections.synchronizedList(new ArrayList<>());
        List<MergeResult<Boolean>> followerOutcome = Collections.synchronizedList(new ArrayList<>());
        Thread leader = submitInBackground(job(0), leaderOutcome);
        assertTrue(flush.entered.await(5, TimeUnit.SECONDS), "第一次 flush 应该已经进入落库");
        Thread follower = submitInBackground(job(1), followerOutcome);
        follower.join(200);

        // 前任卡在落库上：follower 不许带任何终态返回——没有等待上限就没有「结果未知」这一态
        assertTrue(followerOutcome.isEmpty(), "落库被按住期间 follower 必须一直等，不能提前回一个『未知』");
        assertEquals(1, buffer.pending(), "follower 的件在队列里等下一批");
        assertTrue(leaderOutcome.isEmpty(), "leader 还卡在落库上，没有终态");

        flush.hold.countDown();
        leader.join(5_000);
        follower.join(5_000);

        assertEquals(1, leaderOutcome.size());
        assertEquals(MergeResult.State.COMPLETED, leaderOutcome.get(0).state(), "leader 的件照常落库");
        assertEquals(List.of(MergeResult.State.COMPLETED),
                followerOutcome.stream().map(MergeResult::state).toList(),
                "执行权放出后 follower 接班落库，同样拿 COMPLETED");
        assertEquals(2, flush.seenCount(), "两件都落过库");
        assertEquals(0, buffer.pending());
    }
}
