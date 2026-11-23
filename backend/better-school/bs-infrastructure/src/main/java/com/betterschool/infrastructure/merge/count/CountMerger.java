package com.betterschool.infrastructure.merge.count;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 计数侧合并器：<b>固定 N 个分片排空线程（每分片一条常驻 daemon 平台线程）+ 入口路由</b>（spec §2.2）。
 *
 * <h2>一件请求的一生</h2>
 * <pre>
 *   commit(postId, event)
 *     ├─ 按 shardOf(postId) 路由到分片
 *     ├─ 塞进该分片缓冲区（首件起算 maxFlushDelayMillis 合并窗口，攒满 maxBatch 提前唤醒摘批）
 *     ├─ park 在自己的 future 上，最多等 waitTimeoutMs
 *     └─ 缓冲满 / 停机中 → 本线程用同一个落库方法落「组大小 1」（{@link #degrade}）
 *                    ↓（窗口到点 / 攒满，由分片自己的常驻线程接管）
 *   flush：锁内整块摘批 → 锁外按「动作 × 行主键」二次分组 → 一组一个事务落库 → 回填 → 有积压背靠背续跑
 * </pre>
 *
 * <h2>两个入口，不做合并签名</h2>
 * {@link #commit} 等结果并返回 {@link MergeResult}（点赞 / 取消赞 / 评论赞），入队失败、超时、落库失败
 * 都由它自己兜成三态；{@link #submitAsync} 只返回 {@link SubmitOutcome}（浏览）。<b>不等的人连 future
 * 都不分配</b>：10k/s 的浏览若每人一个 future，每秒就是一万次分配 + park/unpark，比落库本身还贵。
 *
 * <h2>一条实例</h2>
 * 点赞（等）与浏览（不等）共用同一份分片与线程——两个实例要 2×N 条常驻线程，而它们打的是
 * 同一批热点帖（同一条业务行必须落同一片，才能合并成一条 UPDATE）。
 *
 * <h2>背压与降级：同步入口引擎自己兜，异步入口交给 case 层</h2>
 * 引擎不说 {@code REJECTED}。同步入口 {@link #commit} 在缓冲满 / 停机中时，用<b>同一个落库方法</b>
 * 在本线程落「组大小 1」——这两种情况件从未入队、必未落库，就地补一次是安全的；对调用方而言它只会
 * 看到 {@code COMPLETED / TIMEOUT / FAILED}，不必自己判断要不要降级。异步入口不做这件事：浏览允许丢，
 * 而「丢了几个」是 case 层的读数（{@code ViewMergeWriter.droppedViews}），引擎不替它数。
 * 两套 SQL 迟早漂移，而漂移只在压力最大时暴露，所以降级必须复用同一个落库方法（spec §2.6）。
 *
 * <h2>五条不变量</h2>
 * I1 不丢件且恰一个终态（{@link Pending#complete} 幂等 + 漏回填补 {@code FAILED}）、
 * I2 同分片单飞（分片串行化落在「同一分片恰一条线程」上，结构即保证）、
 * I3 锁不跨 DB（临界区只碰内存）、I4 停机三步（停收 → 排空 → signal + join）、
 * I5 背压有界（满即不入队：同步入口就地降级、异步入口如实回 {@code CLOSED}；不扩容、不挤掉已入队件）。
 *
 * <h2>装配</h2>
 * {@code @Component}：参数来自 {@code bs-app} 的 {@code CountMergeConfig}，出口是
 * {@link CountFlushDispatcher}（按动作把一组交给拥有那张表的域仓储：社区仓储的点赞 / 取消赞 /
 * 评论赞 / 帖子浏览，交易仓储的商品浏览）。调用方有两个：{@link CountMergeWriter}（点赞类，等结果）
 * 与 {@link ViewMergeWriter}（浏览，不等结果）——同步入口的降级在本类里，case 层只做「三态 → 域出口」
 * 的翻译；异步入口的丢弃计数在 {@link ViewMergeWriter} 里。本类不认识 HTTP 语义。
 */
@Slf4j
@Component
public class CountMerger {

    /** 排空等待的兜底探测间隔：正常由 flush 完成信号唤醒，这里只防信号丢失 */
    private static final long IDLE_PROBE_MS = 5;

    private final MergeConfig config;
    private final ICountFlushHandler flushHandler;
    private final ShardFlusher[] shards;

    /** 排空通知：flush 每走完一批就 signalAll，停机不用轮询 */
    private final ReentrantLock idleLock = new ReentrantLock();
    private final Condition idleSignal = idleLock.newCondition();

    /** 是否接收新件：停机第一步置 false，入口立刻开始拒绝（不再往缓冲区里塞） */
    private volatile boolean accepting = true;

    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong flushed = new AtomicLong();
    private final AtomicLong batches = new AtomicLong();
    private final AtomicLong rejectedFull = new AtomicLong();
    private final AtomicLong rejectedClosed = new AtomicLong();
    private final AtomicLong timedOut = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong drainedFailed = new AtomicLong();
    private final AtomicLong maxFlushNanos = new AtomicLong();

    /**
     * @param flushHandler 落库方法（实现是 {@link CountFlushDispatcher}）。{@code @Lazy} 不是可选项：
     *                     落库方法在 {@code CommunityRepository} / {@code TradeRepository} 上，
     *                     而这两个仓储的写入入口又要调 {@link CountMergeWriter} / {@link ViewMergeWriter}
     *                     → 本类 —— 出口与调用方互相指着，不延迟到 flush 时解析就是循环依赖。
     *                     合并器本来也只在落库那一刻才用到它，构造期不需要真的把它拿到手
     */
    public CountMerger(MergeConfig config, @Lazy ICountFlushHandler flushHandler) {
        if (flushHandler == null) {
            throw new IllegalArgumentException("[配置错误] ICountFlushHandler 不能为 null：合并器没有出口等于丢弃所有件");
        }
        this.config = config;
        this.flushHandler = flushHandler;

        // 每分片一条常驻 daemon 线程（count-merge-flush-<shardNo>），构造即开跑；
        // flush 并发 = shardCount，其与连接池的不变量由装配层（CountMergeConfig）启动时校验
        this.shards = new ShardFlusher[config.shardCount()];
        for (int i = 0; i < config.shardCount(); i++) {
            shards[i] = new ShardFlusher(i, config, this::flushBatch, this::signalIdle);
        }

        log.info("计数合并器启动：shardCount={}(mask={}), maxFlushDelayMillis={}, maxBatch={}, waitTimeoutMs={}, "
                        + "shardCapacity={}, flush=每分片1条常驻线程(共{}), drainTimeoutMs={}",
                config.shardCount(), config.mask(), config.maxFlushDelayMillis(), config.maxBatch(),
                config.waitTimeoutMs(), config.shardCapacity(), config.shardCount(), config.drainTimeoutMs());
    }

    /**
     * 提交一笔并等结果（点赞 / 取消赞 / 评论赞）：本类<b>等结果那条入口</b>，入队、等待、兜底在
     * 一处闭环。叫 {@code commit} 而不是 {@code write}/{@code submit}：返回时这笔要么已随组提交、
     * 要么确定没提交，是「提交语义」；而 {@code submitAsync} 在本类里已经占了「只管投、不等结果」。
     *
     * <p>缓冲满 / 停机中<b>不会返给调用方</b>：件从未入队、必未落库，就地用同一个落库方法落
     * 「组大小 1」（{@link #degrade}）。所以返回值只有三态——{@code COMPLETED} / {@code TIMEOUT} /
     * {@code FAILED}，调用方不必自己判断要不要降级。
     *
     * @param shardKey 这组要更新的那一行的<b>业务主键</b>（postId / commentId 等字符串），<b>由调用方显式给</b>
     * @param event    事件本体；合并器不解析它来推导 key
     */
    public MergeResult<LikeState> commit(String shardKey, CountEvent event) {
        Pending item = Pending.waiting(shardKey, event);
        SubmitOutcome outcome = enqueue(item);
        if (outcome != SubmitOutcome.ACCEPTED) {
            // 未入队：满了或停机中。件从未进过缓冲区，必未 settle ⇒ 本线程就地落一组
            return degrade(shardKey, event, outcome == SubmitOutcome.BUFFER_FULL ? "缓冲满" : "停机中");
        }
        try {
            return item.await(config.waitTimeoutMs());
        } catch (TimeoutException e) {
            // 件仍在缓冲区里，之后大概率仍会落库 —— 对外表达为「结果未知」，不是失败
            timedOut.incrementAndGet();
            return MergeResult.timeout();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 件没有丢（还在缓冲里），只是这个线程不再等了
            return MergeResult.failed();
        }
    }

    /**
     * 同步降级：组大小 1 调<b>同一个落库方法</b>（与 {@link #flushOneGroup} 共用
     * {@link ICountFlushHandler}，不另写一套「同步版」SQL）。
     *
     * <p>结论映射与批量路径同口径：抛异常 = 整组回滚（确定没落）→ {@code FAILED}；
     * 漏回填（null / 长度不足）= 事务可能已提交 → {@code TIMEOUT}（结果未知，不等于失败）。
     *
     * <p><b>不计入 {@code accepted} / {@code flushed} / {@code batches}</b>：这笔从未入队，塞进去会
     * 破坏「{@code accepted == flushed + failed + drainedFailed + 在途}」的守恒；想知道降级了多少，
     * 看 {@link #metrics()} 的 {@code rejectedFull} / {@code rejectedClosed}。{@code maxFlushNanos}
     * 照记——降级也是一次落库，而且它的耗时直接就是调用方的时延。
     */
    private MergeResult<LikeState> degrade(String shardKey, CountEvent event, String reason) {
        log.warn("合并未接住（{}），降级为同步落库（组大小 1）：action={}, shardKey={}",
                reason, event.action(), shardKey);
        List<LikeState> resultList;
        long startNanos = System.nanoTime();
        try {
            resultList = flushHandler.flushGroup(event.action(), shardKey, List.of(event));
        } catch (RuntimeException e) {
            log.error("同步降级落库失败（整组回滚，确定没落）：action={}, shardKey={}, reason={}",
                    event.action(), shardKey, reason, e);
            return MergeResult.failed();
        } finally {
            maxFlushNanos.accumulateAndGet(System.nanoTime() - startNanos, Math::max);
        }
        if (resultList == null || resultList.size() != 1 || resultList.get(0) == null) {
            // 事务可能已提交：不给调用方一个「成功但没有结果」，也不谎报「确定没落」
            log.error("同步降级落库漏回填（事务可能已提交，按结果未知处理）：action={}, shardKey={}",
                    event.action(), shardKey);
            return MergeResult.timeout();
        }
        return MergeResult.completed(resultList.get(0));
    }

    /**
     * 不等结果的入口（帖子 / 商品浏览）：入队即返回，不分配 future。
     *
     * @return {@code ACCEPTED} 已入队；另外两个由 case 层转为「丢弃并 droppedViews++」
     */
    public SubmitOutcome submitAsync(String shardKey, CountEvent event) {
        return enqueue(Pending.fireAndForget(shardKey, event));
    }

    /**
     * 唯一入队通道：停机检查 → 路由到分片收件 → 计数。两个入口只在「要不要 future」和
     * 「拒收怎么翻译」上不同，计数口径必须共用一份。
     *
     * <p><b>计数守恒</b>：{@code accepted == flushed + failed + drainedFailed + 在途 + timedOut 后的在途}
     * 之所以能对上，靠这里三条分支互斥且覆盖：{@code ACCEPTED} → {@code accepted++}；
     * 分片拒绝时要么 {@code rejectedFull++}（件从未入队）、要么 {@code rejectedClosed++}（同前）。
     */
    private SubmitOutcome enqueue(Pending item) {
        if (!accepting) {
            rejectedClosed.incrementAndGet();
            return SubmitOutcome.CLOSED;
        }
        if (!shards[config.shardOf(item.shardKey())].offer(item)) {
            // 重读 accepting：close 与 offer 赛跑时（入队检查后分片才关闭），按停机算，不诬为背压
            if (accepting) {
                rejectedFull.incrementAndGet();
                return SubmitOutcome.BUFFER_FULL;
            }
            rejectedClosed.incrementAndGet();
            return SubmitOutcome.CLOSED;
        }
        accepted.incrementAndGet();
        return SubmitOutcome.ACCEPTED;
    }

    /**
     * 当前缓冲件数（全分片求和，无锁镜像）。只供 {@link #metrics()}、停机日志与同包测试做
     * 排空/背压断言用——近似读数，不做任何正确性决策，故不对外暴露。
     */
    int pending() {
        int total = 0;
        for (ShardFlusher shard : shards) {
            total += shard.pendingSize();
        }
        return total;
    }

    /**
     * 等排空（{@code close()} 与测试用）。
     *
     * <p>判据是「所有分片缓冲都空」，由 flush 完成信号唤醒，探测间隔只防信号丢失；
     * 在途的那一批（已摘批、尚未回填）不在此判据内，由 {@link #close()} 的 join 兜底。
     */
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

    /** 读数快照（不依赖 micrometer，不开端点） */
    public MergeMetrics metrics() {
        return new MergeMetrics(pending(), accepted.get(), flushed.get(), batches.get(),
                rejectedFull.get(), rejectedClosed.get(), timedOut.get(), failed.get(),
                drainedFailed.get(), maxFlushNanos.get());
    }

    /**
     * 优雅停机（I4）：停收 → 排空 → 回填 + signal + join，顺序不能反。
     *
     * <p>先杀线程会把缓冲区里的件变成「永远等不到的 future」；排空超时则显式回填，让调用方立刻
     * 拿到可重试的结果，而不是干等 {@code waitTimeoutMs} 后猜。回填件数计入 {@code drainedFailed}，
     * 这是「停机丢了多少」的唯一读数。收尾把各分片线程 signal 出等待（{@code failRemaining} 置
     * {@code closed} 并 signal），再 {@code join(drainTimeout)}——线程只在 {@code closed} 且缓冲空时
     * 退出，join 返回即代表在途 flush 已收尾。{@code HikariDataSource} 的销毁晚于本方法
     * （Spring 按依赖逆序销毁），所以排空时落库用的连接仍然可用——不要调换顺序。
     */
    @PreDestroy
    public void close() {
        accepting = false;
        boolean drained = awaitIdle(config.drainTimeoutMs());
        int left = 0;
        for (ShardFlusher shard : shards) {
            int remaining = shard.failRemaining(drained ? "应用停机（竞态补收）" : "排空超时");
            if (drained) {
                // 正常排空后还会剩件，只可能是「close 与最后一个 offer 赛跑」漏进来的
                failed.addAndGet(remaining);
            } else {
                drainedFailed.addAndGet(remaining);
            }
            left += remaining;
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
        log.info("计数合并器已停止：drained={}, 剩余积压={}, 读数={}", drained, pending(), metrics());
    }

    // ── 落库路径 ──────────────────────────────────────────────────────────────

    /**
     * 摘批后的落库：按「动作 × 行主键」二次分组，一组一个事务。
     *
     * <p>一组一事务（不是一批一事务）：一组让锁集最小、失败只回滚一组、结果能精确回填到件；
     * 一批一事务会让几个不相干的行以不同顺序被锁，多出一片死锁面。
     */
    private void flushBatch(List<Pending> pendingList) {
        Map<GroupKey, List<Pending>> groups = new LinkedHashMap<>();
        for (Pending item : pendingList) {
            groups.computeIfAbsent(new GroupKey(item.event().action(), item.shardKey()),
                    key -> new ArrayList<>()).add(item);
        }
        try {
            for (Map.Entry<GroupKey, List<Pending>> entry : groups.entrySet()) {
                flushOneGroup(entry.getKey(), entry.getValue());
            }
        } finally {
            // 兜底：任何还没回填的件补 FAILED —— 绝不让等待者悬到超时（I1）
            for (Pending item : pendingList) {
                if (item.complete(MergeState.FAILED, null)) {
                    failed.incrementAndGet();
                }
            }
        }
    }

    /** 落库一组 + 回填 + 计数。落库方法的异常只吞掉本组，别的组照常跑 */
    private void flushOneGroup(GroupKey group, List<Pending> pendingList) {
        List<CountEvent> countEventList = new ArrayList<>(pendingList.size());
        for (Pending item : pendingList) {
            countEventList.add(item.event());
        }

        long startNanos = System.nanoTime();
        List<LikeState> resultList = null;
        try {
            resultList = flushHandler.flushGroup(group.action(), group.shardKey(), countEventList);
        } catch (RuntimeException e) {
            log.error("计数落库失败（整组失败，{} 件）：action={}, shardKey={}",
                    pendingList.size(), group.action(), group.shardKey(), e);
        } finally {
            batches.incrementAndGet();
            maxFlushNanos.accumulateAndGet(System.nanoTime() - startNanos, Math::max);
        }

        int resultCount = resultList == null ? 0 : resultList.size();
        if (resultCount > pendingList.size()) {
            log.warn("落库方法返回的结果比入参多（{} > {}）：action={}, shardKey={}，多出的忽略",
                    resultCount, pendingList.size(), group.action(), group.shardKey());
        }
        for (int i = 0; i < pendingList.size(); i++) {
            Pending item = pendingList.get(i);
            // 长度不足 / 该位是 null 都算「漏回填」：宁可补 FAILED，也不给调用方一个「成功但没有结果」
            boolean inbound = i < resultCount && resultList.get(i) != null;
            if (inbound && item.complete(resultList.get(i))) {
                flushed.incrementAndGet();
            } else if (item.complete(MergeState.FAILED, null)) {
                // 落库方法漏回填（null / 长度不足 / 空元素）：合并器补 FAILED，件不悬着
                failed.incrementAndGet();
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

    /** 二次分组的键：动作 × 行主键 */
    private static final class GroupKey {

        private final CountAction action;
        private final String shardKey;

        private GroupKey(CountAction action, String shardKey) {
            this.action = action;
            this.shardKey = shardKey;
        }

        public CountAction action() {
            return action;
        }

        public String shardKey() {
            return shardKey;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof GroupKey other)) return false;
            return Objects.equals(action, other.action)
                    && Objects.equals(shardKey, other.shardKey);
        }

        @Override
        public int hashCode() {
            return Objects.hash(action, shardKey);
        }

        @Override
        public String toString() {
            return "GroupKey[action=" + action + ", shardKey=" + shardKey + "]";
        }
    }
}
