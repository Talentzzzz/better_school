package com.betterschool.marketing.domain.points.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 积分流水方向：入账行来自 points_bucket、消费行来自 points_detail。
 * 两表的积分数都是恒正的，方向靠这个枚举表达（而不是有符号数）。
 */
@Getter
@AllArgsConstructor
public enum PointsTransactionDirectionVO {

    /** 入账（points_bucket） */
    IN("入账"),

    /** 消费（points_detail） */
    OUT("消费");

    private final String desc;
}
