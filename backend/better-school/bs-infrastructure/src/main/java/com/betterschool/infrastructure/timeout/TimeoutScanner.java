package com.betterschool.infrastructure.timeout;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

/**
 * 扫描器：认领槽位 → <b>有界并行</b>扫这些槽位 → 每槽循环拉批到空 → 批内逐项回调 → 按结果就地结算。
 * <p>
 * 调用方是 {@code trigger/job/TimeoutTaskScanJob}：它把 xxl-job 的分片参数与<b>到点动作</b>
 * （{@link ITimeoutHandler} 的装配实现）一起递进来——组件不认识任何业务，装配留在 trigger。
 * <p>
 * <b>有界并行</b>：认领 N 张表，但只按 {@code timeout.scan.parallelism}（默认 4）并发执行、其余<b>排队</b>
 * ——执行器就是一个宽度 = 并行度的固定池，<b>绝不为「一表一线程」开线程</b>。理由：单体 Hikari 池只有 10
 * 且与用户请求共用，独立中心时代敢一表一虚拟线程是因为它有专属的池 24（1.5× 槽位数）；在单体上照搬，
 * 16 槽位同时抢连接会把池占满，用户请求随即 {@code Connection is not available}（实测见 arch doc §4）。
 * 表数（分片粒度）与连接数（真并发上限）在这里解耦。
 * <p>
 * <b>一次 tick 一个 dueBefore</b>：所有槽位的捞批判据用同一个 tick 起始时刻。重试行结算时被推后到
 * 「结算时刻 + 退避」（≥ tick 起始 + 退避），所以同一轮一定不会再被捞起来——这是「循环拉批到空」
 * 的终止条件。不能用「每批现取 now」：tick 拖长到超过退避间隔时会重复回调同一行。
 * <p>
 * <b>hint 与线程</b>：分表 hint 是 ThreadLocal（实为 TransmittableThreadLocal，见票 01），
 * 由 {@link TimeoutTaskStore} 在每次 DAO 调用内自设自清，所以槽位任务跑在固定池的哪条线程上都成立。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TimeoutScanner {

    /** 单批上限：拉批到空的最小步长，与结算的三条批量 UPDATE 共同决定一个 tick 的写库次数 */
    @Value("${timeout.scan.batch-size:200}")
    private int batchSize;

    /**
     * 真并发上限（认领的表数与它解耦）：池宽度、也就是同一时刻在跑（并因此可能同时占连接）的槽位数。
     * 调大它等于调大扫描对连接池的占用峰值——池 10 是全应用共享，抬之前先看 arch doc §4 的实测。
     */
    @Value("${timeout.scan.parallelism:4}")
    private int parallelism;

    private final TimeoutTaskStore timeoutTaskStore;
    private final TimeoutSettler timeoutSettler;

    /**
     * 跑一轮扫描。
     *
     * @param shardIndex xxl-job 分片广播给本实例的序号
     * @param shardTotal 当前存活实例数（xxl-job 动态重算）
     * @param handler    到点动作（触发层装配；同一实例内所有 taskType 共用一个）
     */
    public ScanSummary scan(int shardIndex, int shardTotal, ITimeoutHandler handler) {
        if (shardTotal < 1 || shardIndex < 0 || shardIndex >= shardTotal) {
            throw new IllegalArgumentException("xxl-job 分片参数不合法：shardIndex=" + shardIndex
                    + ", shardTotal=" + shardTotal);
        }
        List<Integer> slots = claimedSlots(shardIndex, shardTotal);
        LocalDateTime dueBefore = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        Counters counters = new Counters();
        if (slots.isEmpty()) {
            // 实例数 > 槽位数时的正常形态（多出的实例空转），不是错误
            log.info("[超时中心] 本实例没有认领到槽位：shardIndex={}, shardTotal={}", shardIndex, shardTotal);
            return counters.summaryOf(0);
        }

        // 宽度 = min(并行度, 槽位数)：池大小就是真并发上限；认领的表比并行度多时，其余在队列里排队
        int width = Math.max(1, Math.min(parallelism, slots.size()));
        ThreadFactory threadFactory = Thread.ofPlatform().daemon(true).name("timeout-scan-", 0).factory();
        ExecutorService executor = Executors.newFixedThreadPool(width, threadFactory);
        try {
            // 槽位之间互不牵连：某一槽炸了其余照跑（awaitAll 会记账并吞掉异常）
            List<? extends Future<?>> slotTasks = slots.stream()
                    .map(slot -> executor.submit(() -> scanSlot(slot, dueBefore, handler, counters)))
                    .toList();
            awaitAll(slotTasks, "槽位扫描");
        } finally {
            // 每个 tick 一个池：tick 结束即回收，不常驻（tick 由 xxl-job 的 DISCARD_LATER 保证不重叠）
            executor.shutdown();
        }
        return counters.summaryOf(slots.size());
    }

    /**
     * 槽位认领：{@code g % shardTotal == shardIndex} 且 {@code g < TOTAL}。
     * <p>
     * 只吃<b>全局槽位号</b>——与「库 × 表」两维无关（将来 {@code dbCount > 1} 时这段一行都不用改）。
     */
    private static List<Integer> claimedSlots(int shardIndex, int shardTotal) {
        return IntStream.range(0, TimeoutConstants.TOTAL_SLOTS)
                .filter(slot -> slot % shardTotal == shardIndex)
                .boxed()
                .toList();
    }

    /** 单槽：循环拉批到空（无单 tick 批数上限），每批逐项回调后就地结算 */
    private void scanSlot(int slot, LocalDateTime dueBefore, ITimeoutHandler handler, Counters counters) {
        while (true) {
            List<TimeoutTaskEntity> batch = timeoutTaskStore.selectDueBatch(slot, dueBefore, batchSize);
            if (batch.isEmpty()) {
                return;
            }
            counters.batchCount.incrementAndGet();
            counters.taskCount.addAndGet(batch.size());
            // 批内每一行都必须被结算（settle 覆盖全批）——这是下一轮 fetch 不会捞回同一批的前提；
            // 回调在这一步逐项做（网络/DB 都在这一条槽位线程上串行），结算紧随其后
            settle(batch, callback(batch, handler, slot), counters);
        }
    }

    /**
     * 批内逐项回调，返回成功清单（失败项不在清单里 → 结算时按失败重投）。
     * <p>
     * <b>逐项隔离</b>：一项抛异常只让这一项算失败，同批其余项照常回调、照常结算——
     * 一条坏数据（未注册的 taskType、形状不对的 bizId、业务侧的偶发异常）不该拖住整批。
     */
    private CallbackReport callback(List<TimeoutTaskEntity> batch, ITimeoutHandler handler, int slot) {
        Set<TaskKey> succeeded = new HashSet<>(batch.size());
        for (TimeoutTaskEntity timeoutTaskEntity : batch) {
            try {
                handler.fire(timeoutTaskEntity);
                succeeded.add(TaskKey.of(timeoutTaskEntity.getTaskType(), timeoutTaskEntity.getBizId()));
            } catch (Exception e) {
                log.error("[超时中心告警] 回调失败，本项按失败重投：taskType={}, bizId={}, 槽位={}",
                        timeoutTaskEntity.getTaskType(), timeoutTaskEntity.getBizId(), slot, e);
            }
        }
        return CallbackReport.of(succeeded);
    }

    /** 就地结算一批（在槽位线程上跑；三桶各一条条件 UPDATE，不带事务） */
    private void settle(List<TimeoutTaskEntity> batch, CallbackReport report, Counters counters) {
        SettleOutcome settleOutcome = timeoutSettler.settle(batch, report);
        counters.successCount.addAndGet(settleOutcome.getSuccessCount());
        counters.retryCount.addAndGet(settleOutcome.getRetryCount());
        counters.deadCount.addAndGet(settleOutcome.getDeadCount());
    }

    /** 等一批槽位任务收口：异常就地记账，不向上冒（一个槽失败不该毁掉整个 tick，下轮重扫补上） */
    private static void awaitAll(List<? extends Future<?>> futures, String what) {
        for (Future<?> future : futures) {
            try {
                future.get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(what + "被中断", e);
            } catch (ExecutionException e) {
                log.error("[超时中心告警] {}任务异常退出，本轮跳过它、下轮重扫补上", what, e.getCause());
            }
        }
    }

    /** 本轮账本：槽位任务并行累加，最后一次性收成 {@link ScanSummary} */
    private static final class Counters {

        private final AtomicInteger batchCount = new AtomicInteger();
        private final AtomicInteger taskCount = new AtomicInteger();
        private final AtomicInteger successCount = new AtomicInteger();
        private final AtomicInteger retryCount = new AtomicInteger();
        private final AtomicInteger deadCount = new AtomicInteger();

        private ScanSummary summaryOf(int slotCount) {
            return new ScanSummary(slotCount, batchCount.get(), taskCount.get(),
                    successCount.get(), retryCount.get(), deadCount.get());
        }
    }
}
