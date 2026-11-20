package com.betterschool.trigger.ws.support;

import com.betterschool.types.ws.WsHandshakeAttributes;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * 票据握手拦截骨架：query 票据交子类校验（原子取删的一次性短时凭证，
 * 60s 有效），通过后把登录用户 id 写入握手 attributes；校验失败 401 拒绝握手。
 * 浏览器 WS 不能带自定义 header，JWT 不进 URL，故以短时票据换身份。
 * 本骨架属入站连接层（握手），留在 trigger；投递侧骨架已随投递机器下沉 infra
 */
@Slf4j
public abstract class AbstractWsTicketHandshakeInterceptor implements HandshakeInterceptor {

    @Override
    public final boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                         WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String ticket = UriComponentsBuilder.fromUri(request.getURI())
                .build()
                .getQueryParams()
                .getFirst("ticket");
        String userId = authenticateUserId(ticket);
        if (userId == null) {
            log.info("[{}] 票据校验失败，拒绝握手 uri={}", getClass().getSimpleName(), request.getURI());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(WsHandshakeAttributes.ATTR_USER_ID, userId);
        return true;
    }

    @Override
    public final void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                     WebSocketHandler wsHandler, Exception exception) {
        // 无需后置处理
    }

    /**
     * 校验票据换登录用户 id：无效（不存在/已用/过期）或身份不合规（如角色过滤不通过）返回 null，
     * 握手以 401 拒绝
     */
    protected abstract String authenticateUserId(String ticket);
}
