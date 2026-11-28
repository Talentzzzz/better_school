package com.betterschool.domain.notification.adapter.repository;

import com.betterschool.domain.notification.model.entity.NotificationEntity;
import com.betterschool.domain.notification.model.valobj.NotificationBroadcastMessage;
import com.betterschool.domain.notification.model.valobj.NotificationEventMessage;
import com.betterschool.domain.notification.model.valobj.NotificationScopeEnum;
import com.betterschool.domain.notification.model.valobj.NotifyPushMessage;

import java.util.List;

/**
 * 站内通知仓储（notification 域自己的持久化出口）：scope+水位线读模型 + 公告广播（outbox 落库，消费方全用户落表）。
 * 需要原子性的用例（取号+落库、outbox 写入、广播全量落行）由实现内以编程式事务（REQUIRED）收成一个仓储方法，
 * 与调用方既有事务合流；取号行锁到提交 ⇒ 取号序 = 提交序
 */
public interface INotificationRepository {

    /** scope 为 null 时不过滤（全部范围）；已读态由「seq ≤ 水位」算出 */
    List<NotificationEntity> pageList(String userId, NotificationScopeEnum notificationScopeVO, int offset, int limit);

    long count(String userId, NotificationScopeEnum notificationScopeVO);

    /** 未读数 = 计数器 − 水位（O(1)）；scope 必填（非 null） */
    long unreadCount(String userId, NotificationScopeEnum notificationScopeVO);

    /** 标已读 = 把水位推到当前计数器值（O(1)）；该范围从无通知时为 no-op；scope 必填（非 null） */
    void markRead(String userId, NotificationScopeEnum notificationScopeVO);

    /** 落广播消息到 outbox（一个原子用例，事务与业务写合流） */
    void saveBroadcastMessage(NotificationBroadcastMessage notificationBroadcastMessage);

    /** 通知事件写 outbox（一个原子用例，事务与业务写合流）：outbox 写成功即返回 true，落库幂等由消费端收敛 */
    boolean saveEvent(NotificationEventMessage notificationEventMessage);

    /**
     * 同一 notificationId 是否已落库：消费方用它区分「同一事件重放（上次可能投递失败，需再投）」与
     * 「dedupKey 命中但 notificationId 不同（真正的重复事件，不再投递）」。
     */
    boolean existsByNotificationId(String notificationId);

    /** 广播落库（消费方）：按 msgId 幂等，给全用户各插一条（scope=BOTTOM_SYSTEM，逐用户取号；实现内一个事务） */
    void insertBroadcast(String msgId, String type, String title, String content);

    /**
     * 单用户定向通知（一个原子用例）：dedupKey / notificationId 命中时静默忽略（不耗号，返回 false）；
     * 否则取号 + 落库同事务（取号行锁到提交 ⇒ 取号序 = 提交序），真正插入返回 true。
     * 调用方已有事务时并入（REQUIRED）
     */
    boolean insertOne(NotificationEntity notificationEntity);

    /**
     * WS 实时推送 NOTIF 帧（BANNER 通道，技术出站能力走仓储，同 outbox 待遇）：
     * 实现组装帧并委托 infrastructure/ws 连接组件。
     * 尽力而为：不在线/失败静默放弃，漏推由前端按 (scope, seq) 水位补拉重放兜底
     */
    void pushNotif(String userId, NotifyPushMessage notifyPushMessage);
}
