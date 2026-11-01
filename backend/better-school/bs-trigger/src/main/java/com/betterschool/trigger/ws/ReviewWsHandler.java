package com.betterschool.trigger.ws;

import com.betterschool.domain.moderation.adapter.port.IReviewPresencePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * review WS 连接处理器（spec §5.3「presence」，票 10）：服务端无业务路由——C→S 仅 PING
 * （任意入站帧刷新活跃时间），业务下行（REVIEW_SIGNAL）由票 11 的派单信号经注册表推送：
 * 入池事务提交后广播（基础设施 afterCommit → {@link ReviewSignalPushService}），
 * 连接建立即补推当前积压数（离线转在线补推）。
 * presence 三写点：连接建立写入、任意入站帧刷新、断开删除（仅当被关闭的连接仍是该用户
 * 当前连接——被互踢旧连接的迟到 close 不会误删新连接的在线记录）；
 * 在线集合落 Redis（可查、跨实例、重启可恢复），只承载审核端在线可见性
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReviewWsHandler extends TextWebSocketHandler {

    private final ReviewConnectionRegistry connectionRegistry;
    private final ReviewSessionRegistry sessionRegistry;
    private final ReviewWsPusher pusher;
    private final IReviewPresencePort reviewPresencePort;
    private final ReviewSignalPushService reviewSignalPushService;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String userId = (String) session.getAttributes().get(ReviewWsProtocol.ATTR_USER_ID);
        if (userId == null) {
            // 拦截器已挡，理论上不可达；防御性直接断开
            pusher.closeQuietly(session, CloseStatus.SERVER_ERROR);
            return;
        }
        WebSocketSession decorated = pusher.decorate(session);
        session.getAttributes().put(ReviewWsProtocol.ATTR_DECORATED_SESSION, decorated);
        sessionRegistry.track(session, userId, decorated);
        // 占位即互踢：同 userId 已有连接时旧连接收 KICK + close(4001)
        connectionRegistry.register(userId, decorated);
        // presence：连接建立写入（同账号重连覆盖同 field）
        reviewPresencePort.touch(userId, System.currentTimeMillis());
        // 派单信号上线补推（票 11）：由离线转在线立即推当前积压数——离线期间不推、不丢单，
        // 池是 DB 持久化的，此处推送与池中实际一致；互踢替换连接时新连接照推（幂等快照，无害）
        reviewSignalPushService.pushBacklogTo(decorated);
        log.info("[ReviewWsHandler] 连接建立 session={}, userId={}", session.getId(), userId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // 任何入站帧（含解析失败）都刷新活跃时间——本地判死与 presence 同一口径
        sessionRegistry.touch(session);
        String userId = (String) session.getAttributes().get(ReviewWsProtocol.ATTR_USER_ID);
        if (userId != null) {
            reviewPresencePort.touch(userId, System.currentTimeMillis());
        }
        try {
            ReviewWsFrame frame = objectMapper.readValue(message.getPayload(), ReviewWsFrame.class);
            if (frame.getType() == ReviewWsFrameType.PING) {
                WebSocketSession decorated = decoratedOf(session);
                if (decorated != null) {
                    pusher.push(decorated, ReviewWsFrame.builder()
                            .type(ReviewWsFrameType.PONG)
                            .ts(System.currentTimeMillis())
                            .build());
                }
            }
            // C→S 仅 PING，其余类型（REVIEW_SIGNAL 等）无上行路由，静默忽略
        } catch (Exception e) {
            log.debug("[ReviewWsHandler] 入站帧解析失败（忽略）session={}", session.getId(), e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionRegistry.untrack(session);
        String userId = (String) session.getAttributes().get(ReviewWsProtocol.ATTR_USER_ID);
        WebSocketSession decorated = decoratedOf(session);
        if (userId != null && decorated != null
                && connectionRegistry.unregister(userId, decorated)) {
            // 两参注销命中 = 被关闭的连接是该用户当前连接 → 才删在线记录；
            // 被互踢旧连接的迟到 close / 判死已清理过的会话在此自然落空
            reviewPresencePort.markOffline(userId);
        }
        log.info("[ReviewWsHandler] 连接关闭 session={}, userId={}, status={}", session.getId(), userId, status);
    }

    private WebSocketSession decoratedOf(WebSocketSession session) {
        Object decorated = session.getAttributes().get(ReviewWsProtocol.ATTR_DECORATED_SESSION);
        return decorated instanceof WebSocketSession ws ? ws : null;
    }
}
