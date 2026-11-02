package com.betterschool.marketing.infrastructure.inventory.merge;

import com.betterschool.marketing.domain.inventory.model.valobj.ClaimItem;
import com.betterschool.marketing.domain.inventory.model.valobj.ClaimOutcome;
import com.betterschool.marketing.domain.inventory.model.valobj.DeductResult;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryDeductRequestVO;
import com.betterschool.marketing.types.inventory.InventorySegmentVO;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A1–A7 用的落库替身：记录每次「组」调用的形状，并可按需阻塞 / 抛异常 / 漏回填。
 *
 * <p>三条记录口径对应三条不变量：
 * <ul>
 *   <li>{@code seen} 用<b>身份</b>（{@link IdentityHashMap}）而不是 equals：请求 / 确认件都是 record，
 *       值相等不算「同一件」，只有同一对象才证明「这件被落库方法看到了一次」；</li>
 *   <li>{@code duplicates} &gt; 0 就是「一件被落库两次」——I1 的直接反例；</li>
 *   <li>{@code maxInFlight} &gt; 1 就是「同分片两条 flush 同时跑」——I2 的直接反例。预扣与确认共用
 *       计数：它们打的是同一行台账，单飞是两者共同的前提。</li>
 * </ul>
 */
final class RecordingQuotaFlushHandler implements IQuotaMergeFlushHandler {

    private static final long BLOCK_TIMEOUT_SECONDS = 10;

    /** 每次落库调用的形状（按调用顺序） */
    private final List<Group> groups = Collections.synchronizedList(new ArrayList<>());

    /** 落库方法见过的件（身份集合）；重复出现即记入 {@link #duplicates} */
    private final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<>();
    private int duplicates;

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();

    /** 进入落库时的信号：测试用它确认「flush 已经开始」 */
    volatile CountDownLatch entered;
    /** 落库期间要等的闸门：测试用它制造「落库阻塞」 */
    volatile CountDownLatch hold;
    /** 每次落库的人为耗时（毫秒） */
    volatile long delayMs;
    /** true = 整组抛异常（模拟事务回滚：确认 waiting 不足的守卫失败） */
    volatile boolean throwOnFlush;
    /** true = 逐件回 {@code INSUFFICIENT}（模拟「余量不足」这条业务出口，不抛异常） */
    volatile boolean insufficientOnFlush;
    /** &gt;= 0 = 全程只回填前 N 件，其余漏回填（模拟落库方法漏回填）；按件累计，不按组重置 */
    volatile int fillOnlyTotal = -1;

    private final AtomicInteger fillBudget = new AtomicInteger();

    /** 一组落库的形状 */
    record Group(QuotaAction action, InventorySegmentVO segment, String invId, List<Object> payloads) {

        int size() {
            return payloads.size();
        }
    }

    @Override
    public List<DeductResult> flushDeductGroup(InventorySegmentVO segment,
                                               String invId,
                                               List<InventoryDeductRequestVO> requests) {
        enter(new Group(QuotaAction.DEDUCT, segment, invId, List.copyOf(requests)));
        int fill = fillCount(requests.size());
        if (fill == 0) {
            return null;
        }
        return Collections.nCopies(fill, insufficientOnFlush ? DeductResult.INSUFFICIENT : DeductResult.DEDUCTED);
    }

    @Override
    public List<ClaimOutcome> flushClaimGroup(String invId, List<ClaimItem> items) {
        enter(new Group(QuotaAction.CLAIM, null, invId, List.copyOf(items)));
        int fill = fillCount(items.size());
        if (fill == 0) {
            return null;
        }
        return Collections.nCopies(fill, ClaimOutcome.CONFIRMED);
    }

    /** 落库的公共前半段：并发度 / 身份去重 / 阻塞 / 人为耗时 / 抛异常 */
    private void enter(Group group) {
        maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
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
            if (gate != null && !gate.await(BLOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
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
        }
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

    /** 某个台账行上，各件被落库的先后顺序（用于保序断言） */
    List<Object> payloadsOf(String invId) {
        List<Object> ordered = new ArrayList<>();
        synchronized (groups) {
            for (Group group : groups) {
                if (group.invId() == invId) {
                    ordered.addAll(group.payloads());
                }
            }
        }
        return ordered;
    }
}
