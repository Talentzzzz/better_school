package com.betterschool.domain.notification.service;

import com.betterschool.domain.notification.adapter.repository.INotificationRepository;
import com.betterschool.domain.notification.model.entity.NotificationEntity;
import com.betterschool.domain.notification.model.valobj.NotificationBroadcastMessage;
import com.betterschool.domain.notification.model.valobj.NotificationEventMessage;
import com.betterschool.domain.notification.model.valobj.NotificationScopeEnum;
import com.betterschool.domain.notification.model.valobj.NotificationTypeEnum;
import com.betterschool.domain.notification.model.valobj.NotifyChatMessage;
import com.betterschool.domain.notification.model.valobj.NotifyPushMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements INotificationService {

    private final INotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;

    @Override
    public List<NotificationEntity> listNotifications(String userId, NotificationScopeEnum notificationScopeVO, int page, int pageSize) {
        return notificationRepository.pageList(userId, notificationScopeVO, (page - 1) * pageSize, pageSize);
    }

    @Override
    public long countNotifications(String userId, NotificationScopeEnum notificationScopeVO) {
        return notificationRepository.count(userId, notificationScopeVO);
    }

    @Override
    public long unreadCount(String userId, NotificationScopeEnum notificationScopeVO) {
        return notificationRepository.unreadCount(userId, notificationScopeVO);
    }

    @Override
    public void markRead(String userId, NotificationScopeEnum notificationScopeVO) {
        notificationRepository.markRead(userId, notificationScopeVO);
    }

    @Override
    public void publish(NotificationEventMessage notificationEventMessage) {
        notificationRepository.saveEvent(notificationEventMessage);
    }

    @Override
    public void publishBestEffort(NotificationEventMessage notificationEventMessage) {
        // 主链路已提交，通知是附属动作：失败只告警，不回滚主流程
        try {
            publish(notificationEventMessage);
        } catch (Exception e) {
            log.warn("[NotificationService] 通知发布失败（不回滚主流程） type={} refId={}",
                    notificationEventMessage == null ? null : notificationEventMessage.getType(),
                    notificationEventMessage == null ? null : notificationEventMessage.getRefId(), e);
        }
    }

    @Override
    public String buildSystemChatPayload(NotifyChatMessage notifyChatMessage) {
        // 与前端渲染约定；序列化失败（理论不可能）降级为纯文本，保证通知内容不丢
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "notificationId", notifyChatMessage.getNotificationId(),
                    "title", notifyChatMessage.getTitle(),
                    "content", notifyChatMessage.getContent(),
                    "refType", notifyChatMessage.getRefType() == null ? "" : notifyChatMessage.getRefType(),
                    "refId", notifyChatMessage.getRefId() == null ? "" : notifyChatMessage.getRefId()));
        } catch (Exception e) {
            return notifyChatMessage.getTitle() + "：" + notifyChatMessage.getContent();
        }
    }

    @Override
    public void insertBroadcast(String msgId, String type, String title, String content) {
        notificationRepository.insertBroadcast(msgId, type, title, content);
    }

    @Override
    public boolean insertOne(NotificationEntity notificationEntity) {
        return notificationRepository.insertOne(notificationEntity);
    }

    @Override
    public boolean existsByNotificationId(String notificationId) {
        return notificationRepository.existsByNotificationId(notificationId);
    }

    @Override
    public void broadcast(String type, String title, String content) {
        NotificationTypeEnum notificationTypeVO = type == null || type.isBlank() ? NotificationTypeEnum.ANNOUNCEMENT : NotificationTypeEnum.from(type);
        NotificationBroadcastMessage notificationBroadcastMessage = new NotificationBroadcastMessage(UUID.randomUUID().toString(), notificationTypeVO.name(), title, content);
        notificationRepository.saveBroadcastMessage(notificationBroadcastMessage);
    }

    @Override
    public void pushNotif(NotifyPushMessage notifyPushMessage) {
        notificationRepository.pushNotif(notifyPushMessage.getUserId(), notifyPushMessage);
    }
}
