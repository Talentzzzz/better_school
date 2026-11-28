package com.betterschool.domain.post.model.entity;

import com.betterschool.types.rebate.PostRebateClaimTaskStateEnum;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 发帖返利领取任务（{@code post_rebate_claim_task}，ADR-0017「域内最终一致任务表」）。
 * <p>
 * 不是 MQ 消息：行的职责是「这条帖子的返利领取还没做完」，由 post 域自己的扫描 job
 * {@code postRebateClaimScan} 进程内重放，不经过 broker。发帖 / 提交草稿事务内插一行，与帖子行同生同死；
 * 请求线程随后同步领取，正常情况下当次即置 {@link PostRebateClaimTaskStateEnum#DONE}。
 * <p>
 * {@code periodKey} 在发帖事务里按数据库发布时间定死，重放必须沿用它而不是重放当天的日期，
 * 避免跨零点重复占一个周期名额。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PostRebateClaimTaskEntity {

    /** 物理自增 id（扫描抢占 / 死信按 id 条件更新） */
    private Long id;
    /** 帖子业务 ID（uk_post_id：同一帖子只落一条任务） */
    private String postId;
    private String userId;
    /** 返利周期键（yyyyMMdd，Asia/Shanghai），发帖事务内定死 */
    private String periodKey;
    private PostRebateClaimTaskStateEnum state;
    /** 重试次数（扫描抢占时递增） */
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime finishTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
