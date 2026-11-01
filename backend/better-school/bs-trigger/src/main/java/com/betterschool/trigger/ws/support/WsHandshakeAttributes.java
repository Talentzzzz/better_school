package com.betterschool.trigger.ws.support;

/**
 * WS 握手/连接层共享属性键（连接层骨架，票 10）：chat 与 review 两端点共用同一套键名，
 * 各端点协议类（ChatWsProtocol / ReviewWsProtocol）以常量委托本类
 */
public final class WsHandshakeAttributes {

    /** 握手拦截器写入的登录用户 id 属性键 */
    public static final String ATTR_USER_ID = "wsUserId";

    /** Handler 建连时写入的并发写保护包装 session 属性键（close 回调据此取回注销） */
    public static final String ATTR_DECORATED_SESSION = "wsDecoratedSession";

    private WsHandshakeAttributes() {
    }
}
