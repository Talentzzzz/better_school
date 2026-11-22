package com.betterschool.types.ws;

/**
 * WS 握手/连接层共享属性键（WS 连接层共享常量）：握手拦截器写入、连接处理器读取，
 * 各端点协议类以常量委托本类。
 * 仅放跨模块契约（登录用户 id）；infrastructure/ws 组件内部状态（如并发写保护装饰件）
 * 不经握手属性交接，不出组件
 */
public final class WsHandshakeAttributes {

    /** 握手拦截器写入的登录用户 id 属性键 */
    public static final String ATTR_USER_ID = "wsUserId";

    private WsHandshakeAttributes() {
    }

}
