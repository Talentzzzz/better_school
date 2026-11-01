package com.betterschool.trigger.ws;

import com.betterschool.trigger.ws.support.AbstractWsJsonPusher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * chat WS 帧写出器（spec §3）：并发写保护/推送/互踢/静默关闭为共享骨架（票 10），
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
                .payload(Map.of("reason", ChatWsProtocol.KICK_REASON_SESSION_TAKEN_OVER))
                .ts(System.currentTimeMillis())
                .build();
    }

    @Override
    protected String frameTypeName(ChatWsFrame frame) {
        return String.valueOf(frame.getType());
    }
}
