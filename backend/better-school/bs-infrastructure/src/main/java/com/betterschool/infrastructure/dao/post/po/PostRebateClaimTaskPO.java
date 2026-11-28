package com.betterschool.infrastructure.dao.post.po;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 发帖返利领取任务行（{@code post_rebate_claim_task}，域内最终一致任务表，ADR-0017）。
 * 不是 MQ outbox：由 post 域自己的扫描 job 进程内重放，不经过 broker。
 */
@Data
public class PostRebateClaimTaskPO {

    private Long id;
    private String postId;
    private String userId;
    private String periodKey;
    /** 0 待领取 / 1 已完成 / 2 死信 */
    private Integer state;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime finishTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
