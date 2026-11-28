package com.betterschool.infrastructure.adapter.repository.notification;

import com.betterschool.domain.notification.adapter.repository.INotificationRepository;
import com.betterschool.domain.user.adapter.repository.IUserRepository;
import com.betterschool.infrastructure.ws.IWsService;
import com.betterschool.infrastructure.ws.wsframe.WsFrameFactory;
import com.betterschool.domain.notification.model.entity.NotificationEntity;
import com.betterschool.domain.notification.model.valobj.NotificationBroadcastMessage;
import com.betterschool.domain.notification.model.valobj.NotificationEventMessage;
import com.betterschool.domain.notification.model.valobj.NotificationScopeEnum;
import com.betterschool.domain.notification.model.valobj.NotificationTypeEnum;
import com.betterschool.domain.notification.model.valobj.NotifyPushMessage;
import com.betterschool.infrastructure.dao.notification.INotificationDao;
import com.betterschool.infrastructure.dao.notification.INotificationReadDao;
import com.betterschool.infrastructure.dao.notification.INotificationSeqDao;
import com.betterschool.infrastructure.dao.notification.po.Notification;
import com.betterschool.infrastructure.dao.IMqOutboxDao;
import com.betterschool.infrastructure.dao.po.MqOutbox;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Repository
@RequiredArgsConstructor
public class NotificationRepository implements INotificationRepository {

    private final INotificationDao notificationDao;
    /** 取号计数器（notification_seq）与已读水位（notification_read）按表拆分的 DAO */
    private final INotificationSeqDao notificationSeqDao;
    private final INotificationReadDao notificationReadDao;
    private final IMqOutboxDao mqOutboxDao;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    private final IUserRepository userRepository;
    private final TransactionTemplate transactionTemplate;
    private final IWsService wsService;

    @Override
    public List<NotificationEntity> pageList(String userId, NotificationScopeEnum notificationScopeVO, int offset, int limit) {
        String scopeName = notificationScopeVO == null ? null : notificationScopeVO.name();
        List<Notification> notificationList = notificationDao.selectPage(userId, scopeName, offset, limit);
        // is_read（原 LEFT JOIN notification_read 的计算列）：页内 scope 集逐 scope 查水位后 Java 计算
        // ——水位每 user_id × scope 一行，scope 个数有界（当前两个），不随页大小增长
        Map<String, Long> watermarks = new HashMap<>();
        for (String rowScope : notificationList.stream().map(Notification::getScope).distinct().toList()) {
            Long lastReadSeq = notificationReadDao.selectLastReadSeq(userId, rowScope);
            watermarks.put(rowScope, lastReadSeq == null ? 0L : lastReadSeq);
        }
        for (Notification notification : notificationList) {
            notification.setIsRead(notification.getSeq() != null && notification.getSeq() <= watermarks.getOrDefault(notification.getScope(), 0L));
        }
        return notificationList.stream().map(this::toEntity).toList();
    }

    @Override
    public long count(String userId, NotificationScopeEnum notificationScopeVO) {
        return notificationDao.count(userId, notificationScopeVO == null ? null : notificationScopeVO.name());
    }

    @Override
    public long unreadCount(String userId, NotificationScopeEnum notificationScopeVO) {
        Long seq = notificationSeqDao.selectSeq(userId, notificationScopeVO.name());
        if (seq == null) {
            return 0;
        }
        Long lastReadSeq = notificationReadDao.selectLastReadSeq(userId, notificationScopeVO.name());
        return Math.max(0, seq - (lastReadSeq == null ? 0 : lastReadSeq));
    }

    @Override
    public void markRead(String userId, NotificationScopeEnum notificationScopeVO) {
        Long seq = notificationSeqDao.selectSeq(userId, notificationScopeVO.name());
        if (seq == null) {
            // 该范围从未收到过通知：无水位可推
            return;
        }
        notificationReadDao.upsertReadWatermark(userId, notificationScopeVO.name(), seq);
    }

    @Override
    public void saveBroadcastMessage(NotificationBroadcastMessage notificationBroadcastMessage) {
        // 事务内只写 outbox 一行；事务提交后再同步发 MQ
        MqOutbox mqOutbox = transactionTemplate.execute(status ->
                saveOutbox(Constants.NOTIFICATION_BROADCAST_TOPIC, notificationBroadcastMessage));
        sendOutbox(mqOutbox);
    }

    @Override
    public boolean saveEvent(NotificationEventMessage notificationEventMessage) {
        // 事务内只写 outbox 一行；事务提交后再同步发 MQ。返回值语义：outbox 写恒成功即 true
        // （落库幂等由消费端按 notificationId + dedupKey 收敛）
        MqOutbox mqOutbox = transactionTemplate.execute(status ->
                saveOutbox(Constants.NOTIFY_EVENT_TOPIC, notificationEventMessage));
        sendOutbox(mqOutbox);
        return true;
    }

    /** 事务内只做 DAO 写：落一行 PENDING outbox，发送交给事务提交后的调用点 */
    private MqOutbox saveOutbox(String destination, Object payload) {
        MqOutbox mqOutbox = MqOutbox.pending(destination, payload, objectMapper);
        mqOutboxDao.insert(mqOutbox);
        return mqOutbox;
    }

    private void sendOutbox(MqOutbox mqOutbox) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // 并入调用方事务时不提前发送：留给 outboxScan 在事务结束后兜底
            log.debug("[NotificationRepository] outbox 并入调用方事务，留给 outboxScan 兜底 msgId={}",
                    mqOutbox.getMsgId());
            return;
        }
        boolean sent;
        try {
            SendResult sendResult = rocketMQTemplate.syncSend(mqOutbox.getDestination(), mqOutbox.getPayload());
            sent = sendResult != null && sendResult.getSendStatus() == SendStatus.SEND_OK;
            if (!sent) {
                log.warn("outbox 同步发送未确认 msgId={}, status={}", mqOutbox.getMsgId(),
                        sendResult == null ? null : sendResult.getSendStatus());
            }
        } catch (Exception e) {
            log.warn("outbox 同步发送失败 msgId={}", mqOutbox.getMsgId(), e);
            sent = false;
        }
        if (sent) {
            mqOutboxDao.markSentByMsgId(mqOutbox.getMsgId());
        } else if (mqOutbox.getRetryCount() + 1 >= MqOutbox.MAX_RETRY_COUNT) {
            mqOutboxDao.markDeadBatch(List.of(mqOutbox.getId()));
        } else {
            mqOutboxDao.incrementRetryBatch(List.of(mqOutbox.getId()));
        }
    }

    @Override
    public boolean insertOne(NotificationEntity notificationEntity) {
        // 取号 + 落库必须同一事务（取号行锁到提交 ⇒ 取号序 = 提交序）；调用方已有事务则并入
        Boolean inserted = transactionTemplate.execute(status -> doInsertOne(notificationEntity));
        return Boolean.TRUE.equals(inserted);
    }

    @Override
    public boolean existsByNotificationId(String notificationId) {
        return notificationId != null && notificationDao.existsNotificationId(notificationId) > 0;
    }

    @Override
    public void insertBroadcast(String msgId, String type, String title, String content) {
        transactionTemplate.executeWithoutResult(status -> {
            if (notificationDao.existsBroadcast(msgId) > 0) {
                // outbox 重发导致的重复消费：幂等忽略
                return;
            }
            List<String> userIdList = userRepository.listAllUserIds();
            for (String userId : userIdList) {
                NotificationEntity notificationEntity = NotificationEntity.create(userId,
                        NotificationScopeEnum.BOTTOM_SYSTEM, NotificationTypeEnum.valueOf(type), title, content,
                        "BROADCAST", null);
                notificationEntity.setBroadcastMsgId(msgId);
                doInsertOne(notificationEntity);
            }
        });
    }

    /** 取号 + 落库（不含事务边界，返回是否真正插入）：只允许在上面两个自带事务的方法内调用 */
    private boolean doInsertOne(NotificationEntity notificationEntity) {
        if (notificationEntity.getDedupKey() != null
                && notificationDao.existsDedupKey(notificationEntity.getDedupKey()) > 0) {
            // 先查后插：重复消费/重复扫描不白耗 seq 号段；并发窗口内仍由 uk_dedup + insertIgnore 兜底
            return false;
        }
        if (notificationEntity.getNotificationId() != null
                && notificationDao.existsNotificationId(notificationEntity.getNotificationId()) > 0) {
            // 幂等第一锚点：事件驱动消费方重投同一 notificationId 时静默忽略
            // （无 dedupKey 的事件重试走普通 insert，撞 uk_notification_id 会抛异常而非忽略，必须前置检查）
            return false;
        }
        String scope = notificationEntity.getScope().name();
        notificationSeqDao.upsertIncreaseSeq(notificationEntity.getUserId(), scope);
        notificationEntity.setSeq(notificationSeqDao.selectSeq(notificationEntity.getUserId(), scope));
        Notification notification = toPO(notificationEntity);
        if (notification.getNotificationId() == null) {
            // 业务 ID 随 insert 一次写入（uuid-biz-id 票 06）；撞 uk_dedup 被 IGNORE 时本行未落库，发号作废无害
            notification.setNotificationId(UUID.randomUUID().toString());
        }
        boolean inserted;
        if (notificationEntity.getDedupKey() != null) {
            inserted = notificationDao.insertIgnore(notification) > 0;
        } else {
            inserted = notificationDao.insert(notification) > 0;
        }
        notificationEntity.setNotificationId(notification.getNotificationId());
        return inserted;
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
        notificationEntity.setScope(NotificationScopeEnum.valueOf(notification.getScope()));
        notificationEntity.setSeq(notification.getSeq());
        notificationEntity.setSenderId(notification.getSenderId());
        notificationEntity.setType(NotificationTypeEnum.valueOf(notification.getType()));
        notificationEntity.setTitle(notification.getTitle());
        notificationEntity.setContent(notification.getContent());
        notificationEntity.setRead(notification.getIsRead() != null && notification.getIsRead());
        notificationEntity.setRefType(notification.getRefType());
        notificationEntity.setRefId(notification.getRefId());
        notificationEntity.setCreatedAt(notification.getCreateTime());
        return notificationEntity;
    }

    @Override
    public void pushNotif(String userId, NotifyPushMessage notifyPushMessage) {
        wsService.push(userId, WsFrameFactory.notif(notifyPushMessage));
    }
}
