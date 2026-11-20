package com.betterschool.infrastructure.adapter.port.chat.ws.support;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 会话存活注册表骨架：任何入站帧刷新 lastInboundAt，
 * 调度清扫对超过判死阈值无入站的会话主动 close（GOING_AWAY）+ 两参注销。
 * 客户端心跳 25s 一次，60s 判死容忍丢一次心跳（CONTEXT.md「心跳」口径）；
 * 骨架支撑后续第二个 WS 端点复用同一套判死机制
 */
@Slf4j
public abstract class AbstractHeartbeatSessionRegistry {

    /** 判死阈值：60s 无入站（客户端心跳 25s；公开供测试对齐注入时钟，避免常量漂移） */
    public static final long DEAD_THRESHOLD_MS = 60_000;

    /** 清扫周期 */
    private static final long SWEEP_PERIOD_SECONDS = 30;

    private final Map<WebSocketSession, TrackedSession> sessions = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduler;

    @PostConstruct
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, sweepThreadName());
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::sweepTick,
                SWEEP_PERIOD_SECONDS, SWEEP_PERIOD_SECONDS, TimeUnit.SECONDS);
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    public void track(WebSocketSession session, String userId, WebSocketSession decorated) {
        sessions.put(session, new TrackedSession(userId, decorated, System.currentTimeMillis()));
    }

    /** 任何入站帧刷新活跃时间（含 PING 与解析失败的帧） */
    public void touch(WebSocketSession session) {
        touch(session, System.currentTimeMillis());
    }

    /** 测试可注入时钟：生产路径走 {@link #touch(WebSocketSession)} */
    public void touch(WebSocketSession session, long now) {
        sessions.computeIfPresent(session, (key, tracked) -> new TrackedSession(tracked.userId(), tracked.decorated(), now));
    }

    public void untrack(WebSocketSession session) {
        sessions.remove(session);
    }

    void sweepTick() {
        sweep(System.currentTimeMillis(), DEAD_THRESHOLD_MS);
    }

    /** 测试可注入时钟：生产路径走 {@link #sweepTick()} */
    public void sweep(long now, long deadThresholdMs) {
        for (Map.Entry<WebSocketSession, TrackedSession> entry : sessions.entrySet()) {
            TrackedSession tracked = entry.getValue();
            if (now - tracked.lastInboundAt() <= deadThresholdMs) {
                continue;
            }
            if (sessions.remove(entry.getKey(), tracked)) {
                log.info("[{}] 判死清理 session={}, userId={}", getClass().getSimpleName(),
                        tracked.decorated().getId(), tracked.userId());
                onSessionDead(tracked.userId(), tracked.decorated());
            }
        }
    }

    /** 判死清理动作：注销连接注册表并关闭会话（close 事件随后触发 handler 的完整注销链路） */
    protected abstract void onSessionDead(String userId, WebSocketSession decorated);

    /** 清扫线程名（各端点前缀区分） */
    protected abstract String sweepThreadName();

    /** key = 容器原始 session；decorated 为并发写保护包装，推送与注销一律用它 */
    protected static final class TrackedSession {

        private final String userId;
        private final WebSocketSession decorated;
        private final long lastInboundAt;

        protected TrackedSession(String userId, WebSocketSession decorated, long lastInboundAt) {
            this.userId = userId;
            this.decorated = decorated;
            this.lastInboundAt = lastInboundAt;
        }

        public String userId() {
            return userId;
        }

        public WebSocketSession decorated() {
            return decorated;
        }

        public long lastInboundAt() {
            return lastInboundAt;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TrackedSession other)) return false;
            return lastInboundAt == other.lastInboundAt
                    && Objects.equals(userId, other.userId)
                    && Objects.equals(decorated, other.decorated);
        }

        @Override
        public int hashCode() {
            return Objects.hash(userId, decorated, lastInboundAt);
        }

        @Override
        public String toString() {
            return "TrackedSession[userId=" + userId + ", decorated=" + decorated + ", lastInboundAt=" + lastInboundAt + "]";
        }
    }
}
