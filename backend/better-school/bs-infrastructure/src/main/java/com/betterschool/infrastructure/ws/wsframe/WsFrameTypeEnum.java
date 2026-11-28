package com.betterschool.infrastructure.ws.wsframe;

/**
 * WS 协议信封类型（spec §3）：C→S 仅 PING；S→C PONG/MSG/READ/RECALL/NOTIF/KICK。
 * 枚举名即线上传输值（JSON 序列化为 name）：
 * NOTIF=通知横幅（BANNER 通道，在线即弹）
 */
public enum WsFrameTypeEnum {

    PING, PONG, MSG, READ, RECALL, NOTIF, KICK
}
