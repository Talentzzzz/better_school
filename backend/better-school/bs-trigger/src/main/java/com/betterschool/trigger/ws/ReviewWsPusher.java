package com.betterschool.trigger.ws;

import com.betterschool.trigger.ws.support.AbstractWsJsonPusher;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * review WS 帧写出器（票 10）：并发写保护/推送/互踢/静默关闭为共享骨架，
 * 本类只绑定 review 协议信封与 KICK 帧
 */
@Component
public class ReviewWsPusher extends AbstractWsJsonPusher<ReviewWsFrame> {

    public ReviewWsPusher(ObjectMapper objectMapper) {
        super(objectMapper);
    }

    @Override
    protected ReviewWsFrame kickFrame() {
        return ReviewWsFrame.builder()
                .type(ReviewWsFrameType.KICK)
                .payload(Map.of("reason", ReviewWsProtocol.KICK_REASON_SESSION_TAKEN_OVER))
                .ts(System.currentTimeMillis())
                .build();
    }

    @Override
    protected String frameTypeName(ReviewWsFrame frame) {
        return String.valueOf(frame.getType());
    }
}
