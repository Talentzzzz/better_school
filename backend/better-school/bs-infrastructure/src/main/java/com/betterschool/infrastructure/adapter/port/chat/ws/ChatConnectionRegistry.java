package com.betterschool.infrastructure.adapter.port.chat.ws;

import com.betterschool.infrastructure.adapter.port.chat.ws.support.AbstractSingleSessionConnectionRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * chat 连接注册表：单设备互踢骨架（support 抽象类）的 chat 绑定——
 * 被顶掉的旧连接由 chat 推送器发 KICK + close(4001)
 */
@Component
public class ChatConnectionRegistry extends AbstractSingleSessionConnectionRegistry {

    private final ChatWsPusher pusher;

    public ChatConnectionRegistry(ChatWsPusher pusher) {
        this.pusher = pusher;
    }

    @Override
    protected void kickSession(WebSocketSession oldSession) {
        pusher.kick(oldSession);
    }
}
