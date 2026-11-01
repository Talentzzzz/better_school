package com.betterschool.trigger.ws;

import com.betterschool.trigger.ws.support.WsCloseCodes;
import com.betterschool.trigger.ws.support.WsHandshakeAttributes;

/**
 * chat WS 连接层常量（spec §3）：端点、握手属性键、互踢关闭码。
 * 属性键与互踢口径为两端点共享骨架（票 10），以常量委托 support 包
 */
public final class ChatWsProtocol {

    public static final String ENDPOINT = "/ws/chat";

    /** 握手拦截器写入的登录用户 id 属性键 */
    public static final String ATTR_USER_ID = WsHandshakeAttributes.ATTR_USER_ID;

    /** Handler 建连时写入的并发写保护包装 session 属性键（close 回调据此取回注销） */
    public static final String ATTR_DECORATED_SESSION = WsHandshakeAttributes.ATTR_DECORATED_SESSION;

    /** 互踢关闭码：RFC 6455 私有段 4000~4999，客户端收到即停止自动重连 */
    public static final int CLOSE_CODE_TAKEN_OVER = WsCloseCodes.CLOSE_CODE_TAKEN_OVER;

    public static final String KICK_REASON_SESSION_TAKEN_OVER = WsCloseCodes.KICK_REASON_SESSION_TAKEN_OVER;

    private ChatWsProtocol() {
    }
}
