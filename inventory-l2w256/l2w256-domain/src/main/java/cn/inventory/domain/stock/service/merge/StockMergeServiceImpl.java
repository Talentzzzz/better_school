package cn.inventory.domain.stock.service.merge;

import cn.inventory.domain.stock.adapter.repository.IStockRepository;
import cn.inventory.domain.stock.model.valobj.DeductRequestVO;
import cn.inventory.domain.stock.model.valobj.DeductResultVO;
import cn.inventory.domain.stock.model.valobj.MergeConfigVO;
import cn.inventory.types.enums.DeductStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * L2W256 合并扣减引擎：<b>固定 N 个分片缓冲区 + 入口驱动定时器 + 固定大小共享执行池</b>。
 *
 * <h2>一笔请求的一生</h2>
 * <pre>
 *   submit()
 *     ├─ 按 skuId & mask 路由到分片
 *     ├─ 塞进该分片缓冲区（首个请求排定 maxFlushDelayMillis 后的 flush；攒满 200 提前触发）
 *     └─ park 在自己的 future 上，最多等 waitTimeoutMs
 *                    ↓（定时器到点，由执行池线程接管）
 *   flush：整块摘走缓冲区 → 同 SKU 一条 UPDATE 扣 N 件 → 回填 future → 有积压则续排
 * </pre>
 *
 * <h2>为什么它的天花板是「线程数 ÷ 单次 flush 耗时」</h2>
 * 分片数决定不了 DB 并行度：真正并发的 flush 数 = min(活跃分片, 线程数, 连接池大小)。
 * 所以 flush 线程数<b>必须等于连接池大小</b>——大于池，线程抢连接排队；小于池，连接闲置。
 * （8 线程实测卡在 12.4k，正好 100% 跑满；提到 20 后多热点 +27%。这条约束在启动层强制校验。）
 *
 * <h2>分片数怎么定</h2>
 * 分片数 ≈ 峰值<b>并发热点数</b>，取 2 的幂。每片的 SKU 数 = 热点数 ÷ 分片数，
 * 所以分片数 ≥ 热点数才能保证「一批只碰一个热点行」的批纯度；
 * 但也不能乱加：256 片 ≈ 20MB 缓冲 + 每秒 5 万次定时器调度（约 10% 单核），1024 片会到 40%。
 */
@Slf4j
@Service
public class StockMergeServiceImpl implements IStockMergeService, DisposableBean {

    /** 排空等待的兜底探测间隔：正常由 flush 完成信号唤醒，这里只防信号丢失 */
    private static final long IDLE_PROBE_MS = 5;

    private final MergeConfigVO config;
    private final ShardBuffer[] shards;
    private final ScheduledThreadPoolExecutor scheduler;

    /** 排空通知：flush 每完成一批就 signalAll，reset / 停机不用轮询 */
    private final ReentrantLock idleLock = new ReentrantLock();
    private final Condition idleSignal = idleLock.newCondition();

    /** 是否接收新请求：停机第一步置 false，入口立刻开始拒绝（不再往缓冲区里塞） */
    private volatile boolean accepting = true;

    public StockMergeServiceImpl(IStockRepository stockRepository, MergeConfigVO mergeConfig) {
        this.config = mergeConfig;
        this.scheduler = new ScheduledThreadPoolExecutor(
                mergeConfig.getFlushThreads(),
                Thread.ofPlatform().daemon(true).name("stock-merge-flush-", 0).factory(),
                new ThreadPoolExecutor.AbortPolicy());
        // ★ 取消的定时任务立刻出队。攒满提前触发每秒会 cancel 上千个任务，
        //   不开这个策略，它们会一直躺在延时队列里等到原定窗口才被清理，
        //   256 分片 × 200/s 的规模下就是实打实的内存与调度开销。
        this.scheduler.setRemoveOnCancelPolicy(true);

        int shardCount = mergeConfig.getShardCount();
        this.shards = new ShardBuffer[shardCount];
        for (int i = 0; i < shardCount; i++) {
            this.shards[i] = new ShardBuffer(i, mergeConfig, scheduler, stockRepository::deductBatch, this::signalIdle);
        }

        log.info("L2W256 合并扣减器启动：shardCount={}(mask={}), maxFlushDelayMillis={}, maxBatch={}, waitTimeoutMs={}, "
                        + "shardCapacity={}, flushThreads={}, soldoutShortcut={}, drainTimeoutMs={}",
                shardCount, mergeConfig.mask(), mergeConfig.getMaxFlushDelayMillis(), mergeConfig.getMaxBatch(),
                mergeConfig.getWaitTimeoutMs(), mergeConfig.getShardCapacity(), mergeConfig.getFlushThreads(),
                mergeConfig.isSoldoutShortcut(), mergeConfig.getDrainTimeoutMs());
    }

    @Override
    public DeductResultVO submit(DeductRequestVO request) {
        if (!accepting) {
            // 停机窗口：明确拒绝（库存一定没动），别让调用方排进来等一个不会来的结果
            return DeductResultVO.of(request.getBizNo(), DeductStatus.REJECTED);
        }

        int shardNo = config.shardOf(request.getSkuId());
        CompletableFuture<DeductResultVO> future = new CompletableFuture<>();
        if (!shards[shardNo].offer(new MergeTask(request, future))) {
            // 背压：缓冲区满（或已停机）。立刻返回，不占用调用方的等待预算
            return DeductResultVO.of(request.getBizNo(), DeductStatus.REJECTED);
        }

        try {
            return future.get(config.getWaitTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // 请求仍在缓冲区里，之后大概率仍会落库 —— 对外必须表达为「处理中」而不是失败
            return DeductResultVO.of(request.getBizNo(), DeductStatus.TIMEOUT_UNKNOWN);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return DeductResultVO.of(request.getBizNo(), DeductStatus.ERROR);
        } catch (ExecutionException e) {
            // 兜底：正常路径下失败都是 complete(status) 而不是 completeExceptionally
            log.warn("合并扣减落库异常 bizNo={}", request.getBizNo(), e.getCause());
            return DeductResultVO.of(request.getBizNo(), DeductStatus.ERROR);
        }
    }

    @Override
    public int pendingCount() {
        int pending = 0;
        for (ShardBuffer shard : shards) {
            pending += shard.pendingSize();
        }
        return pending;
    }

    @Override
    public boolean awaitIdle() {
        return awaitIdle(config.getDrainTimeoutMs());
    }

    @Override
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

    /** 所有分片都空且无在途 flush（无锁：只读 volatile 镜像） */
    private boolean isIdle() {
        for (ShardBuffer shard : shards) {
            if (!shard.isIdle()) {
                return false;
            }
        }
        return true;
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

    /**
     * 优雅停机：先拒新请求 → 再排空 → 最后关执行池。
     *
     * <p>顺序不能反：先关池会把缓冲区里的请求变成「永远等不到的 future」。
     * 排空失败时显式失败剩余请求，让调用方立刻拿到可重试的结果，而不是干等 100ms 后猜。
     */
    @Override
    public void destroy() {
        accepting = false;
        boolean drained = awaitIdle(config.getDrainTimeoutMs());
        if (!drained) {
            for (ShardBuffer shard : shards) {
                shard.failRemaining(DeductStatus.ERROR, "应用停机");
            }
        }
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(config.getDrainTimeoutMs(), TimeUnit.MILLISECONDS)) {
                scheduler.shutdownNow();
                log.warn("合并执行池未在 {}ms 内结束，已强制中断", config.getDrainTimeoutMs());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            scheduler.shutdownNow();
        }
        log.info("L2W256 合并扣减器已停止：drained={}, 剩余积压={}", drained, pendingCount());
    }
}
