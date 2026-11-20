package com.betterschool.trigger.ws;

import com.betterschool.domain.chat.adapter.port.IWsConnectionRegistryPort;
import com.betterschool.domain.chat.adapter.port.IWsPushPort;
import com.betterschool.domain.chat.model.valobj.ChatWsFrame;
import com.betterschool.domain.chat.model.valobj.ChatWsFrameType;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WS 连接处理器：服务端无业务路由——C→S 仅 PING（任意入站帧刷新活跃时间），
 * 全部业务下行经 IWsPushPort 投递；推送语义与判重见 spec §2。
 * 连接交接走端口：建连/入站/断连把 session 交给 IWsConnectionRegistryPort 登记/续期/注销
 * （装饰、心跳跟踪、互踢、多实例路由表的注册/续期/CAS 注销都封装在 infra 适配器内），
 * trigger 不再自己持有注册表
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWsHandler extends TextWebSocketHandler {

    private final IWsConnectionRegistryPort connectionRegistryPort;
    private final IWsPushPort wsPushPort;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String userId = (String) session.getAttributes().get(ChatWsProtocol.ATTR_USER_ID);
        if (userId == null) {
            // 拦截器已挡，理论上不可达；防御性直接断开
            closeQuietly(session, CloseStatus.SERVER_ERROR);
            return;
        }
        connectionRegistryPort.register(userId, session);
        log.info("[ChatWsHandler] 连接建立 session={}, userId={}", session.getId(), userId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String userId = (String) session.getAttributes().get(ChatWsProtocol.ATTR_USER_ID);
        // 任何入站帧（含解析失败）都刷新活跃时间（多实例形态下顺带续期路由条目）
        connectionRegistryPort.renew(userId, session);
        try {
            ChatWsFrame frame = objectMapper.readValue(message.getPayload(), ChatWsFrame.class);
            if (frame.getType() == ChatWsFrameType.PING && userId != null) {
                wsPushPort.deliver(userId, ChatWsFrame.builder()
                        .type(ChatWsFrameType.PONG)
                        .ts(System.currentTimeMillis())
                        .build());
            }
            // C→S 仅 PING，其余类型无业务路由，静默忽略
        } catch (Exception e) {
            log.debug("[ChatWsHandler] 入站帧解析失败（忽略）session={}", session.getId(), e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String userId = (String) session.getAttributes().get(ChatWsProtocol.ATTR_USER_ID);
        connectionRegistryPort.revoke(userId, session);
        log.info("[ChatWsHandler] 连接关闭 session={}, userId={}, status={}", session.getId(), userId, status);
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception e) {
            log.debug("[ChatWsHandler] 关闭会话异常 session={}", session.getId(), e);
        }
    }
}
