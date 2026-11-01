package com.betterschool.types.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 内容拒绝理由统一口径（人工审核）：拒绝时必选，落库为"枚举文案：补充说明"
 *
 * <p>⚠️ 共享类型手抄互指（票 12，spec §5.4）：本枚举是唯一事实源，
 * 前端手抄方为 {@code front-review/src/types/review.ts}（审核中台）与
 * {@code front/src/types/api.ts}（主前端）；改这里必须同步改两处。
 */
@Getter
@AllArgsConstructor
public enum RejectReasonCode {

    POLITICAL("涉政有害"),
    PORNOGRAPHY("色情低俗"),
    AD_PROMOTION("广告导流"),
    PROHIBITED_ITEM("违禁品交易"),
    FRAUD("诈骗导流"),
    HARASSMENT("人身攻击"),
    INFRINGEMENT("侵权"),
    OTHER("其他");

    /** 作者可见的中文文案 */
    private final String label;

    public static RejectReasonCode from(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return valueOf(code.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
