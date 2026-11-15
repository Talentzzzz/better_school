package com.betterschool.loadtest.inventory.metrics;

import com.betterschool.loadtest.inventory.config.RedisKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Objects;

/**
 * 当前轮次 id 的本地缓存（200ms）。
 * <p>
 * 作用：reset 会换一个新 runId，上一轮还在队列里的 MQ 消息带着旧 runId，消费者据此直接丢弃，
 * 避免「上一轮的扣减打到新一轮的台账上」把不变量打歪。
 * <p>
 * 每条消息都读一次 Redis 会给消费链路凭空加一次 RTT，所以缓存 200ms —— 代价是 reset 后最多
 * 200ms 内的迟到消息仍会被应用，配合「reset 后等 3 秒再开压」的节奏，实际不会命中。
 */
@Component
public class RunIdResolver {

    private static final long TTL_MS = 200L;

    private final StringRedisTemplate redis;
    private final ConcurrentHashMap<Long, Cached> cache = new ConcurrentHashMap<>();

    public RunIdResolver(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public String current(long skuId) {
        long now = System.currentTimeMillis();
        Cached cached = cache.get(skuId);
        if (cached != null && cached.until > now) {
            return cached.value;
        }
        String value = redis.opsForValue().get(RedisKeys.run(skuId));
        String resolved = value == null ? "" : value;
        cache.put(skuId, new Cached(resolved, now + TTL_MS));
        return resolved;
    }

    public boolean matches(String messageRunId, long skuId) {
        if (messageRunId == null || messageRunId.isEmpty()) {
            return false;
        }
        return messageRunId.equals(current(skuId));
    }

    public void invalidate(long skuId) {
        cache.remove(skuId);
    }

    private static final class Cached {

        private final String value;
        private final long until;

        private Cached(String value, long until) {
            this.value = value;
            this.until = until;
        }

        public String value() {
            return value;
        }

        public long until() {
            return until;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Cached other)) return false;
            return until == other.until && Objects.equals(value, other.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(value, until);
        }

        @Override
        public String toString() {
            return "Cached[value=" + value + ", until=" + until + "]";
        }
    }
}
}
