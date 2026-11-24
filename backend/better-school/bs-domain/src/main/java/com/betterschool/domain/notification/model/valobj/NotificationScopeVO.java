package com.betterschool.domain.notification.model.valobj;

import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 通知收件范围（营销票 02）：写入时由生产点显式标注，禁止从 (type, refType) 推导——
 * 同一事件发用户与发审核员只能靠收件人与生产意图区分
 */
@Getter
@AllArgsConstructor
public enum NotificationScopeVO {

    /** 市集消息页·商品（预留，当前无生产方） */
    MARKET_PRODUCT("市集·商品"),
    /** 底部消息页·系统通知（公告/审核结果/审核提醒/举报结果/信用分变动/营销通知） */
    BOTTOM_SYSTEM("系统通知"),
    /** 互动·收到的赞 */
    INTERACTION_LIKE("收到的赞"),
    /** 互动·收到的评论 */
    INTERACTION_COMMENT("收到的评论");

    private final String desc;

    public static NotificationScopeVO from(String name) {
        try {
            return NotificationScopeVO.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "通知范围不合法");
        }
    }
}
