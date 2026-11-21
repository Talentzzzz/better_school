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
 * chat WS 帧写出器：JSON 序列化 + 并发写保护 + 互踢/静默关闭。
 * JSR-356 不允许并发 send——decorate 用 ConcurrentWebSocketSessionDecorator 包装
 * （发送排队串行化，超 5s 或缓冲超 512KB 主动断开）；推送失败不重试——
 * 漏推合法，客户端按 (roomId, seq) 判重 + 增量补拉收敛
 */
@Slf4j
@Component
public class WsPusher {

    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int BUFFER_SIZE_LIMIT = 512 * 1024;

    private final ObjectMapper objectMapper;

    public WsPusher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 并发写保护包装：建连时调用一次，此后推送/关闭一律用装饰后的 session */
    public WebSocketSession decorate(WebSocketSession session) {
        return new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
    }

    public void push(WebSocketSession session, WsFrame<?> frame) {
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(frame)));
        } catch (Exception e) {
            log.warn("[WsPusher] 推送失败（漏推合法，客户端兜底收敛）type={}, session={}",
                    frame.getType(), session.getId(), e);
        }
    }

    /** 跨实例转发件直写：直接写出已序列化的线上 JSON（免 反序列化→再序列化 一轮） */
    public void pushJson(WebSocketSession session, String frameJson) {
        try {
            session.sendMessage(new TextMessage(frameJson));
        } catch (Exception e) {
            log.warn("[WsPusher] 推送失败（漏推合法，客户端兜底收敛）session={}", session.getId(), e);
        }
    }

    /** 互踢：先发 KICK 帧让客户端提示重新登录，随后 close(4001) 阻断自动重连 */
    public void kick(WebSocketSession session) {
        push(session, WsFrameFactory.kick());
        closeQuietly(session, new CloseStatus(WsCloseCodes.CLOSE_CODE_TAKEN_OVER, WsCloseCodes.KICK_REASON_SESSION_TAKEN_OVER));
    }

    public void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception e) {
            log.debug("[WsPusher] 关闭会话异常 session={}", session.getId(), e);
        }
    }
}
