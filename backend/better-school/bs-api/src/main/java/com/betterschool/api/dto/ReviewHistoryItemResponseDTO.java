package com.betterschool.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 历史裁决条目（content_review 只插入审计行；同一内容历轮与同一作者历史共用此口径）
 *
 * <p>⚠️ 共享类型手抄互指（票 12）：前端手抄方 {@code front-review/src/types/review.ts} 的 ReviewHistoryItem；改这里必须同步。
 */
@Data
public class ReviewHistoryItemResponseDTO {

    /** 审核记录业务 ID（UUID 字符串，uuid-biz-id 票 06） */
    private String reviewId;
    private String targetType;
    private String targetId;
    /** 审核轮次（同一目标第 N 次进入待审） */
    private Integer reviewRound;
    /** 动作 APPROVE/REJECT */
    private String action;
    /** 拒绝理由（"枚举文案：补充说明"格式，通过为空） */
    private String reason;
    /** 拒绝理由枚举 code（APPROVE/枚举化前历史数据为 null） */
    private String reasonCode;
    /** 审核员用户 ID */
    private String operatorId;
    private LocalDateTime createdAt;
}
