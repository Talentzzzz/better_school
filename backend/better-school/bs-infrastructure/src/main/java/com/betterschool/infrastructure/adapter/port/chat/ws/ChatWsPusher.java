package com.betterschool.infrastructure.adapter.port.chat.ws;

import com.betterschool.domain.chat.model.valobj.ChatWsFrame;
import com.betterschool.domain.chat.model.valobj.ChatWsFrameType;
import com.betterschool.infrastructure.adapter.port.chat.ws.support.AbstractWsJsonPusher;
import com.betterschool.types.ws.WsCloseCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * chat WS 帧写出器：并发写保护/推送/互踢/静默关闭为共享骨架（support 抽象类），
 * 本类只绑定 chat 协议信封与 KICK 帧
 */
@Component
public class ChatWsPusher extends AbstractWsJsonPusher<ChatWsFrame> {

    public ChatWsPusher(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    protected ChatWsFrame kickFrame() {
        return ChatWsFrame.builder()
                .type(ChatWsFrameType.KICK)
                .payload(Map.of("reason", WsCloseCodes.KICK_REASON_SESSION_TAKEN_OVER))
                .ts(System.currentTimeMillis())
                .build();
    }

    @Override
    protected String frameTypeName(ChatWsFrame frame) {
        return String.valueOf(frame.getType());
    }
}
