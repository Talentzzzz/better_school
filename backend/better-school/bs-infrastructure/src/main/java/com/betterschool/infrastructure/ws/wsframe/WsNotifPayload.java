package com.betterschool.infrastructure.ws.wsframe;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * NOTIF 帧线上契约：scope/seq/type/title/content/refType/refId，与前端横幅渲染约定；
 * refType/refId 由工厂做 null→"" 归一化。字段名即线上 JSON 键名，由 {@link WsFrameFactory} 显式映射
 */
@Getter
@AllArgsConstructor
public class WsNotifPayload {

    private final String scope;

    private final Long seq;

    private final String type;

    private final String title;

    private final String content;

    private final String refType;

    private final String refId;
}
