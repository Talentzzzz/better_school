package com.betterschool.trigger.ws;

import com.betterschool.domain.chat.adapter.port.IWsRoutePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.socket.WebSocketSession;

/**
 * WS 推送路由器（多实例推送路由）：本地优先 → Redis 路由表 → HTTP 转发，失败即放弃。
 * <p>三段语义：
 * <ul>
 *   <li>本地命中：查本机连接注册表，持有即推（单机形态到此为止，与旧路径等价）</li>
 *   <li>路由转发：Redis 查 uid → 端点，不是自己则 HTTP 打到目标实例的内部端点，
 *       由它对本机注册表二次确认（路由信息只是「大概率对」，漂移窗口内可能已迁移）</li>
 *   <li>失败放弃：明确 miss（CAS 清理过期条目）与调用失败（TTL 自然过期）都直接放弃，
 *       漏推由客户端按 (roomId, seq) 判重的增量补拉兜底——转发不承担可靠性</li>
 * </ul>
 * 转发调用占消费线程调用栈，超时压短（连接 300ms / 读 800ms）：宁可靠补拉，不挂死消费队列
 */
@Slf4j
@Component
public class WsPushRouter {

    private static final String INTERNAL_PUSH_PATH = "/internal/ws/push";
    private static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

    private final ChatConnectionRegistry connectionRegistry;
    private final ChatWsPusher pusher;
    private final IWsRoutePort wsRoutePort;
    private final WsRouteProperties routeProperties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public WsPushRouter(ChatConnectionRegistry connectionRegistry,
                        ChatWsPusher pusher,
                        IWsRoutePort wsRoutePort,
                        WsRouteProperties routeProperties,
                        ObjectMapper objectMapper) {
        this.connectionRegistry = connectionRegistry;
        this.pusher = pusher;
        this.wsRoutePort = wsRoutePort;
        this.routeProperties = routeProperties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(300);
        factory.setReadTimeout(800);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * 投递一帧：返回是否确定送达（本地推出或转发方确认 hit）；
     * false = 放弃（不在线/miss/转发失败），漏推合法
     */
    public boolean deliver(String userId, ChatWsFrame frame) {
        WebSocketSession session = connectionRegistry.get(userId);
        if (session != null && session.isOpen()) {
            pusher.push(session, frame);
            return true;
        }
        if (!routeProperties.enabled()) {
            return false;
        }
        String endpoint = wsRoutePort.lookupEndpoint(userId);
        if (endpoint == null) {
            return false;
        }
        if (endpoint.equals(routeProperties.getSelfEndpoint())) {
            // 路由指向自己但本地无连接：刚断线/判死、注销未落——清理后放弃
            wsRoutePort.revokeByEndpoint(userId, endpoint);
            return false;
        }
        return forward(endpoint, userId, frame);
    }

    private boolean forward(String endpoint, String userId, ChatWsFrame frame) {
        try {
            InternalPushResponse response = restClient.post()
                    .uri(endpoint + INTERNAL_PUSH_PATH)
                    .header(INTERNAL_TOKEN_HEADER, routeProperties.getInternalToken())
                    .body(objectMapper.writeValueAsString(new InternalPushRequest(userId, frame)))
                    .retrieve()
                    .body(InternalPushResponse.class);
            if (response != null && response.hit()) {
                return true;
            }
            // 明确 miss：路由过期（连接已断/迁到别处），CAS 清理后放弃
            log.info("[WsPushRouter] 转发 miss userId={} endpoint={}，清理路由条目", userId, endpoint);
            wsRoutePort.revokeByEndpoint(userId, endpoint);
            return false;
        } catch (Exception e) {
            // 调用失败（超时/宕机）结果不确定：不动路由（TTL 自然过期），放弃本次推送
            log.warn("[WsPushRouter] 转发失败 userId={} endpoint={}（漏推由补拉兜底）", userId, endpoint, e);
            return false;
        }
    }
}
