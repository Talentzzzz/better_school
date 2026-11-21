package com.betterschool.infrastructure.ws.wsframe;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * ORDER 帧线上契约：{orderId, status}，前端收到即重拉订单详情。
 * 字段名即线上 JSON 键名，由 {@link WsFrameFactory} 显式映射
 */
@Getter
@AllArgsConstructor
public class WsOrderPayload {

    private final String orderId;

    private final String status;
}
