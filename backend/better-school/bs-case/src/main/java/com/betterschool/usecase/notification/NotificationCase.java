package com.betterschool.usecase.notification;

import com.betterschool.domain.chat.model.entity.MessageEntity;
import com.betterschool.domain.chat.model.entity.RoomEntity;
import com.betterschool.domain.chat.model.valobj.ChatUserVO;
import com.betterschool.domain.chat.model.valobj.MsgTypeEnum;
import com.betterschool.domain.chat.service.send.IChatSendService;
import com.betterschool.domain.user.model.entity.UserEntity;
import com.betterschool.domain.notification.model.entity.NotificationEntity;
import com.betterschool.domain.notification.model.valobj.NotificationEventMessage;
import com.betterschool.domain.notification.model.valobj.NotificationScopeEnum;
import com.betterschool.domain.notification.model.valobj.NotificationTypeEnum;
import com.betterschool.domain.notification.model.valobj.NotifyChatMessage;
import com.betterschool.domain.notification.model.valobj.NotifyPushMessage;
import com.betterschool.domain.notification.service.INotificationService;
import com.betterschool.domain.user.service.profile.IUserProfileService;
import com.betterschool.types.common.Constants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 通知用例实现：BANNER 实时推送 + CHAT 系统消息投递。
 * <p>
 * BANNER（瞬态横幅）：点赞/评论等通知投递给单收件人，只传本域推送消息、不碰帧信封
 * （NOTIF 帧组装与 payload 约定归 infrastructure/ws，仓储实现组帧）。推送是加速路径：
 * 不做事务、不做重试，不在线/失败由仓储出站（infrastructure/ws 连接组件）静默放弃——
 * 漏推由前端按 (scope, seq) 水位补拉重放兜底（保证弹过：上线后把积压的通知逐条弹完）。
 * <p>
 * CHAT（留档）：把一条通知经 chat 发送管线落进用户与系统助手（{@link Constants#SYSTEM_USER_ID}）的
 * 1v1 会话。幂等三重收敛：① notification 落库 dedup 幂等（outbox 重发源头少）② chat uk_room_client
 * （client_msg_id = "NOTIFY:" + notificationId）重复消费收敛同一条 ③ 系统会话 uk_pair 建会话幂等。
 * 会话懒创建（首次投递时建，恒 1v1、source 为 NULL 不进市集消息页筛子）；用户不可向系统会话
 * 发消息/主动建会话（守卫在 ChatSendServiceImpl）。投递失败向上抛由 broker 重试。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationCase implements INotificationCase {

    private static final String CLIENT_MSG_ID_PREFIX = "NOTIFY:";

    private final INotificationService notificationService;
    private final IChatSendService chatSendService;
    private final IUserProfileService userProfileService;

    @Override
    public void consume(NotificationEventMessage notificationEventMessage) {
        NotificationEntity notificationEntity = NotificationEntity.create(
                notificationEventMessage.getUserId(),
                NotificationScopeEnum.valueOf(notificationEventMessage.getScope()),
                NotificationTypeEnum.valueOf(notificationEventMessage.getType()),
                notificationEventMessage.getTitle(), notificationEventMessage.getContent(),
                notificationEventMessage.getRefType(), notificationEventMessage.getRefId());
        // notificationId 必须预填（生产点已生成）——不能让落库现生成，否则幂等锚丢失
        notificationEntity.setNotificationId(notificationEventMessage.getNotificationId());
        notificationEntity.setSenderId(notificationEventMessage.getSenderId());
        notificationEntity.setDedupKey(notificationEventMessage.getDedupKey());

        // 落库（幂等：dedupKey / notificationId 命中即吞，返回 false 且 seq 不回填）
        boolean inserted = notificationService.insertOne(notificationEntity);
        // 同一 notificationId 已落库 = 同一事件重放（上次可能落库成功但投递失败），CHAT 需要再投一次；
        // dedupKey 命中但 notificationId 不同 = 真正的重复事件，不再投递（保持旧语义）
        boolean sameEventReplay = !inserted
                && notificationService.existsByNotificationId(notificationEntity.getNotificationId());
        if (!inserted) {
            log.info("[NotificationCase] 重复通知事件 notificationId={} dedupKey={} sameEventReplay={}",
                    notificationEventMessage.getNotificationId(), notificationEventMessage.getDedupKey(), sameEventReplay);
        }

        // 落库后原地投递（不写第二跳 outbox、不经第二跳 MQ）：CHAT 失败抛出交 broker 重投；BANNER 尽力而为
        NotificationTypeEnum.ChannelEnum channel = notificationEntity.getType().getChannel();
        switch (channel) {
            case CHAT -> {
                if (inserted || sameEventReplay) {
                    deliver(new NotifyChatMessage(
                            notificationEntity.getNotificationId(), notificationEntity.getUserId(), notificationEntity.getType().name(),
                            notificationEntity.getTitle(), notificationEntity.getContent(), notificationEntity.getRefType(), notificationEntity.getRefId()));
                }
            }
            case BANNER -> {
                // 幂等命中（重复事件）没有 seq，跳过，避免重复弹横幅
                if (inserted) {
                    try {
                        push(new NotifyPushMessage(
                                notificationEntity.getUserId(), notificationEntity.getScope().name(), notificationEntity.getSeq(),
                                notificationEntity.getType().name(), notificationEntity.getTitle(), notificationEntity.getContent(),
                                notificationEntity.getRefType(), notificationEntity.getRefId()));
                    } catch (Exception e) {
                        // 横幅是加速路径：失败只记日志，漏推由前端按 (scope, seq) 水位补拉
                        log.warn("[NotificationCase] BANNER 推送失败（不重试） notificationId={}",
                                notificationEntity.getNotificationId(), e);
                    }
                }
            }
            case NONE -> {
                // 仅落通知中心，不投递
            }
        }
        log.info("[NotificationCase] 通知事件落库完成 notificationId={} userId={} type={} channel={} inserted={}",
                notificationEntity.getNotificationId(), notificationEntity.getUserId(), notificationEntity.getType().name(), channel, inserted);
    }

    @Override
    public void push(NotifyPushMessage notifyPushMessage) {
        notificationService.pushNotif(notifyPushMessage);
    }

    @Override
    public void deliver(NotifyChatMessage notifyChatMessage) {
        // 系统账号异常兜底：种子行缺失（schema.sql）时打不进来——不抛（避免 broker 无限重试死信噪音），错误日志暴露
        List<UserEntity> systemUsers = userProfileService.listBasic(List.of(Constants.SYSTEM_USER_ID));
        if (systemUsers.isEmpty()) {
            log.error("[NotificationCase] 系统助手账号不存在，通知投递跳过 notificationId={}", notifyChatMessage.getNotificationId());
            return;
        }
        UserEntity userEntity = systemUsers.get(0);
        if (Constants.SYSTEM_USER_ID.equals(notifyChatMessage.getUserId())) {
            return; // 不会发生：不给系统账号自己发
        }
        // 系统侧发起建/取会话：user_pair_room.uk_pair 幂等，首次投递才真正建
        RoomEntity roomEntity = chatSendService.createDirectRoom(Constants.SYSTEM_USER_ID, notifyChatMessage.getUserId(), null);
        MessageEntity messageEntity = new MessageEntity();
        messageEntity.setRoomId(roomEntity.getRoomId());
        messageEntity.setFromUserId(Constants.SYSTEM_USER_ID);
        messageEntity.setMsgType(MsgTypeEnum.SYSTEM);
        messageEntity.setContent(notificationService.buildSystemChatPayload(notifyChatMessage));
        // 客户端幂等键锚定通知业务 ID：消费重放/重试收敛同一条
        messageEntity.setClientMsgId(CLIENT_MSG_ID_PREFIX + notifyChatMessage.getNotificationId());
        ChatUserVO chatUserVO = new ChatUserVO();
        chatUserVO.setUserId(userEntity.getUserId());
        chatUserVO.setNickname(userEntity.getNickname());
        chatUserVO.setAvatar(userEntity.getAvatar());
        chatSendService.sendMessage(messageEntity, chatUserVO);
        log.info("[NotificationCase] 系统消息投递成功 notificationId={} userId={}",
                notifyChatMessage.getNotificationId(), notifyChatMessage.getUserId());
    }
}
