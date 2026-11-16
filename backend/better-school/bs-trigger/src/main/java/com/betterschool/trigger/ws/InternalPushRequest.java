package com.betterschool.trigger.ws;

/**
 * 实例间内部转发请求体：目标 userId + 待推 WS 帧（JSON 反序列化后 payload 为 Map，原样再序列化即可）
 */
public record InternalPushRequest(String userId, ChatWsFrame frame) {
}
