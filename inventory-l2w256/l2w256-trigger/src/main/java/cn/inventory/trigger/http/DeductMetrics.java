package cn.inventory.trigger.http;

import cn.inventory.types.enums.DeductStatus;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 进程内结果计数器（不落库，随进程重启清零）
 *
 * <p>为什么必须有：压测结束只看到 TPS 是没法下结论的——同样是 20k TPS，
 * 「3% 超时」和「3% 库存不足」是两个完全不同的系统。这里按状态分桶累计，
 * 并对异常按「可判读的桶」再分一次（锁等待超时 / 死锁 / 池排队 / 驱动超时 / SQL 错误码），
 * 因为把 1205 和 socket 超时混成一个 ERROR，等于没记。
 *
 * <p>并发安全：{@link EnumMap} 在构造期把所有 key 填满，之后只读 key、只改 {@link LongAdder} 的值，
 * 不存在结构性修改，所以无需额外同步。
 */
@Component
public class DeductMetrics {

    private final Map<DeductStatus, LongAdder> counters = new EnumMap<>(DeductStatus.class);
    private final Map<String, LongAdder> errorBuckets = new ConcurrentHashMap<>();

    public DeductMetrics() {
        for (DeductStatus status : DeductStatus.values()) {
            counters.put(status, new LongAdder());
        }
    }

    public void record(DeductStatus status) {
        counters.get(status).increment();
    }

    /** 记录一个异常桶，如 {@code ERROR_LOCK_WAIT_TIMEOUT} / {@code ERROR_POOL_TIMEOUT} */
    public void recordError(String bucket) {
        errorBuckets.computeIfAbsent(bucket, key -> new LongAdder()).increment();
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> result = new LinkedHashMap<>();
        counters.forEach((status, counter) -> result.put(status.name(), counter.sum()));
        return result;
    }

    public Map<String, Long> errorSnapshot() {
        Map<String, Long> result = new LinkedHashMap<>();
        errorBuckets.forEach((bucket, counter) -> result.put(bucket, counter.sum()));
        return result;
    }

    public void clear() {
        counters.values().forEach(LongAdder::reset);
        errorBuckets.clear();
    }
}
