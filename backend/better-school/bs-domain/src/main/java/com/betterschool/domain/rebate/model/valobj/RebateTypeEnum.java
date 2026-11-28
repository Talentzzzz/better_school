package com.betterschool.domain.rebate.model.valobj;

import com.betterschool.types.enums.ResponseCodeEnum;
import com.betterschool.types.exception.BusinessException;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 行为返利的返利类型（action_rebate_config.rebate_type）：决定返利落哪张桶表——
 * POINTS → points_bucket，DRAW_COUNT → draw_count_bucket；
 * {@code config_id} 也随之指向对应的有效期配置（points_validity_config / draw_count_config）。
 */
@Getter
@AllArgsConstructor
public enum RebateTypeEnum {

    /** 返利落积分桶，有效期看 points_validity_config */
    POINTS("积分"),

    /** 返利落抽奖次数桶，有效期看 draw_count_config */
    DRAW_COUNT("抽奖次数");

    private final String desc;

    /** 按枚举名反查类型；未知取值抛业务异常（入账消息里的类型只认约定值） */
    public static RebateTypeEnum from(String name) {
        try {
            return RebateTypeEnum.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new BusinessException(ResponseCodeEnum.BUSINESS_ERROR, "不支持的返利类型：" + name);
        }
    }
}
