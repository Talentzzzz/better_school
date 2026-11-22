package com.betterschool.infrastructure.ws;

import com.betterschool.infrastructure.ws.wsframe.WsFrame;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.time.Duration;

/**
 * WS 多实例路由组件实现：{@link IWsRouter} 的机器本体（Redis）。
 * 条目：key = {@code Constants.WS_ROUTE_KEY + userId}，value token = {@code 实例 URL|sessionId}（代际标识），
 * TTL {@code Constants.WS_ROUTE_TTL_SECONDS}s 兜底收敛（入站帧心跳续期）；调用方只交接 (userId, sessionId)。
 * <p>两种注销口径的原子性差异是刻意的：
 * unbind 走 Redisson compareAndSet（内部即 Lua 原子脚本，值仍等于 token 才删）——
 * 互踢/接管后迟到的 close 若盲删，会把接管后的新条目一起删掉，
 * 且该竞态无法靠 TTL 快速自愈（新条目删除后要等客户端重连才重建），这里值得多一层原子性；
 * cleanForInstance 是转发 miss 清理，转发方只持有实例 URL、没有 sessionId 做不了 CAS——
 * 两步读-比-删的竞态后果有界：最坏误删一条仍有效的条目，
 * 下次推送 miss 后由客户端重连/补拉自愈，TTL 兜底收敛，加速数据上的竞态不值得上脚本，直白优先
 * <p>定向投递运输（pub/sub 定向频道，Redisson RTopic）是本类内部实现细节：
 * channel = {@code Constants.WS_PUSH_CHANNEL_PREFIX + 实例 URL}，每实例一条，发布即定向，
 * 只有持有连接的那台会收到并推帧；收端启动即订阅自己的频道，收到转发件后对本机注册表二次确认——
 * 路由信息只是「大概率对」（漂移窗口内可能已迁移）；本地 miss = 路由过期（连接已断/迁走），
 * 清理本实例名下条目后丢弃，这是 fire-and-forget 通道下的过期兜底；命中即直发线上 JSON。
 * 目标实例不在线（PUBLISH 订阅者数为 0）/发布失败/序列化异常一律清理路由条目后放弃——
 * 漏推合法，由客户端按 (roomId, seq) 判重的增量补拉兜底，转发不承担可靠性；
 * PUBLISH 返回订阅者数是 fire-and-forget 通道能提供的全部回执——订阅了≠推到 WS 连接了。
 * 转发信封是本类内部实现细节：消息体用 StringCodec 走原样 JSON 字符串——序列化口径归本类
 * （持有协议信封），避免 Redisson 全局编解码器对 Object payload 做默认类型注入
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WsRouter implements IWsRouter, InitializingBean, DisposableBean {

    private final RedissonClient redissonClient;
    private final WsRouteProperties routeProperties;
    private final WsLocalSessions localSessions;
    private final IWsSender frameSender;
    private final ObjectMapper objectMapper;

    private Integer listenerId;

    // ---- 记账：路由条目生命周期 ----

    @Override
    public void bind(String userId, String sessionId) {
        if (!routeProperties.enabled()) {
            return;
        }
        bucket(userId).set(token(sessionId), Duration.ofSeconds(Constants.WS_ROUTE_TTL_SECONDS));
    }

    @Override
    public void renew(String userId, String sessionId) {
        if (!routeProperties.enabled()) {
            return;
        }
        RBucket<String> bucket = bucket(userId);
        String token = token(sessionId);
        if (token.equals(bucket.get())) {
            bucket.set(token, Duration.ofSeconds(Constants.WS_ROUTE_TTL_SECONDS));
        }
    }

    @Override
    public void unbind(String userId, String sessionId) {
        if (!routeProperties.enabled()) {
            return;
        }
        // Redisson compareAndSet 内部即 Lua 原子脚本：值仍等于 token 才置 null（Redisson 语义 = 删除）
        bucket(userId).compareAndSet(token(sessionId), null);
    }

    // ---- 定向投递：跨实例转发（发端） ----

    @Override
    public void forward(String userId, WsFrame<?> frame) {
        if (!routeProperties.enabled()) {
            return;
        }
        String instanceUrl = findInstanceUrl(userId);
        if (instanceUrl == null) {
            return;
        }
        if (instanceUrl.equals(routeProperties.getInstanceUrl())) {
            // 路由指向自己但本地无连接：刚断线/判死、注销未落——过期条目，清理后放弃
            cleanForInstance(userId, instanceUrl);
            return;
        }
        publish(instanceUrl, userId, frame);
    }

    private void publish(String instanceUrl, String userId, WsFrame<?> frame) {
        try {
            String frameJson = objectMapper.writeValueAsString(frame);
            String messageJson = objectMapper.writeValueAsString(new ForwardEnvelope(userId, frameJson));
            long subscribers = topicOf(instanceUrl).publish(messageJson);
            if (subscribers == 0) {
                // 目标实例没在线（重启/掉线，订阅已断）：路由必然过期，清理后放弃
                log.info("[WsRouter] 目标实例不在线 instanceUrl={} userId={}，清理路由条目", instanceUrl, userId);
                cleanForInstance(userId, instanceUrl);
            }
        } catch (Exception e) {
            // 发布失败（Redis 抖动/序列化异常）结果不确定：不动路由（TTL 自然过期），放弃本次推送
            log.warn("[WsRouter] 定向发布失败 userId={} instanceUrl={}（漏推由补拉兜底）", userId, instanceUrl, e);
        }
    }

    // ---- 定向投递：跨实例转发（收端：本实例频道的订阅与本地确认） ----

    @Override
    public void afterPropertiesSet() {
        if (!routeProperties.enabled()) {
            return;
        }
        String instanceUrl = routeProperties.getInstanceUrl();
        listenerId = topicOf(instanceUrl).addListener(String.class, (channel, message) -> onForward(message));
        log.info("[WsRouter] 已订阅本实例推送频道 instanceUrl={}", instanceUrl);
    }

    @Override
    public void destroy() {
        if (listenerId != null) {
            topicOf(routeProperties.getInstanceUrl()).removeListener(listenerId);
        }
    }

    private void onForward(String messageJson) {
        ForwardEnvelope message;
        try {
            message = objectMapper.readValue(messageJson, ForwardEnvelope.class);
        } catch (Exception e) {
            log.warn("[WsRouter] 转发件解析失败（丢弃）", e);
            return;
        }
        WebSocketSession session = localSessions.get(message.getUserId());
        if (session != null && session.isOpen()) {
            frameSender.pushJson(session, message.getFrameJson());
            return;
        }
        // 本地 miss：路由指向自己但连接已断/迁走——过期条目，清理后丢弃（漏推由补拉兜底）
        log.info("[WsRouter] 转发 miss userId={}，清理本实例路由条目", message.getUserId());
        cleanForInstance(message.getUserId(), routeProperties.getInstanceUrl());
    }

    // ---- 路由条目读写（内部） ----

    /** 过期清理（转发 miss / 目标实例不在线）：只持实例 URL 做不了 CAS，按 URL 前缀两步读-比-删 */
    private void cleanForInstance(String userId, String instanceUrl) {
        RBucket<String> bucket = bucket(userId);
        String value = bucket.get();
        if (value != null && value.startsWith(instanceUrl + "|")) {
            bucket.delete();
        }
    }

    /** 查询路由：userId → 实例 URL（条目 value 按 | 切前段）；无条目返回 null */
    private String findInstanceUrl(String userId) {
        String value = bucket(userId).get();
        int split = value == null ? -1 : value.indexOf('|');
        return split <= 0 ? null : value.substring(0, split);
    }

    private String token(String sessionId) {
        return routeProperties.getInstanceUrl() + "|" + sessionId;
    }

    private RBucket<String> bucket(String userId) {
        return redissonClient.<String>getBucket(Constants.WS_ROUTE_KEY + userId);
    }

    private RTopic topicOf(String instanceUrl) {
        return redissonClient.getTopic(Constants.WS_PUSH_CHANNEL_PREFIX + instanceUrl, StringCodec.INSTANCE);
    }

    /**
     * 跨实例转发信封（pub/sub 定向频道负载，JSON 文本）：本地 miss 时，
     * 推送方实例委托持有连接的目标实例代为推送。userId 用于目标实例查本机连接，
     * frameJson 为原样线上 JSON（收端拿到直接写出，免 反序列化→再序列化 一轮）。
     * 只活一跳：出生在本类 publish()，消亡在本类 onForward()——不落库/不重试/不排队，
     * 漏推由客户端 (roomId, seq) 增量补拉兜底
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ForwardEnvelope {

        private String userId;

        private String frameJson;
    }
}
