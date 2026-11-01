package com.betterschool.domain.notification.service;

import com.betterschool.domain.notification.adapter.repository.INotificationRepository;
import com.betterschool.domain.notification.model.entity.NotificationEntity;
import com.betterschool.domain.notification.model.valobj.NotificationBroadcastMessage;
import com.betterschool.domain.notification.model.valobj.NotificationScopeVO;
import com.betterschool.domain.notification.model.valobj.NotificationTypeVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class NotificationServiceImpl implements INotificationService {

    private final INotificationRepository notificationRepository;

    @Override
    public List<NotificationEntity> listNotifications(String userId, NotificationScopeVO scope, int page, int pageSize) {
        return notificationRepository.pageList(userId, scope, (page - 1) * pageSize, pageSize);
    }

    @Override
    public long countNotifications(String userId, NotificationScopeVO scope) {
        return notificationRepository.count(userId, scope);
    }

    @Override
    public long unreadCount(String userId, NotificationScopeVO scope) {
        return notificationRepository.unreadCount(userId, scope);
    }

    @Override
    public void markRead(String userId, NotificationScopeVO scope) {
        notificationRepository.markRead(userId, scope);
    }

    @Override
    public void sendToUser(String userId, NotificationScopeVO scope, NotificationTypeVO type, String title,
                           String content, String refType, String refId) {
        insertWithSeq(NotificationEntity.create(userId, scope, type, title, content, refType, refId));
    }

    @Override
    public void sendInteraction(String userId, String senderId, NotificationScopeVO scope, NotificationTypeVO type,
                                String title, String content, String refType, String refId, String dedupKey) {
        insertWithSeq(NotificationEntity.createInteraction(
                userId, senderId, scope, type, title, content, refType, refId, dedupKey));
    }

    @Override
    public void sendSystem(String userId, NotificationScopeVO scope, NotificationTypeVO type, String title,
                           String content, String refType, String refId, String dedupKey) {
        insertWithSeq(NotificationEntity.createSystem(userId, scope, type, title, content, refType, refId, dedupKey));
    }

    @Override
    public void insertBroadcast(String msgId, String type, String title, String content) {
        notificationRepository.insertBroadcast(msgId, type, title, content);
    }

    /** 取号 + 落库的原子性由仓储方法自带的事务保证（PROPAGATION_REQUIRED）：调用方已有事务则并入 */
    private void insertWithSeq(NotificationEntity notificationEntity) {
        notificationRepository.insertOne(notificationEntity);
    }

    @Override
    public void broadcast(String type, String title, String content) {
        NotificationTypeVO typeVO = type == null || type.isBlank() ? NotificationTypeVO.ANNOUNCEMENT : NotificationTypeVO.from(type);
        NotificationBroadcastMessage message = new NotificationBroadcastMessage(UUID.randomUUID().toString(), typeVO.name(), title, content);
        notificationRepository.saveBroadcastMessage(message);
    }
}
