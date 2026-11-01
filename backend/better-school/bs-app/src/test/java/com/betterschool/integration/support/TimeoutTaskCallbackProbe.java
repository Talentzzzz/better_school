package com.betterschool.integration.support;

import com.betterschool.domain.timeout.adapter.port.callback.ITimeoutTaskCallbackPort;
import com.betterschool.domain.timeout.adapter.repository.ITimeoutRepository;
import com.betterschool.domain.timeout.model.entity.TimeoutTaskEntity;
import com.betterschool.domain.timeout.model.valobj.TaskKeyVO;
import com.betterschool.usecase.timeout.TimeoutTaskCallbackDispatcher;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 扫描用例的回调 seam 探针（超时中心内嵌化票 04）：{@code @Primary} 顶掉生产的进程内分发器，
 * 让用例能控制「回调成功 / 失败 / 回调途中把这一行取消」，并观察**回调时刻的真并发**。
 *
 * <p>默认 {@link Outcome#DELEGATE}——原样委派真分发器，所以既有用例（走 Dubbo Provider 打回调）
 * 的行为一字不变；只有显式摆了模式的用例才走桩。
 *
 * <p>为什么要探针而不是 Hikari 指标做并行度断言：并发上限的**因果链**是「扫描 case 只按
 * {@code timeout.scan.parallelism} 开线程 → 同时回调的项数 ≤ 并行度 → 同时占的连接 ≤ 并行度」。
 * 探针卡在链条中段（回调那一刻），量到的是确定性的整数；Hikari 的 active 是采样式观测量，
 * 受别的线程干扰。两者在集成测试里都量（见 {@code TimeoutTaskScanParallelismIntegrationTest}）。
 */
@Component
@Primary
public class TimeoutTaskCallbackProbe implements ITimeoutTaskCallbackPort {

    /** 回调处置方式 */
    public enum Outcome {
        /** 原样委派真分发器（默认；既有用例与真实链路的行为） */
        DELEGATE,
        /** 直接成功，不碰业务（只验扫描/结算机器时用，免造业务夹具） */
        SUCCEED,
        /** 全部按失败（进重试/死信） */
        FAIL,
    }

    private final TimeoutTaskCallbackDispatcher dispatcher;
    private final ITimeoutRepository timeoutRepository;

    private volatile Outcome outcome = Outcome.DELEGATE;
    private final Set<TaskKeyVO> failingKeys = ConcurrentHashMap.newKeySet();
    private final Set<TaskKeyVO> cancelingKeys = ConcurrentHashMap.newKeySet();
    private final List<TaskKeyVO> invocations = new CopyOnWriteArrayList<>();
    private final Set<String> threadNames = ConcurrentHashMap.newKeySet();

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger maxInFlight = new AtomicInteger();

    private volatile CyclicBarrier concurrencyBarrier;
    private volatile Duration concurrencyBarrierTimeout = Duration.ofSeconds(3);
    private final AtomicInteger barrierTrips = new AtomicInteger();
    private final AtomicInteger barrierTimeouts = new AtomicInteger();

    public TimeoutTaskCallbackProbe(TimeoutTaskCallbackDispatcher dispatcher,
                                    ITimeoutRepository timeoutRepository) {
        this.dispatcher = dispatcher;
        this.timeoutRepository = timeoutRepository;
    }

    @Override
    public void callback(TimeoutTaskEntity task) {
        TaskKeyVO key = TaskKeyVO.of(task.getTaskType(), task.getBizId());
        invocations.add(key);
        threadNames.add(Thread.currentThread().getName());
        int now = inFlight.incrementAndGet();
        maxInFlight.accumulateAndGet(now, Math::max);
        try {
            awaitBarrier();
            if (outcome == Outcome.FAIL || failingKeys.contains(key)) {
                throw new IllegalStateException("探针：这一项按失败回报 " + task.getTaskType() + ":" + task.getBizId());
            }
            if (cancelingKeys.remove(key)) {
                // 竞态用例：在「捞批」与「结算」之间的窗口里取消这一行——
                // 结算的三条 UPDATE 都带 status IN ('WAITING','RETRYING') 守卫，不该把它翻回来
                timeoutRepository.cancelActive(task.getTaskType(), task.getBizId());
            }
            if (outcome == Outcome.DELEGATE) {
                dispatcher.callback(task);
            }
        } finally {
            inFlight.decrementAndGet();
        }
    }

    /**
     * 摆上并发闩：回调必须凑满 {@code parties} 个同时在跑的项才放行（超时即记一次失败，不挂死用例）。
     * 这是并行度断言的**因果**一侧——真并行必然凑满，退回「一表一线程串行」则永远凑不满。
     */
    public void armConcurrencyBarrier(int parties) {
        concurrencyBarrier = new CyclicBarrier(parties);
    }

    /** 每次回调都成功（不碰业务） */
    public void stubSuccess() {
        outcome = Outcome.SUCCEED;
    }

    /** 每次回调都失败（进重试，第 16 次进死信） */
    public void stubFailure() {
        outcome = Outcome.FAIL;
    }

    /** 只有这些项失败，其余成功——验「逐项隔离：一条坏数据不拖垮同批」 */
    public void failOnly(String taskType, String bizId) {
        outcome = Outcome.SUCCEED;
        failingKeys.add(TaskKeyVO.of(taskType, bizId));
    }

    /** 回调这一项时先把它取消掉，再回报成功——验结算的行级守卫 */
    public void cancelOnCallback(String taskType, String bizId) {
        outcome = Outcome.SUCCEED;
        cancelingKeys.add(TaskKeyVO.of(taskType, bizId));
    }

    /** 回到默认（委派真分发器）并清空全部观测 */
    public void reset() {
        outcome = Outcome.DELEGATE;
        failingKeys.clear();
        cancelingKeys.clear();
        invocations.clear();
        threadNames.clear();
        inFlight.set(0);
        maxInFlight.set(0);
        concurrencyBarrier = null;
        barrierTrips.set(0);
        barrierTimeouts.set(0);
    }

    /** 回调被调用的项（按调用顺序） */
    public List<TaskKeyVO> invocations() {
        return List.copyOf(invocations);
    }

    public int invocationCount() {
        return invocations.size();
    }

    /** 某一项被回调了几次（同一行在一个 tick 里被重捞会 > 1） */
    public long invocationCountOf(String taskType, String bizId) {
        TaskKeyVO key = TaskKeyVO.of(taskType, bizId);
        return invocations.stream().filter(key::equals).count();
    }

    /** 回调时刻同时在跑的项数峰值（= 扫描 case 的实际并发；受 timeout.scan.parallelism 约束） */
    public int maxInFlight() {
        return maxInFlight.get();
    }

    /** 跑过回调的线程名（固定池命名 {@code timeout-scan-N}；去重后的大小 = 真开了几条线程） */
    public Set<String> callbackThreadNames() {
        return Set.copyOf(threadNames);
    }

    /** 并发闩凑满的次数（> 0 = 真并行发生过） */
    public int barrierTrips() {
        return barrierTrips.get();
    }

    /** 并发闩超时次数（> 0 = 没凑满，即并发上限不够或退化成串行） */
    public int barrierTimeouts() {
        return barrierTimeouts.get();
    }

    private void awaitBarrier() {
        CyclicBarrier barrier = concurrencyBarrier;
        if (barrier == null) {
            return;
        }
        try {
            barrier.await(concurrencyBarrierTimeout.toMillis(), TimeUnit.MILLISECONDS);
            barrierTrips.incrementAndGet();
        } catch (TimeoutException | java.util.concurrent.BrokenBarrierException e) {
            // 不抛出：用例要的是「没凑满」这个事实，不是把扫描整轮炸掉（超时后闩已破，后续 await 立刻返回）
            barrierTimeouts.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("并发闩等待被中断", e);
        }
    }
}
