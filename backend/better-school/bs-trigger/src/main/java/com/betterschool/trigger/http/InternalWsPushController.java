package com.betterschool.trigger.http;

import com.betterschool.trigger.ws.ChatConnectionRegistry;
import com.betterschool.trigger.ws.ChatWsPusher;
import com.betterschool.trigger.ws.InternalPushRequest;
import com.betterschool.trigger.ws.InternalPushResponse;
import com.betterschool.trigger.ws.WsRouteProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.socket.WebSocketSession;

/**
 * 实例间内部转发端点（多实例推送路由）：目标实例对本机注册表二次确认后推帧。
 * fail-closed：内部密钥未配置或校验不过一律 403——它等于「替任意用户推任意帧」的口子，绝不裸奔。
 * 路径在 /internal/** 下，SecurityConfig 放行 JWT（自带共享密钥校验）
 */
@Slf4j
@RestController
@RequestMapping("/internal/ws")
@RequiredArgsConstructor
public class InternalWsPushController {

    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final ChatConnectionRegistry connectionRegistry;
    private final ChatWsPusher pusher;
    private final WsRouteProperties routeProperties;

    @PostMapping("/push")
    public ResponseEntity<InternalPushResponse> push(
            @RequestHeader(value = INTERNAL_TOKEN_HEADER, required = false) String token,
            @RequestBody InternalPushRequest request) {
        if (request.userId() == null || request.frame() == null
                || routeProperties.getInternalToken().isBlank()
                || !routeProperties.getInternalToken().equals(token)) {
            return ResponseEntity.status(403).build();
        }
        WebSocketSession session = connectionRegistry.get(request.userId());
        if (session == null || !session.isOpen()) {
            // 明确 miss：本地不持有该连接（漂移/断线），转发方据此清理路由
            return ResponseEntity.ok(new InternalPushResponse(false));
        }
        pusher.push(session, request.frame());
        return ResponseEntity.ok(new InternalPushResponse(true));
    }
}
