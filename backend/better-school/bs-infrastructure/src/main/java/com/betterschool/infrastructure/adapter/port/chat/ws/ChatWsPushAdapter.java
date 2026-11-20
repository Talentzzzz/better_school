package com.betterschool.infrastructure.adapter.port.chat.ws;

import com.betterschool.domain.chat.adapter.port.IWsConnectionRegistryPort;
import com.betterschool.domain.chat.adapter.port.IWsPushPort;
import com.betterschool.domain.chat.model.valobj.ChatWsFrame;
import com.betterschool.types.ws.WsHandshakeAttributes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.function.BiConsumer;

/**
 * WS 推送适配器：把「投递一帧」与「连接登记」两件出站能力打包在两个 domain 端口后——
 * <ul>
 *   <li>{@link IWsPushPort}：委托 {@link WsPushRouter}（本地优先 → Redis 路由表 → 定向频道）</li>
 *   <li>{@link IWsConnectionRegistryPort}：建连时装饰 session（并发写保护）、心跳跟踪、
 *       连接注册表占位互踢、多实例形态下注册路由条目；入站帧续期；断连两参注销 + 代际 token CAS 注销</li>
 * </ul>
 * Redis 路由表（WsRouteStore）与 pub/sub 定向频道（WsPushChannels）是本适配器私有细节：
 * 路由条目 token = {@code 端点标识|sessionId}，构造与存放（session attributes）都不出适配器
 */
@Component
@RequiredArgsConstructor
public class ChatWsPushAdapter implements IWsPushPort, IWsConnectionRegistryPort {

    /** 路由条目代际标识存在 session attributes：端点|sessionId，注销时 CAS 比对用 */
    private static final String ATTR_ROUTE_TOKEN = "ws.route.token";

    private final ChatConnectionRegistry connectionRegistry;
    private final ChatSessionRegistry sessionRegistry;
    private final ChatWsPusher pusher;
    private final WsPushRouter wsPushRouter;
    private final WsRouteProperties routeProperties;
    private final WsRouteStore routeStore;

    @Override
    public void deliver(String userId, ChatWsFrame frame) {
        wsPushRouter.deliver(userId, frame);
    }

    @Override
    public void register(String userId, WebSocketSession session) {
        WebSocketSession decorated = pusher.decorate(session);
        session.getAttributes().put(WsHandshakeAttributes.ATTR_DECORATED_SESSION, decorated);
        sessionRegistry.track(session, userId, decorated);
        // 占位即互踢：同 userId 已有连接时旧连接收 KICK + close(4001)
        connectionRegistry.register(userId, decorated);
        registerRoute(userId, session);
    }

    @Override
    public void renew(String userId, WebSocketSession session) {
        // 任何入站帧（含 PING 与解析失败的帧）都刷新心跳活跃时间
        sessionRegistry.touch(session);
        renewRoute(userId, session);
    }

    @Override
    public void revoke(String userId, WebSocketSession session) {
        sessionRegistry.untrack(session);
        WebSocketSession decorated = decoratedOf(session);
        if (userId != null && decorated != null) {
            // 两参注销：被互踢的旧连接迟到 close 不会误删新连接
            connectionRegistry.unregister(userId, decorated);
        }
        revokeRoute(userId, session);
    }

    /** 路由注册：token = 端点标识|sessionId（代际标识，接管场景防 CAS 误删）；单机形态（未配置端点）跳过 */
    private void registerRoute(String userId, WebSocketSession session) {
        if (!routeProperties.enabled()) {
            return;
        }
        String token = routeProperties.getSelfEndpoint() + "|" + session.getId();
        session.getAttributes().put(ATTR_ROUTE_TOKEN, token);
        routeStore.register(userId, token);
    }

    /** 路由续期：任意入站帧（心跳 25s）刷 TTL；token 比对防误续接管后的新条目 */
    private void renewRoute(String userId, WebSocketSession session) {
        applyRoute(userId, session, routeStore::renew);
    }

    /** 路由注销：token 比对 CAS——被互踢/接管后迟到的 close 不误删新连接的条目 */
    private void revokeRoute(String userId, WebSocketSession session) {
        applyRoute(userId, session, routeStore::revoke);
    }

    /** 路由动作共享形态：单机形态（未配置端点）跳过；token 比对通过才对 (userId, token) 执行 */
    private void applyRoute(String userId, WebSocketSession session, BiConsumer<String, String> action) {
        if (!routeProperties.enabled()) {
            return;
        }
        Object token = session.getAttributes().get(ATTR_ROUTE_TOKEN);
        if (token instanceof String t && userId != null) {
            action.accept(userId, t);
        }
    }

    private WebSocketSession decoratedOf(WebSocketSession session) {
        Object decorated = session.getAttributes().get(WsHandshakeAttributes.ATTR_DECORATED_SESSION);
        return decorated instanceof WebSocketSession ws ? ws : null;
    }
}
