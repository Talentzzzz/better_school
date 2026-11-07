package com.betterschool.infrastructure.dao.chat.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息行（message）：id 是物理自增，msg_id 是业务 ID（跨表引用与对外暴露用它；timeline.msg_id 外联它）
 */
@Data
public class Message {

    private Long id;
    private String msgId;
    private String roomId;
    private Long seq;
    private String fromUserId;
    private String msgType;
    private String content;
    private String clientMsgId;
    private String refType;
    private String refId;
    private String state;
    private LocalDateTime recalledAt;
    private String recalledBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
