package com.betterschool.infrastructure.adapter.port.notification;

import com.betterschool.domain.notification.adapter.port.IBroadcastRecipientPort;
import com.betterschool.domain.user.adapter.repository.IUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 公告广播收件人端口实现：只读 user 域拿全量用户 ID。
 * 落位在 adapter/port（不是 repository），跨域只读边的伸手点集中在这里
 */
@Component
@RequiredArgsConstructor
public class BroadcastRecipientPort implements IBroadcastRecipientPort {

    private final IUserRepository userRepository;

    @Override
    public List<String> listAllUserIds() {
        return userRepository.listAllUserIds();
    }
}
