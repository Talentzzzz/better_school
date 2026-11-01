package com.betterschool.trigger.ws;

import com.betterschool.domain.chat.adapter.port.IWsTicketPort;
import com.betterschool.trigger.ws.support.AbstractWsTicketHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * chat WS 握手拦截（spec §3）：query 票据原子取删校验（60s 一次性，重放失败），
 * 通过后把登录用户 id 写入握手 attributes（骨架职责，票 10）；
 * 失败 401 拒绝握手。chat 端点不做角色过滤——任何登录用户皆可连
 */
@Component
@RequiredArgsConstructor
public class ChatWsHandshakeInterceptor extends AbstractWsTicketHandshakeInterceptor {

    private final IWsTicketPort wsTicketPort;

    @Override
    protected String authenticateUserId(String ticket) {
        return wsTicketPort.take(ticket);
    }
}
