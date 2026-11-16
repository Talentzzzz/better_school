package com.betterschool.trigger.ws;

import com.betterschool.domain.chat.adapter.repository.IChatRepository;
import com.betterschool.domain.chat.model.entity.RoomMemberEntity;
import com.betterschool.domain.chat.model.valobj.ChatMessagePushEvent;
import com.betterschool.domain.chat.model.valobj.ChatReadPushEvent;
import com.betterschool.domain.chat.model.valobj.ChatRecallPushEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * WS 推送服务（outbox 消费者的落地侧，spec §2）：按会话成员逐个经 WsPushRouter 投递，
 * 仅在线成员推送，不在线直接跳过（漏推合法，timeline 补拉兜底）。
 * MSG 排除发送者本人（发送方以 HTTP 200 为准）
 */
@Component
@RequiredArgsConstructor
public class ChatWsPushService {

    private final WsPushRouter wsPushRouter;
    private final IChatRepository chatRepository;

    /** MSG：payload=完整消息体（含发送者昵称头像），客户端按 (roomId, seq) 判重 */
    public void pushMessage(ChatMessagePushEvent chatMessagePushEvent) {
        broadcast(chatMessagePushEvent.getRoomId(), ChatWsFrame.builder()
                .type(ChatWsFrameType.MSG)
                .roomId(chatMessagePushEvent.getRoomId())
                .seq(chatMessagePushEvent.getSeq())
                .payload(chatMessagePushEvent)
                .ts(System.currentTimeMillis())
                .build(), chatMessagePushEvent.getFromUserId());
    }

    /** READ：payload={userId(readerId), readSeq}，推给 reader 之外的其他在线成员 */
    public void pushRead(ChatReadPushEvent chatReadPushEvent) {
        broadcast(chatReadPushEvent.getRoomId(), ChatWsFrame.builder()
                .type(ChatWsFrameType.READ)
                .roomId(chatReadPushEvent.getRoomId())
                .payload(Map.of(
                        "userId", chatReadPushEvent.getUserId(),
                        "readSeq", chatReadPushEvent.getReadSeq()))
                .ts(System.currentTimeMillis())
                .build(), chatReadPushEvent.getUserId());
    }

    /** RECALL：轻事件只带定位，推给会话全部在线成员 */
    public void pushRecall(ChatRecallPushEvent chatRecallPushEvent) {
        broadcast(chatRecallPushEvent.getRoomId(), ChatWsFrame.builder()
                .type(ChatWsFrameType.RECALL)
                .roomId(chatRecallPushEvent.getRoomId())
                .seq(chatRecallPushEvent.getSeq())
                .ts(System.currentTimeMillis())
                .build(), null);
    }

    private void broadcast(String roomId, ChatWsFrame frame, String excludeUserId) {
        for (RoomMemberEntity memberEntity : chatRepository.listRoomMembers(roomId)) {
            if (excludeUserId != null && excludeUserId.equals(memberEntity.getUserId())) {
                continue;
            }
            wsPushRouter.deliver(memberEntity.getUserId(), frame);
        }
    }
}
