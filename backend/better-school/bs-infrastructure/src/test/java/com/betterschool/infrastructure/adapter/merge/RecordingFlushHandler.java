package com.betterschool.infrastructure.adapter.merge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A1–A7 用的落库替身：记录每次「组」调用的形状，并可按需阻塞 / 抛异常 / 漏回填。
 *
 * <p>三条记录口径对应三条不变量：
 * <ul>
 *   <li>{@code seen} 用<b>身份</b>（{@link IdentityHashMap}）而不是 equals：{@link CountEvent} 是 record，
 *       值相等不算「同一件」，只有同一对象才证明「这件被落库方法看到了一次」；</li>
 *   <li>{@code duplicates} &gt; 0 就是「一件被落库两次」——I1 的直接反例；</li>
 *   <li>{@code maxInFlight} &gt; 1 就是「同分片两条 flush 同时跑」——I2 的直接反例。</li>
 * </ul>
 */
final class RecordingFlushHandler implements ICountFlushHandler {

    private static final long BLOCK_TIMEOUT_SECONDS = 10;

    /** 每次落库调用的形状（按调用顺序） */
    private final List<Group> groups = Collections.synchronizedList(new ArrayList<>());

    /** 落库方法见过的件（身份集合）；重复出现即记入 {@link #duplicates} */
    private final IdentityHashMap<CountEvent, Boolean> seen = new IdentityHashMap<>();
    private int duplicates;

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();

    /** 进入落库时的信号：测试用它确认「flush 已经开始」 */
    volatile CountDownLatch entered;
    /** 落库期间要等的闸门：测试用它制造「落库阻塞」 */
    volatile CountDownLatch hold;
    /** 每次落库的人为耗时（毫秒） */
    volatile long delayMs;
    /** true = 整组抛异常（模拟事务回滚） */
    volatile boolean throwOnFlush;
    /** &gt;= 0 = 全程只回填前 N 件，其余漏回填（模拟落库方法漏回填）；按件累计，不按组重置 */
    volatile int fillOnlyTotal = -1;

    private final AtomicInteger fillBudget = new AtomicInteger();

    /** 一组落库的形状 */
    static final class Group {

        private final CountAction action;
        private final String shardKey;
        private final List<CountEvent> events;

        Group(CountAction action, String shardKey, List<CountEvent> events) {
            this.action = action;
            this.shardKey = shardKey;
            this.events = events;
        }

        public CountAction action() {
            return action;
        }

        public String shardKey() {
            return shardKey;
        }

        public List<CountEvent> events() {
            return events;
        }

        int size() {
            return events.size();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Group other)) return false;
            return Objects.equals(action, other.action)
                    && Objects.equals(shardKey, other.shardKey)
                    && Objects.equals(events, other.events);
        }

        @Override
        public int hashCode() {
            return Objects.hash(action, shardKey, events);
        }

        @Override
        public String toString() {
            return "Group[action=" + action + ", shardKey=" + shardKey + ", events=" + events + "]";
        }
    }

    @Override
    public List<LikeState> flushGroup(CountAction action, String shardKey, List<CountEvent> events) {
        maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        try {
            groups.add(new Group(action, shardKey, List.copyOf(events)));
            synchronized (seen) {
                for (CountEvent event : events) {
                    if (seen.put(event, Boolean.TRUE) != null) {
                        duplicates++;
                    }
                }
            }
            CountDownLatch startSignal = entered;
            if (startSignal != null) {
                startSignal.countDown();
            }
            CountDownLatch gate = hold;
            if (gate != null && !gate.await(BLOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("落库替身等闸门超时");
            }
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
            if (throwOnFlush) {
                throw new IllegalStateException("模拟事务回滚");
            }
            int budget = fillOnlyTotal < 0 ? Integer.MAX_VALUE : fillOnlyTotal;
            int fill = Math.min(events.size(), Math.max(0, budget - fillBudget.getAndAdd(events.size())));
            if (fill == 0) {
                return null;
            }
            return Collections.nCopies(fill, LikeState.LIKED);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("落库替身被中断", e);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    int seenCount() {
        synchronized (seen) {
            return seen.size();
        }
    }

    boolean hasSeen(CountEvent event) {
        synchronized (seen) {
            return seen.containsKey(event);
        }
    }

    int duplicateCount() {
        synchronized (seen) {
            return duplicates;
        }
    }

    int maxInFlight() {
        return maxInFlight.get();
    }

    int callCount() {
        return groups.size();
    }

    int maxGroupSize() {
        synchronized (groups) {
            int max = 0;
            for (Group group : groups) {
                max = Math.max(max, group.size());
            }
            return max;
        }
    }

    /** 某个行主键上，各件被落库的先后顺序（用于保序断言） */
    List<CountEvent> eventsOf(String shardKey) {
        List<CountEvent> ordered = new ArrayList<>();
        synchronized (groups) {
            for (Group group : groups) {
                if (group.shardKey().equals(shardKey)) {
                    ordered.addAll(group.events());
                }
            }
        }
        return ordered;
    }
}
