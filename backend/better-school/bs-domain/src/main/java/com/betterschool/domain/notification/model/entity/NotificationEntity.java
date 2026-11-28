package com.betterschool.domain.notification.model.entity;

import com.betterschool.domain.notification.model.valobj.NotificationScopeEnum;
import com.betterschool.domain.notification.model.valobj.NotificationTypeEnum;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class NotificationEntity {

    /** 业务 ID（notification_id，UUID 字符串，uuid-biz-id 票 06） */
    private String notificationId;
    private String userId;
    /** 收件范围（生产点显式标注，见 NotificationScopeEnum） */
    private NotificationScopeEnum scope;
    /** per-(user, scope) 收件序号（notification_seq 计数器写入事务内取号）；查询路径由仓储回填 */
    private Long seq;
    /** 触发者用户 ID（互动通知：谁评/回/赞了你；广播类为空） */
    private String senderId;
    private NotificationTypeEnum type;
    private String title;
    private String content;
    /** 已读态（seq ≤ 该范围水位），由读路径算出，非落库列 */
    private boolean read;
    private String refType;
    /** 关联业务 ID（POST/COMMENT=UUID 字符串；REF/订单/营销等过渡期仍是数值 id 的字符串形） */
    private String refId;

    /** 内部幂等键：广播消息 msg_id（公告落库幂等检查用，不对外暴露） */
    private String broadcastMsgId;

    /** 内部幂等键：互动通知 dedup_key（同一触发者对同一目标只发一条，取消动作不撤回） */
    private String dedupKey;

    private LocalDateTime createdAt;

    public static NotificationEntity create(String userId, NotificationScopeEnum notificationScopeVO, NotificationTypeEnum notificationTypeVO,
                                            String title, String content, String refType, String refId) {
        NotificationEntity notificationEntity = new NotificationEntity();
        notificationEntity.userId = userId;
        notificationEntity.scope = notificationScopeVO;
        notificationEntity.type = notificationTypeVO;
        notificationEntity.title = title;
        notificationEntity.content = content;
        notificationEntity.refType = refType;
        notificationEntity.refId = refId;
        return notificationEntity;
    }

}
