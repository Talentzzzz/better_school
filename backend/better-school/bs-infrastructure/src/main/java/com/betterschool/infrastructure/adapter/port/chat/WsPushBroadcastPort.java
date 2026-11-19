package com.betterschool.infrastructure.adapter.port.chat;

import com.betterschool.domain.chat.adapter.port.IWsPushBroadcastPort;
import com.betterschool.types.common.Constants;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.codec.StringCodec;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;

/**
 * WS 跨实例推送 pub/sub 定向频道（Redis pub/sub，Redisson RTopic）：
 * channel = {@code bs:ws-push:{实例端点标识}}，每实例一条，发布即定向。
 * 消息体用 StringCodec 走原样 JSON 字符串——序列化口径归调用方（trigger 层持有协议信封），
 * 避免客户端全局编解码器对 Object payload 做默认类型注入。
 * PUBLISH 返回订阅者数：0 意味着目标实例不在线（重启/掉线），发布方据此清理过期路由条目；
 * 这是 fire-and-forget 通道能提供的全部回执——订阅了≠推到 WS 连接了，漏推由补拉兜底
 */
@Component
@RequiredArgsConstructor
public class WsPushBroadcastPort implements IWsPushBroadcastPort {

    private final RedissonClient redissonClient;

    @Override
    public long publish(String endpoint, String messageJson) {
        return topicOf(endpoint).publish(messageJson);
    }

    @Override
    public String subscribe(String endpoint, Consumer<String> listener) {
        return topicOf(endpoint).addListener(String.class, (channel, message) -> listener.accept(message));
    }

    @Override
    public void unsubscribe(String endpoint, String listenerId) {
        topicOf(endpoint).removeListener(listenerId);
    }

    private RTopic topicOf(String endpoint) {
        return redissonClient.getTopic(Constants.WS_PUSH_CHANNEL_PREFIX + endpoint, StringCodec.INSTANCE);
    }
}
