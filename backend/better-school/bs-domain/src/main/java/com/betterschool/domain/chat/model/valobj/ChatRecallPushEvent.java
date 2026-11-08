package com.betterschool.domain.chat.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 撤回推送事件载荷（RECALL，outbox → MQ → WS 推送）：轻事件只带定位（roomId+seq），
 * 内容终态以拉取为准；消费者推给会话全部在线成员（spec §3）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRecallPushEvent {

    private String roomId;

    private Long seq;
}
