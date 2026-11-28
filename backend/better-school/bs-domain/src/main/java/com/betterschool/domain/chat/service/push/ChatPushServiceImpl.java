package com.betterschool.domain.chat.service.push;

import com.betterschool.domain.chat.adapter.repository.IChatRepository;
import com.betterschool.domain.chat.model.entity.RoomMemberEntity;
import com.betterschool.domain.chat.model.valobj.ChatMessagePushEvent;
import com.betterschool.domain.chat.model.valobj.ChatReadPushEvent;
import com.betterschool.domain.chat.model.valobj.ChatRecallPushEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/**
 * WS 实时推送实现：成员扇出规则是 chat 域自身业务，故落在域服务而非用例层；
 * 帧组装与投递细节归仓储实现（infrastructure/ws）。
 * <p>
 * 推送是加速路径：写事务提交后由调用方直接调用，异常只记日志不上抛——不能因为推送失败
 * 让已提交的写请求返回 500；漏推由客户端 (roomId, seq) 增量补拉兜底
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatPushServiceImpl implements IChatPushService {

    private final IChatRepository chatRepository;

    @Override
    public void pushMessage(ChatMessagePushEvent chatMessagePushEvent) {
        safePush("MSG", chatMessagePushEvent.getRoomId(), () ->
                forEachMember(chatMessagePushEvent.getRoomId(), chatMessagePushEvent.getFromUserId(),
                        userId -> chatRepository.pushMessage(userId, chatMessagePushEvent)));
    }

    @Override
    public void pushRead(ChatReadPushEvent chatReadPushEvent) {
        safePush("READ", chatReadPushEvent.getRoomId(), () ->
                forEachMember(chatReadPushEvent.getRoomId(), chatReadPushEvent.getUserId(),
                        userId -> chatRepository.pushRead(userId, chatReadPushEvent)));
    }

    @Override
    public void pushRecall(ChatRecallPushEvent chatRecallPushEvent) {
        safePush("RECALL", chatRecallPushEvent.getRoomId(), () ->
                forEachMember(chatRecallPushEvent.getRoomId(), null,
                        userId -> chatRepository.pushRecall(userId, chatRecallPushEvent)));
    }

    /** 推送失败不重试、不上抛（写已提交，漏推合法） */
    private void safePush(String scene, String roomId, Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.warn("[ChatPushService] {} 推送失败（漏推由客户端补拉兜底）roomId={}", scene, roomId, e);
        }
    }

    /** 成员扇出：逐成员投递，excludeUserId 非空时跳过该成员（帧组装与投递细节归仓储实现） */
    private void forEachMember(String roomId, String excludeUserId, Consumer<String> push) {
        for (RoomMemberEntity roomMemberEntity : chatRepository.listRoomMembers(roomId)) {
            if (excludeUserId != null && excludeUserId.equals(roomMemberEntity.getUserId())) {
                continue;
            }
            push.accept(roomMemberEntity.getUserId());
        }
    }
}
