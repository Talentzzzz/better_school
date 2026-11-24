package com.betterschool.domain.notification.model.valobj;

import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum NotificationTypeVO {

    /** 平台公告（广播落库，无实时投递通道） */
    ANNOUNCEMENT("平台公告", Channel.NONE),
    /** 信用分变动/封禁/解封——走系统消息（聊天）通道 */
    CREDIT_CHANGE("信用分变动", Channel.CHAT),
    POST_COMMENTED("帖子被评论", Channel.BANNER),
    COMMENT_REPLIED("评论被回复", Channel.BANNER),
    POST_LIKED("帖子被赞", Channel.BANNER),
    COMMENT_LIKED("评论被赞", Channel.BANNER),
    /** 营销：抽奖中奖（bs-lottery-drawn 事件；谢谢参与不发事件不落通知）——只落通知中心，不推送 */
    LOTTERY_WON("抽奖中奖", Channel.NONE),
    /** 营销：积分变动（bs-points-changed 事件）——只落通知中心，不推送 */
    POINTS_CHANGED("积分变动", Channel.NONE),
    /** 营销：库存对账告警（bs-marketing-recon-alert 事件，对账轮 CONFIRMED 差错 / DEAD 孤儿单点名）——
     *  维护者告警，收件人 = 系统助手账号，只落通知中心，不推送 */
    RECON_ALERT("对账告警", Channel.NONE);

    /** 投递通道（通知落库是唯一真相源，通道只是呈现方式的路由） */
    public enum Channel {
        /** 系统消息（聊天）：经系统助手会话投递，在线 WS 冒泡、离线 timeline 补拉兜底 */
        CHAT,
        /** 顶部弹一下（瞬态横幅）：WS NOTIF 帧只推在线者，离线由前端按 scope seq 水位补拉重放 */
        BANNER,
        /** 不推送：仅落 notification 表（通知中心被动可见） */
        NONE
    }

    private final String desc;
    private final Channel channel;

    public static NotificationTypeVO from(String name) {
        try {
            return NotificationTypeVO.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "通知类型不合法");
        }
    }
}
