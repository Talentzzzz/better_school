package com.betterschool.domain.notification.service;

import com.betterschool.domain.notification.model.entity.NotificationEntity;
import com.betterschool.domain.notification.model.valobj.NotificationScopeVO;
import com.betterschool.domain.notification.model.valobj.NotificationTypeVO;

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

    /** 单用户定向通知（取号+落库自成一事务；调用方已有事务则并入）：直插 notification 表不走 MQ */
    void sendToUser(String userId, NotificationScopeVO scope, NotificationTypeVO type, String title,
                    String content, String refType, String refId);

    /**
     * 互动通知（带触发者）：按 dedupKey 幂等（同一触发者对同一目标只落一条，冲突静默忽略）。
     * 供 case 层在业务事务提交后尽力而为调用，取消动作不撤回已发通知。
     */
    void sendInteraction(String userId, String senderId, NotificationScopeVO scope, NotificationTypeVO type,
                         String title, String content, String refType, String refId, String dedupKey);

    /**
     * 系统通知（无触发者）：带 dedupKey 时按唯一键幂等忽略（消费重放/重复扫描只落一条）。
     * 供消费方/扫描任务尽力而为调用，调用方自行吞异常（通知失败不影响主流程）。
     */
    void sendSystem(String userId, NotificationScopeVO scope, NotificationTypeVO type, String title,
                    String content, String refType, String refId, String dedupKey);
}
