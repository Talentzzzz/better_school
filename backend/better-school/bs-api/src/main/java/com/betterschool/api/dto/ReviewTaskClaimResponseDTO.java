package com.betterschool.api.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 领单结果（spec §5.3 原子指派 + 起 5 分钟租约）：工单定位 + 租约/轮次派单态；内容素材走 GET /tasks/{taskId} 详情聚合
 *
 * <p>⚠️ 共享类型手抄互指（票 12，spec §5.4）：本 DTO 是唯一事实源，
 * 前端手抄方 {@code front-review/src/types/review.ts} 的 ReviewTaskClaim；改这里必须同步。
 */
@Data
public class ReviewTaskClaimResponseDTO {

    private Long taskId;

    /** 内容类型 POST/COMMENT/PRODUCT/ERRAND */
    private String targetType;

    private String targetId;

    /** 审核轮次 */
    private Integer reviewRound;

    /** 工单态（领单成功恒 CLAIMED） */
    private String claimState;

    /** 领单轮次（本次 = +1 后的值；复合 bizId {taskId}:{claimSeq} 的另一半） */
    private Integer claimSeq;

    /** 租约到期时刻（领单时刻 + 5min；到点未裁决由超时中心回调回收） */
    private LocalDateTime leaseExpireAt;

    /** 累计回收次数（≥3 已置需人工干预标记） */
    private Integer recycleCount;

    /** 需人工干预标记（recycle_count≥3） */
    private Boolean escalated;

    /** 进入待审时刻（FIFO 锚点；回收不改，等待时长继续累加） */
    private LocalDateTime enteredAt;
}
