package com.betterschool.domain.notification.adapter.repository;

import com.betterschool.domain.notification.model.entity.NotificationEntity;
import com.betterschool.domain.notification.model.valobj.NotificationBroadcastMessage;
import com.betterschool.domain.notification.model.valobj.NotificationScopeVO;

import java.util.List;

/**
 * 站内通知仓储（notification 域自己的持久化出口）：scope+水位线读模型 + 公告广播（outbox 落库，消费方全用户落表）。
 * 需要原子性的用例（取号+落库、outbox 写入、广播全量落行）由实现内以编程式事务（REQUIRED）收成一个仓储方法，
 * 与调用方既有事务合流；取号行锁到提交 ⇒ 取号序 = 提交序
 */
public interface INotificationRepository {

    /** scope 为 null 时不过滤（全部范围）；已读态由「seq ≤ 水位」算出 */
    List<NotificationEntity> pageList(String userId, NotificationScopeVO scope, int offset, int limit);

    long count(String userId, NotificationScopeVO scope);

    /** 未读数 = 计数器 − 水位（O(1)）；scope 必填（非 null） */
    long unreadCount(String userId, NotificationScopeVO scope);

    /** 标已读 = 把水位推到当前计数器值（O(1)）；该范围从无通知时为 no-op；scope 必填（非 null） */
    void markRead(String userId, NotificationScopeVO scope);

    /** 落广播消息到 outbox（一个原子用例，事务与业务写合流） */
    void saveBroadcastMessage(NotificationBroadcastMessage message);

    /** 广播落库（消费方）：按 msgId 幂等，给全用户各插一条（scope=BOTTOM_SYSTEM，逐用户取号；实现内一个事务） */
    void insertBroadcast(String msgId, String type, String title, String content);

    /**
     * 单用户定向通知（一个原子用例）：dedupKey 命中时静默忽略（不耗号）；否则取号 + 落库同事务
     * （取号行锁到提交 ⇒ 取号序 = 提交序）。调用方已有事务时并入（REQUIRED）
     */
    void insertOne(NotificationEntity notificationEntity);
}
