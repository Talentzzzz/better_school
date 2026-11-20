package com.betterschool.infrastructure.adapter.port.chat.ws.support;

import com.betterschool.types.ws.WsCloseCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

/**
 * WS 帧写出器骨架：JSON 序列化 + 并发写保护（JSR-356 不允许并发 send，
 * 包装 ConcurrentWebSocketSessionDecorator；缓冲超限由装饰器主动断开）。
 * 推送失败不重试——漏推合法，客户端靠兜底收敛；骨架支撑后续第二个 WS 端点复用
 */
@Slf4j
public abstract class AbstractWsJsonPusher<F> {

    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int BUFFER_SIZE_LIMIT = 512 * 1024;

    protected final ObjectMapper objectMapper;

    protected AbstractWsJsonPusher(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public WebSocketSession decorate(WebSocketSession session) {
        return new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
    }

    public void push(WebSocketSession session, F frame) {
        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(frame)));
        } catch (Exception e) {
            log.warn("[{}] 推送失败（漏推合法，客户端兜底收敛）type={}, session={}",
                    getClass().getSimpleName(), frameTypeName(frame), session.getId(), e);
        }
    }

    /** 互踢：先发 KICK 帧让客户端提示重新登录，随后 close(4001) 阻断自动重连 */
    public void kick(WebSocketSession session) {
        push(session, kickFrame());
        closeQuietly(session, new CloseStatus(WsCloseCodes.CLOSE_CODE_TAKEN_OVER, WsCloseCodes.KICK_REASON_SESSION_TAKEN_OVER));
    }

    public void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception e) {
            log.debug("[{}] 关闭会话异常 session={}", getClass().getSimpleName(), session.getId(), e);
        }
    }

    /** 互踢 KICK 帧（协议信封与 KICK 帧类型由子类按各自端点给出） */
    protected abstract F kickFrame();

    /** 日志用帧类型名（帧类型枚举未实现 toString 契约时由子类给出） */
    protected String frameTypeName(F frame) {
        return String.valueOf(frame);
    }
}
