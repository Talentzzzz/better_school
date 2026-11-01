package com.betterschool.domain.moderation.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 审核/举报对象类型：审核对象 POST/COMMENT/ERRAND/PRODUCT（USER/MESSAGE 仅举报可用）
 *
 * <p>⚠️ 共享类型手抄互指（票 12，spec §5.4）：本枚举是唯一事实源，
 * 前端手抄方为 {@code front-review/src/types/review.ts}（审核中台）与
 * {@code front/src/types/api.ts}（主前端）；改这里必须同步改两处。
 */
@Getter
@AllArgsConstructor
public enum ReviewTargetTypeVO {

    POST("帖子"),
    COMMENT("评论"),
    ERRAND("跑腿订单"),
    PRODUCT("闲置商品"),
    USER("用户"),
    MESSAGE("消息");

    private final String desc;

    /** 容错转换：未知类型返回 null（历史脏数据/非法入参，避免 500） */
    public static ReviewTargetTypeVO from(String name) {
        if (name == null) {
            return null;
        }
        try {
            return ReviewTargetTypeVO.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
