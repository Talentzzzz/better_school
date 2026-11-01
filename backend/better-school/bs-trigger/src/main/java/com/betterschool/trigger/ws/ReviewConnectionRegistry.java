package com.betterschool.trigger.ws;

import com.betterschool.trigger.ws.support.AbstractSingleSessionConnectionRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * review 连接注册表（票 10）：单设备互踢骨架的 review 绑定——
 * 被顶掉的旧连接由 review 推送器发 KICK + close(4001)。
 * 与 chat 注册表各持一份：同账号的 chat 连接与 review 连接互不干扰、互不误踢
 */
@Component
public class ReviewConnectionRegistry extends AbstractSingleSessionConnectionRegistry {

    private final ReviewWsPusher pusher;

    public ReviewConnectionRegistry(ReviewWsPusher pusher) {
        this.pusher = pusher;
    }

    @Override
    protected void kickSession(WebSocketSession oldSession) {
        pusher.kick(oldSession);
    }
}
