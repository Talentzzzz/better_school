package com.betterschool.infrastructure.dao.chat.po;

import lombok.Data;


@Data
public class Timeline {

    private Long id;
    private String userId;
    private String roomId;
    private Long seq;
    /** 消息业务 ID（外联 message.msg_id） */
    private String msgId;
    private Long deliverySeq;
}
