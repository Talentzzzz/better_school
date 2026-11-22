package com.betterschool.usecase.chat;

import com.betterschool.domain.chat.adapter.repository.IChatRepository;
import com.betterschool.domain.chat.model.entity.RoomMemberEntity;
import com.betterschool.domain.chat.model.valobj.ChatMessagePushEvent;
import com.betterschool.domain.chat.model.valobj.ChatReadPushEvent;
import com.betterschool.domain.chat.model.valobj.ChatRecallPushEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * WS 推送用例（outbox 推送消费的落地侧决策）：按会话成员逐个经本域仓储 pushXxx 投递，
 * 只传本域推送事件、不碰帧信封（线上协议归 infrastructure/ws 托管，仓储实现组帧），
 * 仅在线成员推送，不在线直接跳过（漏推合法，timeline 补拉兜底）。
 * <p>
 * 成员扇出决策的唯一归属地：经 chat 仓储解析会话成员，MSG/READ 排除触发者
 * （MSG 发送方以 HTTP 200 为准、READ reader 本人不需要回执），RECALL 推全员（含发送者）。
 * 推送是加速路径：本用例不做事务、不做重试，投递失败/不在线由仓储出站静默放弃
 */
@Service
@RequiredArgsConstructor
public class ChatWsPushCase {

    private final IChatRepository chatRepository;

    /** MSG：payload=完整消息体（含发送者昵称头像），客户端按 (roomId, seq) 判重 */
    public void pushMessage(ChatMessagePushEvent chatMessagePushEvent) {
        forEachMember(chatMessagePushEvent.getRoomId(), chatMessagePushEvent.getFromUserId(),
                userId -> chatRepository.pushMessage(userId, chatMessagePushEvent));
    }

    /** READ：payload={userId(readerId), readSeq}，推给 reader 之外的其他在线成员 */
    public void pushRead(ChatReadPushEvent chatReadPushEvent) {
        forEachMember(chatReadPushEvent.getRoomId(), chatReadPushEvent.getUserId(),
                userId -> chatRepository.pushRead(userId, chatReadPushEvent));
    }

    /** RECALL：轻事件只带定位，推给会话全部在线成员 */
    public void pushRecall(ChatRecallPushEvent chatRecallPushEvent) {
        forEachMember(chatRecallPushEvent.getRoomId(), null,
                userId -> chatRepository.pushRecall(userId, chatRecallPushEvent));
    }

    /** 成员扇出：逐成员投递，excludeUserId 非空时跳过该成员（帧组装与投递细节归仓储实现） */
    private void forEachMember(String roomId, String excludeUserId, Consumer<String> push) {
        for (RoomMemberEntity memberEntity : chatRepository.listRoomMembers(roomId)) {
            if (excludeUserId != null && excludeUserId.equals(memberEntity.getUserId())) {
                continue;
            }
            push.accept(memberEntity.getUserId());
        }
    }
}
