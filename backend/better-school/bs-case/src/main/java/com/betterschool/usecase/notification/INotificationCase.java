package com.betterschool.usecase.notification;

import com.betterschool.domain.notification.model.valobj.NotificationEventMessage;
import com.betterschool.domain.notification.model.valobj.NotifyChatMessage;
import com.betterschool.domain.notification.model.valobj.NotifyPushMessage;

/**
 * 通知用例（通知落库 + BANNER 实时推送 + CHAT 系统消息投递）的统一入口。
 * <p>
 * 所有通知消费入口（{@code NotifyEventConsumer}、{@code PostLikedConsumer} 等）都走 {@link #consume}：
 * 幂等落 notification 后原地投递，不写第二跳 outbox、不经第二跳 MQ。BANNER 是瞬态横幅、
 * 尽力而为；CHAT 是留档消息、失败上抛由 broker 重投来源事件。跨域组合只在实现里顺序调用
 * 各域服务（ADR-0016，无跨域事务）。
 */
public interface INotificationCase {

    /**
     * 消费一条通知事件：按 notificationId / dedupKey 幂等落 notification，落库成功后原地投递。
     * <p>
     * 未真正落库（重复事件）时不投递；同一 notificationId 的事件重放（上次落库成功但投递失败）
     * 只补投 CHAT。投递失败时异常上抛，由 broker 重投来源消息。
     */
    void consume(NotificationEventMessage notificationEventMessage);

    /**
     * BANNER 横幅实时推送（点赞/评论等瞬态通知）：只推在线者，不做事务、不做重试，
     * 漏推由前端按 (scope, seq) 水位补拉重放兜底
     */
    void push(NotifyPushMessage notifyPushMessage);

    /**
     * CHAT 系统消息投递：把一条通知经 chat 发送管线落进用户与系统助手的 1v1 会话；
     * 投递失败向上抛由 broker 重试（chat 幂等键收敛重复消费）
     */
    void deliver(NotifyChatMessage notifyChatMessage);
}
