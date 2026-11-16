package com.betterschool.domain.notification.service;

import com.betterschool.domain.notification.model.entity.NotificationEntity;
import com.betterschool.domain.notification.model.valobj.NotificationEventMessage;
import com.betterschool.domain.notification.model.valobj.NotificationScopeVO;

import java.util.List;

/**
 * 站内通知服务（通知域对外的唯一入口）：各域发通知只经本接口，不直连仓储。
 * 与 api 契约 {@code com.betterschool.api.INotificationService} 同名但不同层，调用方按包区分
 */
public interface INotificationService {

    /** scope 为 null 时不过滤（全部范围） */
    List<NotificationEntity> listNotifications(String userId, NotificationScopeVO scope, int page, int pageSize);

    long countNotifications(String userId, NotificationScopeVO scope);

    /** 未读数 = 计数器 − 水位（O(1)，按范围独立计算） */
    long unreadCount(String userId, NotificationScopeVO scope);

    /** 标已读 = 把水位推到当前计数器值（O(1)；各范围各推各的水位，互不误清） */
    void markRead(String userId, NotificationScopeVO scope);

    /** 管理员公告广播：outbox 落广播消息，消费方全用户落库 */
    void broadcast(String type, String title, String content);

    /** 公告广播落库（消费方入口）：按 msgId 幂等，给全用户各落一条 */
    void insertBroadcast(String msgId, String type, String title, String content);

    /**
     * 发布通知事件：业务事务内只写 outbox 一行（与业务写同事务原子），提交后发 MQ，
     * 由 NotifyEventConsumer 统一落 notification 表并按类型通道投递——通知落库时机=事件消费时
     */
    void publish(NotificationEventMessage event);
}
