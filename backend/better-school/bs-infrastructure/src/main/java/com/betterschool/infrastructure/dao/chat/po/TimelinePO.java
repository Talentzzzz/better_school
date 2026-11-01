package com.betterschool.infrastructure.dao.chat.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TimelinePO {

    private Long id;
    private String userId;
    private String roomId;
    private Long seq;
    /** 消息业务 ID（外联 message.msg_id） */
    private String msgId;
    private Long deliverySeq;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
