package com.betterschool.marketing.domain.inventory.service.merge;

import com.betterschool.marketing.domain.inventory.adapter.repository.IInventoryRepository;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryClaimRequestVO;
import com.betterschool.marketing.domain.inventory.model.valobj.ClaimOutcome;
import com.betterschool.marketing.domain.inventory.model.valobj.DeductResult;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryDeductRequestVO;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryLedgerVO;
import com.betterschool.marketing.types.inventory.AwardInventoryType;

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
 *   <li>{@code seen} 用<b>身份</b>（{@link IdentityHashMap}）而不是 equals：请求 / 确认件都是值对象，
 *       值相等不算「同一件」，只有同一对象才证明「这件被落库方法看到了一次」；</li>
 *   <li>{@code duplicates} &gt; 0 就是「一件被落库两次」——I1 的直接反例；</li>
 *   <li>{@code maxInFlight} &gt; 1 就是「同分片两条 flush 同时跑」——I2 的直接反例。预扣与确认共用
 *       计数：它们打的是同一行台账，单飞是两者共同的前提。</li>
 * </ul>
 *
 * <p>实现的是领域端口 {@link IInventoryRepository}（引擎的落库出口就是它）：
 * 只认真实现两个 flush 组方法，其余存储方法在纯内存测试里不该被触到，
 * 一律抛 {@link UnsupportedOperationException}。
 */
final class RecordingQuotaFlushHandler implements IInventoryRepository {

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
    static final class Group {

        private final Action action;
        private final AwardInventoryType type;
        private final String awardId;
        private final List<Object> payloads;

        Group(Action action, AwardInventoryType type, String awardId, List<Object> payloads) {
            this.action = action;
            this.type = type;
            this.awardId = awardId;
            this.payloads = payloads;
        }

        public Action action() {
            return action;
        }

        public AwardInventoryType type() {
            return type;
        }

        public String awardId() {
            return awardId;
        }

        public List<Object> payloads() {
            return payloads;
        }

        int size() {
            return payloads.size();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Group other)) return false;
            return Objects.equals(action, other.action)
                    && Objects.equals(type, other.type)
                    && Objects.equals(awardId, other.awardId)
                    && Objects.equals(payloads, other.payloads);
        }

        @Override
        public int hashCode() {
            return Objects.hash(action, type, awardId, payloads);
        }

        @Override
        public String toString() {
            return "Group[action=" + action + ", type=" + type + ", awardId=" + awardId
                    + ", payloads=" + payloads + "]";
        }
    }

    @Override
    public List<DeductResult> flushDeductGroup(AwardInventoryType type,
                                               String awardId,
                                               List<InventoryDeductRequestVO> requests) {
        enter(new Group(Action.DEDUCT, type, awardId, List.copyOf(requests)));
        int fill = fillCount(requests.size());
        if (fill == 0) {
            return null;
        }
        return Collections.nCopies(fill, insufficientOnFlush ? DeductResult.INSUFFICIENT : DeductResult.DEDUCTED);
    }

    @Override
    public List<ClaimOutcome> flushClaimGroup(List<InventoryClaimRequestVO> items) {
        enter(new Group(Action.CLAIM, null, items.get(0).awardId(), List.copyOf(items)));
        int fill = fillCount(items.size());
        if (fill == 0) {
            return null;
        }
        return Collections.nCopies(fill, ClaimOutcome.CONFIRMED);
    }

    // ---- 端口的其余存储方法：纯内存测试不触到，触到即失败 ----

    @Override
    public boolean allocate(com.betterschool.marketing.types.inventory.InventorySegmentVO poolSegment,
                            String poolInvId, String awardId, long quantity, String allocBizId) {
        throw new UnsupportedOperationException("落库替身不该被调分配");
    }

    @Override
    public InventoryLedgerVO queryAwardInventory(String awardId) {
        throw new UnsupportedOperationException("落库替身不该被调台账查询");
    }

    @Override
    public List<InventoryLedgerVO> listOpenAwardInventories() {
        throw new UnsupportedOperationException("落库替身不该被调台账查询");
    }

    @Override
    public InventoryLedgerVO queryLedger(com.betterschool.marketing.types.inventory.InventorySegmentVO segment, String invId) {
        throw new UnsupportedOperationException("落库替身不该被调台账查询");
    }

    @Override
    public List<InventoryLedgerVO> listOpenLedgers(com.betterschool.marketing.types.inventory.InventorySegmentVO segment) {
        throw new UnsupportedOperationException("落库替身不该被调台账查询");
    }

    @Override
    public boolean existsDeductDetail(String awardId, String bizType, String bizId) {
        throw new UnsupportedOperationException("落库替身不该被调幂等预检");
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

    /** 某个奖品库存账户上，各件被落库的先后顺序（用于保序断言） */
    List<Object> payloadsOf(String awardId) {
        List<Object> ordered = new ArrayList<>();
        synchronized (groups) {
            for (Group group : groups) {
                if (group.awardId() == awardId) {
                    ordered.addAll(group.payloads());
                }
            }
        }
        return ordered;
    }
}
