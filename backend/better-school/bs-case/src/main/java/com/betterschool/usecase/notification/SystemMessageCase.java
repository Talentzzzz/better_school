package com.betterschool.usecase.notification;

import com.betterschool.domain.chat.adapter.port.IUserQueryPort;
import com.betterschool.domain.chat.model.entity.MessageEntity;
import com.betterschool.domain.chat.model.entity.RoomEntity;
import com.betterschool.domain.chat.model.valobj.MsgTypeVO;
import com.betterschool.domain.chat.service.send.IChatSendService;
import com.betterschool.domain.notification.model.valobj.NotifyChatMessage;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 系统消息投递用例（通知 CHAT 通道的消费落地）：把一条通知经 chat 发送管线落进
 * 用户与系统助手（{@link Constants#SYSTEM_USER_ID}）的 1v1 会话。
 * <p>
 * 幂等三重收敛：① notification 落库 dedup 幂等（outbox 重发源头少）② chat uk_room_client
 * （client_msg_id = "NOTIFY:" + notificationId）重复消费收敛同一条 ③ 系统会话 uk_pair 建会话幂等。
 * 会话懒创建（首次投递时建，恒 1v1、source 为 NULL 不进市集消息页筛子）；用户不可向系统会话
 * 发消息/主动建会话（守卫在 ChatSendServiceImpl）。投递失败向上抛由 broker 重试
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SystemMessageCase {

    private static final String CLIENT_MSG_ID_PREFIX = "NOTIFY:";

    private final IChatSendService chatSendService;
    private final IUserQueryPort userQueryPort;
    private final ObjectMapper objectMapper;

    public void deliver(NotifyChatMessage message) {
        // 系统账号异常兜底：种子行缺失（schema.sql）时打不进来——不抛（避免 broker 无限重试死信噪音），错误日志暴露
        if (userQueryPort.findById(Constants.SYSTEM_USER_ID) == null) {
            log.error("[SystemMessageCase] 系统助手账号不存在，通知投递跳过 notificationId={}", message.getNotificationId());
            return;
        }
        if (Constants.SYSTEM_USER_ID.equals(message.getUserId())) {
            return; // 不会发生：不给系统账号自己发
        }
        // 系统侧发起建/取会话：user_pair_room.uk_pair 幂等，首次投递才真正建
        RoomEntity roomEntity = chatSendService.createDirectRoom(Constants.SYSTEM_USER_ID, message.getUserId(), null);
        MessageEntity messageEntity = new MessageEntity();
        messageEntity.setRoomId(roomEntity.getRoomId());
        messageEntity.setFromUserId(Constants.SYSTEM_USER_ID);
        messageEntity.setMsgType(MsgTypeVO.SYSTEM);
        messageEntity.setContent(toPayload(message));
        // 客户端幂等键锚定通知业务 ID：消费重放/重试收敛同一条
        messageEntity.setClientMsgId(CLIENT_MSG_ID_PREFIX + message.getNotificationId());
        chatSendService.sendMessage(messageEntity);
        log.info("[SystemMessageCase] 系统消息投递成功 notificationId={} userId={}",
                message.getNotificationId(), message.getUserId());
    }

    /** SYSTEM 载荷（与前端渲染约定）：{notificationId, title, content, refType?, refId?} */
    private String toPayload(NotifyChatMessage message) {
        try {
            return objectMapper.writeValueAsString(java.util.Map.of(
                    "notificationId", message.getNotificationId(),
                    "title", message.getTitle(),
                    "content", message.getContent(),
                    "refType", message.getRefType() == null ? "" : message.getRefType(),
                    "refId", message.getRefId() == null ? "" : message.getRefId()));
        } catch (Exception e) {
            // JSON 序列化失败（理论不可能）：降级为纯文本，保证通知内容不丢
            return message.getTitle() + "：" + message.getContent();
        }
    }
}
