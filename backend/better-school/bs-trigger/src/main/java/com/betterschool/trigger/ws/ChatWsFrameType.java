package com.betterschool.trigger.ws;

/**
 * WS 协议信封类型（spec §3）：C→S 仅 PING；S→C PONG/MSG/READ/RECALL/KICK。
 * 枚举名即线上传输值（JSON 序列化为 name）
 */
public enum ChatWsFrameType {

    PING, PONG, MSG, READ, RECALL, KICK
}
