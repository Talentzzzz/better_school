package com.betterschool.trigger.ws;

import com.betterschool.types.ws.WsCloseCodes;
import com.betterschool.types.ws.WsHandshakeAttributes;

/**
 * WS 连接层常量（本端点门面）：端点、握手属性键、互踢关闭码。
 * 跨模块共享的真身常量（属性键、关闭码）定义在 types.ws，
 * 本类以常量委托为 trigger/app 侧消费方提供本地归口；仅入站侧使用的 ENDPOINT 就地定义
 */
public final class WsConnectionConstants {

    public static final String ENDPOINT = "/ws";

    /** 握手拦截器写入的登录用户 id 属性键 */
    public static final String ATTR_USER_ID = WsHandshakeAttributes.ATTR_USER_ID;

    /** Handler 建连时写入的并发写保护包装 session 属性键（close 回调据此取回注销） */
    public static final String ATTR_DECORATED_SESSION = WsHandshakeAttributes.ATTR_DECORATED_SESSION;

    /** 互踢关闭码：RFC 6455 私有段 4000~4999，客户端收到即停止自动重连 */
    public static final int CLOSE_CODE_TAKEN_OVER = WsCloseCodes.CLOSE_CODE_TAKEN_OVER;

    public static final String KICK_REASON_SESSION_TAKEN_OVER = WsCloseCodes.KICK_REASON_SESSION_TAKEN_OVER;

    private WsConnectionConstants() {
    }
}
