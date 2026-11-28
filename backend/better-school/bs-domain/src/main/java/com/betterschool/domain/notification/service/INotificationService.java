package com.betterschool.domain.notification.service;

import com.betterschool.domain.notification.model.entity.NotificationEntity;
import com.betterschool.domain.notification.model.valobj.NotificationEventMessage;
import com.betterschool.domain.notification.model.valobj.NotificationScopeEnum;
import com.betterschool.domain.notification.model.valobj.NotifyChatMessage;
import com.betterschool.domain.notification.model.valobj.NotifyPushMessage;

import java.util.List;

/**
 * 站内通知服务（通知域对外的唯一入口）：各域发通知只经本接口，不直连仓储。
 * 与 api 契约 {@code com.betterschool.api.INotificationService} 同名但不同层，调用方按包区分
 */
public interface INotificationService {

    /** scope 为 null 时不过滤（全部范围） */
    List<NotificationEntity> listNotifications(String userId, NotificationScopeEnum notificationScopeVO, int page, int pageSize);

    long countNotifications(String userId, NotificationScopeEnum notificationScopeVO);

    /** 未读数 = 计数器 − 水位（O(1)，按范围独立计算） */
    long unreadCount(String userId, NotificationScopeEnum notificationScopeVO);

    /** 标已读 = 把水位推到当前计数器值（O(1)；各范围各推各的水位，互不误清） */
    void markRead(String userId, NotificationScopeEnum notificationScopeVO);

    /** 管理员公告广播：outbox 落广播消息，消费方全用户落库 */
    void broadcast(String type, String title, String content);

    /** 公告广播落库（消费方入口）：按 msgId 幂等，给全用户各落一条 */
    void insertBroadcast(String msgId, String type, String title, String content);

    /**
     * 单用户定向通知落库（消费方入口）：dedupKey / notificationId 命中即静默忽略（不耗号），
     * 真正插入返回 true；调用方已有事务时并入（REQUIRED）
     */
    boolean insertOne(NotificationEntity notificationEntity);

    /** 同一 notificationId 是否已落库（消费方区分「同事件重放」与「dedupKey 命中的真重复」） */
    boolean existsByNotificationId(String notificationId);

    /**
     * 发布通知事件：业务事务内只写 outbox 一行（与业务写同事务原子），提交后发 MQ，
     * 由 NotifyEventConsumer 统一落 notification 表并按类型通道投递——通知落库时机=事件消费时
     */
    void publish(NotificationEventMessage notificationEventMessage);

    /**
     * 尽力而为的发布：语义同 {@link #publish}，但失败只告警不抛出——用于「主链路已提交、通知只是
     * 附属动作」的场景（如点赞通知），通知失败不回滚主流程。
     */
    void publishBestEffort(NotificationEventMessage notificationEventMessage);

    /**
     * 系统消息（CHAT 通道）载荷 JSON（与前端渲染约定）：{@code {notificationId, title, content,
     * refType?, refId?}}；序列化失败降级为纯文本，保证通知内容不丢。
     */
    String buildSystemChatPayload(NotifyChatMessage notifyChatMessage);

    /**
     * BANNER 横幅实时推送（技术出站能力在仓储 {@code pushNotif}，对本域外只暴露服务）：
     * 尽力而为，不在线/失败静默放弃，漏推由前端按 (scope, seq) 水位补拉重放兜底
     */
    void pushNotif(NotifyPushMessage notifyPushMessage);
}
