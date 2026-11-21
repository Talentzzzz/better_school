package com.betterschool.infrastructure.ws.wsframe;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * RECALL 帧线上契约：轻事件只带定位 {roomId, seq}，内容终态以拉取为准。
 * 字段名即线上 JSON 键名，由 {@link WsFrameFactory} 显式映射
 */
@Getter
@AllArgsConstructor
public class WsRecallPayload {

    private final String roomId;

    private final Long seq;
}
