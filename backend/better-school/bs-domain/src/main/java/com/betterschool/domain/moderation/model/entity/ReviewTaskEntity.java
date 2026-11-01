package com.betterschool.domain.moderation.model.entity;

import com.betterschool.domain.moderation.model.valobj.ReviewTargetTypeVO;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审核工单（review_task 行；在途派单凭据，与 content_review 审计语义分离）：
 * 工单单生命周期、可变（领单/回收/裁决），裁决结论落在 content_review，工单只有一个终态 DONE；
 * 不承载裁决与可见性——对外可见与否由业务行状态流转决定
 */
@Data
public class ReviewTaskEntity {

    private Long id;
    /** 内容类型 POST/COMMENT/PRODUCT/ERRAND（ReviewTargetTypeVO 可审值） */
    private ReviewTargetTypeVO targetType;
    private String targetId;
    /** 审核轮次（与 content_review.review_round 同源；COMMENT/ERRAND 恒 1） */
    private int reviewRound;
    /** 作者用户 ID（冗余，工单详情/历史聚合免 join） */
    private String authorId;
    /** 入池来源 MANUAL/KEYWORD/RANDOM（ReviewSourceVO name） */
    private String source;
    /** 工单态 PENDING(在池)/CLAIMED(租约中)/DONE(已裁决)；勿与业务表 state 混用 */
    private String claimState;
    /** 当前持单人（审核员用户 ID，PENDING/DONE 为空） */
    private String claimBy;
    /** 领单轮次（每次 claim +1；租约复合 bizId {id}:{claimSeq} 与回调守卫） */
    private Integer claimSeq;
    private LocalDateTime leaseExpireAt;
    /** 回收次数（≥3 置 escalated + 告警） */
    private Integer recycleCount;
    private boolean escalated;
    /** 进入待审时刻（FIFO 排序锚点；回收不改，等待时长继续累加） */
    private LocalDateTime enteredAt;
    /** 裁决时刻（DONE 时写） */
    private LocalDateTime decidedAt;
    private LocalDateTime createdAt;
    private LocalDateTime updateTime;
}
