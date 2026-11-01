package com.betterschool.domain.moderation.model.entity;

import com.betterschool.domain.moderation.model.valobj.ReviewActionVO;
import com.betterschool.domain.moderation.model.valobj.ReviewSourceVO;
import com.betterschool.domain.moderation.model.valobj.ReviewTargetTypeVO;
import lombok.Data;

import java.time.LocalDateTime;

/** 审核动作记录（每次人工审核插一行，纯审计；uk(target_type,target_id,action,review_round) 幂等） */
@Data
public class ContentReviewEntity {

    private Long id;
    private ReviewTargetTypeVO targetType;
    private String targetId;
    /** 作者用户 ID（冗余；对齐 content_report.author_id，按作者聚合历史裁决免反查业务表） */
    private String authorId;
    private int reviewRound;
    private ReviewActionVO action;
    private ReviewSourceVO source;
    private String operatorId;
    private String reason;
    /** 拒绝理由枚举 code（RejectReasonCode，APPROVE/枚举化前历史数据为 NULL） */
    private String reasonCode;
    private LocalDateTime createdAt;

    public static ContentReviewEntity create(ReviewTargetTypeVO targetType, String targetId, String authorId,
                                             int reviewRound, ReviewActionVO action, ReviewSourceVO source,
                                             String operatorId, String reason, String reasonCode) {
        ContentReviewEntity reviewEntity = new ContentReviewEntity();
        reviewEntity.targetType = targetType;
        reviewEntity.targetId = targetId;
        reviewEntity.authorId = authorId;
        reviewEntity.reviewRound = reviewRound;
        reviewEntity.action = action;
        reviewEntity.source = source;
        reviewEntity.operatorId = operatorId;
        reviewEntity.reason = reason;
        reviewEntity.reasonCode = reasonCode;
        reviewEntity.createdAt = LocalDateTime.now();
        return reviewEntity;
    }
}
