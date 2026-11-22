package com.betterschool.infrastructure.ws.wsframe;

import lombok.Getter;

/**
 * WS 协议信封（JSON 文本帧）：{type, payload?, ts}——与业务无关，所有业务通用。
 * roomId/seq 等业务字段一律随 payload 下发（形状按 type 解释，线上契约由同包
 * 各 Ws*Payload 类显式承载：WsMsgPayload/WsReadPayload/WsRecallPayload/WsNotifPayload/WsOrderPayload/WsKickPayload），
 * 信封不开业务专属槽位。
 * <p>泛型参数 T = payload 的契约类型，只声明组帧那一刻的载荷形状；帧一出工厂进入传输链
 * （hub → pusher → router → Redis）即按 {@code WsFrame<?>} 传递——各帧载荷类型互异，
 * 路由/推送层不感知载荷，T 的信息在链上自然退化为通配符。type ↔ payload 的配对
 * 由 {@link WsFrameFactory} 收口保证：构造器包私有，工厂是唯一合法构造入口
 * （@Getter 只读、字段 final，无 setter/builder 后门）。
 * <p>S→C 纯出站信封，生产代码不反序列化本类（C→S 仅 PING，入站由 WsHandler readTree 识别；
 * 跨实例转发传原样 frameJson）——私有无参构造仅供 Jackson 反序列化还原帧
 * （测试侧 wire 还原用，payload 还原为 Map）
 */
@Getter
public class WsFrame<T> {

    private final WsFrameType type;

    /** 与业务无关的载荷槽位：形状按 type 解释，契约见对应 Ws*Payload 类 */
    private final T payload;

    private final Long ts;

    WsFrame(WsFrameType type, T payload, Long ts) {
        this.type = type;
        this.payload = payload;
        this.ts = ts;
    }

    /** 仅供 Jackson 反序列化还原帧（当前仅测试侧从 wire JSON 还原，payload 为 Map） */
    private WsFrame() {
        this(null, null, null);
    }
}
