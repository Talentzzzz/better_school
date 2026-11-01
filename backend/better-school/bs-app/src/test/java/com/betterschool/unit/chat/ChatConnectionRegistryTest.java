package com.betterschool.unit.chat;

import com.betterschool.trigger.ws.ChatConnectionRegistry;
import com.betterschool.trigger.ws.ChatWsPusher;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 连接注册表互斥规则单元测试（票 04）：互踢占位、两参注销防误删
 */
public class ChatConnectionRegistryTest {

    private final ChatWsPusher pusher = mock(ChatWsPusher.class);
    private final ChatConnectionRegistry registry = new ChatConnectionRegistry(pusher);

    @Test
    void registerNewConnection_noKick() {
        WebSocketSession session = mock(WebSocketSession.class);
        WebSocketSession evicted = registry.register("u1", session);
        assertThat(evicted).isNull();
        assertThat(registry.get("u1")).isSameAs(session);
        verify(pusher, never()).kick(any());
    }

    @Test
    void registerSameUser_takeoverKicksOldSession() {
        WebSocketSession old = mock(WebSocketSession.class);
        WebSocketSession fresh = mock(WebSocketSession.class);
        registry.register("u1", old);

        WebSocketSession evicted = registry.register("u1", fresh);

        assertThat(evicted).isSameAs(old);
        assertThat(registry.get("u1")).isSameAs(fresh);
        verify(pusher).kick(old);
    }

    @Test
    void differentUsers_noKick() {
        registry.register("u1", mock(WebSocketSession.class));
        registry.register("u2", mock(WebSocketSession.class));
        verify(pusher, never()).kick(any());
    }

    @Test
    void unregister_matchingSession_removes() {
        WebSocketSession session = mock(WebSocketSession.class);
        registry.register("u1", session);

        assertThat(registry.unregister("u1", session)).isTrue();
        assertThat(registry.get("u1")).isNull();
    }

    @Test
    void unregister_staleSessionAfterTakeover_keepsNewConnection() {
        WebSocketSession old = mock(WebSocketSession.class);
        WebSocketSession fresh = mock(WebSocketSession.class);
        registry.register("u1", old);
        registry.register("u1", fresh);

        // 旧连接迟到的 close 回调：两参注销不匹配，新连接不被误删
        assertThat(registry.unregister("u1", old)).isFalse();
        assertThat(registry.get("u1")).isSameAs(fresh);

        // 新连接自己的 close 才真正注销
        assertThat(registry.unregister("u1", fresh)).isTrue();
        assertThat(registry.get("u1")).isNull();
    }

    @Test
    void unregister_unknownUserOrSession_returnsFalse() {
        assertThat(registry.unregister("u999", mock(WebSocketSession.class))).isFalse();
        WebSocketSession session = mock(WebSocketSession.class);
        registry.register("u1", session);
        assertThat(registry.unregister("u1", mock(WebSocketSession.class))).isFalse();
    }

    @Test
    void reRegisterAfterClose_allowed() {
        WebSocketSession first = mock(WebSocketSession.class);
        registry.register("u1", first);
        registry.unregister("u1", first);

        WebSocketSession second = mock(WebSocketSession.class);
        WebSocketSession evicted = registry.register("u1", second);
        assertThat(evicted).isNull();
        verify(pusher, never()).kick(any());
        assertThat(registry.get("u1")).isSameAs(second);
    }
}
