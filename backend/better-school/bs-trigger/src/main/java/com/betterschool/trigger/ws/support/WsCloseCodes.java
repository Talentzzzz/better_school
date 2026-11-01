package com.betterschool.trigger.ws.support;

/**
 * WS 互踢关闭口径（连接层骨架，票 10）：各端点共用同一关闭码与 KICK 原因，
 * 客户端据此停止自动重连（由用户重新登录）
 */
public final class WsCloseCodes {

    /** 互踢关闭码：RFC 6455 私有段 4000~4999，客户端收到即停止自动重连 */
    public static final int CLOSE_CODE_TAKEN_OVER = 4001;

    public static final String KICK_REASON_SESSION_TAKEN_OVER = "SESSION_TAKEN_OVER";

    private WsCloseCodes() {
    }
}
