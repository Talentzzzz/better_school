package com.betterschool.trigger.ws;

import com.betterschool.domain.chat.adapter.repository.IChatRepository;
import com.betterschool.domain.chat.model.entity.RoomMemberEntity;
import com.betterschool.domain.chat.model.valobj.ChatMessagePushMessage;
import com.betterschool.domain.chat.model.valobj.ChatReadPushMessage;
import com.betterschool.domain.chat.model.valobj.ChatRecallPushMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;

/**
 * WS 推送服务（outbox 消费者的落地侧，spec §2）：按会话成员查注册表，仅在线成员推送，
 * 不在线直接跳过（漏推合法，timeline 补拉兜底）。MSG 排除发送者本人（发送方以 HTTP 200 为准）
 */
@Component
@RequiredArgsConstructor
public class ChatWsPushService {

    private final ChatConnectionRegistry connectionRegistry;
    private final ChatWsPusher pusher;
    private final IChatRepository chatRepository;

    /** MSG：payload=完整消息体（含发送者昵称头像），客户端按 (roomId, seq) 判重 */
    public void pushMessage(ChatMessagePushMessage message) {
        broadcast(message.getRoomId(), ChatWsFrame.builder()
                .type(ChatWsFrameType.MSG)
                .roomId(message.getRoomId())
                .seq(message.getSeq())
                .payload(message)
                .ts(System.currentTimeMillis())
                .build(), message.getFromUserId());
    }

    /** READ：payload={userId(readerId), readSeq}，推给 reader 之外的其他在线成员 */
    public void pushRead(ChatReadPushMessage message) {
        broadcast(message.getRoomId(), ChatWsFrame.builder()
                .type(ChatWsFrameType.READ)
                .roomId(message.getRoomId())
                .payload(Map.of(
                        "userId", message.getUserId(),
                        "readSeq", message.getReadSeq()))
                .ts(System.currentTimeMillis())
                .build(), message.getUserId());
    }

    /** RECALL：轻事件只带定位，推给会话全部在线成员 */
    public void pushRecall(ChatRecallPushMessage message) {
        broadcast(message.getRoomId(), ChatWsFrame.builder()
                .type(ChatWsFrameType.RECALL)
                .roomId(message.getRoomId())
                .seq(message.getSeq())
                .ts(System.currentTimeMillis())
                .build(), null);
    }

    private void broadcast(String roomId, ChatWsFrame frame, String excludeUserId) {
        for (RoomMemberEntity member : chatRepository.listRoomMembers(roomId)) {
            if (excludeUserId != null && excludeUserId.equals(member.getUserId())) {
                continue;
            }
            WebSocketSession session = connectionRegistry.get(member.getUserId());
            if (session != null && session.isOpen()) {
                pusher.push(session, frame);
            }
        }
    }
}
