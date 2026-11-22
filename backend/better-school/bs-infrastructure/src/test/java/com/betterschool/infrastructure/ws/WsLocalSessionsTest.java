package com.betterschool.infrastructure.ws;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 本实例连接本地状态单元测试（票 04）：互踢占位、两参注销防误删、
 * 入站刷新活跃时间、60s 无入站判死清理。
 * 装饰是组件内部细节：注册走两参入口，装饰件由帧发送器桩出
 */
public class WsLocalSessionsTest {

    private static final long DEAD_THRESHOLD_MS = 60_000;

    private final IWsSender frameSender = mock(IWsSender.class);
    private final WsLocalSessions sessions = new WsLocalSessions(frameSender);

    /** 组件内登记入口：桩出帧发送器的装饰结果，装饰件由测试指定以便断言 */
    private void register(String userId, WebSocketSession raw, WebSocketSession decorated) {
        when(frameSender.decorate(raw)).thenReturn(decorated);
        sessions.register(userId, raw);
    }

    // ---------- 互踢表：单设备占位即互踢 ----------

    @Test
    void registerNewConnection_noKick() {
        WebSocketSession decorated = mock(WebSocketSession.class);
        register("u1", mock(WebSocketSession.class), decorated);
        assertThat(sessions.get("u1")).isSameAs(decorated);
        verify(frameSender, never()).kick(any());
    }

    @Test
    void registerSameUser_takeoverKicksOldSession() {
        WebSocketSession old = mock(WebSocketSession.class);
        WebSocketSession fresh = mock(WebSocketSession.class);
        register("u1", mock(WebSocketSession.class), old);

        register("u1", mock(WebSocketSession.class), fresh);

        assertThat(sessions.get("u1")).isSameAs(fresh);
        verify(frameSender).kick(old);
    }

    @Test
    void differentUsers_noKick() {
        register("u1", mock(WebSocketSession.class), mock(WebSocketSession.class));
        register("u2", mock(WebSocketSession.class), mock(WebSocketSession.class));
        verify(frameSender, never()).kick(any());
    }

    @Test
    void revoke_matchingSession_removes() {
        WebSocketSession raw = mock(WebSocketSession.class);
        WebSocketSession decorated = mock(WebSocketSession.class);
        register("u1", raw, decorated);

        sessions.revoke("u1", raw);
        assertThat(sessions.get("u1")).isNull();
    }

    @Test
    void revoke_staleSessionAfterTakeover_keepsNewConnection() {
        WebSocketSession oldRaw = mock(WebSocketSession.class);
        WebSocketSession freshRaw = mock(WebSocketSession.class);
        WebSocketSession old = mock(WebSocketSession.class);
        WebSocketSession fresh = mock(WebSocketSession.class);
        register("u1", oldRaw, old);
        register("u1", freshRaw, fresh);

        // 旧连接迟到的 close 回调：按卡上装饰件两参注销不匹配，新连接不被误删
        sessions.revoke("u1", oldRaw);
        assertThat(sessions.get("u1")).isSameAs(fresh);

        // 新连接自己的 close 才真正注销
        sessions.revoke("u1", freshRaw);
        assertThat(sessions.get("u1")).isNull();
    }

    @Test
    void revoke_unknownUserOrSession_noop() {
        WebSocketSession raw = mock(WebSocketSession.class);
        WebSocketSession decorated = mock(WebSocketSession.class);
        register("u1", raw, decorated);

        sessions.revoke("u999", mock(WebSocketSession.class));
        // 陌生 session（账本无卡）注销不动现有连接
        sessions.revoke("u1", mock(WebSocketSession.class));
        assertThat(sessions.get("u1")).isSameAs(decorated);
    }

    @Test
    void reRegisterAfterClose_allowed() {
        WebSocketSession firstRaw = mock(WebSocketSession.class);
        WebSocketSession first = mock(WebSocketSession.class);
        register("u1", firstRaw, first);
        sessions.revoke("u1", firstRaw);

        WebSocketSession second = mock(WebSocketSession.class);
        register("u1", mock(WebSocketSession.class), second);
        verify(frameSender, never()).kick(any());
        assertThat(sessions.get("u1")).isSameAs(second);
    }

    // ---------- 心跳判死：60s 无入站清扫 ----------

    @Test
    void sweepExpired_recentlyActive_notKilled() {
        WebSocketSession raw = mock(WebSocketSession.class);
        WebSocketSession decorated = mock(WebSocketSession.class);
        register("u1", raw, decorated);
        sessions.refresh(raw, 100_000L);

        sessions.sweepExpired(120_000L, DEAD_THRESHOLD_MS);

        verify(frameSender, never()).closeQuietly(any(), any(CloseStatus.class));
        assertThat(sessions.get("u1")).isSameAs(decorated);
    }

    @Test
    void sweepExpired_silentBeyondThreshold_killed() {
        WebSocketSession raw = mock(WebSocketSession.class);
        WebSocketSession decorated = mock(WebSocketSession.class);
        register("u1", raw, decorated);
        sessions.refresh(raw, 100_000L);

        // 最后入站 100s，now=161s，间隔 61s > 60s 判死
        sessions.sweepExpired(161_000L, DEAD_THRESHOLD_MS);

        verify(frameSender).closeQuietly(decorated, CloseStatus.GOING_AWAY);
        assertThat(sessions.get("u1")).isNull();
        // 判死后条目已清理，重复清扫无二次动作
        sessions.sweepExpired(200_000L, DEAD_THRESHOLD_MS);
        verify(frameSender).closeQuietly(decorated, CloseStatus.GOING_AWAY);
    }

    @Test
    void refresh_refreshesLastInbound_deferDeath() {
        WebSocketSession raw = mock(WebSocketSession.class);
        WebSocketSession decorated = mock(WebSocketSession.class);
        register("u1", raw, decorated);
        sessions.refresh(raw, 100_000L);

        // 55s 时心跳刷新，再过 30s 不判死
        sessions.refresh(raw, 155_000L);
        sessions.sweepExpired(185_000L, DEAD_THRESHOLD_MS);
        verify(frameSender, never()).closeQuietly(any(), any(CloseStatus.class));

        // 之后 61s 无入站才判死
        sessions.sweepExpired(216_000L, DEAD_THRESHOLD_MS);
        verify(frameSender).closeQuietly(decorated, CloseStatus.GOING_AWAY);
    }

    @Test
    void revoke_onClose_silentSessionNotSwept() {
        WebSocketSession raw = mock(WebSocketSession.class);
        WebSocketSession decorated = mock(WebSocketSession.class);
        register("u1", raw, decorated);

        sessions.revoke("u1", raw);
        sessions.sweepExpired(1_000_000L, DEAD_THRESHOLD_MS);

        verify(frameSender, never()).closeQuietly(any(), any(CloseStatus.class));
        assertThat(sessions.get("u1")).isNull();
    }
}
