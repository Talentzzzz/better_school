package com.betterschool.integration.support;

import com.betterschool.types.concurrent.VirtualThreadFanout;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 调度扇出 seam 断言的测试探针（spec §5.2 #3）：{@code @Primary} 顶掉生产的扇出模板，
 * 在**扇出项真正执行的那一刻**（即项所在的线程上）记下 {@code isVirtual()} 与线程名，再原样委派。
 *
 * <p>断言对象是扇出项、不是 job handler 本身——handler 跑在 xxl-job 的 {@code JobThread} 平台线程上，
 * 那是 xxl 自有池，本 effort 不改。探针只在 test classpath，生产打包不含。
 *
 * <p>它同时兜两条退回路径（spec §5.3）：扇出被改回串行 ⇒ 探针一次都不会被调用（计数为 0）；
 * 扇出被换成平台线程池 ⇒ 项计数涨而平台线程项计数也涨。
 */
@Component
@Primary
public class VtJobFanoutProbe extends VirtualThreadFanout {

    private static final AtomicInteger ITEM_COUNT = new AtomicInteger();
    private static final AtomicInteger PLATFORM_ITEM_COUNT = new AtomicInteger();
    private static final Set<String> THREAD_NAMES = ConcurrentHashMap.newKeySet();

    @Override
    public <T> void forEach(String namePrefix, List<T> items, Consumer<T> action) {
        super.forEach(namePrefix, items, item -> {
            recordItem();
            action.accept(item);
        });
    }

    @Override
    public <T, R> List<R> map(String namePrefix, List<T> items, Function<T, R> action) {
        return super.map(namePrefix, items, item -> {
            recordItem();
            return action.apply(item);
        });
    }

    private static void recordItem() {
        if (!Thread.currentThread().isVirtual()) {
            PLATFORM_ITEM_COUNT.incrementAndGet();
        }
        THREAD_NAMES.add(Thread.currentThread().getName());
        ITEM_COUNT.incrementAndGet();
    }

    public static void reset() {
        ITEM_COUNT.set(0);
        PLATFORM_ITEM_COUNT.set(0);
        THREAD_NAMES.clear();
    }

    /** 自上次 {@link #reset()} 起扇出项被执行的次数；为 0 = 该处根本没走扇出（改回串行了） */
    public static int itemCount() {
        return ITEM_COUNT.get();
    }

    /** 其中跑在平台线程上的项数；应恒为 0 */
    public static int platformItemCount() {
        return PLATFORM_ITEM_COUNT.get();
    }

    /** 观察到的项线程名（校验模板的命名规范：{@code 前缀 + 自增序号}） */
    public static Set<String> threadNames() {
        return Set.copyOf(THREAD_NAMES);
    }
}
