package com.betterschool.infrastructure.ws.wsframe;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * READ 帧线上契约：{roomId, userId(readerId), readSeq}，供前端就地更新成员水位 map。
 * 字段名即线上 JSON 键名，由 {@link WsFrameFactory} 显式映射
 */
@Getter
@AllArgsConstructor
public class WsReadPayload {

    private final String roomId;

    private final String userId;

    private final Long readSeq;
}
