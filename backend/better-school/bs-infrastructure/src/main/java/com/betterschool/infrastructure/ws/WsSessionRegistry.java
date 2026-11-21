package com.betterschool.infrastructure.ws;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * chat 会话存活注册表：心跳判死——任何入站帧 refresh 刷新活跃时间，
 * 守护线程每 30s sweepExpired，对超过判死阈值（60s）无入站的会话主动 close（GOING_AWAY）+ 注销。
 * 客户端心跳 25s 一次，60s 判死容忍丢一次心跳（CONTEXT.md「心跳」口径）
 */
@Slf4j
@Component
public class WsSessionRegistry {

    /** 判死阈值：60s 无入站（客户端心跳 25s；公开供测试对齐注入时钟，避免常量漂移） */
    public static final long DEAD_THRESHOLD_MS = 60_000;

    /** 清扫周期 */
    private static final long SWEEP_PERIOD_SECONDS = 30;

    private static final String SWEEP_THREAD_NAME = "ws-session-sweep";

    private final Map<WebSocketSession, TrackedSession> sessions = new ConcurrentHashMap<>();

    private final WsPusher pusher;

    private final WsConnectionRegistry connectionRegistry;

    private ScheduledExecutorService scheduler;

    public WsSessionRegistry(WsPusher pusher, WsConnectionRegistry connectionRegistry) {
        this.pusher = pusher;
        this.connectionRegistry = connectionRegistry;
    }

    @PostConstruct
    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, SWEEP_THREAD_NAME);
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

    /** 存活登记：给新会话建卡（初始记为刚活跃），此后每条入站帧 refresh 续命，60s 沉默判死 */
    public void register(WebSocketSession session, String userId, WebSocketSession decorated) {
        sessions.put(session, new TrackedSession(userId, decorated, System.currentTimeMillis()));
    }

    /** 任何入站帧刷新活跃时间（含 PING 与解析失败的帧） */
    public void refresh(WebSocketSession session) {
        refresh(session, System.currentTimeMillis());
    }

    /** 测试可注入时钟：生产路径走 {@link #refresh(WebSocketSession)} */
    public void refresh(WebSocketSession session, long now) {
        sessions.computeIfPresent(session, (key, tracked) -> new TrackedSession(tracked.userId(), tracked.decorated(), now));
    }

    /** 注销：会话正常关闭，从此与判死无关 */
    public void unregister(WebSocketSession session) {
        sessions.remove(session);
    }

    void sweepTick() {
        sweepExpired(System.currentTimeMillis(), DEAD_THRESHOLD_MS);
    }

    /** 清扫过期会话：最后入站距今超过阈值（60s 沉默）的，出册并判死清理 */
    public void sweepExpired(long now, long deadThresholdMs) {
        for (Map.Entry<WebSocketSession, TrackedSession> entry : sessions.entrySet()) {
            TrackedSession tracked = entry.getValue();
            if (now - tracked.lastInboundAt() <= deadThresholdMs) {
                continue;
            }
            // 按值比对移除：扫描途中被 refresh 换新卡的，本轮免死
            if (sessions.remove(entry.getKey(), tracked)) {
                log.info("[WsSessionRegistry] 判死清理 session={}, userId={}",
                        tracked.decorated().getId(), tracked.userId());
                onSessionDead(tracked.userId(), tracked.decorated());
            }
        }
    }

    /** 判死清理动作：注销连接注册表并关闭会话（close 事件随后触发 handler 的完整注销链路） */
    private void onSessionDead(String userId, WebSocketSession decorated) {
        connectionRegistry.unregister(userId, decorated);
        pusher.closeQuietly(decorated, CloseStatus.GOING_AWAY);
    }

    /** key = 容器原始 session；decorated 为并发写保护包装，推送与注销一律用它 */
    private static final class TrackedSession {

        private final String userId;
        private final WebSocketSession decorated;
        private final long lastInboundAt;

        TrackedSession(String userId, WebSocketSession decorated, long lastInboundAt) {
            this.userId = userId;
            this.decorated = decorated;
            this.lastInboundAt = lastInboundAt;
        }

        String userId() {
            return userId;
        }

        WebSocketSession decorated() {
            return decorated;
        }

        long lastInboundAt() {
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
