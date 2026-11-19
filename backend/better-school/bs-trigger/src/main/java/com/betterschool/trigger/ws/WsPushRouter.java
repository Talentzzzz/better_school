package com.betterschool.trigger.ws;

import com.betterschool.domain.chat.adapter.port.IWsPushBroadcastPort;
import com.betterschool.domain.chat.adapter.port.IWsRoutePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

/**
 * WS 推送路由器（多实例推送路由）：本地优先 → Redis 路由表 → pub/sub 定向频道，尽力而为。
 * <p>三段语义：
 * <ul>
 *   <li>本地命中：查本机连接注册表，持有即推（单机形态到此为止，与旧路径等价）</li>
 *   <li>定向发布：Redis 查 uid → 实例端点，不是自己则发布到目标实例的专属频道
 *       （每实例只订阅自己的频道，只有持有连接的那台会收到并推帧）</li>
 *   <li>尽力而为：PUBLISH 订阅者数为 0（目标实例重启/掉线，路由已过期）则清理条目后放弃；
 *       发布失败同样放弃——漏推合法，由客户端按 (roomId, seq) 判重的增量补拉兜底，
 *       转发不承担可靠性</li>
 * </ul>
 * 路由信息只是「大概率对」（漂移窗口内可能已迁移）：目标实例本地 miss 时自行清理自己名下的条目
 */
@Slf4j
@Component
public class WsPushRouter {

    private final ChatConnectionRegistry connectionRegistry;
    private final ChatWsPusher pusher;
    private final IWsRoutePort wsRoutePort;
    private final IWsPushBroadcastPort wsPushBroadcastPort;
    private final WsRouteProperties routeProperties;
    private final ObjectMapper objectMapper;

    public WsPushRouter(ChatConnectionRegistry connectionRegistry,
                        ChatWsPusher pusher,
                        IWsRoutePort wsRoutePort,
                        IWsPushBroadcastPort wsPushBroadcastPort,
                        WsRouteProperties routeProperties,
                        ObjectMapper objectMapper) {
        this.connectionRegistry = connectionRegistry;
        this.pusher = pusher;
        this.wsRoutePort = wsRoutePort;
        this.wsPushBroadcastPort = wsPushBroadcastPort;
        this.routeProperties = routeProperties;
        this.objectMapper = objectMapper;
    }

    /** 投递一帧：本地命中即推，否则发布到目标实例的定向频道；尽力而为，漏推合法 */
    public void deliver(String userId, ChatWsFrame frame) {
        WebSocketSession session = connectionRegistry.get(userId);
        if (session != null && session.isOpen()) {
            pusher.push(session, frame);
            return;
        }
        if (!routeProperties.enabled()) {
            return;
        }
        String endpoint = wsRoutePort.lookupEndpoint(userId);
        if (endpoint == null) {
            return;
        }
        if (endpoint.equals(routeProperties.getSelfEndpoint())) {
            // 路由指向自己但本地无连接：刚断线/判死、注销未落——清理后放弃
            wsRoutePort.revokeByEndpoint(userId, endpoint);
            return;
        }
        publish(endpoint, userId, frame);
    }

    private void publish(String endpoint, String userId, ChatWsFrame frame) {
        try {
            String messageJson = objectMapper.writeValueAsString(new WsPushBroadcast(userId, frame));
            long subscribers = wsPushBroadcastPort.publish(endpoint, messageJson);
            if (subscribers == 0) {
                // 目标实例没在线（重启/掉线，订阅已断）：路由必然过期，清理后放弃
                log.info("[WsPushRouter] 目标实例不在线 endpoint={} userId={}，清理路由条目", endpoint, userId);
                wsRoutePort.revokeByEndpoint(userId, endpoint);
            }
        } catch (Exception e) {
            // 发布失败（Redis 抖动/序列化异常）结果不确定：不动路由（TTL 自然过期），放弃本次推送
            log.warn("[WsPushRouter] 定向发布失败 userId={} endpoint={}（漏推由补拉兜底）", userId, endpoint, e);
        }
    }
}
