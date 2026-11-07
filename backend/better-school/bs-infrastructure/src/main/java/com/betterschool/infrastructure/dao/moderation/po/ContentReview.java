package com.betterschool.infrastructure.dao.moderation.po;

import lombok.Data;

import java.time.LocalDateTime;

/** content_review 审核动作记录表 PO */
@Data
public class ContentReview {

    /** 物理自增 id（内部键；部分列投影语句才带出，「同 target 最新一轮 REJECT = id 最大的行」裁决用） */
    private Long id;

    /** 业务 ID（UUID 字符串；uuid-biz-id 票 06：UUID.randomUUID() 随 insert 一次写入，物理自增 id 保留纯内部键） */
    private String reviewId;
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
