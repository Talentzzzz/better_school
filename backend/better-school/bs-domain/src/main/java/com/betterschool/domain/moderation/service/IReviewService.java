package com.betterschool.domain.moderation.service;

import com.betterschool.domain.moderation.model.entity.ContentReviewEntity;
import com.betterschool.domain.moderation.model.entity.ReviewQueueItemEntity;
import com.betterschool.domain.moderation.model.entity.ReviewTaskDetailEntity;
import com.betterschool.domain.moderation.model.entity.ReviewTaskEntity;
import com.betterschool.domain.moderation.model.valobj.ReviewActionVO;
import com.betterschool.domain.moderation.model.valobj.ReviewPendingWaitStats;
import com.betterschool.domain.moderation.model.valobj.ReviewSourceVO;
import com.betterschool.domain.moderation.model.valobj.ReviewTargetTypeVO;

import java.util.List;
import java.util.Map;

/**
 * moderation 域审核服务（命名避免与 api.IModerationService 契约重名）。
 * 域内只做：审核动作记录/审核队列只读查询/审核员角色指派；业务状态写与通知由 case 层编排（不依赖其他域）
 */
public interface IReviewService {

    /**
     * 记录人工审核动作（INSERT IGNORE uk(target_type,target_id,action,review_round) 幂等，重试只插一行；
     * reviewRound 由业务表行锚定，同一目标可被多次审核而不冲突；authorId 冗余落列（spec §5.1，按作者聚合历史用）；
     * reasonCode 落枚举列供理由分布统计，通过时为 null）
     */
    void recordReview(ReviewTargetTypeVO targetType, String targetId, String authorId, int reviewRound,
                      ReviewActionVO action, ReviewSourceVO source, String operatorId, String reason, String reasonCode);

    /**
     * 工单出池关单：该目标全部在途工单（PENDING/CLAIMED）停 DONE（裁决完成即出池；幂等，0 行无害）。
     * 入池由各域进入待审的同事务经 ReviewTaskPoolStore 开单（不经本服务）
     */
    void closeTask(ReviewTargetTypeVO targetType, String targetId);

    /** 各类型积压计数（工单池 PENDING 行数；驱动审核端计数条与派单信号的积压数，四类型缺省补 0） */
    Map<String, Long> backlogCounts();

    /** 当前积压总数（各类型计数之和；派单信号载荷与观测 job 共用同一求和口径，勿各自手写） */
    long backlogTotal();

    /** 待审等待统计（票 11 可观测：池内条数 / 平均等待秒 / 最老等待秒；积压告警的等待时长阈值以它为据） */
    ReviewPendingWaitStats pendingWaitStats();

    /**
     * 领单（spec §5.3 原子取一件）：一次只取一条（不预取），FIFO 先到先审（targetType 只作过滤器不做
     * 优先级插队），条件 UPDATE 抢占并发下仅一人成功；领单事务内起 5 分钟租约并注册
     * {@code REVIEW_LEASE_TIMEOUT}（复合 bizId {taskId}:{claimSeq}）。池空/抢占落空返回 null
     */
    ReviewTaskEntity claimTask(ReviewTargetTypeVO targetType, String auditorId);

    /**
     * 租约超时回调回收（spec §5.3）：按 claimSeq 轮次守卫条件 UPDATE 回 PENDING、清持单人、recycle_count+1；
     * 命中 0 行（已裁决/已放回/已按新轮次重领）＝幂等成功零副作用（不报错）；达阈值置 escalated 并告警
     */
    void handleLeaseTimeout(long taskId, int claimSeq);

    /**
     * 主动放回（spec §5.2/§5.3）：审不了退池 CLAIMED→PENDING、recycle_count+1（与租约回收同口径计数）；
     * 非持有人/已裁决/已回收抛 BUSINESS_ERROR。reason 为审核员填的放回原因（可空，仅记录日志不落库）
     */
    void releaseTask(long taskId, String auditorId, String reason);

    /**
     * 工单按 id 载入（裁决用例断言入口；不存在返回 null）
     */
    ReviewTaskEntity findTask(long taskId);

    /**
     * 反查当前审核员持有的在途工单（spec §5.2 遗留入口收口）：按「类型 + 内容 id + 持有人」取
     * claim_state='CLAIMED' 的工单；未持有（未领单/已被回收/已被裁决）返回 null（→「请先领单」）
     */
    ReviewTaskEntity findHeldTask(ReviewTargetTypeVO targetType, String targetId, String auditorId);

    /**
     * 持有人守卫关单（spec §5.2 裁决收口 ③）：本工单 CLAIMED→DONE + 写裁决时刻，双守卫 id + claim_by；
     * 命中 0 行（租约已回收/已按他人手裁决/非持有人）返回 false，由调用方分流幂等与冲突
     */
    boolean closeHeldTask(long taskId, String auditorId);

    /** 审核队列：读 review_task 工单池（claim_state=PENDING + 类型过滤 + entered_at FIFO；targetType null = 跨类型） */
    List<ReviewQueueItemEntity> listReviewQueue(ReviewTargetTypeVO targetType, int page, int pageSize);

    long countReviewQueue(ReviewTargetTypeVO targetType);

    ReviewQueueItemEntity reviewDetail(ReviewTargetTypeVO targetType, String targetId);

    /**
     * 工单详情聚合（spec §5.1：一屏拿到裁决所需全部上下文）：工单定位 + 按类型分支直查内容素材
     * （通用列：内容/配图/作者昵称头像/进入待审时刻/审核轮次；类型专属：跑腿悬赏、评论的所属帖子/父评论）
     * + 同一内容历轮裁决 + 同一作者历史裁决（content_review 单表 author_id 直查，免 join 工单表）。
     * 纯只读聚合，不改变任何业务行状态与对外可见性（工单不承载可见性）；
     * 工单不存在或内容已不存在抛 NOT_FOUND
     */
    ReviewTaskDetailEntity reviewTaskDetail(long taskId);

    /** 拒绝理由（content_review.action=REJECT 的 reason，无记录返回 null） */
    String rejectReason(ReviewTargetTypeVO targetType, String targetId);

    /** 批量拒绝理由：targetId → 最新一次 REJECT 的 reason（无记录的 target 不在 Map 中；列表页免 N+1） */
    Map<String, String> rejectReasons(ReviewTargetTypeVO targetType, List<String> targetIds);

    /** 用户是否存在（举报 USER 对象前校验） */
    boolean existsUser(String userId);

    /** admin 指派审核员（条件 UPDATE bs_user.role，已是 AUDITOR 幂等成功；用户不存在抛 NOT_FOUND） */
    void assignAuditor(String userId);

    /** admin 回收审核员（回 USER 角色） */
    void removeAuditor(String userId);
}
