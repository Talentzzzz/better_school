package com.betterschool.infrastructure.adapter.repository.notification;

import com.betterschool.domain.notification.adapter.port.IBroadcastRecipientPort;
import com.betterschool.domain.notification.adapter.repository.INotificationRepository;
import com.betterschool.domain.notification.model.entity.NotificationEntity;
import com.betterschool.domain.notification.model.valobj.NotificationBroadcastMessage;
import com.betterschool.domain.notification.model.valobj.NotificationScopeVO;
import com.betterschool.domain.notification.model.valobj.NotificationTypeVO;
import com.betterschool.infrastructure.dao.notification.INotificationDao;
import com.betterschool.infrastructure.dao.notification.po.Notification;
import com.betterschool.infrastructure.outbox.OutboxMessageStore;
import com.betterschool.types.common.Constants;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class NotificationRepository implements INotificationRepository {

    private final INotificationDao notificationDao;
    private final OutboxMessageStore outboxMessageStore;
    private final IBroadcastRecipientPort broadcastRecipientPort;
    private final TransactionTemplate transactionTemplate;

    @Override
    public List<NotificationEntity> pageList(String userId, NotificationScopeVO scope, int offset, int limit) {
        String scopeName = scope == null ? null : scope.name();
        List<Notification> rows = notificationDao.selectPage(userId, scopeName, offset, limit);
        // is_read（原 LEFT JOIN notification_read 的计算列）：页内 scope 集逐 scope 查水位后 Java 计算
        // ——水位每 user_id × scope 一行，scope 个数有界（当前两个），不随页大小增长
        Map<String, Long> watermarks = new HashMap<>();
        for (String rowScope : rows.stream().map(Notification::getScope).distinct().toList()) {
            Long lastReadSeq = notificationDao.selectLastReadSeq(userId, rowScope);
            watermarks.put(rowScope, lastReadSeq == null ? 0L : lastReadSeq);
        }
        for (Notification row : rows) {
            row.setIsRead(row.getSeq() != null && row.getSeq() <= watermarks.getOrDefault(row.getScope(), 0L));
        }
        return rows.stream().map(this::toEntity).toList();
    }

    @Override
    public long count(String userId, NotificationScopeVO scope) {
        return notificationDao.count(userId, scope == null ? null : scope.name());
    }

    @Override
    public long unreadCount(String userId, NotificationScopeVO scope) {
        Long seq = notificationDao.selectSeq(userId, scope.name());
        if (seq == null) {
            return 0;
        }
        Long lastReadSeq = notificationDao.selectLastReadSeq(userId, scope.name());
        return Math.max(0, seq - (lastReadSeq == null ? 0 : lastReadSeq));
    }

    @Override
    public void markRead(String userId, NotificationScopeVO scope) {
        Long seq = notificationDao.selectSeq(userId, scope.name());
        if (seq == null) {
            // 该范围从未收到过通知：无水位可推
            return;
        }
        notificationDao.upsertReadWatermark(userId, scope.name(), seq);
    }

    @Override
    public void saveBroadcastMessage(NotificationBroadcastMessage message) {
        // OutboxMessageStore 用 TransactionSynchronization 在提交后投递，必须有活事务；
        // 这里只写 outbox 一行，事务传播恒 REQUIRED（调用方已有事务则并入，无则本行自成一个事务）
        transactionTemplate.executeWithoutResult(status ->
                outboxMessageStore.save(Constants.NOTIFICATION_BROADCAST_TOPIC, message));
    }

    @Override
    public void insertOne(NotificationEntity notificationEntity) {
        // 取号 + 落库必须同一事务（取号行锁到提交 ⇒ 取号序 = 提交序）；调用方已有事务则并入
        transactionTemplate.executeWithoutResult(status -> doInsertOne(notificationEntity));
    }

    @Override
    public void insertBroadcast(String msgId, String type, String title, String content) {
        transactionTemplate.executeWithoutResult(status -> {
            if (notificationDao.existsBroadcast(msgId) > 0) {
                // outbox 重发导致的重复消费：幂等忽略
                return;
            }
            List<String> userIds = broadcastRecipientPort.listAllUserIds();
            for (String userId : userIds) {
                NotificationEntity notificationEntity = NotificationEntity.create(userId,
                        NotificationScopeVO.BOTTOM_SYSTEM, NotificationTypeVO.valueOf(type), title, content,
                        "BROADCAST", null);
                notificationEntity.setBroadcastMsgId(msgId);
                doInsertOne(notificationEntity);
            }
        });
    }

    /** 取号 + 落库（不含事务边界）：只允许在上面两个自带事务的方法内调用 */
    private void doInsertOne(NotificationEntity notificationEntity) {
        if (notificationEntity.getDedupKey() != null
                && notificationDao.existsDedupKey(notificationEntity.getDedupKey()) > 0) {
            // 先查后插：重复消费/重复扫描不白耗 seq 号段；并发窗口内仍由 uk_dedup + insertIgnore 兜底
            return;
        }
        String scope = notificationEntity.getScope().name();
        notificationDao.upsertIncreaseSeq(notificationEntity.getUserId(), scope);
        notificationEntity.setSeq(notificationDao.selectSeq(notificationEntity.getUserId(), scope));
        Notification notification = toPO(notificationEntity);
        if (notification.getNotificationId() == null) {
            // 业务 ID 随 insert 一次写入（uuid-biz-id 票 06）；撞 uk_dedup 被 IGNORE 时本行未落库，发号作废无害
            notification.setNotificationId(UUID.randomUUID().toString());
        }
        if (notificationEntity.getDedupKey() != null) {
            notificationDao.insertIgnore(notification);
        } else {
            notificationDao.insert(notification);
        }
        notificationEntity.setNotificationId(notification.getNotificationId());
    }

    private Notification toPO(NotificationEntity notificationEntity) {
        Notification notification = new Notification();
        notification.setUserId(notificationEntity.getUserId());
        notification.setScope(notificationEntity.getScope().name());
        notification.setSeq(notificationEntity.getSeq());
        notification.setSenderId(notificationEntity.getSenderId());
        notification.setType(notificationEntity.getType().name());
        notification.setTitle(notificationEntity.getTitle());
        notification.setContent(notificationEntity.getContent());
        notification.setRefType(notificationEntity.getRefType());
        notification.setRefId(notificationEntity.getRefId());
        notification.setBroadcastMsgId(notificationEntity.getBroadcastMsgId());
        notification.setDedupKey(notificationEntity.getDedupKey());
        return notification;
    }

    private NotificationEntity toEntity(Notification notification) {
        NotificationEntity notificationEntity = new NotificationEntity();
        notificationEntity.setNotificationId(notification.getNotificationId());
        notificationEntity.setUserId(notification.getUserId());
        notificationEntity.setScope(NotificationScopeVO.valueOf(notification.getScope()));
        notificationEntity.setSeq(notification.getSeq());
        notificationEntity.setSenderId(notification.getSenderId());
        notificationEntity.setType(NotificationTypeVO.valueOf(notification.getType()));
        notificationEntity.setTitle(notification.getTitle());
        notificationEntity.setContent(notification.getContent());
        notificationEntity.setRead(notification.getIsRead() != null && notification.getIsRead());
        notificationEntity.setRefType(notification.getRefType());
        notificationEntity.setRefId(notification.getRefId());
        notificationEntity.setCreatedAt(notification.getCreateTime());
        return notificationEntity;
    }
}
