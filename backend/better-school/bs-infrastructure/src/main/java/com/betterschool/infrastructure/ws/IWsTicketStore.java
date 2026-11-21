package com.betterschool.infrastructure.ws;

/**
 * WS 连接票据存储（infrastructure/ws 技术组件的对外 API，spec §3）：
 * 60s 一次性票据；实现须原子取删——同一票据只有一次生效机会，重放失败
 */
public interface IWsTicketStore {

    /** 签发一张新票据：绑定当前用户，60s 有效 */
    String createTicket(String userId);

    /** 原子取删校验：有效返回绑定的 userId 并立刻作废，无效（不存在/已用/过期）返回 null */
    String validateAndConsume(String ticket);
}
