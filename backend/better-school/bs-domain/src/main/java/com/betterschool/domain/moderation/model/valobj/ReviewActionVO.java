package com.betterschool.domain.moderation.model.valobj;

import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 人工审核动作
 *
 * <p>⚠️ 共享类型手抄互指（票 12）：前端手抄方 {@code front-review/src/types/review.ts}；改这里必须同步。
 */
@Getter
@AllArgsConstructor
public enum ReviewActionVO {

    APPROVE("通过"),
    REJECT("驳回");

    private final String desc;

    public static ReviewActionVO from(String name) {
        try {
            return ReviewActionVO.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "审核动作不合法");
        }
    }
}
