package com.betterschool.trigger.ws;

import com.betterschool.domain.moderation.adapter.port.IReviewPresencePort;
import com.betterschool.trigger.ws.support.AbstractHeartbeatSessionRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * review 会话存活注册表（票 10）：心跳判死骨架的 review 绑定——
 * 心跳口径与 chat 相同（25s PING、60s 无入站判死）。判死清理 = 注销连接注册表 + 关闭会话；
 * presence 删除仅在「被清理的会话仍是该用户当前连接」时执行（两参注销返回值判定），
 * 防止把同账号新连接的在线记录误删
 */
@Component
public class ReviewSessionRegistry extends AbstractHeartbeatSessionRegistry {

    private final ReviewWsPusher pusher;
    private final ReviewConnectionRegistry connectionRegistry;
    private final IReviewPresencePort reviewPresencePort;

    public ReviewSessionRegistry(ReviewWsPusher pusher, ReviewConnectionRegistry connectionRegistry,
                                 IReviewPresencePort reviewPresencePort) {
        this.pusher = pusher;
        this.connectionRegistry = connectionRegistry;
        this.reviewPresencePort = reviewPresencePort;
    }

    @Override
    protected void onSessionDead(String userId, WebSocketSession decorated) {
        if (connectionRegistry.unregister(userId, decorated)) {
            reviewPresencePort.markOffline(userId);
        }
        pusher.closeQuietly(decorated, CloseStatus.GOING_AWAY);
    }

    @Override
    protected String sweepThreadName() {
        return "review-ws-session-sweep";
    }
}
