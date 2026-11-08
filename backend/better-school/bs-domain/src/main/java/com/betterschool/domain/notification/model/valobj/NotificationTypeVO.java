package com.betterschool.domain.notification.model.valobj;

import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum NotificationTypeVO {

    ANNOUNCEMENT("平台公告"),
    ORDER_EVENT("订单动态"),
    CREDIT_CHANGE("信用分变动"),
    POST_COMMENTED("帖子被评论"),
    COMMENT_REPLIED("评论被回复"),
    POST_LIKED("帖子被赞"),
    COMMENT_LIKED("评论被赞"),
    /** 营销：抽奖中奖（bs-lottery-drawn 事件；谢谢参与不发事件不落通知） */
    LOTTERY_WON("抽奖中奖"),
    /** 营销：积分变动（bs-points-changed 事件） */
    POINTS_CHANGED("积分变动");

    private final String desc;

    public static NotificationTypeVO from(String name) {
        try {
            return NotificationTypeVO.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "通知类型不合法");
        }
    }
}
