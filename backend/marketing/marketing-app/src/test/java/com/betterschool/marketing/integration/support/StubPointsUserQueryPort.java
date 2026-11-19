package com.betterschool.marketing.integration.support;

import com.betterschool.marketing.domain.points.adapter.port.marketing.IUserQueryPort;
import com.betterschool.marketing.domain.points.model.valobj.PointsUserVO;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单体用户中心的 stub 替身（积分域端口）：测试源码里的 {@code @Primary} Bean，
 * 覆盖 infrastructure 的 {@code PointsUserQueryPort}（真实现要单体作为 Dubbo Provider 起着才通）。
 * <p>
 * 每个消费方域一个桩：端口按域声明、桩也按域实现（两个域的读视图类型不同，本就没法共用一份实现）。
 */
@Component
@Primary
public class StubPointsUserQueryPort implements IUserQueryPort {

    private final Map<String, PointsUserVO> users = new ConcurrentHashMap<>();

    public void put(String userId, String nickname, String avatar, LocalDateTime createdAt) {
        PointsUserVO user = new PointsUserVO();
        user.setUserId(userId);
        user.setNickname(nickname);
        user.setAvatar(avatar);
        user.setCreatedAt(createdAt);
        users.put(userId, user);
    }

    public void clear() {
        users.clear();
    }

    @Override
    public PointsUserVO getBasic(String userId) {
        return userId == null ? null : users.get(userId);
    }
}
