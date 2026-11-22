package com.betterschool.marketing.integration.support;

import com.betterschool.marketing.domain.raffle.adapter.port.marketing.IUserQueryPort;
import com.betterschool.marketing.domain.raffle.model.valobj.RaffleUserVO;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单体用户中心的 stub 替身（抽奖域端口）：测试源码里的 {@code @Primary} Bean，
 * 覆盖 infrastructure 的 {@code RaffleUserQueryPort}（真实现要单体作为 Dubbo Provider 起着才通）。
 */
@Component
@Primary
public class StubRaffleUserQueryPort implements IUserQueryPort {

    private final Map<String, RaffleUserVO> users = new ConcurrentHashMap<>();

    public void put(String userId, String nickname, String avatar, LocalDateTime createdAt) {
        RaffleUserVO user = new RaffleUserVO();
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
    public RaffleUserVO getBasic(String userId) {
        return userId == null ? null : users.get(userId);
    }
}
