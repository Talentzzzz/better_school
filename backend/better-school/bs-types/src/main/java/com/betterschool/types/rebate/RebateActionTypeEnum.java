package com.betterschool.types.rebate;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 行为返利的行为类型（{@code action_rebate_config.action_type} 的取值）。
 * <p>
 * 规则行由后台写入，发放方（points / draw）经各自的只读 port 按 {@code (actionType, rebate_type)}
 * 取开启规则；生产方（签到 / 支付 / 发帖）与后台校验共用这一份取值，避免三处各写一遍魔法字符串。
 */
@Getter
@AllArgsConstructor
public enum RebateActionTypeEnum {

    /** 签到 */
    SIGN("sign"),

    /** 支付 */
    PAY("pay"),

    /** 发帖（帖子首次发布 / 草稿提交即发布） */
    POST("post");

    private final String code;
}
