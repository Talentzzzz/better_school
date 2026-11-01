package com.betterschool.domain.moderation.model.valobj;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 审核触发来源：本期仅 MANUAL（人工审核动作记录）；
 * KEYWORD(关键词自动拦截)/RANDOM(随机抽查) 为扩展点
 */
@Getter
@AllArgsConstructor
public enum ReviewSourceVO {

    MANUAL("人工审核"),
    KEYWORD("关键词拦截"),
    RANDOM("随机抽查");

    private final String desc;

    /** 容错转换：未知来源返回 null（历史脏数据，避免 500） */
    public static ReviewSourceVO from(String name) {
        if (name == null) {
            return null;
        }
        try {
            return ReviewSourceVO.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
