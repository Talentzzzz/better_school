package com.betterschool.trigger.ws;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * review WS 协议信封（JSON 文本帧，票 10）：{type, payload?, ts}。
 * 审核端点无会话/序号语义，信封比 chat 窄；REVIEW_SIGNAL 载荷归票 11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReviewWsFrame {

    private ReviewWsFrameType type;

    /** KICK={reason}；REVIEW_SIGNAL 载荷归票 11；其余为空 */
    private Object payload;

    private Long ts;
}
