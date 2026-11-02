package com.betterschool.domain.moderation.adapter.repository;

import com.betterschool.domain.moderation.model.entity.ContentReportEntity;
import com.betterschool.domain.moderation.model.entity.ContentReviewEntity;
import com.betterschool.domain.moderation.model.entity.ReviewQueueItemEntity;
import com.betterschool.domain.moderation.model.entity.ReviewTargetDetailEntity;
import com.betterschool.domain.moderation.model.entity.ReviewTaskEntity;
import com.betterschool.domain.moderation.model.entity.SensitiveWordEntity;
import com.betterschool.domain.moderation.model.valobj.ReviewPendingWaitStats;

import java.util.List;
import java.util.Map;

/**
 * moderation 仓储：content_review/content_report/sensitive_word 读写 + 审核队列三业务表只读直查。
 * 只读直查（post/post_comment/errand_order 的 PENDING_REVIEW 与 bs_user 昵称）不依赖其他域接口；
 * 业务表状态写仍走原域方法（community/errand）
 */
public interface IModerationRepository {

    /** 审核动作记录（INSERT IGNORE uk 幂等） */
    void saveReview(ContentReviewEntity reviewEntity);

    /** 工单出池关单：该目标全部在途工单（PENDING/CLAIMED）停 DONE（裁决完成/内容退出待审；幂等，0 行无害） */
    void closeOpenTasks(String targetType, String targetId);

    /**
     * 领单（spec §5.3 原子指派 + 起租约，一个原子用例）：惰性回收过期租约 → FIFO 选候选（targetType
     * 只作过滤器）→ 条件 UPDATE 抢占 → 复合 bizId 注册租约超时；无候选/抢占落空返回 null
     */
    ReviewTaskEntity claimTask(String targetType, String claimerId, int leaseMinutes);

    /** 租约超时回调回收（claimSeq 轮次守卫）：回 PENDING 清持单人 recycle_count+1；0 行＝幂等，返回 false */
    boolean recycleTaskByRound(String taskId, int claimSeq);

    /** 主动放回（持有人守卫）：CLAIMED→PENDING recycle_count+1；0 行＝非持有人/已离开 CLAIMED，返回 false */
    boolean releaseTask(String taskId, String claimerId);

    /** 各类型积压计数（review_task claim_state=PENDING 按类型分组；key=类型名） */
    Map<String, Long> countBacklogByType();

    /** 待审等待统计（票 11 可观测）：PENDING 行数 / 平均等待秒 / 最老等待秒（池空全 0） */
    ReviewPendingWaitStats pendingWaitStats();

    /** 举报单（uk(reporter,target) 幂等）：返回举报单业务 ID（UUID 字符串），重复举报返回 null */
    String insertIgnoreReport(ContentReportEntity reportEntity);

    /** 核实：PENDING → PROCESSED，返回影响行数（1=本次刚核实） */
    boolean processById(String reportId, String handlerId);

    /** 忽略：PENDING → IGNORED，返回影响行数 */
    boolean ignoreById(String reportId, String handlerId);

    ContentReportEntity findReportById(String reportId);

    /** 同 target 所有举报单是否已产生过 REPORT_CONFIRMED 流水（直查 user_credit_detail，一内容一罚判定） */
    boolean existsProcessedCreditLog(String authorId, String targetType, String targetId);

    List<ReviewQueueItemEntity> pageReviewQueue(String targetType, int offset, int limit);

    long countReviewQueue(String targetType);

    ReviewQueueItemEntity findReviewDetail(String targetType, String targetId);

    /** 工单按业务键定位（详情聚合入口；不存在返回 null） */
    ReviewTaskEntity findTaskById(String taskId);

    /** 反查审核员持有的在途工单（claim_by=我 且 claim_state='CLAIMED'；未持有返回 null） */
    ReviewTaskEntity findHeldTaskByTarget(String targetType, String targetId, String claimBy);

    /**
     * 持有人守卫关单（裁决收口）：本工单 CLAIMED→DONE + decided_at，双守卫 id + claim_by；
     * 命中 0 行（租约已回收/非持有人）返回 false
     */
    boolean closeTaskByHolder(String taskId, String claimBy);

    /** 工单内容素材按类型分支直查（通用列 + 类型专属上下文；业务行已删/不存在返回 null） */
    ReviewTargetDetailEntity findTargetDetail(String targetType, String targetId);

    /** 同一内容历轮裁决（content_review 按 target_type+target_id，review_round 升序） */
    List<ContentReviewEntity> listReviewsByTarget(String targetType, String targetId);

    /** 同一作者历史裁决（content_review 单表 author_id 直查免 join，id 降序取最新 limit 条） */
    List<ContentReviewEntity> listReviewsByAuthor(String authorId, int limit);

    /** 拒绝理由（content_review.action=REJECT 的 reason，无记录返回 null） */
    String findRejectReason(String targetType, String targetId);

    /** 批量拒绝理由：targetId → 最新一次 REJECT 的 reason（无记录的 target 不在 Map 中；列表页免 N+1） */
    Map<String, String> findRejectReasons(String targetType, List<String> targetIds);

    List<ContentReportEntity> pageReports(String status, int offset, int limit);

    long countReports(String status);

    /** admin 指派/回收审核员（条件 UPDATE bs_user.role，幂等） */
    boolean updateUserRole(String userId, String role);

    boolean existsUser(String userId);

    void saveWord(SensitiveWordEntity wordEntity);

    void deleteWord(Long wordId);

    List<String> listEnabledWords();

    List<SensitiveWordEntity> pageWords(int offset, int limit);

    long countWords();
}
