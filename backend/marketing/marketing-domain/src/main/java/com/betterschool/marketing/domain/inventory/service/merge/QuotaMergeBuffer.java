package com.betterschool.marketing.domain.inventory.service.merge;

import com.betterschool.marketing.domain.inventory.adapter.repository.IInventoryRepository;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryClaimRequestVO;
import com.betterschool.marketing.domain.inventory.model.valobj.ClaimOutcome;
import com.betterschool.marketing.domain.inventory.model.valobj.DeductResult;
import com.betterschool.marketing.domain.inventory.model.valobj.InventoryDeductRequestVO;
import com.betterschool.marketing.types.inventory.AwardInventoryType;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 库存侧进程内合并器：N 个分片排空线程（每分片一条常驻 daemon 平台线程）+ 入口路由。
 *
 * <pre>
 *   submitAndWait(request)
 *     ├─ 按 shardOf(awardId) 路由到分片
 *     ├─ 塞进分片缓冲区（首件起算 maxFlushDelayMillis 合并窗口，攒满 maxBatch 提前摘批）
 *     └─ park 在自己的 future 上，最多等 waitTimeoutMs
 *                    ↓（窗口到点 / 攒满，由分片自己的常驻线程接管）
 *   flush：锁内整块摘批 → 锁外按「动作 × type × awardId」分组、一组一个事务落库 → 逐件回填 → 有积压则背靠背续跑
 * </pre>
 *
 * <p>成功 = 已提交：{@code DEDUCTED} / {@code CONFIRMED} 只在落库方法返回（COMMIT）之后回填。
 * 引擎只回答 {@code BUFFER_FULL / CLOSED / TIMEOUT_UNKNOWN / FAILED}，
 * 降级动作（缓冲满 / 停机中，调用方线程同步落库组大小 1）在调用方，落库方法与合并器内共用。
 *
 * <p>不变量：恰一终态（{@code complete/fail} 幂等，漏回填补失败态）、同分片单飞（分片串行化在
 * 每分片恰一条 flush 线程上）、锁不跨 DB、停机三步（停收 → 排空 → signal + join）。
 * 落库出口是 {@link IInventoryRepository}
 * （真身 {@code InventoryQuotaFlushService}，经 {@code InventoryRepository} 委托）；
 * 实例由 {@code QuotaMergeConfig} 以 {@code @Bean} 装配，
 * {@link #close()} 由 Spring 的 {@code @Bean} destroyMethod 推断在停机时调用。
 *
 * <p>本类在 domain 层：它是纯 Java 并发编排（不碰 SQL、不碰框架），
 * 域服务直接注入入队，落库经端口回调——依赖单向，仓储不认识本类。
 */
@Slf4j
public class QuotaMergeBuffer {

    /** 排空等待的兜底探测间隔：正常由 flush 完成信号唤醒，这里只防信号丢失 */
    private static final long IDLE_PROBE_MS = 5;

    private final MergeConfig config;
    private final IInventoryRepository flushHandler;
    private final ShardFlusher[] shards;

    /** 排空通知：flush 每走完一批就 signalAll，停机不用轮询 */
    private final ReentrantLock idleLock = new ReentrantLock();
    private final Condition idleSignal = idleLock.newCondition();

    /** 是否接收新件：停机第一步置 false，入口立刻开始拒绝（不再往缓冲区里塞） */
    private volatile boolean accepting = true;

    public QuotaMergeBuffer(MergeConfig config, IInventoryRepository flushHandler) {
        if (flushHandler == null) {
            throw new IllegalArgumentException(
                    "[配置错误] IInventoryRepository 不能为 null：合并器没有出口等于丢弃所有扣减");
        }
        this.config = config;
        this.flushHandler = flushHandler;

        // 每分片一条常驻 daemon 线程（quota-merge-flush-<shardNo>），构造即开跑；
        // flush 并发 = shardCount，其与连接池的不变量由装配层（QuotaMergeConfig）启动时校验
        this.shards = new ShardFlusher[config.shardCount()];
        for (int i = 0; i < config.shardCount(); i++) {
            shards[i] = new ShardFlusher(i, config, this::flushBatch, this::signalIdle);
        }

        log.info("库存合并器启动：shardCount={}(mask={}), maxFlushDelayMillis={}, maxBatch={}, waitTimeoutMs={}, "
                        + "shardCapacity={}, flush=每分片1条常驻线程(共{}), drainTimeoutMs={}",
                config.shardCount(), config.mask(), config.maxFlushDelayMillis(), config.maxBatch(),
                config.waitTimeoutMs(), config.shardCapacity(), config.shardCount(), config.drainTimeoutMs());
    }

    /** 预扣（等结果）。分片键就是 {@code request.awardId()} */
    public DeductResult submitAndWait(InventoryDeductRequestVO request) {
        Pending item = Pending.deduct(request);
        if (!enqueue(item)) {
            // 未入队：满了或停机中。件从未进过缓冲区，必未 settle，交给调用方降级
            return accepting ? DeductResult.BUFFER_FULL : DeductResult.CLOSED;
        }
        try {
            // 强转必然成立：DEDUCT 件只会被 flushDeductGroup 以 DeductResult 回填
            return (DeductResult) item.await(config.waitTimeoutMs());
        } catch (TimeoutException e) {
            // 件仍在缓冲区里，之后大概率仍会扣 —— 对外表达为「结果未知」，不是失败
            return DeductResult.TIMEOUT_UNKNOWN;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 件没有丢（还在缓冲里），只是这个线程不再等了
            return DeductResult.FAILED;
        }
    }

    /** 兑换确认（等结果，仅实物账户）；{@code awardId} 空（不限量档）的短路在调用方做 */
    public ClaimOutcome submitAndWait(InventoryClaimRequestVO request) {
        Pending pending = Pending.claim(request);
        if (!enqueue(pending)) {
            // 未入队：满了或停机中。件从未进过缓冲区，必未 settle，交给调用方降级
            return accepting ? ClaimOutcome.BUFFER_FULL : ClaimOutcome.CLOSED;
        }
        try {
            // 强转必然成立：CLAIM 件只会被 flushClaimGroup 以 ClaimOutcome 回填
            return (ClaimOutcome) pending.await(config.waitTimeoutMs());
        } catch (TimeoutException e) {
            return ClaimOutcome.TIMEOUT_UNKNOWN;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ClaimOutcome.FAILED;
        }
    }

    /** 当前缓冲件数（全分片求和，无锁镜像） */
    public int pending() {
        int total = 0;
        for (ShardFlusher shard : shards) {
            total += shard.pendingSize();
        }
        return total;
    }

    /** 等排空（{@code close()} 与测试用）：判据是所有分片缓冲空 */
    public boolean awaitIdle(long timeoutMs) {
        if (isIdle()) {
            return true;
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        idleLock.lock();
        try {
            while (!isIdle()) {
                long remainNanos = deadline - System.nanoTime();
                if (remainNanos <= 0) {
                    return false;
                }
                try {
                    idleSignal.awaitNanos(Math.min(remainNanos, TimeUnit.MILLISECONDS.toNanos(IDLE_PROBE_MS)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return isIdle();
                }
            }
            return true;
        } finally {
            idleLock.unlock();
        }
    }

    /**
     * 优雅停机：停收 → 排空 → 回填 + signal + join，顺序不能反。
     *
     * <p>先杀线程会把缓冲区里的件变成「永远等不到的 future」；排空超时则显式回填，让调用方立刻
     * 拿到可重试的失败态。收尾把各分片线程 signal 出等待（{@code failRemaining} 置 {@code closed}
     * 并 signal），再 {@code join(drainTimeout)}——线程只在 {@code closed} 且缓冲空时退出，
     * join 返回即代表在途 flush 已收尾。{@code HikariDataSource} 的销毁晚于本方法
     * （Spring 按依赖逆序销毁），所以排空时落库用的连接仍然可用——不要调换顺序。
     */
    public void close() {
        accepting = false;
        boolean drained = awaitIdle(config.drainTimeoutMs());
        int left = 0;
        for (ShardFlusher shard : shards) {
            left += shard.failRemaining(drained ? "应用停机（竞态补收）" : "排空超时");
        }
        if (left > 0) {
            log.warn("停机回填 {} 件未落库（从未进入事务，可安全重试）", left);
        }
        long joinDeadlineMillis = System.currentTimeMillis() + config.drainTimeoutMs();
        for (int i = 0; i < shards.length; i++) {
            long remainMs = Math.max(1, joinDeadlineMillis - System.currentTimeMillis());
            if (!shards[i].joinFlushThread(remainMs)) {
                log.warn("分片 {} 的 flush 线程未在 {}ms 内退出（大概率阻塞在落库上）：daemon 线程不阻塞 JVM 停机，放弃等待",
                        i, remainMs);
            }
        }
        log.info("库存合并器已停止：drained={}, 剩余积压={}", drained, pending());
    }

    // ── 落库路径 ──────────────────────────────────────────────────────────────

    /** 唯一入队通道：停机检查 → 路由到分片收件。false = 未入队（满了或已停机） */
    private boolean enqueue(Pending item) {
        return accepting && shards[config.shardOf(item.shardKey())].offer(item);
    }

    /**
     * 摘批后的落库：按「动作 × type × awardId」二次分组，一组一个事务（锁集最小、失败只回滚一组、
     * 结果能精确回填到件）。收尾兜底：任何还没回填的件补失败态 —— 绝不让调用方悬到超时。
     */
    private void flushBatch(List<Pending> batch) {
        Map<GroupKey, List<Pending>> groups = new LinkedHashMap<>();
        for (Pending item : batch) {
            groups.computeIfAbsent(new GroupKey(item.action(), item.type(), item.shardKey()),
                    key -> new ArrayList<>()).add(item);
        }
        try {
            for (List<Pending> group : groups.values()) {
                flushOneGroup(group);
            }
        } finally {
            for (Pending item : batch) {
                item.fail();
            }
        }
    }

    /** 按「动作」分流到各自的落库路径：一组同键分桶内动作必然一致（GroupKey 第一维） */
    private void flushOneGroup(List<Pending> items) {
        if (items.get(0).action() == Action.DEDUCT) {
            flushDeductGroup(items);
        } else {
            flushClaimGroup(items);
        }
    }

    /** 预扣组：落库 → 逐件回填 {@code DEDUCTED} / {@code INSUFFICIENT}。异常只吞掉本组，别的组照常跑 */
    private void flushDeductGroup(List<Pending> items) {
        Pending first = items.get(0);
        List<InventoryDeductRequestVO> requests = items.stream().map(Pending::deductRequest).toList();
        List<DeductResult> results = null;
        try {
            results = flushHandler.flushDeductGroup(first.type(), first.shardKey(), requests);
        } catch (RuntimeException e) {
            log.error("预扣落库失败（整组回滚，{} 件）：type={}, awardId={}",
                    items.size(), first.type(), first.shardKey(), e);
        }
        backfill(items, results);
    }

    /** 确认组：落库 → 逐件回填 {@code CONFIRMED}。异常只吞掉本组，别的组照常跑 */
    private void flushClaimGroup(List<Pending> items) {
        List<InventoryClaimRequestVO> claims = items.stream().map(Pending::claimRequest).toList();
        List<ClaimOutcome> results = null;
        try {
            results = flushHandler.flushClaimGroup(claims);
        } catch (RuntimeException e) {
            log.error("兑换确认落库失败（整组回滚，{} 件）：awardId={}",
                    items.size(), items.get(0).shardKey(), e);
        }
        backfill(items, results);
    }

    /**
     * 逐件回填：长度不足 / 该位是 null 都算「漏回填」，补失败态——宁可给调用方一个可重试的失败，
     * 也不给它一个「成功但没有结果」。
     */
    private void backfill(List<Pending> items, List<?> results) {
        int resultCount = results == null ? 0 : results.size();
        if (resultCount > items.size()) {
            log.warn("落库方法返回的结果比入参多（{} > {}）：多出的忽略", resultCount, items.size());
        }
        for (int i = 0; i < items.size(); i++) {
            Object result = i < resultCount ? results.get(i) : null;
            if (result != null) {
                items.get(i).complete(result);
            } else {
                items.get(i).fail();
            }
        }
    }

    /** flush 完成回调：唤醒排空等待者。没人等时 tryLock 直接失败，省掉一次抢锁 */
    private void signalIdle() {
        if (!idleLock.tryLock()) {
            return;
        }
        try {
            idleSignal.signalAll();
        } finally {
            idleLock.unlock();
        }
    }

    /** 所有分片缓冲都空（无锁：只读 volatile 镜像；在途批由 close 的 join 兜底） */
    private boolean isIdle() {
        for (ShardFlusher shard : shards) {
            if (!shard.isIdle()) {
                return false;
            }
        }
        return true;
    }

    /** 二次分组的键：动作 × 账户类型 × 奖品库存账户（awardId） */
    private static final class GroupKey {

        private final Action action;
        private final AwardInventoryType type;
        private final String shardKey;

        private GroupKey(Action action, AwardInventoryType type, String shardKey) {
            this.action = action;
            this.type = type;
            this.shardKey = shardKey;
        }

        public Action action() {
            return action;
        }

        public AwardInventoryType type() {
            return type;
        }

        public String shardKey() {
            return shardKey;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof GroupKey other)) return false;
            return Objects.equals(action, other.action)
                    && Objects.equals(type, other.type)
                    && Objects.equals(shardKey, other.shardKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(action, type, shardKey);
        }

        @Override
        public String toString() {
            return "GroupKey[action=" + action + ", type=" + type + ", shardKey=" + shardKey + "]";
        }
    }
}
