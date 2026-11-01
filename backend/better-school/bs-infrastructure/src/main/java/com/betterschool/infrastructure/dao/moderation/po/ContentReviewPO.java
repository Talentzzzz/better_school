package com.betterschool.infrastructure.dao.moderation.po;

import lombok.Data;

import java.time.LocalDateTime;

/** content_review 审核动作记录表 PO */
@Data
public class ContentReviewPO {

    private Long id;
    private String targetType;
    private String targetId;
    /** 作者用户 ID（冗余；对齐 content_report.author_id） */
    private String authorId;
    private Integer reviewRound;
    private String action;
    private String source;
    private String operatorId;
    private String reason;
    private String reasonCode;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
