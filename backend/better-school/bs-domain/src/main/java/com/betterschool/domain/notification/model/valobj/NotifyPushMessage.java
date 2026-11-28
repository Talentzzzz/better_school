package com.betterschool.domain.notification.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通知 BANNER 通道推送消息（NotifyEventConsumer 落库后直接调 NotificationCase.push → WS NOTIF 帧，只推在线者）：
 * 载荷即前端横幅展示所需的全量字段；离线漏推由前端按 (scope, seq) 水位补拉重放
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class NotifyPushMessage {

    /** 收件用户 */
    private String userId;
    /** 收件范围（前端未读角标按 scope 累加） */
    private String scope;
    /** per-(user, scope) 收件序号（前端补拉游标锚点） */
    private Long seq;
    private String type;
    private String title;
    private String content;
    private String refType;
    private String refId;
}
