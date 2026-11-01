package com.betterschool.types.concurrent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 调度扇出：把「一批任务串行跑」换成「一批任务各跑一条虚拟线程」的唯一落地处。
 * <p>
 * 四条硬规矩就在这里，各处调用点不再各写一遍：
 * <ol>
 *   <li><b>并发宽度 = 批大小</b>，不加信号量——上限由连接池兜；</li>
 *   <li><b>执行器即用即关</b>：粒度 = 一次批调用，VT 执行器不是池、不能常驻（常驻的代价不是性能，
 *       是「全进程共享的无界池」——任何一处忘了设界就把压力串到别人身上）；</li>
 *   <li><b>结果回调用线程汇总</b>（{@link #map} 返回列表与入参按下标一一对应），不靠并发容器凑；</li>
 *   <li><b>项内异常吞并记账、不向上冒</b>（批量信封为部分失败设计，一条坏数据不该拖住整批）。</li>
 * </ol>
 * <p>
 * ⚠️ 调用会等到**全部**项结束（{@code ExecutorService.close()} 是无限等待）：<b>项内必须自带超时</b>
 * （DB {@code connection-timeout} / RPC 超时 / 调度超时），否则一项卡住就把调用点永远挂住。
 * <p>
 * 线程命名：一律 {@code Thread.ofVirtual().name(prefix, 0).factory()}，日志的线程列因此可排障。
 */
public class VirtualThreadFanout {

    private static final Logger log = LoggerFactory.getLogger(VirtualThreadFanout.class);

    /** 逐项执行、不回结果（项内自己吞异常时用这个） */
    public <T> void forEach(String namePrefix, List<T> items, Consumer<T> action) {
        run(namePrefix, items, item -> {
            action.accept(item);
            return null;
        });
    }

    /** 逐项执行并回结果：返回列表与入参**按下标一一对应**，执行失败的槽位为 null */
    public <T, R> List<R> map(String namePrefix, List<T> items, Function<T, R> action) {
        return run(namePrefix, items, action);
    }

    private <T, R> List<R> run(String namePrefix, List<T> items, Function<T, R> action) {
        if (items.isEmpty()) {
            return List.of();
        }
        ThreadFactory factory = Thread.ofVirtual().name(namePrefix, 0).factory();
        List<R> results = new ArrayList<>(Collections.nCopies(items.size(), null));
        try (ExecutorService executor = Executors.newThreadPerTaskExecutor(factory)) {
            List<Future<R>> futures = new ArrayList<>(items.size());
            for (T item : items) {
                futures.add(executor.submit(() -> invokeItem(namePrefix, item, action)));
            }
            for (int i = 0; i < futures.size(); i++) {
                results.set(i, awaitItem(namePrefix, futures.get(i)));
            }
        }
        return results;
    }

    private <T, R> R invokeItem(String namePrefix, T item, Function<T, R> action) {
        try {
            return action.apply(item);
        } catch (Exception e) {
            log.error("[VtFanout] 扇出项执行失败，本项跳过不影响同批其他项 prefix={} item={}", namePrefix, item, e);
            return null;
        }
    }

    private <R> R awaitItem(String namePrefix, Future<R> future) {
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[VtFanout] 扇出等待被中断 prefix={}", namePrefix, e);
            return null;
        } catch (ExecutionException e) {
            // 项内 Exception 已在 invokeItem 兜住：能走到这里的是 Error（OOM 之类）——规矩 4 只管「项内异常」，
            // 不覆盖 Error，所以原样抛出去，别把进程级故障悄悄吞成「这一项跳过」
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            log.error("[VtFanout] 扇出项异常 prefix={}", namePrefix, e.getCause());
            return null;
        }
    }
}
