package com.betterschool.trigger.ws;

import com.betterschool.domain.chat.adapter.port.IWsTicketPort;
import com.betterschool.domain.user.adapter.repository.IUserRepository;
import com.betterschool.domain.user.model.valobj.UserRoleVO;
import com.betterschool.trigger.ws.support.AbstractWsTicketHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * review WS 握手拦截（spec §5.3「presence：只收 AUDITOR/ADMIN」，票 10）：
 * 票据校验之外叠加**角色过滤**（新逻辑）——票据换出 userId 后回查用户角色，
 * 非 AUDITOR/ADMIN 一律 401。角色在握手时点回查而非只看票据签发口，
 * 是为了堵「普通用户拿 chat 票据跨端点握手」的绕行（票据与端点无关）
 */
@Component
@RequiredArgsConstructor
public class ReviewWsHandshakeInterceptor extends AbstractWsTicketHandshakeInterceptor {

    private final IWsTicketPort wsTicketPort;
    private final IUserRepository userRepository;

    @Override
    protected String authenticateUserId(String ticket) {
        String userId = wsTicketPort.take(ticket);
        if (userId == null) {
            return null;
        }
        var user = userRepository.findById(userId);
        if (user == null) {
            return null;
        }
        UserRoleVO role = user.getRole();
        return role == UserRoleVO.AUDITOR || role == UserRoleVO.ADMIN ? userId : null;
    }
}
