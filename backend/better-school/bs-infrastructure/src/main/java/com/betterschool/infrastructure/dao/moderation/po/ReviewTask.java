package com.betterschool.infrastructure.dao.moderation.po;

import lombok.Data;

import java.time.LocalDateTime;

/** 审核工单 PO（review_task；在途派单凭据，与 content_review 审计语义分离） */
@Data
public class ReviewTask {

    /** 业务 ID（UUID 字符串；uuid-biz-id 票 06：UUID.randomUUID() 随 insert 一次写入，物理自增 id 保留纯内部键） */
    private String taskId;
    /** 内容类型 POST/COMMENT/PRODUCT/ERRAND */
    private String targetType;
    private String targetId;
    /** 审核轮次（与 content_review.review_round 同源；COMMENT/ERRAND 恒 1） */
    private Integer reviewRound;
    private String authorId;
    /** 入池来源 MANUAL/KEYWORD/RANDOM */
    private String source;
    /** 工单态 PENDING(在池)/CLAIMED(租约中)/DONE(已裁决) */
    private String claimState;
    private String claimBy;
    private Integer claimSeq;
    private LocalDateTime leaseExpireAt;
    private Integer recycleCount;
    private Boolean escalated;
    /** 进入待审时刻（FIFO 排序锚点；回收不改） */
    private LocalDateTime enteredAt;
    private LocalDateTime decidedAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
