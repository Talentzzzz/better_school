package com.betterschool.domain.chat.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * WS 协议信封（JSON 文本帧，spec §3）：{type, roomId?, seq?, payload?, ts}
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatWsFrame {

    private ChatWsFrameType type;

    private String roomId;

    private Long seq;

    /** MSG=完整消息体；KICK={reason}；READ={userId, readSeq}；其余为空 */
    private Object payload;

    private Long ts;
}
