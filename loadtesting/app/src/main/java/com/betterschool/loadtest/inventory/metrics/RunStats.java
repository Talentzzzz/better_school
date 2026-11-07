package com.betterschool.loadtest.inventory.metrics;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 应用侧计数（不落库、不依赖 Micrometer）。reset 时清零，stats 接口直接读。
 * 计数器和 DB 台账对照，就能看出「受理了多少 / 真正落库了多少」的缺口。
 */
@Component
public class RunStats {

    private final ConcurrentHashMap<String, AtomicLong> counters = new ConcurrentHashMap<>();
    private volatile long resetAt = 0L;

    public void inc(String key) {
        counters.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
    }

    public void add(String key, long delta) {
        counters.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(delta);
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> out = new TreeMap<>();
        counters.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }

    public long resetAt() {
        return resetAt;
    }

    public void reset() {
        counters.clear();
        resetAt = System.currentTimeMillis();
    }
}
