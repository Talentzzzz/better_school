package com.betterschool.infrastructure.adapter.port.chat.ws.support;

import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单设备连接注册表骨架：Map&lt;userId, session&gt;，单设备互踢。
 * 互斥规则：新连接先 put 占位，返回的旧连接交子类互踢（KICK + close 4001）；
 * close 回调走两参 remove（userId + session 身份比对），迟到 close 不会误删新连接。
 * 支撑后续第二个 WS 端点的骨架：各端点各持一份实例，同一账号在不同端点的连接互不干扰
 */
public abstract class AbstractSingleSessionConnectionRegistry {

    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /**
     * 注册新连接：占位并互踢旧连接
     *
     * @return 被踢掉的旧连接（无旧连接返回 null）
     */
    public WebSocketSession register(String userId, WebSocketSession session) {
        WebSocketSession old = sessions.put(userId, session);
        if (old != null && old != session) {
            kickSession(old);
        }
        return old;
    }

    /** 两参注销：仅当该用户当前连接就是此 session 时移除 */
    public boolean unregister(String userId, WebSocketSession session) {
        return sessions.remove(userId, session);
    }

    public WebSocketSession get(String userId) {
        return sessions.get(userId);
    }

    /** 互踢动作：对被顶掉的旧连接发 KICK 通知并关闭（子类绑定各自端点的推送器） */
    protected abstract void kickSession(WebSocketSession oldSession);
}
