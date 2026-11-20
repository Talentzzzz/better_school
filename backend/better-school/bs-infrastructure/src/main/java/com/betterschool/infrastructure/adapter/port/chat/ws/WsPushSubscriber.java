package com.betterschool.infrastructure.adapter.port.chat.ws;

import com.betterschool.domain.chat.model.valobj.ChatWsFrame;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * 本实例推送频道订阅器（多实例推送路由）：启动即订阅自己的定向频道，
 * 收到跨实例广播后对本机注册表二次确认——路由信息只是「大概率对」，漂移窗口内可能已迁移。
 * <p>本地 miss = 路由过期（连接已断/迁走）：清理自己名下的路由条目后丢弃，
 * 这是 fire-and-forget 通道下的过期兜底；命中即推帧。序列化异常/推送失败一律丢弃——
 * 漏推由客户端 (roomId, seq) 增量补拉兜底。
 * Redis 路由表（WsRouteStore）与定向频道（WsPushChannels）是本适配器的内部实现细节
 */
@Slf4j
@Component
public class WsPushSubscriber implements InitializingBean, DisposableBean {

    private final ChatConnectionRegistry connectionRegistry;
    private final ChatWsPusher pusher;
    private final WsRouteStore routeStore;
    private final WsPushChannels pushChannels;
    private final WsRouteProperties routeProperties;
    private final ObjectMapper objectMapper;

    private Integer listenerId;

    public WsPushSubscriber(ChatConnectionRegistry connectionRegistry,
                            ChatWsPusher pusher,
                            WsRouteStore routeStore,
                            WsPushChannels pushChannels,
                            WsRouteProperties routeProperties,
                            ObjectMapper objectMapper) {
        this.connectionRegistry = connectionRegistry;
        this.pusher = pusher;
        this.routeStore = routeStore;
        this.pushChannels = pushChannels;
        this.routeProperties = routeProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterPropertiesSet() {
        if (!routeProperties.enabled()) {
            return;
        }
        String selfEndpoint = routeProperties.getSelfEndpoint();
        listenerId = pushChannels.subscribe(selfEndpoint, this::onMessage);
        log.info("[WsPushSubscriber] 已订阅本实例推送频道 endpoint={}", selfEndpoint);
    }

    @Override
    public void destroy() {
        if (listenerId != null) {
            pushChannels.unsubscribe(routeProperties.getSelfEndpoint(), listenerId);
        }
    }

    private void onMessage(String messageJson) {
        WsPushBroadcast message;
        try {
            message = objectMapper.readValue(messageJson, WsPushBroadcast.class);
        } catch (Exception e) {
            log.warn("[WsPushSubscriber] 广播消息解析失败（丢弃）", e);
            return;
        }
        WebSocketSession session = connectionRegistry.get(message.userId());
        if (session != null && session.isOpen()) {
            pusher.push(session, message.frame());
            return;
        }
        // 本地 miss：路由指向自己但连接已断/迁走——过期条目，清理后丢弃（漏推由补拉兜底）
        log.info("[WsPushSubscriber] 广播 miss userId={}，清理本实例路由条目", message.userId());
        routeStore.revokeByEndpoint(message.userId(), routeProperties.getSelfEndpoint());
    }
}
