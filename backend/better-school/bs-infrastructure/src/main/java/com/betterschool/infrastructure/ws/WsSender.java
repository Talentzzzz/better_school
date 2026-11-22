package com.betterschool.infrastructure.ws;

import com.betterschool.infrastructure.ws.wsframe.WsFrame;
import com.betterschool.infrastructure.ws.wsframe.WsFrameFactory;
import com.betterschool.types.ws.WsCloseCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * WS 帧发送器实现：{@link IWsSender} 的机器本体。
 * JSR-356 不允许并发 send——decorate 用 ConcurrentWebSocketSessionDecorator 包装
 * （发送排队串行化，超 5s 或缓冲超 512KB 主动断开）；推送失败不重试——
 * 漏推合法，客户端按 (roomId, seq) 判重 + 增量补拉收敛
 */
@Slf4j
@Component
public class WsSender implements IWsSender {

    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int BUFFER_SIZE_LIMIT = 512 * 1024;

    private final ObjectMapper objectMapper;

    public WsSender(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public WebSocketSession decorate(WebSocketSession session) {
        return new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
    }

    @Override
    public void push(WebSocketSession session, WsFrame<?> frame) {
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(frame)));
        } catch (Exception e) {
            log.warn("[WsSender] 推送失败（漏推合法，客户端兜底收敛）type={}, session={}",
                    frame.getType(), session.getId(), e);
        }
    }

    @Override
    public void pushJson(WebSocketSession session, String frameJson) {
        try {
            session.sendMessage(new TextMessage(frameJson));
        } catch (Exception e) {
            log.warn("[WsSender] 推送失败（漏推合法，客户端兜底收敛）session={}", session.getId(), e);
        }
    }

    @Override
    public void kick(WebSocketSession session) {
        push(session, WsFrameFactory.kick());
        closeQuietly(session, new CloseStatus(WsCloseCodes.CLOSE_CODE_TAKEN_OVER, WsCloseCodes.KICK_REASON_SESSION_TAKEN_OVER));
    }

    @Override
    public void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception e) {
            log.debug("[WsSender] 关闭会话异常 session={}", session.getId(), e);
        }
    }
}
