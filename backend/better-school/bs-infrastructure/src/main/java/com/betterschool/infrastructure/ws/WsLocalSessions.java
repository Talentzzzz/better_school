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
 * 本实例连接的本地状态（infrastructure/ws 技术组件）：互踢表 + 心跳判死，一类的两份账。
 * 同一批连接的两份状态生命周期事件同源（建连/入站/断连），合并记账：
 * <ul>
 *   <li>互踢表 Map&lt;userId, session&gt;：单设备「一个用户一条连接」规则的执行者——
 *       新连接先 put 占位（put 原子返回被顶掉的旧连接，占位即互踢，后到者赢），
 *       旧连接由帧发送器互踢（KICK + close 4001）；注销走两参 remove
 *       （userId + session 身份比对），被互踢旧连接的迟到 close 不误删新连接</li>
 *   <li>心跳判死：任何入站帧 refresh 刷新活跃时间，守护线程每 30s sweepExpired，
 *       对超过判死阈值（60s）无入站的会话主动 close（GOING_AWAY）+ 注销。
 *       客户端心跳 25s 一次，60s 判死容忍丢一次心跳（CONTEXT.md「心跳」口径）</li>
 * </ul>
 * key 口径：互踢表与判死清理一律用并发写保护包装后的 session（decorated），
 * 心跳卡以容器原始 session 为 key——推送与注销一律走 decorated。
 * 装饰是组件内部细节：登记时经帧发送器装饰入册，注销/判死从卡上取回装饰件，
 * 装饰件不出本组件、不落握手属性
 */
@Slf4j
@Component
public class WsLocalSessions {

    /** 判死阈值：60s 无入站（客户端心跳 25s；公开供测试对齐注入时钟，避免常量漂移） */
    public static final long DEAD_THRESHOLD_MS = 60_000;

    /** 清扫周期 */
    private static final long SWEEP_PERIOD_SECONDS = 30;

    private static final String SWEEP_THREAD_NAME = "ws-session-sweep";

    /** 互踢表：userId → decorated session，「一个用户一条连接」 */
    private final Map<String, WebSocketSession> connections = new ConcurrentHashMap<>();

    /** 心跳卡：容器原始 session → 活跃时间（decorated 随卡携带，判死清理用） */
    private final Map<WebSocketSession, TrackedSession> tracked = new ConcurrentHashMap<>();

    private final IWsSender frameSender;

    private ScheduledExecutorService scheduler;

    public WsLocalSessions(IWsSender frameSender) {
        this.frameSender = frameSender;
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

    /** 建连登记：并发写保护装饰（组件内完成，装饰件不出本组件）→ 心跳卡建卡（初始记为刚活跃）+ 互踢占位——同 userId 已有连接时旧连接收 KICK + close(4001) */
    public void register(String userId, WebSocketSession session) {
        WebSocketSession decorated = frameSender.decorate(session);
        tracked.put(session, new TrackedSession(userId, decorated, System.currentTimeMillis()));
        WebSocketSession old = connections.put(userId, decorated);
        if (old != null && old != decorated) {
            frameSender.kick(old);
        }
    }

    /** 任何入站帧刷新活跃时间（含 PING 与解析失败的帧） */
    public void refresh(WebSocketSession session) {
        refresh(session, System.currentTimeMillis());
    }

    /** 测试可注入时钟：生产路径走 {@link #refresh(WebSocketSession)} */
    public void refresh(WebSocketSession session, long now) {
        tracked.computeIfPresent(session, (key, card) -> new TrackedSession(card.userId(), card.decorated(), now));
    }

    /** 断连注销：心跳去卡（装饰件随卡取回，不经握手属性交接）→ 两参互踢注销（迟到 close 不误删新连接） */
    public void revoke(String userId, WebSocketSession session) {
        TrackedSession card = tracked.remove(session);
        if (card != null && userId != null) {
            connections.remove(userId, card.decorated());
        }
    }

    /** 当前连接（推送本地命中检查用）：该用户在本实例的 decorated session，无则 null */
    public WebSocketSession get(String userId) {
        return connections.get(userId);
    }

    void sweepTick() {
        sweepExpired(System.currentTimeMillis(), DEAD_THRESHOLD_MS);
    }

    /** 清扫过期会话：最后入站距今超过阈值（60s 沉默）的，出册并判死清理 */
    public void sweepExpired(long now, long deadThresholdMs) {
        for (Map.Entry<WebSocketSession, TrackedSession> entry : tracked.entrySet()) {
            TrackedSession card = entry.getValue();
            if (now - card.lastInboundAt() <= deadThresholdMs) {
                continue;
            }
            // 按值比对移除：扫描途中被 refresh 换新卡的，本轮免死
            if (tracked.remove(entry.getKey(), card)) {
                log.info("[WsLocalSessions] 判死清理 session={}, userId={}",
                        card.decorated().getId(), card.userId());
                onSessionDead(card.userId(), card.decorated());
            }
        }
    }

    /** 判死清理动作：注销互踢表并关闭会话（close 事件随后触发 handler 的完整注销链路） */
    private void onSessionDead(String userId, WebSocketSession decorated) {
        connections.remove(userId, decorated);
        frameSender.closeQuietly(decorated, CloseStatus.GOING_AWAY);
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
