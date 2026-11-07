package com.betterschool.infrastructure.dao.moderation;

import com.betterschool.infrastructure.dao.moderation.po.ContentReportPO;
import com.betterschool.infrastructure.dao.moderation.po.ContentReviewPO;
import com.betterschool.infrastructure.dao.moderation.po.ReviewQueueItemPO;
import com.betterschool.infrastructure.dao.moderation.po.ReviewTaskPO;
import com.betterschool.infrastructure.dao.moderation.po.ReviewTaskTargetPO;
import com.betterschool.infrastructure.dao.moderation.po.SensitiveWordPO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * moderation Mapper：content_review/content_report/review_task/sensitive_word 读写 + 审核队列三业务表只读直查，
 * SQL 定义在 app 模块 resources/mybatis/mapper/ModerationMapper.xml
 */
@Mapper
public interface IModerationDao {

    /** INSERT IGNORE：uk_target_action 冲突 = 已记录过（幂等，审计只插一次） */
    int insertIgnoreReview(ContentReviewPO reviewPO);

    /** INSERT IGNORE：uk_target_round 冲突 = 同轮已开单（幂等入池，0 行不报错） */
    int insertIgnoreTask(ReviewTaskPO taskPO);

    /** 关单：该目标该轮之外的全部在途工单（PENDING/CLAIMED）停 DONE（入池前清旧轮遗留，幂等） */
    int closeStaleRoundTasks(@Param("targetType") String targetType, @Param("targetId") String targetId,
                             @Param("reviewRound") int reviewRound);

    /** 关单：该目标全部在途工单（PENDING/CLAIMED）停 DONE（裁决/退出待审出池，幂等） */
    int closeOpenTasks(@Param("targetType") String targetType, @Param("targetId") String targetId);

    /** 领单候选：池内最早待审工单业务键 task_id（FIFO；targetType 为 null = 跨类型；无候选返回 null） */
    String selectClaimCandidateId(@Param("targetType") String targetType);

    /**
     * 条件 UPDATE 抢占（spec §5.3 影响行数 1 者赢，并发下仅一人成功）：
     * claim_by/claim_seq+1/租约到期时刻一起落；WHERE claim_state='PENDING' 是唯一守卫
     */
    int claimById(@Param("taskId") String taskId, @Param("claimBy") String claimBy,
                  @Param("leaseMinutes") int leaseMinutes);

    /** 领单后同事务读回领单轮次（复合 bizId {taskId}:{claimSeq} 的另一半） */
    Integer selectClaimSeqById(@Param("taskId") String taskId);

    /**
     * 惰性兜底（spec §5.3 claim 前顺带一行）：租约已过期仍处 CLAIMED 的行放回池
     * （防超时中心回调延迟/丢失把工单卡死；recycle_count+1、达阈值置 escalated）
     */
    int recycleExpiredLeases();

    /** 回调回收（轮次守卫）：CLAIMED→PENDING、清持单人、recycle_count+1；命中 0 行＝陈旧轮次/已裁决，幂等 */
    int recycleByRound(@Param("taskId") String taskId, @Param("claimSeq") int claimSeq);

    /** 主动放回（持有人守卫）：CLAIMED→PENDING、recycle_count+1；命中 0 行＝非持有人/已离开 CLAIMED */
    int releaseByClaimer(@Param("taskId") String taskId, @Param("claimBy") String claimBy);

    /** 池内已置需人工干预标记的在池工单数（惰性兜底批量回收后告警用） */
    int countEscalatedPending();

    /** INSERT IGNORE：uk_reporter_target 冲突 = 重复举报（幂等），返回影响行数 */
    int insertIgnoreReport(ContentReportPO reportPO);

    /** 核实：PENDING → PROCESSED，返回影响行数（1=本次刚核实；按业务键 report_id 定位） */
    int processById(@Param("reportId") String reportId, @Param("handlerId") String handlerId);

    /** 忽略：PENDING → IGNORED，返回影响行数 */
    int ignoreById(@Param("reportId") String reportId, @Param("handlerId") String handlerId);

    ContentReportPO selectReportById(@Param("reportId") String reportId);

    /** 同 target 举报单是否已有 REPORT_CONFIRMED 流水（直查 user_credit_detail 子查询，一内容一罚判定） */
    int existsProcessedCreditLog(@Param("authorId") String authorId, @Param("targetType") String targetType,
                                 @Param("targetId") String targetId);

    /**
     * 审核队列（review_task 单表）：工单定位列（targetType/targetId/reviewRound/authorId/enteredAt），
     * 素材与作者昵称由仓储按 target_id 集回查业务表与 bs_user 拼装（SQL 层零 JOIN）
     */
    List<ReviewQueueItemPO> selectReviewQueue(@Param("targetType") String targetType,
                                              @Param("offset") int offset, @Param("limit") int limit);

    /** 审核队列计数（targetType 为 null = 跨类型全量） */
    long countReviewQueue(@Param("targetType") String targetType);

    /** 各类型积压计数（claim_state=PENDING 分组；resultType=map：targetType/cnt） */
    List<Map<String, Object>> countBacklogByType();

    /**
     * 待审等待统计（票 11 可观测，一条 SQL）：PENDING 行数 / 平均等待秒 / 最老等待秒
     * （entered_at 起算，回收不改 entered_at、等待持续累加；池空 COUNT=0、AVG/MAX 落 0）
     */
    Map<String, Object> selectPendingWaitStats();

    /** 审核详情素材（业务表单表直查）：authorNickname 仓储回查 bs_user 拼装 */
    ReviewQueueItemPO selectReviewDetail(@Param("targetType") String targetType, @Param("targetId") String targetId);

    /** 工单按业务键定位（详情聚合入口） */
    ReviewTaskPO selectTaskById(@Param("taskId") String taskId);

    /** 反查审核员持有的在途工单（遗留审核入口收口用：claim_by=我 且 claim_state='CLAIMED'；未持有返回 null） */
    ReviewTaskPO selectHeldTaskByTarget(@Param("targetType") String targetType, @Param("targetId") String targetId,
                                        @Param("claimBy") String claimBy);

    /**
     * 持有人守卫关单（spec §5.2 裁决收口 ③）：本工单 CLAIMED→DONE + decided_at，
     * 双守卫 id + claim_by（重放已 DONE / 租约已回收 / 非持有人均命中 0 行＝幂等）
     */
    int closeByHolder(@Param("taskId") String taskId, @Param("claimBy") String claimBy);

    /** 工单内容素材按类型分支单表直查（通用列 + 类型专属上下文 id；不筛业务行状态，DONE 工单也要能看） */
    ReviewTaskTargetPO selectTaskTargetDetail(@Param("targetType") String targetType, @Param("targetId") String targetId);

    /** 同一内容历轮裁决（review_round 升序） */
    List<ContentReviewPO> selectReviewsByTarget(@Param("targetType") String targetType, @Param("targetId") String targetId);

    /** 同一作者历史裁决（单表 author_id 直查免 join，id 降序取最新 limit 条） */
    List<ContentReviewPO> selectReviewsByAuthor(@Param("authorId") String authorId, @Param("limit") int limit);

    /** 拒绝理由：uk_target_action 下 REJECT 至多一行，取最新一条兜底 */
    String selectRejectReason(@Param("targetType") String targetType, @Param("targetId") String targetId);

    /** 批量拒绝理由取数：该批 target 的全部 REJECT 行（含物理 id），「每 target 取最新」裁决在仓储 Java 侧做 */
    List<ContentReviewPO> selectRejectRowsByTargetIds(@Param("targetType") String targetType,
                                                      @Param("targetIds") List<String> targetIds);

    /** 某类型全部 APPROVE 流水的最新时刻（content_review 单表 GROUP BY，feed 索引重建拼 feed_time 用） */
    List<ContentReviewPO> selectApproveRowsByTargetType(@Param("targetType") String targetType);

    /** 举报列表（content_report 单表）：举报人/被举报人昵称由仓储回查 bs_user 拼装 */
    List<ContentReportPO> selectReports(@Param("status") String status, @Param("offset") int offset,
                                        @Param("limit") int limit);

    long countReports(@Param("status") String status);

    /** 条件 UPDATE bs_user.role（已是目标角色不生效），返回影响行数 */
    int updateUserRole(@Param("userId") String userId, @Param("role") String role);

    int existsUser(@Param("userId") String userId);

    int insertIgnoreWord(SensitiveWordPO wordPO);

    int deleteWord(@Param("wordId") Long wordId);

    List<String> selectEnabledWords();

    List<SensitiveWordPO> selectWords(@Param("offset") int offset, @Param("limit") int limit);

    long countWords();
}
