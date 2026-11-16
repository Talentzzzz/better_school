package com.betterschool.trigger.listener;

import com.betterschool.domain.notification.adapter.repository.INotificationRepository;
import com.betterschool.domain.notification.model.entity.NotificationEntity;
import com.betterschool.domain.notification.model.valobj.NotificationEventMessage;
import com.betterschool.domain.notification.model.valobj.NotificationScopeVO;
import com.betterschool.domain.notification.model.valobj.NotificationTypeVO;
import com.betterschool.domain.notification.model.valobj.NotifyChatMessage;
import com.betterschool.domain.notification.model.valobj.NotifyPushMessage;
import com.betterschool.types.common.Constants;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Component;

/**
 * 通知事件消费方（通知域唯一写入口）：落 notification（幂等）→ 按 NotificationTypeVO.Channel
 * 写投递 outbox（CHAT→bs-notify-chat / BANNER→bs-notify-push / NONE→止）。
 * 失败向上抛由 broker 重试——通知落库是持久化义务，不是加速路径；
 * 幂等靠 notificationId（uk_notification_id）+ dedupKey（uk_dedup）双锚收敛，重试不重复落库。
 * 幂等命中（未真正插入）时没有 seq，BANNER 投递跳过——重复事件不重复弹横幅，前端补拉兜底
 */
@Slf4j
@Component
@RequiredArgsConstructor
@RocketMQMessageListener(topic = Constants.NOTIFY_EVENT_TOPIC, consumerGroup = "bs-notify-event-group")
public class NotifyEventConsumer implements RocketMQListener<String> {

    private final INotificationRepository notificationRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void onMessage(String payload) {
        NotificationEventMessage message;
        try {
            message = objectMapper.readValue(payload, NotificationEventMessage.class);
        } catch (Exception e) {
            log.error("[NotifyEventConsumer] 通知事件反序列化失败 payload={}", payload, e);
            throw e instanceof RuntimeException runtimeException ? runtimeException : new IllegalStateException(e);
        }

        NotificationEntity entity;
        try {
            entity = NotificationEntity.create(message.getUserId(), NotificationScopeVO.valueOf(message.getScope()),
                    NotificationTypeVO.valueOf(message.getType()), message.getTitle(), message.getContent(),
                    message.getRefType(), message.getRefId());
            // notificationId 必须预填（生产点已生成）——不能让落库现生成，否则幂等锚丢失
            entity.setNotificationId(message.getNotificationId());
            entity.setSenderId(message.getSenderId());
            entity.setDedupKey(message.getDedupKey());

            // 落库（幂等：dedupKey / notificationId 命中即吞，返回 false 且 seq 不回填）
            boolean inserted = notificationRepository.insertOne(entity);
            if (!inserted) {
                log.info("[NotifyEventConsumer] 重复通知事件，幂等跳过落库与投递 notificationId={} dedupKey={}",
                        message.getNotificationId(), message.getDedupKey());
                return;
            }

            // 按类型通道路由投递（落库成功才投）：CHAT 系统消息链路 / BANNER 横幅链路 / NONE 到此为止
            NotificationTypeVO.Channel channel = entity.getType().getChannel();
            switch (channel) {
                case CHAT -> notificationRepository.saveChatDelivery(new NotifyChatMessage(
                        entity.getNotificationId(), entity.getUserId(), entity.getType().name(),
                        entity.getTitle(), entity.getContent(), entity.getRefType(), entity.getRefId()));
                case BANNER -> notificationRepository.savePushDelivery(new NotifyPushMessage(
                        entity.getUserId(), entity.getScope().name(), entity.getSeq(), entity.getType().name(),
                        entity.getTitle(), entity.getContent(), entity.getRefType(), entity.getRefId()));
                case NONE -> {
                    // 仅落通知中心，不投递
                }
            }
            log.info("[NotifyEventConsumer] 通知事件落库完成 notificationId={} userId={} type={} channel={}",
                    entity.getNotificationId(), entity.getUserId(), entity.getType().name(), channel);
        } catch (Exception e) {
            log.error("[NotifyEventConsumer] 通知事件消费失败（broker 重试） notificationId={}",
                    message.getNotificationId(), e);
            throw e instanceof RuntimeException runtimeException ? runtimeException : new IllegalStateException(e);
        }
    }
}
