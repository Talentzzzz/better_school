package com.betterschool.trigger.ws;

import com.betterschool.infrastructure.ws.IWsConnectionHub;
import com.betterschool.infrastructure.ws.wsframe.WsFrameFactory;
import com.betterschool.infrastructure.ws.wsframe.WsFrameType;
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
 * 全部业务下行由各业务域仓储出站（pushMessage/pushNotif/pushOrderStatus → infrastructure/ws 连接组件）投递；推送语义与判重见 spec §2。
 * 连接交接走组件 API：建连/入站/断连把 session 交给 IWsConnectionHub 登记/续期/注销
 * （装饰、心跳跟踪、互踢、多实例路由表的注册/续期/CAS 注销都封装在 infrastructure/ws 组件内），
 * trigger 不自己持有注册表
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsHandler extends TextWebSocketHandler {

    private final IWsConnectionHub connectionHub;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String userId = (String) session.getAttributes().get(WsConnectionConstants.ATTR_USER_ID);
        if (userId == null) {
            // 拦截器已挡，理论上不可达；防御性直接断开
            closeQuietly(session, CloseStatus.SERVER_ERROR);
            return;
        }
        connectionHub.register(userId, session);
        log.info("[WsHandler] 连接建立 session={}, userId={}", session.getId(), userId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String userId = (String) session.getAttributes().get(WsConnectionConstants.ATTR_USER_ID);
        // 任何入站帧（含解析失败）都刷新活跃时间（多实例形态下顺带续期路由条目）
        connectionHub.renew(userId, session);
        try {
            // 入站不反序列化出站信封：C→S 仅 PING，readTree 取 type 识别即可
            String type = objectMapper.readTree(message.getPayload()).path("type").asText("");
            if (WsFrameType.PING.name().equals(type) && userId != null) {
                connectionHub.push(userId, WsFrameFactory.pong());
            }
            // C→S 仅 PING，其余类型无业务路由，静默忽略
        } catch (Exception e) {
            log.debug("[WsHandler] 入站帧解析失败（忽略）session={}", session.getId(), e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String userId = (String) session.getAttributes().get(WsConnectionConstants.ATTR_USER_ID);
        connectionHub.revoke(userId, session);
        log.info("[WsHandler] 连接关闭 session={}, userId={}, status={}", session.getId(), userId, status);
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception e) {
            log.debug("[WsHandler] 关闭会话异常 session={}", session.getId(), e);
        }
    }
}
