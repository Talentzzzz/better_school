package com.betterschool.infrastructure.merge;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A1–A7 用的落库替身：记录每次「组」调用的形状，并可按需阻塞 / 抛异常 / 漏回填。
 *
 * <p>三条记录口径对应三条不变量：
 * <ul>
 *   <li>{@code seen} 用<b>身份</b>（{@link IdentityHashMap}）而不是 equals：件是值对象，
 *       值相等不算「同一件」，只有同一对象才证明「这件被落库函数看到了一次」；</li>
 *   <li>{@code duplicates} &gt; 0 就是「一件被落库两次」——I1 的直接反例；</li>
 *   <li>{@code maxInFlightOf(key)} &gt; 1 就是「同 key 两条 flush 同时跑」——I2 的直接反例；
 *       {@code maxDistinctKeysInFlight()} 则相反，它<b>应当</b> &gt; 1：不同 key 可并发 flush
 *       是 per-invId leader 模型「撞片串行消除」的钉子。</li>
 * </ul>
 *
 * <p>它就是一个 {@link MergeStrategy.Flush}——引擎与落库之间的契约面只有一个函数，替身自然也就这么薄。
 */
final class RecordingFlush implements MergeStrategy.Flush<MergeEngineTest.Job, Boolean> {

    private static final long BLOCK_TIMEOUT_SECONDS = 10;

    /** 每次落库调用的形状（按调用顺序） */
    private final List<Group> groups = Collections.synchronizedList(new ArrayList<>());

    /** 落库函数见过的件（身份集合）；重复出现即记入 {@link #duplicates} */
    private final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
    private int duplicates;

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();
    /** 每个 key 的在途 flush 计数：同 key 单飞（I2）按 key 看，全局并发按跨 key 看 */
    private final Map<String, Integer> keyInFlight = new ConcurrentHashMap<>();
    private final Map<String, Integer> maxKeyInFlight = new ConcurrentHashMap<>();
    /** 同时在途的<b>不同 key</b> 数的最大值 */
    private final AtomicInteger distinctKeysInFlight = new AtomicInteger();
    private final AtomicInteger maxDistinctKeysInFlight = new AtomicInteger();

    /** 进入落库时的信号：测试用它确认「flush 已经开始」 */
    volatile CountDownLatch entered;
    /** 落库期间要等的闸门：测试用它制造「落库阻塞」 */
    volatile CountDownLatch hold;
    /** 只拦这个 key 的闸门（{@code null} = 不按 key 区分）：验证「别的 key 不被拖住」时用 */
    volatile String gatedKey;
    /** 每次落库的人为耗时（毫秒） */
    volatile long delayMs;
    /** true = 整组抛异常（模拟事务回滚） */
    volatile boolean throwOnFlush;
    /** true = 逐件回 {@code false}（模拟「业务上没成功」这条出口，不抛异常） */
    volatile boolean negativeOnFlush;
    /** &gt;= 0 = 全程只回填前 N 件，其余漏回填（模拟落库函数漏回填）；按件累计，不按组重置 */
    volatile int fillOnlyTotal = -1;

    private final AtomicInteger fillBudget = new AtomicInteger();

    /** 一组落库的形状 */
    static final class Group {

        private final String shardKey;
        private final List<Object> payloads;

        Group(String shardKey, List<Object> payloads) {
            this.shardKey = shardKey;
            this.payloads = payloads;
        }

        public String shardKey() {
            return shardKey;
        }

        public List<Object> payloads() {
            return payloads;
        }

        int size() {
            return payloads.size();
        }
    }

    @Override
    public List<Boolean> flush(List<MergeEngineTest.Job> group) {
        enter(new Group(group.get(0).shardKey(), List.copyOf(group)));
        int fill = fillCount(group.size());
        if (fill == 0) {
            return null;
        }
        return Collections.nCopies(fill, !negativeOnFlush);
    }

    /** 落库的公共前半段：并发度 / 身份去重 / 阻塞 / 人为耗时 / 抛异常 */
    private void enter(Group group) {
        maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        trackKeyInFlight(group.shardKey());
        try {
            groups.add(group);
            synchronized (seen) {
                for (Object payload : group.payloads()) {
                    if (seen.put(payload, Boolean.TRUE) != null) {
                        duplicates++;
                    }
                }
            }
            CountDownLatch startSignal = entered;
            if (startSignal != null) {
                startSignal.countDown();
            }
            CountDownLatch gate = hold;
            boolean gated = gatedKey == null || gatedKey.equals(group.shardKey());
            if (gate != null && gated && !gate.await(BLOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("落库替身等闸门超时");
            }
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
            if (throwOnFlush) {
                throw new IllegalStateException("模拟事务回滚");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("落库替身被中断", e);
        } finally {
            inFlight.decrementAndGet();
            untrackKeyInFlight(group.shardKey());
        }
    }

    /** 同 key 在途计数 +1，并刷新「同时在不同 key 上落库」的峰值 */
    private void trackKeyInFlight(String key) {
        int now = keyInFlight.compute(key, (k, v) -> (v == null ? 0 : v) + 1);
        maxKeyInFlight.compute(key, (k, v) -> Math.max(v == null ? 0 : v, now));
        int distinct = distinctKeysInFlight.incrementAndGet();
        maxDistinctKeysInFlight.accumulateAndGet(distinct, Math::max);
    }

    private void untrackKeyInFlight(String key) {
        keyInFlight.compute(key, (k, v) -> (v == null || v <= 1) ? null : v - 1);
        distinctKeysInFlight.decrementAndGet();
    }

    private int fillCount(int size) {
        int budget = fillOnlyTotal < 0 ? Integer.MAX_VALUE : fillOnlyTotal;
        return Math.min(size, Math.max(0, budget - fillBudget.getAndAdd(size)));
    }

    int seenCount() {
        synchronized (seen) {
            return seen.size();
        }
    }

    boolean hasSeen(Object payload) {
        synchronized (seen) {
            return seen.containsKey(payload);
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

    /** 某个 key 上的在途 flush 峰值：&gt; 1 = 同 key 单飞被破坏（I2 反例） */
    int maxInFlightOf(String key) {
        return maxKeyInFlight.getOrDefault(key, 0);
    }

    /** 同时在不同 key 上落库的峰值：per-invId 模型下不同 key 应当能并发（&gt; 1） */
    int maxDistinctKeysInFlight() {
        return maxDistinctKeysInFlight.get();
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

    /** 某个分片键上，各件被落库的先后顺序（用于保序断言） */
    List<Object> payloadsOf(String shardKey) {
        List<Object> ordered = new ArrayList<>();
        synchronized (groups) {
            for (Group group : groups) {
                if (group.shardKey().equals(shardKey)) {
                    ordered.addAll(group.payloads());
                }
            }
        }
        return ordered;
    }
}
