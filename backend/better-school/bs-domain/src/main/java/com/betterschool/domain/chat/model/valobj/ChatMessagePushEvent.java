package com.betterschool.domain.chat.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 消息推送事件载荷（MSG，outbox → MQ → WS 推送，票 04 消费）：
 * 全量消息体 + 发送者昵称头像，气泡渲染免二次查询；(roomId, seq) 为消费端判重键
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessagePushEvent {

    private String roomId;

    /** 消息序号（会话内判重键一半） */
    private Long seq;

    private String messageId;

    private String fromUserId;

    private String fromNickname;

    private String fromAvatar;

    private MsgTypeVO msgType;

    private String content;

    private CardRefTypeVO refType;

    private String refId;

    private String clientMsgId;

    private LocalDateTime createdAt;
}
