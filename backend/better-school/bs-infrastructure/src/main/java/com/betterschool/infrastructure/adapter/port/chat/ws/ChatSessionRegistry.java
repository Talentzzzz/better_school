package com.betterschool.infrastructure.adapter.port.chat.ws;

import com.betterschool.infrastructure.adapter.port.chat.ws.support.AbstractHeartbeatSessionRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * chat 会话存活注册表：心跳判死骨架（support 抽象类）的 chat 绑定——
 * 判死清理时注销 chat 连接注册表并关闭会话
 */
@Component
public class ChatSessionRegistry extends AbstractHeartbeatSessionRegistry {

    private final ChatWsPusher pusher;
    private final ChatConnectionRegistry connectionRegistry;

    public ChatSessionRegistry(ChatWsPusher pusher, ChatConnectionRegistry connectionRegistry) {
        this.pusher = pusher;
        this.connectionRegistry = connectionRegistry;
    }

    @Override
    protected void onSessionDead(String userId, WebSocketSession decorated) {
        connectionRegistry.unregister(userId, decorated);
        pusher.closeQuietly(decorated, CloseStatus.GOING_AWAY);
    }

    @Override
    protected String sweepThreadName() {
        return "chat-ws-session-sweep";
    }
}
