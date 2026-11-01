package com.betterschool.infrastructure.adapter.port.moderation;

import com.betterschool.domain.moderation.adapter.port.IReviewPresencePort;
import com.betterschool.types.common.Constants;
import org.redisson.api.RMap;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 审核端 presence 实现（Redis RMap，spec §5.3）：单 key Hash，field=userId、value=最近活跃毫秒。
 * 连接触达写入、心跳刷新、断开删除都是 O(1) 单 field 操作；在线快照走 readAllMap。
 * 实例崩溃留下的无主条目靠 lastSeenMs 新鲜度过滤（读方口径），不做后台清扫
 */
@Component
public class ReviewPresencePort implements IReviewPresencePort {

    private final RedissonClient redissonClient;

    public ReviewPresencePort(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    @Override
    public void touch(String userId, long lastSeenAtMs) {
        presenceMap().put(userId, lastSeenAtMs);
    }

    @Override
    public void markOffline(String userId) {
        presenceMap().remove(userId);
    }

    @Override
    public Map<String, Long> onlineSnapshot() {
        return presenceMap().readAllMap();
    }

    @Override
    public int countOnline(long freshnessMs) {
        long now = System.currentTimeMillis();
        return (int) presenceMap().readAllMap().values().stream()
                .filter(lastSeenMs -> now - lastSeenMs <= freshnessMs)
                .count();
    }

    private RMap<String, Long> presenceMap() {
        return redissonClient.getMap(Constants.REVIEW_PRESENCE_KEY);
    }
}
