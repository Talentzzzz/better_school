package com.betterschool.marketing.integration.support;

import com.betterschool.marketing.domain.common.adapter.port.marketing.IUserQueryPort;
import com.betterschool.marketing.domain.common.model.valobj.marketing.UserBasicVO;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单体的 stub 替身（测试源码里的 {@code @Primary} Bean，覆盖 infrastructure 的真 Dubbo Consumer）。
 * <p>
 * 真实现要单体作为 Dubbo Provider 起着才通（跨进程调用归票 08/09 的联调），
 * 本服务的集成测试只关心「拿到 createdAt / 昵称头像之后怎么用」，所以把这一跳换成可控的桩：
 * 用例先把用户塞进来，就得到确定的注册时间与展示信息。
 */
@Component
@Primary
public class StubUserQueryPort implements IUserQueryPort {

    private final Map<String, UserBasicVO> users = new ConcurrentHashMap<>();

    public void put(String userId, String nickname, String avatar, LocalDateTime createdAt) {
        UserBasicVO user = new UserBasicVO();
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
    public UserBasicVO getBasic(String userId) {
        return userId == null ? null : users.get(userId);
    }
}
