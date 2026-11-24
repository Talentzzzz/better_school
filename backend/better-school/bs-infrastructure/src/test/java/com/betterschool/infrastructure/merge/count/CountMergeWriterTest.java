package com.betterschool.infrastructure.merge.count;

import com.betterschool.domain.community.model.valobj.LikeStateVO;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.betterschool.infrastructure.merge.count.CountAction.POST_LIKE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三条降级 + 超时/失败的对外翻译（spec §2.6）。
 *
 * <p>纯内存：合并器是真的（参数调到能人为制造 {@code BUFFER_FULL} / {@code TIMEOUT}），落库方法是
 * 一个记账用的替身。断言的核心是<b>降级复用了同一个落库方法</b>——替身收到的组必须正好是「组大小 1」，
 * 事件也必须是那一笔，否则就是悄悄换了条 SQL。降级本身已下沉到 {@code CountMerger#commit}，
 * 本类不再持有落库方法，所以这些用例端到端地验「引擎降级 → 本类翻译」这一段。
 *
 * <p>per-invId leader 模型下超时只发生在<b>等待者</b>身上（leader 在落库上，不参与等待预算），
 * 所以超时场景 = 前任 leader 卡在落库 + follower 等待预算耗尽。
 */
class CountMergeWriterTest {

    private static final String POST_ID = "post-7700001";

    private final RecordingHandler handler = new RecordingHandler();
    private CountMerger merger;
    private CountMergeWriter writer;

    @AfterEach
    void tearDown() {
        if (merger != null) {
            merger.close();
            merger = null;
        }
    }

    /** 单组、单件容量、够长的窗口：容量 1 ⇒ 第二笔必然 BUFFER_FULL */
    private CountMergeWriter startFullBufferScenario() {
        merger = new CountMerger(new MergeConfig(1, 1_000, 1, 2_000, 1, 1_000), handler);
        writer = new CountMergeWriter(merger);
        return writer;
    }

    @Test
    @DisplayName("COMPLETED：业务结果原样上抛（LIKED / DUPLICATE 都走这条）")
    void completedPassesBusinessResultThrough() {
        merger = new CountMerger(new MergeConfig(1, 1, 10, 2_000, 100, 1_000), handler);
        writer = new CountMergeWriter(merger);

        handler.nextState = LikeState.DUPLICATE;

        assertEquals(LikeStateVO.DUPLICATE, writer.write(POST_LIKE, POST_ID, "42"));
        assertEquals(1, handler.callCount.get(), "正常路径只该落库一次");
        assertEquals(1, handler.batches.get(0).size());
    }

    @Test
    @DisplayName("缓冲满：调用方线程用同一个落库方法落「组大小 1」，不是另一套 SQL")
    void bufferFullDegradesToSameFlushMethodWithSingleItem() throws Exception {
        startFullBufferScenario();
        // 后台提交把容量 1 的组塞满（leader 在收集窗口里等，件留在队列）；窗口 1s 内不会被 flush 走
        List<MergeResult<LikeState>> filler = new CopyOnWriteArrayList<>();
        Thread fillerThread = Thread.ofVirtual()
                .start(() -> filler.add(merger.commit(POST_ID, new CountEvent(POST_LIKE, POST_ID, "1"))));
        long deadline = System.currentTimeMillis() + 5_000;
        while (merger.pending() < 1) {
            assertTrue(System.currentTimeMillis() < deadline, "第一笔该在队列里等窗口");
            Thread.sleep(1);
        }

        LikeStateVO state = writer.write(POST_LIKE, POST_ID, "2");

        assertEquals(LikeStateVO.LIKED, state, "降级成功 = 对用户就是成功");
        assertEquals(1, handler.callCount.get(), "降级必须真的调落库方法（同一个）");
        List<CountEvent> degraded = handler.batches.get(0);
        assertEquals(1, degraded.size(), "降级组大小恒为 1");
        assertEquals("2", degraded.get(0).userId(), "打的是这一笔请求本人");
        assertEquals(1, merger.metrics().rejectedFull(), "背压由合并器计数，不进用户视野");

        // 让填充线程收尾（close 由 tearDown 触发排空），不影响上面的断言
        fillerThread.join(5_000);
    }

    @Test
    @DisplayName("停机中（CLOSED）：同样降级同步落库，COMPLETED/CLOSED 都不回 REJECTED")
    void closedDegradesToSameFlushMethodWithSingleItem() {
        merger = new CountMerger(new MergeConfig(1, 1, 10, 2_000, 100, 1_000), handler);
        writer = new CountMergeWriter(merger);
        merger.close();

        assertEquals(LikeStateVO.LIKED, writer.write(POST_LIKE, POST_ID, "7"));
        assertEquals(1, handler.callCount.get());
        assertEquals("7", handler.batches.get(0).get(0).userId());
        assertEquals(1, merger.metrics().rejectedClosed());
    }

    @Test
    @DisplayName("等超时：回可重试（SYSTEM_ERROR），不重试、不落第二笔")
    void timeoutIsRetryableWithoutSecondWrite() throws Exception {
        handler.sleepMillis = 500;
        handler.entered = new CountDownLatch(1);
        merger = new CountMerger(new MergeConfig(1, 1, 10, 60, 100, 200), handler);
        writer = new CountMergeWriter(merger);

        // leader：后台提交，进收集窗 1ms 后发车，卡在人为慢的落库上（不参与等待预算）
        List<MergeResult<LikeState>> leaderOutcome = new CopyOnWriteArrayList<>();
        Thread leaderThread = Thread.ofVirtual().start(
                () -> leaderOutcome.add(merger.commit(POST_ID, new CountEvent(POST_LIKE, POST_ID, "0"))));
        assertTrue(handler.entered.await(5, TimeUnit.SECONDS), "leader 应该已经进入落库");

        // follower：等待预算 60ms ≪ 落库 500ms，必然先超时（超时前会尝试接班，但落库权被前任占着）
        BusinessException e = assertThrows(BusinessException.class, () -> writer.write(POST_LIKE, POST_ID, "9"));

        assertEquals(ResponseCode.SYSTEM_ERROR.getCode(), e.getCode(), "对外是「系统繁忙，请稍后重试」");
        assertEquals(1, handler.callCount.get(), "超时只说明结果未知，不能再落一笔");
        assertEquals(1, merger.metrics().timedOut());

        // 件仍在队列：无人接手时由停机排空显式回填 FAILED（可安全重试）
        merger.close();
        assertEquals(1, merger.metrics().drainedFailed());
        leaderThread.join(5_000);
        assertEquals(MergeState.COMPLETED, leaderOutcome.get(0).state(), "leader 的件照常落库");
    }

    @Test
    @DisplayName("落库失败（整组回滚）：调用方拿可重试，不吞不假装成功")
    void flushFailureIsRetryable() {
        handler.failNext = true;
        merger = new CountMerger(new MergeConfig(1, 1, 10, 2_000, 100, 1_000), handler);
        writer = new CountMergeWriter(merger);

        BusinessException e = assertThrows(BusinessException.class, () -> writer.write(POST_LIKE, POST_ID, "11"));

        assertEquals(ResponseCode.SYSTEM_ERROR.getCode(), e.getCode());
        assertEquals(1, handler.callCount.get());
    }

    @Test
    @DisplayName("降级也失败：仍是可重试（行一定没动），不抛出内部异常类型")
    void degradeFailureIsRetryable() {
        merger = new CountMerger(new MergeConfig(1, 1, 10, 2_000, 100, 1_000), handler);
        writer = new CountMergeWriter(merger);
        merger.close();
        handler.failNext = true;

        BusinessException e = assertThrows(BusinessException.class, () -> writer.write(POST_LIKE, POST_ID, "13"));

        assertEquals(ResponseCode.SYSTEM_ERROR.getCode(), e.getCode());
    }

    /** 落库方法替身：记账 + 可编排「慢」「抛异常」「返回什么状态」 */
    private static final class RecordingHandler implements ICountFlushHandler {

        private final AtomicInteger callCount = new AtomicInteger();
        private final List<List<CountEvent>> batches = new CopyOnWriteArrayList<>();
        private volatile LikeState nextState = LikeState.LIKED;
        private volatile boolean failNext;
        private volatile long sleepMillis;
        /** 进入落库时的信号：测试用它确认「flush 已经开始」 */
        volatile CountDownLatch entered;
        /** 落库期间要等的闸门：测试用它制造「落库阻塞」 */
        volatile CountDownLatch hold;

        @Override
        public List<LikeState> flushGroup(CountAction action, String shardKey, List<CountEvent> events) {
            callCount.incrementAndGet();
            batches.add(List.copyOf(events));
            CountDownLatch startSignal = entered;
            if (startSignal != null) {
                startSignal.countDown();
            }
            try {
                CountDownLatch gate = hold;
                if (gate != null && !gate.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("落库替身等闸门超时");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("落库替身被中断", e);
            }
            if (failNext) {
                throw new IllegalStateException("模拟落库失败");
            }
            if (sleepMillis > 0) {
                try {
                    Thread.sleep(sleepMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return events.stream().map(event -> nextState).toList();
        }
    }
}
