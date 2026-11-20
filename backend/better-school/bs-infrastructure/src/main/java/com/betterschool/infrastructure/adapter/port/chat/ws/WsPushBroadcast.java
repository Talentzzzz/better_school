package com.betterschool.infrastructure.adapter.port.chat.ws;

import com.betterschool.domain.chat.model.valobj.ChatWsFrame;

/**
 * 跨实例推送广播消息体（pub/sub 定向频道负载，JSON 文本）：
 * userId 用于目标实例查本机注册表，frame 为原样协议信封
 */
public record WsPushBroadcast(String userId, ChatWsFrame frame) {
}
