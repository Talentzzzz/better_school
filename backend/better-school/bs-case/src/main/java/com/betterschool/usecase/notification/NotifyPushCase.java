package com.betterschool.usecase.notification;

import com.betterschool.domain.notification.adapter.repository.INotificationRepository;
import com.betterschool.domain.notification.model.valobj.NotifyPushMessage;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 通知 WS 推送用例（BANNER 通道的落地侧决策）：点赞/评论等瞬态横幅投递给单收件人，
 * 只传本域推送消息、不碰帧信封（NOTIF 帧组装与 payload 约定归 infrastructure/ws，仓储实现组帧）。
 * refType/refId 消费侧 null→"" 归一化在帧组装处（WsFrameFactory.notif，老事件回放载荷可能缺列，前端按空串渲染）。
 * 推送是加速路径：不做事务、不做重试，不在线/失败由仓储出站（infrastructure/ws 连接组件）静默放弃——
 * 漏推由前端按 (scope, seq) 水位补拉重放兜底（保证弹过：上线后把积压的通知逐条弹完）
 */
@Service
@RequiredArgsConstructor
public class NotifyPushCase {

    private final INotificationRepository notificationRepository;

    public void push(NotifyPushMessage message) {
        notificationRepository.pushNotif(message.getUserId(), message);
    }
}
