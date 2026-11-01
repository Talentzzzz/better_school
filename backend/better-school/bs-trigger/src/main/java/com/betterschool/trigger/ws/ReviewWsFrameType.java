package com.betterschool.trigger.ws;

/**
 * review WS 协议信封类型（spec §5.3/§5.4，票 10）：C→S 仅 PING（心跳口径与 chat 相同，
 * 任意入站帧刷新活跃时间）；S→C PONG/REVIEW_SIGNAL/KICK。
 * REVIEW_SIGNAL 是审核专属帧（派单信号），其内容与推送时机归票 11——本票只建通道。
 * 枚举名即线上传输值（JSON 序列化为 name）
 */
public enum ReviewWsFrameType {

    PING, PONG, REVIEW_SIGNAL, KICK
}
