package com.betterschool.domain.chat.adapter.port;

/**
 * WS 连接票据端口：60s 一次性票据（握手认证换票，spec §3）。
 * 实现须原子取删——同一票据只有一次生效机会，重放失败
 */
public interface IWsTicketPort {

    /** 签发票据：绑定当前用户，60s 有效 */
    String issue(String userId);

    /** 原子取删校验：有效返回绑定的 userId 并立刻作废，无效（不存在/已用/过期）返回 null */
    String take(String ticket);
}
