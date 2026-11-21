package com.betterschool.infrastructure.ws.wsframe;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * KICK 帧线上契约：{reason}，随后 close(4001) 阻断自动重连。
 * 字段名即线上 JSON 键名，由 {@link WsFrameFactory} 显式映射
 */
@Getter
@AllArgsConstructor
public class WsKickPayload {

    private final String reason;
}
