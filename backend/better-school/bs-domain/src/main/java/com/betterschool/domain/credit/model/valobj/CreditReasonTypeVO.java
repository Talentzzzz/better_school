package com.betterschool.domain.credit.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 信用分变动原因（规则表）：delta 为本次变动量，dailyCap 非空表示该类型有每日加分上限
 * TRADE_COMPLAINT 为预留（本期未接入）
 */
@Getter
@AllArgsConstructor
public enum CreditReasonTypeVO {

    ERRAND_TAKER_CANCEL(-20, "跑腿接单后取消", null),
    ERRAND_TIMEOUT(-20, "跑腿超时未完成", null),
    ADMIN_ADJUST(0, "管理员调分", null),
    ADMIN_BAN(0, "管理员封禁", null),
    ADMIN_UNBAN(0, "管理员解封", null),
    TRADE_COMPLAINT(-20, "交易被投诉核实", null);

    private final int delta;

    private final String desc;

    private final Integer dailyCap;

    public boolean hasDailyCap() {
        return dailyCap != null;
    }

    /** 容错：历史脏数据/未知类型返回 null（查流水展示时兜底显原值，防 500） */
    public static CreditReasonTypeVO from(String name) {
        if (name == null) {
            return null;
        }
        try {
            return CreditReasonTypeVO.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
