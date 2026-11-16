package com.betterschool.infrastructure.adapter.port.chat;

import com.betterschool.domain.chat.adapter.port.IWsRoutePort;
import com.betterschool.types.common.Constants;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * WS 连接路由表（Redis，多实例推送路由）：value = {@code 端点|sessionId}。
 * 注册/续期/注销都是「比对后操作」——接管与迁移场景下不会误删/误续别人的条目；
 * 查询只返回端点部分（sessionId 是防误删的代际标识，不参与转发）
 */
@Component
@RequiredArgsConstructor
public class WsRoutePort implements IWsRoutePort {

    private final RedissonClient redissonClient;

    @Override
    public void register(String userId, String token) {
        redissonClient.<String>getBucket(Constants.WS_ROUTE_KEY + userId)
                .set(token, Duration.ofSeconds(Constants.WS_ROUTE_TTL_SECONDS));
    }

    @Override
    public void renew(String userId, String token) {
        RBucket<String> bucket = redissonClient.getBucket(Constants.WS_ROUTE_KEY + userId);
        if (token.equals(bucket.get())) {
            bucket.set(token, Duration.ofSeconds(Constants.WS_ROUTE_TTL_SECONDS));
        }
    }

    @Override
    public void revoke(String userId, String token) {
        // Redisson compareAndSet 内部即 Lua 原子脚本：值仍等于 token 才置 null（Redisson 语义 = 删除）。
        // 严格原子化的理由：迟到的注销竞态若盲删，会把同实例接管后的新条目一起删掉，
        // 且该竞态无法靠 TTL 快速自愈（新条目删除后要等客户端重连才重建）——这里值得多一层原子性
        redissonClient.<String>getBucket(Constants.WS_ROUTE_KEY + userId).compareAndSet(token, null);
    }

    @Override
    public void revokeByEndpoint(String userId, String endpoint) {
        // 转发 miss 清理：转发方只持有端点、不知道 sessionId，做不了 compareAndSet（需要完整 token）。
        // 两步读比删的竞态后果有界：最坏误删一条仍有效的条目，下次推送 miss 后由客户端重连/补拉自愈，
        // 且 TTL 90s 兜底收敛——加速数据上的竞态不值得上脚本，直白优先
        RBucket<String> bucket = redissonClient.getBucket(Constants.WS_ROUTE_KEY + userId);
        String value = bucket.get();
        if (value != null && value.startsWith(endpoint + "|")) {
            bucket.delete();
        }
    }

    @Override
    public String lookupEndpoint(String userId) {
        String value = redissonClient.<String>getBucket(Constants.WS_ROUTE_KEY + userId).get();
        int split = value == null ? -1 : value.indexOf('|');
        return split <= 0 ? null : value.substring(0, split);
    }
}
