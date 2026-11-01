package com.betterschool.trigger.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WS 连接处理器（spec §3）：服务端无业务路由——C→S 仅 PING（任意入站帧刷新活跃时间），
 * 全部业务下行由 ChatPushConsumer 查注册表推送；推送语义与判重见 spec §2
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWsHandler extends TextWebSocketHandler {

    private final ChatConnectionRegistry connectionRegistry;
    private final ChatSessionRegistry sessionRegistry;
    private final ChatWsPusher pusher;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String userId = (String) session.getAttributes().get(ChatWsProtocol.ATTR_USER_ID);
        if (userId == null) {
            // 拦截器已挡，理论上不可达；防御性直接断开
            pusher.closeQuietly(session, CloseStatus.SERVER_ERROR);
            return;
        }
        WebSocketSession decorated = pusher.decorate(session);
        session.getAttributes().put(ChatWsProtocol.ATTR_DECORATED_SESSION, decorated);
        sessionRegistry.track(session, userId, decorated);
        // 占位即互踢：同 userId 已有连接时旧连接收 KICK + close(4001)
        connectionRegistry.register(userId, decorated);
        log.info("[ChatWsHandler] 连接建立 session={}, userId={}", session.getId(), userId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // 任何入站帧（含解析失败）都刷新活跃时间
        sessionRegistry.touch(session);
        try {
            ChatWsFrame frame = objectMapper.readValue(message.getPayload(), ChatWsFrame.class);
            if (frame.getType() == ChatWsFrameType.PING) {
                WebSocketSession decorated = decoratedOf(session);
                if (decorated != null) {
                    pusher.push(decorated, ChatWsFrame.builder()
                            .type(ChatWsFrameType.PONG)
                            .ts(System.currentTimeMillis())
                            .build());
                }
            }
            // C→S 仅 PING，其余类型无业务路由，静默忽略
        } catch (Exception e) {
            log.debug("[ChatWsHandler] 入站帧解析失败（忽略）session={}", session.getId(), e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionRegistry.untrack(session);
        String userId = (String) session.getAttributes().get(ChatWsProtocol.ATTR_USER_ID);
        WebSocketSession decorated = decoratedOf(session);
        if (userId != null && decorated != null) {
            // 两参注销：被互踢的旧连接迟到 close 不会误删新连接
            connectionRegistry.unregister(userId, decorated);
        }
        log.info("[ChatWsHandler] 连接关闭 session={}, userId={}, status={}", session.getId(), userId, status);
    }

    private WebSocketSession decoratedOf(WebSocketSession session) {
        Object decorated = session.getAttributes().get(ChatWsProtocol.ATTR_DECORATED_SESSION);
        return decorated instanceof WebSocketSession ws ? ws : null;
    }
}
