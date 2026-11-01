package com.betterschool.domain.chat.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 已读推送事件载荷（READ，outbox → MQ → WS 推送，票 04 消费）：
 * userId 即 readerId（1v1/群统一），供前端就地更新成员水位 map；重复打开幂等不产生事件
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatReadPushMessage {

    private String roomId;

    private String userId;

    private Long readSeq;
}
