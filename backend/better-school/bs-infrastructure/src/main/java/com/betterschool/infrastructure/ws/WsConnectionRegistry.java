package com.betterschool.infrastructure.ws;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * chat 连接注册表：单设备互踢——Map&lt;userId, session&gt;，「一个用户一条连接」的规则执行者。
 * 互斥规则：新连接先 put 占位（put 原子返回被顶掉的旧连接，占位即互踢，后到者赢），
 * 旧连接由推送器互踢（KICK + close 4001）；close 回调走两参 remove（userId + session 身份比对），
 * 被互踢旧连接的迟到 close 不会误删新连接
 */
@Component
public class WsConnectionRegistry {

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    private final WsPusher pusher;

    public WsConnectionRegistry(WsPusher pusher) {
        this.pusher = pusher;
    }

    /** 注册新连接：占位并互踢被顶掉的旧连接（无旧连接不踢） */
    public void register(String userId, WebSocketSession session) {
        WebSocketSession old = sessions.put(userId, session);
        if (old != null && old != session) {
            pusher.kick(old);
        }
    }

    /** 两参注销：仅当该用户当前连接就是此 session 时移除（迟到 close 不误删新连接） */
    public boolean unregister(String userId, WebSocketSession session) {
        return sessions.remove(userId, session);
    }

    public WebSocketSession get(String userId) {
        return sessions.get(userId);
    }
}
