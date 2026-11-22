package com.betterschool.infrastructure.ws;

/**
 * WS 连接票据契约（spec §3）：握手前的一次性入场券——60s 有效，
 * 原子取删保证同一票据只有一次生效机会，并发握手重放必有一个失败。
 * 实现：{@link WsTicketRepository}
 */
public interface IWsTicketRepository {

    /** 签发一张新票据：绑定当前用户，60s 有效（spec §3） */
    String createTicket(String userId);

    /** 原子取删校验：有效返回绑定的 userId 并立刻作废，无效（不存在/已用/过期）返回 null（spec §3） */
    String validateAndConsume(String ticket);
}
