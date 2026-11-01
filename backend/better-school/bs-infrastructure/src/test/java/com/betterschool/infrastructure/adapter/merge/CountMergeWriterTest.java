package com.betterschool.infrastructure.adapter.merge;

import com.betterschool.domain.community.model.valobj.LikeStateVO;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static com.betterschool.infrastructure.adapter.merge.CountAction.POST_LIKE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三条降级 + 超时/失败的对外翻译（票 03 验收；spec §2.6）。
 *
 * <p>纯内存：合并器是真的（参数调到能人为制造 {@code BUFFER_FULL} / {@code TIMEOUT}），落库方法是
 * 一个记账用的替身。断言的核心是<b>降级复用了同一个落库方法</b>——替身收到的组必须正好是「组大小 1」，
 * 事件也必须是那一笔，否则就是悄悄换了条 SQL。
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

    /** 单分片、单件一批、够长的窗口：分片容量 1 ⇒ 第二笔必然 BUFFER_FULL */
    private CountMergeWriter startFullBufferScenario() {
        merger = new CountMerger(new MergeConfig(1, 1_000, 1, 2_000, 1, 1, 1_000), handler);
        writer = new CountMergeWriter(merger, handler);
        return writer;
    }

    /** 窗口 1ms、等 60ms：落库方法故意慢 ⇒ 必然 TIMEOUT */
    private CountMergeWriter startTimeoutScenario(long handlerSleepMs) {
        handler.sleepMillis = handlerSleepMs;
        merger = new CountMerger(new MergeConfig(1, 1, 10, 60, 100, 1, 1_000), handler);
        writer = new CountMergeWriter(merger, handler);
        return writer;
    }

    @Test
    @DisplayName("COMPLETED：业务结果原样上抛（LIKED / DUPLICATE 都走这条）")
    void completedPassesBusinessResultThrough() {
        merger = new CountMerger(new MergeConfig(1, 1, 10, 2_000, 100, 1, 1_000), handler);
        writer = new CountMergeWriter(merger, handler);

        handler.nextState = LikeState.DUPLICATE;

        assertEquals(LikeStateVO.DUPLICATE, writer.write(POST_LIKE, POST_ID, "42"));
        assertEquals(1, handler.callCount.get(), "正常路径只该落库一次");
        assertEquals(1, handler.batches.get(0).size());
    }

    @Test
    @DisplayName("缓冲满：调用方线程用同一个落库方法落「组大小 1」，不是另一套 SQL")
    void bufferFullDegradesToSameFlushMethodWithSingleItem() {
        startFullBufferScenario();
        // 先用不等结果的那条入口把分片塞满（容量 1），窗口 1s 内不会被 flush 走
        assertEquals(SubmitOutcome.ACCEPTED, merger.submitAsync(POST_ID, new CountEvent(POST_LIKE, POST_ID, "1")));

        LikeStateVO state = writer.write(POST_LIKE, POST_ID, "2");

        assertEquals(LikeStateVO.LIKED, state, "降级成功 = 对用户就是成功");
        assertEquals(1, handler.callCount.get(), "降级必须真的调落库方法（同一个）");
        List<CountEvent> degraded = handler.batches.get(0);
        assertEquals(1, degraded.size(), "降级组大小恒为 1");
        assertEquals("2", degraded.get(0).userId(), "打的是这一笔请求本人");
        assertEquals(1, merger.metrics().rejectedFull(), "背压由合并器计数，不进用户视野");
    }

    @Test
    @DisplayName("停机中（CLOSED）：同样降级同步落库，COMPLETED/CLOSED 都不回 REJECTED")
    void closedDegradesToSameFlushMethodWithSingleItem() {
        merger = new CountMerger(new MergeConfig(1, 1, 10, 2_000, 100, 1, 1_000), handler);
        writer = new CountMergeWriter(merger, handler);
        merger.close();

        assertEquals(LikeStateVO.LIKED, writer.write(POST_LIKE, POST_ID, "7"));
        assertEquals(1, handler.callCount.get());
        assertEquals("7", handler.batches.get(0).get(0).userId());
        assertEquals(1, merger.metrics().rejectedClosed());
    }

    @Test
    @DisplayName("等超时：回可重试（SYSTEM_ERROR），不重试、不落第二笔")
    void timeoutIsRetryableWithoutSecondWrite() {
        startTimeoutScenario(400);

        BusinessException e = assertThrows(BusinessException.class, () -> writer.write(POST_LIKE, POST_ID, "9"));

        assertEquals(ResponseCode.SYSTEM_ERROR.getCode(), e.getCode(), "对外是「系统繁忙，请稍后重试」");
        assertEquals(1, handler.callCount.get(), "超时只说明结果未知，不能再落一笔");
        // 件仍在途：等它落完再断言，避免 teardown 的 close() 把在途件补 FAILED（那只影响读数）
        assertTrue(merger.awaitIdle(2_000), "在途件应最终落库");
        assertEquals(1, merger.metrics().timedOut());
    }

    @Test
    @DisplayName("落库失败（整组回滚）：调用方拿可重试，不吞不假装成功")
    void flushFailureIsRetryable() {
        handler.failNext = true;
        merger = new CountMerger(new MergeConfig(1, 1, 10, 2_000, 100, 1, 1_000), handler);
        writer = new CountMergeWriter(merger, handler);

        BusinessException e = assertThrows(BusinessException.class, () -> writer.write(POST_LIKE, POST_ID, "11"));

        assertEquals(ResponseCode.SYSTEM_ERROR.getCode(), e.getCode());
        assertEquals(1, handler.callCount.get());
    }

    @Test
    @DisplayName("降级也失败：仍是可重试（行一定没动），不抛出内部异常类型")
    void degradeFailureIsRetryable() {
        merger = new CountMerger(new MergeConfig(1, 1, 10, 2_000, 100, 1, 1_000), handler);
        writer = new CountMergeWriter(merger, handler);
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

        @Override
        public List<LikeState> flushGroup(CountAction action, String shardKey, List<CountEvent> events) {
            callCount.incrementAndGet();
            batches.add(List.copyOf(events));
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
