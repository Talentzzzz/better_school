package com.betterschool.infrastructure.merge.count;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 计数侧合并器：<b>per-invId leader 模型</b>（对齐数据库内核热点组提交，spec：合并引擎 per-invId
 * leader 改造）。没有分片数组、没有常驻 flush 线程——以「动作 × 行主键」为键的动态热点组表替代
 * 固定分片：第一条到达的请求惰性建组并成为 leader，<b>队列非空即发车</b>（没有收集窗口——收集窗
 * 就是落库耗时本身）；leader 带全队一次落库（一组一个事务不变，单批上界 {@code maxBatch}），
 * 发车即交出收集领导权——收集与落库流水线重叠；组空闲即乐观删除。点赞 / 取消赞 / 评论赞 / 浏览
 * 统一走这一套提交语义。
 *
 * <h2>一件请求的一生</h2>
 * <pre>
 *   commit(rowId, event)
 *     ├─ 按「动作 × 行主键」找组（惰性创建），锁内入队
 *     ├─ 领导权空缺 → 接管：队列有货就摘批（单批 ≤maxBatch），队列空则等新件 / 等落库结束
 *     ├─ 领导权被占 → park 在组条件变量上等终态（最多 waitTimeoutMs）
 *     └─ 缓冲满 / 停机中 → 本线程用同一个落库方法落「组大小 1」（{@link #degrade}）
 *                    ↓（队列非空即由 leader 在自己的请求线程上发车）
 *   flush：锁内摘批 → 锁内交收集领导权、占落库权 → 锁外落库（一组一个事务）→ 逐件回填
 *          → 队列空则乐观删除出 map；非空则叫醒等待者接班（接班次数可观测）
 * </pre>
 *
 * <h2>为什么没有收集窗口（改回去之前先读这段）</h2>
 * <p>对照物是数据库内核的组提交（PolarDB-X Inventory Hint）：内核的收集窗<b>就是 leader 持行锁
 * 处理的那段时间本身</b>——落库期间 follower 源源进队，提交即清空、立即下一轮，没有额外 sleep。
 * 本引擎同构，所以发车条件只有一条：<b>队列里有货就走</b>（{@code maxBatch} 只是单批上界）。
 * 两条结构安全性：① 串行形态下事务速率 = 1/flush 耗时 ≈ DB 单行事务周期 ⇒ <b>天然 ≤ DB 天花板</b>，
 * 节流阀就是 flush 耗时本身，不需要窗口「保护 DB」；② 合并比 = 到达速率 × flush 耗时，低速率
 * 每批 1 件（延迟最低）、高速率自动变大而事务速率恒为 1/flush，稳态自平衡。窗口唯一的理论残值
 * （突发头部聚拢）也不存在：那一刻队列本来就有货，发车一次性取 {@code min(maxBatch, size)}。
 * 决策与论证见 {@code backend/docs/architecture/merge-window-removal-spec.md}。
 *
 * <h2>浏览语义已翻转（统一等待语义）</h2>
 * 浏览与点赞共用 {@link #commit} 等结果——超时 = 结果未知（{@code TIMEOUT} → 上层
 * {@code RETRY_LATER} → 重放被明细唯一键吸收）。不再有「允许丢」，也不再有不分配 future 的
 * {@code submitAsync} 入口；两个入口两套等待模型、两套降级心智一并退役。
 *
 * <h2>背压与降级：引擎自己兜</h2>
 * 引擎不说 {@code REJECTED}。{@code commit} 在缓冲满 / 停机中时，用<b>同一个落库方法</b>在本线程落
 * 「组大小 1」——这两种情况件从未入队、必未落库，就地补一次是安全的；对调用方而言它只会看到
 * {@code COMPLETED / TIMEOUT / FAILED}，不必自己判断要不要降级。两套 SQL 迟早漂移，而漂移只在
 * 压力最大时暴露，所以降级必须复用同一个落库方法。
 *
 * <h2>五条不变量</h2>
 * I1 不丢件且恰一个终态（{@link Pending#complete} 幂等 + 漏回填补 {@code FAILED}）、
 * I2 同 key 单飞（落库权标志锁内置位，结构即保证）、I3 锁不跨 DB（临界区只碰内存）、
 * I4 停机三步（停收 → 排空 → 残余回填 {@code FAILED}；无后台线程可 join）、
 * I5 背压有界（满即不入队：入口就地降级；不扩容、不挤掉已入队件）。
 *
 * <h2>装配</h2>
 * {@code @Component}：参数来自 {@code bs-app} 的 {@code CountMergeConfig}，出口是
 * {@link CountFlushDispatcher}（按动作把一组交给拥有那张表的域仓储：社区仓储的点赞 / 取消赞 /
 * 评论赞 / 帖子浏览，交易仓储的商品浏览，跑腿仓储的跑腿浏览）。调用方有两个：
 * {@link CountMergeWriter}（点赞类）与 {@link ViewMergeWriter}（浏览）——现在两类写入走同一套
 * 等待语义，都只做「三态 → 域出口」的翻译。本类不认识 HTTP 语义。
 */
@Slf4j
@Component
public class CountMerger {

    /** 等待者条件等待的兜底探测间隔：正常由 flush 完成 / 接班信号唤醒，这里只防信号丢失 */
    private static final long WAKEUP_PROBE_MS = 5;

    private final MergeConfig config;
    private final ICountFlushHandler flushHandler;
    /** 「动作 × 行主键」→ 热点组：惰性创建、空闲即乐观删除 */
    private final Map<GroupKey, Group> groups = new ConcurrentHashMap<>();

    /** 排空通知：每走完一批就 signalAll，停机不用轮询 */
    private final ReentrantLock idleLock = new ReentrantLock();
    private final Condition idleSignal = idleLock.newCondition();

    /** 是否接收新件：停机第一步置 false，入口立刻开始拒绝（不再往队列里塞） */
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
    /** 接班次数：前任 leader 已发过车的组再次被接管（观测接班链活性） */
    private final AtomicLong handovers = new AtomicLong();

    /**
     * @param flushHandler 落库方法（实现是 {@link CountFlushDispatcher}）。{@code @Lazy} 不是可选项：
     *                     落库方法在 {@code CommunityRepository} / {@code TradeRepository} /
     *                     {@code ErrandRepository} 上，而这些仓储的写入入口又要调
     *                     {@link CountMergeWriter} / {@link ViewMergeWriter} → 本类 —— 出口与调用方
     *                     互相指着，不延迟到 flush 时解析就是循环依赖。
     *                     合并器本来也只在落库那一刻才用到它，构造期不需要真的把它拿到手
     */
    public CountMerger(MergeConfig config, @Lazy ICountFlushHandler flushHandler) {
        if (flushHandler == null) {
            throw new IllegalArgumentException("[配置错误] ICountFlushHandler 不能为 null：合并器没有出口等于丢弃所有件");
        }
        this.config = config;
        this.flushHandler = flushHandler;

        log.info("计数合并器启动（per-invId leader 模型）：发车条件=队列非空即发（无收集窗口）, "
                        + "maxBatch={}, waitTimeoutMs={}, groupCapacity={}, drainTimeoutMs={}",
                config.maxBatch(), config.waitTimeoutMs(),
                config.shardCapacity(), config.drainTimeoutMs());
    }

    /**
     * 提交一笔并等结果（点赞 / 取消赞 / 评论赞 / 浏览统一入口）：本类<b>唯一的对外入口</b>，
     * 入队、等待、兜底在一处闭环。叫 {@code commit} 而不是 {@code write}/{@code submit}：
     * 返回时这笔要么已随组提交、要么确定没提交，是「提交语义」。
     *
     * <p>缓冲满 / 停机中<b>不会返给调用方</b>：件从未入队、必未落库，就地用同一个落库方法落
     * 「组大小 1」（{@link #degrade}）。所以返回值只有三态——{@code COMPLETED} / {@code TIMEOUT} /
     * {@code FAILED}，调用方不必自己判断要不要降级。
     *
     * <p>超时收尾：等超时、准备返回 {@code TIMEOUT} 之前，若领导权空缺则自任 leader 清一次队
     * ——把自己的 {@code TIMEOUT} 收敛为 {@code COMPLETED}，杜绝孤儿批；领导权被占则如实返回
     * {@code TIMEOUT}（结果未知，件大概率仍会落库）。
     *
     * @param shardKey 这组要更新的那一行的<b>业务主键</b>（postId / commentId / productId 等
     *                 字符串），<b>由调用方显式给</b>
     * @param event    事件本体；合并器不解析它来推导 key
     */
    public MergeResult<LikeState> commit(String shardKey, CountEvent event) {
        GroupKey key = new GroupKey(event.action(), shardKey);
        Pending mine = new Pending(shardKey, event);
        Group group = groups.computeIfAbsent(key, k -> new Group(k, config));
        Group.EnqueueOutcome outcome = group.enqueue(mine, accepting);
        // 乐观删除竞态自愈：撞上刚被摘除的组就换组重建（同 key 永远收敛到同一个活组）
        while (outcome == Group.EnqueueOutcome.GROUP_REMOVED) {
            group = groups.computeIfAbsent(key, k -> new Group(k, config));
            outcome = group.enqueue(mine, accepting);
        }
        if (outcome != Group.EnqueueOutcome.ENQUEUED) {
            boolean full = outcome == Group.EnqueueOutcome.BUFFER_FULL;
            // 背压读数就落在这一处：件没进队列（随后就地降级），用户看不到，但调参要看得见
            if (full) {
                rejectedFull.incrementAndGet();
            } else {
                rejectedClosed.incrementAndGet();
            }
            if (group.tryRemoveIfIdle()) {
                groups.remove(key, group);
            }
            return degrade(shardKey, event, full ? "缓冲满" : "停机中");
        }
        accepted.incrementAndGet();

        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(config.waitTimeoutMs());
        // false = 刚经历过一次「接管但没发成车」（预算耗尽），此刻再接管只会原样再退——
        // 先等一个唤醒再试，避免接管-退出的自旋
        boolean mayPromote = true;
        while (true) {
            MergeResult<LikeState> settled = mine.resultIfSettled();
            if (settled != null) {
                return settled;
            }
            if (mayPromote && group.tryPromote()) {
                mayPromote = driveFlushCycles(group, mine, deadlineNanos);
                continue;
            }
            long remainNanos = deadlineNanos - System.nanoTime();
            if (remainNanos <= 0) {
                if (mayPromote && group.tryPromote()) {
                    mayPromote = driveFlushCycles(group, mine, deadlineNanos);
                    continue;
                }
                timedOut.incrementAndGet();
                // 件仍在队列里，之后大概率仍会落库 —— 对外是「结果未知」，不是失败
                return MergeResult.timeout();
            }
            group.awaitWakeup(Math.min(remainNanos, TimeUnit.MILLISECONDS.toNanos(WAKEUP_PROBE_MS)));
            mayPromote = true;
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
     * 当前缓冲件数（各组求和，无锁镜像）。只供 {@link #metrics()}、停机日志与同包测试做
     * 排空/背压断言用——近似读数，不做任何正确性决策，故不对外暴露。
     */
    int pending() {
        int total = 0;
        for (Group group : groups.values()) {
            total += group.pendingSize();
        }
        return total;
    }

    /**
     * 等排空（{@code close()} 与测试用）。
     *
     * <p>判据是「所有组队列都空且没有在途 flush」，由 flush 完成信号唤醒，探测间隔只防信号丢失。
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
                    idleSignal.awaitNanos(Math.min(remainNanos, TimeUnit.MILLISECONDS.toNanos(WAKEUP_PROBE_MS)));
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

    /** 读数快照（不依赖 micrometer，不开端点）。{@code activeGroups} / {@code handovers} 是
     * per-invId leader 模型的新增读数（批大小 / 组容量调参看它说话） */
    public MergeMetrics metrics() {
        return new MergeMetrics(pending(), accepted.get(), flushed.get(), batches.get(),
                rejectedFull.get(), rejectedClosed.get(), timedOut.get(), failed.get(),
                drainedFailed.get(), maxFlushNanos.get(), groups.size(), handovers.get());
    }

    /**
     * 优雅停机（I4）：停收 → 排空 → 残余回填 {@code FAILED}，顺序不能反。
     *
     * <p>停收后先叫醒所有在位 leader 与等待者：leader 立即发车排空、等待者接班清队，在途 leader
     * 与接班链排空即视为 idle。无后台线程可 join——发车都跑在调用方线程上。排空超时则把仍在
     * 队列里的件显式回填 {@code FAILED}，让调用方立刻拿到可重试的结果，而不是干等
     * {@code waitTimeoutMs} 后猜。回填件数计入 {@code drainedFailed}，这是「停机丢了多少」的唯一读数。
     * {@code HikariDataSource} 的销毁晚于本方法（Spring 按依赖逆序销毁），所以排空时落库用的连接
     * 仍然可用——不要调换顺序。
     */
    @PreDestroy
    public void close() {
        accepting = false;
        for (Group group : groups.values()) {
            group.markClosed();
        }
        boolean drained = awaitIdle(config.drainTimeoutMs());
        int left = 0;
        for (Group group : List.copyOf(groups.values())) {
            int remaining = group.failRemaining(drained ? "应用停机（竞态补收）" : "排空超时");
            if (drained) {
                // 正常排空后还会剩件，只可能是「close 与最后一个入队赛跑」漏进来的
                failed.addAndGet(remaining);
            } else {
                drainedFailed.addAndGet(remaining);
            }
            left += remaining;
            groups.remove(group.key(), group);
        }
        if (left > 0) {
            log.warn("停机回填 {} 件未落库（从未进入事务，可安全重试）", left);
        }
        log.info("计数合并器已停止：drained={}, 剩余积压={}, 读数={}", drained, pending(), metrics());
    }

    // ── 落库路径 ──────────────────────────────────────────────────────────────

    /**
     * 领导期：连续发车直到自己的件终态 / 预算耗尽 / 领导权被抢。
     *
     * @return true = 还值得再尝试接管；false = 这次接管没发成车（预算耗尽 / 件已被回填），
     *         先等一个唤醒再试
     */
    private boolean driveFlushCycles(Group group, Pending mine, long deadlineNanos) {
        while (!mine.isSettled()) {
            List<Pending> batch = group.takeForFlush(mine, deadlineNanos);
            if (batch == null) {
                // 收集段内部已交还领导权；这里补空组清理——队列空且无在途 flush 时没人再动它
                group.releaseLeadership();
                if (group.tryRemoveIfIdle()) {
                    groups.remove(group.key(), group);
                }
                return false;
            }
            flushOneGroup(group.key(), batch);
            if (group.afterFlush()) {
                groups.remove(group.key(), group);
            }
            signalIdle();
            if (!group.tryPromote()) {
                // 领导权被别的等待者抢走：回外层按等待者协议继续
                return true;
            }
        }
        // 自己的件已终态而退出：此刻手里可能还握着收集领导权（上一轮 tryPromote 成功），
        // 必须交还并叫醒等待者——带走这张票的话，队列里的件没人能接手，同组全部等到超时
        group.releaseLeadership();
        // 顺手摘掉空组：队列空且无在途 flush 时没人再动它，留着就是 map 里的常驻空壳
        if (group.tryRemoveIfIdle()) {
            groups.remove(group.key(), group);
        }
        return true;
    }

    /**
     * 一批落库：同组同「动作 × 行主键」，一组一个事务（不是一批一事务——一批一事务会让几个
     * 不相干的行以不同顺序被锁，多出一片死锁面）。
     */
    private void flushOneGroup(GroupKey key, List<Pending> pendingList) {
        List<CountEvent> countEventList = new ArrayList<>(pendingList.size());
        for (Pending item : pendingList) {
            countEventList.add(item.event());
        }

        long startNanos = System.nanoTime();
        List<LikeState> resultList = null;
        try {
            resultList = flushHandler.flushGroup(key.action(), key.rowKey(), countEventList);
        } catch (RuntimeException e) {
            log.error("计数落库失败（整组失败，{} 件）：action={}, rowKey={}",
                    pendingList.size(), key.action(), key.rowKey(), e);
        } finally {
            batches.incrementAndGet();
            maxFlushNanos.accumulateAndGet(System.nanoTime() - startNanos, Math::max);
        }

        int resultCount = resultList == null ? 0 : resultList.size();
        if (resultCount > pendingList.size()) {
            log.warn("落库方法返回的结果比入参多（{} > {}）：action={}, rowKey={}，多出的忽略",
                    resultCount, pendingList.size(), key.action(), key.rowKey());
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

    /** 所有组都没有积压、也没有在途 flush（无锁：只读 volatile 镜像） */
    private boolean isIdle() {
        for (Group group : groups.values()) {
            if (!group.isIdle()) {
                return false;
            }
        }
        return true;
    }

    /** 二次分组的键：动作 × 行主键（组身份；同 key 单飞、保序、一组一个事务都锚在它上） */
    private record GroupKey(CountAction action, String rowKey) {

        @Override
        public String toString() {
            return "GroupKey[action=" + action + ", rowKey=" + rowKey + "]";
        }
    }

    // ── 热点组：per-invId 队列 + 领导权 + 落库权单飞（与通用引擎 MergeGroup 同构） ──

    /**
     * 一个「动作 × 行主键」的热点组：动态创建、空闲即乐观删除。双层并发控制——map 层乐观探测，
     * 组内一把锁 + 一个条件变量；同 key 落库顺序 = 入队顺序（story 17），任一时刻至多一条
     * flush 在途（story 18）。
     */
    private final class Group {

        enum EnqueueOutcome {
            ENQUEUED,
            BUFFER_FULL,
            CLOSED,
            GROUP_REMOVED
        }

        private final GroupKey key;
        private final int maxBatch;
        private final int capacity;

        private final ReentrantLock lock = new ReentrantLock();
        /**
         * <b>follower 专用</b>条件：等「自己的件有终态」与「接班」。落库收尾 / 停机会 signalAll；
         * 进件<b>不</b>碰它——否则每进一件就惊醒同组全部等待者（惊群）。
         */
        private final Condition ready = lock.newCondition();
        /**
         * <b>collector 专用</b>条件：等「队列进件」与「落库结束」两种唤醒。只有收集段在它上面等，
         * 同一时刻至多一个（收集领导权互斥），所以 signalAll 最多叫醒一根线程——这就是去掉收集窗口
         * 之后「队列非空即发」能立刻发车、又不必广播给 follower 的原因。
         */
        private final Condition arrival = lock.newCondition();
        private final ArrayDeque<Pending> queue = new ArrayDeque<>();

        private volatile int size;
        private volatile boolean flushing;
        private volatile boolean closed;
        private boolean collectorActive;
        private boolean removed;
        private long departedBatches;

        Group(GroupKey key, MergeConfig config) {
            this.key = key;
            this.maxBatch = config.maxBatch();
            this.capacity = config.shardCapacity();
        }

        GroupKey key() {
            return key;
        }

        EnqueueOutcome enqueue(Pending item, boolean engineAccepting) {
            lock.lock();
            try {
                if (removed) {
                    return EnqueueOutcome.GROUP_REMOVED;
                }
                if (!engineAccepting || closed) {
                    return EnqueueOutcome.CLOSED;
                }
                if (queue.size() >= capacity) {
                    return EnqueueOutcome.BUFFER_FULL;
                }
                queue.addLast(item);
                size = queue.size();
                // 队列非空即发：新件到了就叫醒可能正空等的 collector（只 signal arrival，不碰跟随者）
                arrival.signalAll();
                return EnqueueOutcome.ENQUEUED;
            } finally {
                lock.unlock();
            }
        }

        boolean tryPromote() {
            lock.lock();
            try {
                if (removed || collectorActive) {
                    return false;
                }
                collectorActive = true;
                if (departedBatches > 0) {
                    // 前任 leader 已发过车：这次接管是「接班」（story 12 的观测读数）
                    handovers.incrementAndGet();
                }
                return true;
            } finally {
                lock.unlock();
            }
        }

        /**
         * 收集段：<b>队列非空就摘批发车</b>——没有窗口、也没有「攒满才发」，单批上界才是
         * {@code maxBatch} 的唯一职责（论证见 {@link CountMerger} 类注释「为什么没有收集窗口」）。
         *
         * <p><b>发车即交出收集领导权</b>（{@code collectorActive=false}）、同时置 {@code flushing}：
         * 收集与落库因此可以流水重叠，而同组仍至多一条 flush 在途。
         *
         * <p><b>队列空 / 落库在途时才等</b>：{@code arrival} 由进件、落库收尾、停机三方 signal；
         * 等待上限 = 调用方的 {@code waitTimeoutMs} 预算。预算耗尽就交权返回 {@code null}
         * （调用方那边收敛为 {@code TIMEOUT}，件仍在队列里、之后照常落库）。
         *
         * @param mine          本线程自己的件（已终态则立即收手）
         * @param deadlineNanos 调用方等待预算的绝对截止（{@code System.nanoTime()} 口径）
         * @return 摘走的批（非空）；{@code null} = 未发车（件已终态 / 预算耗尽 / 被中断），领导权已交还
         */
        List<Pending> takeForFlush(Pending mine, long deadlineNanos) {
            lock.lock();
            try {
                while (true) {
                    if (mine.isSettled()) {
                        yieldLeadership();
                        return null;
                    }
                    long now = System.nanoTime();
                    if (!queue.isEmpty() && !flushing) {
                        List<Pending> batch = takeBatch();
                        collectorActive = false;
                        flushing = true;
                        departedBatches++;
                        // 叫醒跟随者去竞争收集领导权：谁抢到谁在落库结束后接着发下一批
                        ready.signalAll();
                        return batch;
                    }
                    long remainNanos = deadlineNanos - now;
                    if (remainNanos <= 0) {
                        yieldLeadership();
                        return null;
                    }
                    try {
                        arrival.awaitNanos(Math.min(remainNanos,
                                TimeUnit.MILLISECONDS.toNanos(WAKEUP_PROBE_MS)));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        yieldLeadership();
                        return null;
                    }
                }
            } finally {
                lock.unlock();
            }
        }

        boolean afterFlush() {
            lock.lock();
            try {
                flushing = false;
                boolean retired = queue.isEmpty() && !collectorActive;
                if (retired) {
                    removed = true;
                }
                // follower 要醒（自己的件有终态了 / 该接班了），collector 也要醒（可以发下一批了）
                ready.signalAll();
                arrival.signalAll();
                return retired;
            } finally {
                lock.unlock();
            }
        }

        void markClosed() {
            lock.lock();
            try {
                closed = true;
                ready.signalAll();
                arrival.signalAll();
            } finally {
                lock.unlock();
            }
        }

        int failRemaining(String reason) {
            lock.lock();
            try {
                closed = true;
                removed = true;
                if (queue.isEmpty()) {
                    ready.signalAll();
                    arrival.signalAll();
                    return 0;
                }
                List<Pending> leftover = new ArrayList<>(queue);
                queue.clear();
                size = 0;
                int filled = 0;
                for (Pending item : leftover) {
                    if (item.complete(MergeState.FAILED, null)) {
                        filled++;
                    }
                }
                log.warn("组 {} 未落库即回填 {} 件（原因：{}）", key, leftover.size(), reason);
                ready.signalAll();
                arrival.signalAll();
                return filled;
            } finally {
                lock.unlock();
            }
        }

        boolean tryRemoveIfIdle() {
            lock.lock();
            try {
                if (!queue.isEmpty() || collectorActive || flushing) {
                    return false;
                }
                removed = true;
                return true;
            } finally {
                lock.unlock();
            }
        }

        void awaitWakeup(long nanos) {
            lock.lock();
            try {
                ready.awaitNanos(nanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                lock.unlock();
            }
        }

        int pendingSize() {
            return size;
        }

        boolean isIdle() {
            return size == 0 && !flushing;
        }

        private List<Pending> takeBatch() {
            int n = Math.min(maxBatch, queue.size());
            List<Pending> batch = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                batch.add(queue.pollFirst());
            }
            size = queue.size();
            return batch;
        }

        /** 锁内交还收集领导权（调用方必持组锁） */
        private void yieldLeadership() {
            collectorActive = false;
            ready.signalAll();
        }

        /** 无锁交还收集领导权（收集段之外的退出路径用）：放权并叫醒等待者接班 */
        void releaseLeadership() {
            lock.lock();
            try {
                yieldLeadership();
            } finally {
                lock.unlock();
            }
        }
    }
}
