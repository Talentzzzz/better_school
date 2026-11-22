package com.betterschool.infrastructure.ws.wsframe;

import com.betterschool.domain.chat.model.valobj.CardRefTypeVO;
import com.betterschool.domain.chat.model.valobj.MsgTypeVO;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

/**
 * MSG 帧线上契约：完整消息体——判重键 (roomId, seq)、发送者昵称头像随帧下发，气泡渲染免二次查询。
 * 字段名即线上 JSON 键名，由 {@link WsFrameFactory} 显式映射
 * （禁止域事件直传，防止域字段重构静默改变线上协议）
 */
@Getter
@AllArgsConstructor
public class WsMsgPayload {

    private final String roomId;

    /** 会话内判重键一半 */
    private final Long seq;

    private final String messageId;

    private final String fromUserId;

    private final String fromNickname;

    private final String fromAvatar;

    private final MsgTypeVO msgType;

    private final String content;

    private final CardRefTypeVO refType;

    private final String refId;

    private final String clientMsgId;

    private final LocalDateTime createdAt;
}
