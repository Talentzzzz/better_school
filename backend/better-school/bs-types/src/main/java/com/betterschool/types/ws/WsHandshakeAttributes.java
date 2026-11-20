package com.betterschool.types.ws;

/**
 * WS 握手/连接层共享属性键（WS 连接层共享常量）：握手拦截器写入、连接处理器与
 * infra 侧投递适配器（登记/注销时取回装饰 session）共用同一套键名，
 * 各端点协议类以常量委托本类
 */
public final class WsHandshakeAttributes {

    /** 握手拦截器写入的登录用户 id 属性键 */
    public static final String ATTR_USER_ID = "wsUserId";

    /** Handler 建连时写入的并发写保护包装 session 属性键（close 回调据此取回注销） */
    public static final String ATTR_DECORATED_SESSION = "wsDecoratedSession";

    private WsHandshakeAttributes() {
    }
}
