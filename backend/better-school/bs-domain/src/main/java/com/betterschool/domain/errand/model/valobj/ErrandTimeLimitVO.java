package com.betterschool.domain.errand.model.valobj;

import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ErrandTimeLimitVO {

    HOURS_2(2),
    HOURS_4(4),
    HOURS_12(12),
    HOURS_24(24),
    DAYS_7(168),
    DAYS_30(720),
    DAYS_365(8760);

    private final int hours;

    public static ErrandTimeLimitVO from(String name) {
        try {
            return ErrandTimeLimitVO.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResponseCode.PARAM_ERROR,
                    "时限不合法（可选 HOURS_2/HOURS_4/HOURS_12/HOURS_24/DAYS_7/DAYS_30/DAYS_365）");
        }
    }

    public static ErrandTimeLimitVO fromHours(int hours) {
        for (ErrandTimeLimitVO timeLimit : values()) {
            if (timeLimit.hours == hours) {
                return timeLimit;
            }
        }
        throw new BusinessException(ResponseCode.PARAM_ERROR, "时限不合法: " + hours + "h");
    }
}
