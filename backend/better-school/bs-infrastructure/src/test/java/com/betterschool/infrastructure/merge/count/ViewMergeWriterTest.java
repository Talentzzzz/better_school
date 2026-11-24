package com.betterschool.infrastructure.merge.count;

import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.betterschool.infrastructure.merge.count.CountAction.ERRAND_VIEW;
import static com.betterschool.infrastructure.merge.count.CountAction.POST_LIKE;
import static com.betterschool.infrastructure.merge.count.CountAction.POST_VIEW;
import static com.betterschool.infrastructure.merge.count.CountAction.PRODUCT_VIEW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 浏览写入的<b>统一等待语义</b>（spec：浏览类从 fire-and-forget 翻转为 commit 等终态）。
 *
 * <p>纯内存测试：合并器是 {@link CountMerger} 的真身（不是 mock），落库是 {@link ProbeHandler}。
 * 语义翻转后的三条断言口径：
 * <ul>
 *   <li><b>等终态</b>：{@code write} 返回时浏览必已落库（正常路径 flush 就跑在首位提交者自己的
 *       线程上——per-invId leader 模型没有独立的合并线程池）；</li>
 *   <li><b>降级在调用方线程上、组大小恒为 1</b>：缓冲满 / 停机中由引擎就地兜一次独立落库；</li>
 *   <li><b>失败可见</b>：降级也失败时抛可重试异常——不再有「允许丢」与 {@code droppedViews}，
 *       重放被明细唯一键吸收。</li>
 * </ul>
 */
class ViewMergeWriterTest {

    private static final String POST_ID = "post-5001";

    private static final String PRODUCT_ID = "f3a1c2d4-0005-4a6b-8c9d-0e1f2a3b4c5d";

    private static final String ERRAND_ID = "b7e8f9a0-0006-4b7c-9d0e-1f2a3b4c5d6e";

    /** 帖子浏览必须带浏览者（UV 明细表主键的一半）；本类只看线程归属与组大小，给谁无所谓 */
    private static final String VIEWER = "user-viewer-42";

    private CountMerger merger;

    @AfterEach
    void tearDown() {
        if (merger != null) {
            merger.close();
            merger = null;
        }
    }

    private ViewMergeWriter start(MergeConfig config, ProbeHandler handler) {
        merger = new CountMerger(config, handler);
        return new ViewMergeWriter(merger);
    }

    /** 单热点、窄窗口：正常路径用 */
    private static MergeConfig hotConfig() {
        return new MergeConfig(8, 1, 200, 5_000, 5_000, 2_000);
    }

    @Test
    @DisplayName("等终态：write 返回时浏览必已落库；首位提交者自己发车（flush 跑在它线程上）")
    void writeReturnsOnlyAfterViewIsFlushed() throws Exception {
        ProbeHandler handler = new ProbeHandler();
        ViewMergeWriter writer = start(hotConfig(), handler);

        // write 是同步的：返回即已落库（窗口 1ms + 落库都在本次调用内完成）
        writer.write(POST_VIEW, POST_ID, VIEWER);

        assertEquals(1, handler.callCount(), "返回时必须已经落库");
        assertEquals(Thread.currentThread().getName(), handler.callThreads().get(0),
                "首位提交者自己发车：flush 跑在调用方线程上，不再有独立的合并线程池");
        assertEquals(1, handler.seenEvents());
        assertTrue(merger.awaitIdle(5_000));
    }

    @Test
    @DisplayName("落库权被前任占住时，后续浏览等终态而不是丢给后台：最终全部落库")
    void laterViewsWaitForTerminalStateWhilePreviousFlushInFlight() throws Exception {
        ProbeHandler handler = new ProbeHandler();
        handler.entered = new CountDownLatch(1);
        handler.hold = new CountDownLatch(1);
        ViewMergeWriter writer = start(hotConfig(), handler);

        // 首位浏览者发车并被闸门挡住（在途 flush）
        List<String> firstOutcome = Collections.synchronizedList(new ArrayList<>());
        Thread firstThread = Thread.ofVirtual().start(() -> {
            writer.write(POST_VIEW, POST_ID, VIEWER);
            firstOutcome.add("done");
        });
        assertTrue(handler.entered.await(5, TimeUnit.SECONDS), "第一件应该已经进入落库");

        // 第二件：等待落库权（等终态），在闸门放开前不该返回
        List<String> secondOutcome = Collections.synchronizedList(new ArrayList<>());
        Thread secondThread = Thread.ofVirtual().start(() -> {
            writer.write(POST_VIEW, POST_ID, VIEWER);
            secondOutcome.add("done");
        });
        Thread.sleep(100);
        assertTrue(secondOutcome.isEmpty(), "统一等待语义：write 必须等终态，不能入队即回");

        handler.hold.countDown();
        firstThread.join(5_000);
        secondThread.join(5_000);
        assertTrue(merger.awaitIdle(5_000));
        assertEquals(2, handler.seenEvents(), "两件都必须落库——不再有「允许丢」");
        assertEquals(2, firstOutcome.size() + secondOutcome.size(), "两位调用方都拿到了终态");
    }

    @Test
    @DisplayName("shardKey 由调用方给：帖子取 postId、商品取 productId，动作不混组")
    void shardKeyComesFromRowIdForBothTargets() {
        ProbeHandler handler = new ProbeHandler();
        ViewMergeWriter writer = start(hotConfig(), handler);

        writer.write(POST_VIEW, POST_ID, VIEWER);
        writer.write(PRODUCT_VIEW, PRODUCT_ID, VIEWER);
        assertTrue(merger.awaitIdle(5_000));

        List<ProbeHandler.Call> calls = handler.callsSnapshot();
        assertEquals(2, calls.size(), "两个不同动作不该被合并进同一组");
        assertEquals(POST_ID, shardKeyOf(calls, POST_VIEW), "帖子浏览的合并键是 postId");
        assertEquals(PRODUCT_ID, shardKeyOf(calls, PRODUCT_VIEW), "商品浏览的合并键是 productId");
    }

    private static String shardKeyOf(List<ProbeHandler.Call> calls, CountAction action) {
        return calls.stream()
                .filter(call -> call.action() == action)
                .map(ProbeHandler.Call::shardKey)
                .findFirst()
                .orElseThrow(() -> new AssertionError("没有看到 " + action + " 的落库调用"));
    }

    @Test
    @DisplayName("缓冲满 → 调用方线程同步一次 +1，不算丢弃、也不报错")
    void bufferFullDegradesToSingleUpdateOnCallerThread() throws Exception {
        ProbeHandler handler = new ProbeHandler();
        // 窗口 500ms ≫ 两次 write 的间隔、容量 1（= maxBatch 下限）：第二件必然撞满，且第一件还没开始落库
        ViewMergeWriter writer = start(new MergeConfig(8, 500, 1, 5_000, 1, 2_000), handler);

        List<String> filler = Collections.synchronizedList(new ArrayList<>());
        Thread fillerThread = Thread.ofVirtual().start(() -> {
            writer.write(POST_VIEW, POST_ID, VIEWER);
            filler.add("done");
        });
        long deadline = System.currentTimeMillis() + 5_000;
        while (merger.pending() < 1) {
            assertTrue(System.currentTimeMillis() < deadline, "第一件该在队列里等窗口");
            Thread.sleep(1);
        }

        writer.write(POST_VIEW, POST_ID, VIEWER);

        assertEquals(1, merger.metrics().rejectedFull(), "第二件应被背压拒收（容量 1）");
        assertEquals(1, handler.callCount(), "唯一一次落库就是降级那次：窗口还没到");
        ProbeHandler.Call degrade = handler.callsSnapshot().get(0);
        assertEquals(Thread.currentThread().getName(), degrade.thread(),
                "降级必须在调用方线程上同步执行");
        assertEquals(1, degrade.size(), "降级组大小恒为 1：一次独立 UPDATE … + 1");

        fillerThread.join(5_000);
    }

    @Test
    @DisplayName("停机中 → 同样一次独立 +1，返回给调用方的仍是「无事发生」")
    void closedMergerDegradesToSingleUpdate() {
        ProbeHandler handler = new ProbeHandler();
        ViewMergeWriter writer = start(hotConfig(), handler);
        merger.close();

        writer.write(PRODUCT_VIEW, PRODUCT_ID, VIEWER);

        assertEquals(1, merger.metrics().rejectedClosed());
        assertEquals(1, handler.callCount());
        assertEquals(Thread.currentThread().getName(), handler.callsSnapshot().get(0).thread());
        assertEquals(1, handler.callsSnapshot().get(0).size());
    }

    @Test
    @DisplayName("降级也失败 → 抛可重试异常：不再有「允许丢」，重放被明细唯一键吸收")
    void degradeFailureIsRetryableInsteadOfSilentDrop() {
        ProbeHandler handler = new ProbeHandler();
        handler.throwOnFlush = true;
        ViewMergeWriter writer = start(hotConfig(), handler);
        merger.close();

        for (int i = 0; i < 2; i++) {
            BusinessException e = assertThrows(BusinessException.class,
                    () -> writer.write(POST_VIEW, POST_ID, VIEWER),
                    "统一等待语义：落库失败必须可见，可安全重试");
            assertEquals(ResponseCode.SYSTEM_ERROR.getCode(), e.getCode());
        }
    }

    @Test
    @DisplayName("只接受浏览动作：点赞类走点赞入口，不许从这里溜进去")
    void rejectsNonViewAction() {
        ProbeHandler handler = new ProbeHandler();
        ViewMergeWriter writer = start(hotConfig(), handler);

        assertThrows(IllegalArgumentException.class, () -> writer.write(POST_LIKE, POST_ID, VIEWER));
    }

    @Test
    @DisplayName("浏览必须带浏览者（三域同规）：哨兵 0 会把全体匿名塌成明细表里的一行，直接拦")
    void rejectsViewWithoutViewer() {
        ProbeHandler handler = new ProbeHandler();
        ViewMergeWriter writer = start(hotConfig(), handler);

        assertThrows(IllegalArgumentException.class, () -> writer.write(POST_VIEW, POST_ID, ""));
        // 商品/跑腿浏览同是 UV 明细口径（market-feed-uv 票 02），无主哨兵 0 一并拦下；
        // 匿名访问的跳过在仓储层做（那里才知道这次访问有没有主）；user_id 是 UUID 后哨兵改空串
        assertThrows(IllegalArgumentException.class, () -> writer.write(PRODUCT_VIEW, PRODUCT_ID, ""));
        assertThrows(IllegalArgumentException.class, () -> writer.write(ERRAND_VIEW, ERRAND_ID, ""));
        assertEquals(0, handler.callCount());
    }

    /**
     * 落库替身：记录「谁在哪个线程上、落了多大一组」，可阻塞、可抛异常。
     *
     * <p>刻意不复用 {@link RecordingFlushHandler}：那个替身服务的是 A 组不变量（身份去重、单飞计数），
     * 这里要看的是<b>线程归属</b>与<b>组大小</b>。
     */
    private static final class ProbeHandler implements ICountFlushHandler {

        private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());

        /** 进入落库时的信号 */
        volatile CountDownLatch entered;
        /** 落库期间要等的闸门 */
        volatile CountDownLatch hold;
        /** true = 整组抛异常（模拟 UPDATE 失败） */
        volatile boolean throwOnFlush;

        static final class Call {

            private final CountAction action;
            private final String shardKey;
            private final int size;
            private final String thread;

            Call(CountAction action, String shardKey, int size, String thread) {
                this.action = action;
                this.shardKey = shardKey;
                this.size = size;
                this.thread = thread;
            }

            public CountAction action() {
                return action;
            }

            public String shardKey() {
                return shardKey;
            }

            public int size() {
                return size;
            }

            public String thread() {
                return thread;
            }

            @Override
            public boolean equals(Object o) {
                if (this == o) return true;
                if (!(o instanceof Call other)) return false;
                return size == other.size
                        && Objects.equals(action, other.action)
                        && Objects.equals(shardKey, other.shardKey)
                        && Objects.equals(thread, other.thread);
            }

            @Override
            public int hashCode() {
                return Objects.hash(action, shardKey, size, thread);
            }

            @Override
            public String toString() {
                return "Call[action=" + action + ", shardKey=" + shardKey + ", size=" + size + ", thread=" + thread + "]";
            }
        }

        @Override
        public List<LikeState> flushGroup(CountAction action, String shardKey, List<CountEvent> events) {
            calls.add(new Call(action, shardKey, events.size(), Thread.currentThread().getName()));
            CountDownLatch startSignal = entered;
            if (startSignal != null) {
                startSignal.countDown();
            }
            CountDownLatch gate = hold;
            if (gate != null) {
                try {
                    if (!gate.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("落库替身等闸门超时");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("落库替身被中断", e);
                }
            }
            if (throwOnFlush) {
                throw new IllegalStateException("模拟 UPDATE 失败");
            }
            // 浏览件没有业务结果：回等长占位（APPLIED），只为让引擎记 flushed（否则会被补 FAILED，读数塌掉）
            return Collections.nCopies(events.size(), LikeState.APPLIED);
        }

        int callCount() {
            synchronized (calls) {
                return calls.size();
            }
        }

        int seenEvents() {
            synchronized (calls) {
                return calls.stream().mapToInt(Call::size).sum();
            }
        }

        List<String> callThreads() {
            synchronized (calls) {
                return calls.stream().map(Call::thread).toList();
            }
        }

        List<Call> callsSnapshot() {
            synchronized (calls) {
                return List.copyOf(calls);
            }
        }
    }
}
