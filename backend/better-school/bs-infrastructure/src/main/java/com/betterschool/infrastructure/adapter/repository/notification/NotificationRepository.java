package com.betterschool.infrastructure.adapter.repository.notification;

import com.betterschool.domain.notification.adapter.port.IBroadcastRecipientPort;
import com.betterschool.domain.notification.adapter.repository.INotificationRepository;
import com.betterschool.domain.notification.model.entity.NotificationEntity;
import com.betterschool.domain.notification.model.valobj.NotificationBroadcastMessage;
import com.betterschool.domain.notification.model.valobj.NotificationScopeVO;
import com.betterschool.domain.notification.model.valobj.NotificationTypeVO;
import com.betterschool.infrastructure.dao.notification.INotificationDao;
import com.betterschool.infrastructure.dao.notification.po.NotificationPO;
import com.betterschool.infrastructure.outbox.OutboxMessageStore;
import com.betterschool.types.common.Constants;
import com.betterschool.types.id.IIdGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class NotificationRepository implements INotificationRepository {

    private final INotificationDao notificationDao;
    private final OutboxMessageStore outboxMessageStore;
    private final IBroadcastRecipientPort broadcastRecipientPort;
    private final TransactionTemplate transactionTemplate;
    /** 业务 ID 发号器（notification_id UUID，uuid-biz-id 票 06） */
    private final IIdGenerator idGenerator;

    @Override
    public List<NotificationEntity> pageList(String userId, NotificationScopeVO scope, int offset, int limit) {
        String scopeName = scope == null ? null : scope.name();
        return notificationDao.selectPage(userId, scopeName, offset, limit).stream().map(this::toEntity).toList();
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
        NotificationPO notificationPO = toPO(notificationEntity);
        if (notificationPO.getNotificationId() == null) {
            // 业务 ID 随 insert 一次写入（uuid-biz-id 票 06）；撞 uk_dedup 被 IGNORE 时本行未落库，发号作废无害
            notificationPO.setNotificationId(idGenerator.nextId());
        }
        if (notificationEntity.getDedupKey() != null) {
            notificationDao.insertIgnore(notificationPO);
        } else {
            notificationDao.insert(notificationPO);
        }
        notificationEntity.setNotificationId(notificationPO.getNotificationId());
    }

    private NotificationPO toPO(NotificationEntity notificationEntity) {
        LocalDateTime now = LocalDateTime.now();
        NotificationPO notificationPO = new NotificationPO();
        notificationPO.setUserId(notificationEntity.getUserId());
        notificationPO.setScope(notificationEntity.getScope().name());
        notificationPO.setSeq(notificationEntity.getSeq());
        notificationPO.setSenderId(notificationEntity.getSenderId());
        notificationPO.setType(notificationEntity.getType().name());
        notificationPO.setTitle(notificationEntity.getTitle());
        notificationPO.setContent(notificationEntity.getContent());
        notificationPO.setRefType(notificationEntity.getRefType());
        notificationPO.setRefId(notificationEntity.getRefId());
        notificationPO.setBroadcastMsgId(notificationEntity.getBroadcastMsgId());
        notificationPO.setDedupKey(notificationEntity.getDedupKey());
        notificationPO.setCreateTime(now);
        notificationPO.setUpdateTime(now);
        return notificationPO;
    }

    private NotificationEntity toEntity(NotificationPO notificationPO) {
        NotificationEntity notificationEntity = new NotificationEntity();
        notificationEntity.setNotificationId(notificationPO.getNotificationId());
        notificationEntity.setUserId(notificationPO.getUserId());
        notificationEntity.setScope(NotificationScopeVO.valueOf(notificationPO.getScope()));
        notificationEntity.setSeq(notificationPO.getSeq());
        notificationEntity.setSenderId(notificationPO.getSenderId());
        notificationEntity.setType(NotificationTypeVO.valueOf(notificationPO.getType()));
        notificationEntity.setTitle(notificationPO.getTitle());
        notificationEntity.setContent(notificationPO.getContent());
        notificationEntity.setRead(notificationPO.getIsRead() != null && notificationPO.getIsRead());
        notificationEntity.setRefType(notificationPO.getRefType());
        notificationEntity.setRefId(notificationPO.getRefId());
        notificationEntity.setCreatedAt(notificationPO.getCreateTime());
        return notificationEntity;
    }
}
