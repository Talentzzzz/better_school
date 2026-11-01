package com.betterschool.domain.notification.model.entity;

import com.betterschool.domain.notification.model.valobj.NotificationScopeVO;
import com.betterschool.domain.notification.model.valobj.NotificationTypeVO;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class NotificationEntity {

    private Long id;
    private String userId;
    /** 收件范围（生产点显式标注，见 NotificationScopeVO） */
    private NotificationScopeVO scope;
    /** per-(user, scope) 收件序号（notification_seq 计数器写入事务内取号）；查询路径由仓储回填 */
    private Long seq;
    /** 触发者用户 ID（互动通知：谁评/回/赞了你；广播类为空） */
    private String senderId;
    private NotificationTypeVO type;
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

    public static NotificationEntity create(String userId, NotificationScopeVO scope, NotificationTypeVO type,
                                            String title, String content, String refType, String refId) {
        NotificationEntity notificationEntity = new NotificationEntity();
        notificationEntity.userId = userId;
        notificationEntity.scope = scope;
        notificationEntity.type = type;
        notificationEntity.title = title;
        notificationEntity.content = content;
        notificationEntity.refType = refType;
        notificationEntity.refId = refId;
        notificationEntity.createdAt = LocalDateTime.now();
        return notificationEntity;
    }

    public static NotificationEntity createInteraction(String userId, String senderId, NotificationScopeVO scope,
                                                       NotificationTypeVO type, String title, String content,
                                                       String refType, String refId, String dedupKey) {
        NotificationEntity notificationEntity = create(userId, scope, type, title, content, refType, refId);
        notificationEntity.senderId = senderId;
        notificationEntity.dedupKey = dedupKey;
        return notificationEntity;
    }

    /** 系统通知（无触发者）：带 dedupKey 时按唯一键幂等（消费重放/重复扫描只落一条） */
    public static NotificationEntity createSystem(String userId, NotificationScopeVO scope, NotificationTypeVO type,
                                                  String title, String content, String refType, String refId,
                                                  String dedupKey) {
        NotificationEntity notificationEntity = create(userId, scope, type, title, content, refType, refId);
        notificationEntity.dedupKey = dedupKey;
        return notificationEntity;
    }
}
