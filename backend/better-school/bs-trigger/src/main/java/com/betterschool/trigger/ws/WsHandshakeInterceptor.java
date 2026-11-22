package com.betterschool.trigger.ws;

import com.betterschool.infrastructure.ws.IWsService;
import com.betterschool.types.ws.WsHandshakeAttributes;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

import java.util.Map;

/**
 * WS 握手拦截（spec §3）：query 票据原子取删校验（60s 一次性，重放失败），
 * 通过后把登录用户 id 写入握手 attributes；失败 401 拒绝握手。
 * 浏览器 WS 不能带自定义 header，JWT 不进 URL，故以短时票据换身份。
 * 端点不做角色过滤——任何登录用户皆可连
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsHandshakeInterceptor implements HandshakeInterceptor {

    private final IWsService wsService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String ticket = UriComponentsBuilder.fromUri(request.getURI())
                .build()
                .getQueryParams()
                .getFirst("ticket");
        String userId = wsService.validateAndConsume(ticket);
        if (userId == null) {
            log.info("[WsHandshakeInterceptor] 票据校验失败，拒绝握手 uri={}", request.getURI());
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
        attributes.put(WsHandshakeAttributes.ATTR_USER_ID, userId);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需后置处理
    }
}
