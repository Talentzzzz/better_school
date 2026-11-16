package com.betterschool.trigger.ws;

/**
 * WS 协议信封类型（spec §3）：C→S 仅 PING；S→C PONG/MSG/READ/RECALL/NOTIF/ORDER/KICK。
 * 枚举名即线上传输值（JSON 序列化为 name）：
 * NOTIF=通知横幅（BANNER 通道，在线即弹）；ORDER=跑腿订单状态变化（订单详情页实时刷新）
 */
public enum ChatWsFrameType {

    PING, PONG, MSG, READ, RECALL, NOTIF, ORDER, KICK
}
