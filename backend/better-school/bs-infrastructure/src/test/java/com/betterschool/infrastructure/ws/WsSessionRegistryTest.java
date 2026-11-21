package com.betterschool.infrastructure.ws;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * 会话存活注册表单元测试（票 04）：入站刷新活跃时间、60s 无入站判死清理、活跃会话不受影响
 */
public class WsSessionRegistryTest {

    private static final long DEAD_THRESHOLD_MS = 60_000;

    private final WsPusher pusher = mock(WsPusher.class);
    private final WsConnectionRegistry connectionRegistry = mock(WsConnectionRegistry.class);
    private final WsSessionRegistry registry = new WsSessionRegistry(pusher, connectionRegistry);

    @Test
    void sweepExpired_recentlyActive_notKilled() {
        WebSocketSession raw = mock(WebSocketSession.class);
        WebSocketSession decorated = mock(WebSocketSession.class);
        registry.register(raw, "u1", decorated);
        registry.refresh(raw, 100_000L);

        registry.sweepExpired(120_000L, DEAD_THRESHOLD_MS);

        verify(pusher, never()).closeQuietly(any(), any(CloseStatus.class));
        verify(connectionRegistry, never()).unregister(anyString(), any());
    }

    @Test
    void sweepExpired_silentBeyondThreshold_killed() {
        WebSocketSession raw = mock(WebSocketSession.class);
        WebSocketSession decorated = mock(WebSocketSession.class);
        registry.register(raw, "u1", decorated);
        registry.refresh(raw, 100_000L);

        // 最后入站 100s，now=161s，间隔 61s > 60s 判死
        registry.sweepExpired(161_000L, DEAD_THRESHOLD_MS);

        verify(pusher).closeQuietly(decorated, CloseStatus.GOING_AWAY);
        verify(connectionRegistry).unregister("u1", decorated);
        // 判死后条目已清理，重复清扫无二次动作
        registry.sweepExpired(200_000L, DEAD_THRESHOLD_MS);
        verify(pusher).closeQuietly(decorated, CloseStatus.GOING_AWAY);
    }

    @Test
    void refresh_refreshesLastInbound_deferDeath() {
        WebSocketSession raw = mock(WebSocketSession.class);
        WebSocketSession decorated = mock(WebSocketSession.class);
        registry.register(raw, "u1", decorated);
        registry.refresh(raw, 100_000L);

        // 55s 时心跳刷新，再过 30s 不判死
        registry.refresh(raw, 155_000L);
        registry.sweepExpired(185_000L, DEAD_THRESHOLD_MS);
        verify(pusher, never()).closeQuietly(any(), any(CloseStatus.class));

        // 之后 61s 无入站才判死
        registry.sweepExpired(216_000L, DEAD_THRESHOLD_MS);
        verify(pusher).closeQuietly(decorated, CloseStatus.GOING_AWAY);
    }

    @Test
    void unregister_onClose_silentSessionNotSwept() {
        WebSocketSession raw = mock(WebSocketSession.class);
        WebSocketSession decorated = mock(WebSocketSession.class);
        registry.register(raw, "u1", decorated);

        registry.unregister(raw);
        registry.sweepExpired(1_000_000L, DEAD_THRESHOLD_MS);

        verify(pusher, never()).closeQuietly(any(), any(CloseStatus.class));
        verify(connectionRegistry, never()).unregister(anyString(), any());
    }
}
