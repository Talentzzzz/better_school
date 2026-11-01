package com.betterschool.domain.user.model.valobj;

import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;

/**
 * 性别值对象：仅 MALE/FEMALE 两个取值，「不展示」用 null 表达（不另设第三个枚举值）
 */
public enum GenderVO {

    MALE,
    FEMALE;

    /** 由入参字符串解析（null/空串＝不展示，返回 null；非法值报参数错误） */
    public static GenderVO from(String gender) {
        if (gender == null || gender.isBlank()) {
            return null;
        }
        try {
            return valueOf(gender);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "性别不合法（MALE/FEMALE/不展示）");
        }
    }
}
