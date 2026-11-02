package com.betterschool.domain.moderation.service;

import com.betterschool.domain.moderation.adapter.repository.IModerationRepository;
import com.betterschool.domain.moderation.model.entity.ContentReviewEntity;
import com.betterschool.domain.moderation.model.entity.ReviewQueueItemEntity;
import com.betterschool.domain.moderation.model.entity.ReviewTargetDetailEntity;
import com.betterschool.domain.moderation.model.entity.ReviewTaskDetailEntity;
import com.betterschool.domain.moderation.model.entity.ReviewTaskEntity;
import com.betterschool.domain.moderation.model.valobj.ReviewActionVO;
import com.betterschool.domain.moderation.model.valobj.ReviewPendingWaitStats;
import com.betterschool.domain.moderation.model.valobj.ReviewSourceVO;
import com.betterschool.domain.moderation.model.valobj.ReviewTargetTypeVO;
import com.betterschool.types.enums.ResponseCode;
import com.betterschool.types.exception.BusinessException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewServiceImpl implements IReviewService {

    /** 作者历史裁决条数上限（一屏裁决上下文，防大列表） */
    private static final int AUTHOR_HISTORY_LIMIT = 20;

    /** 审核租约时长（spec §5.3 固定 5min：审核员的裁决窗口，从领单时刻起算） */
    private static final int LEASE_MINUTES = 5;

    private final IModerationRepository moderationRepository;

    @Override
    public void recordReview(ReviewTargetTypeVO targetType, String targetId, String authorId, int reviewRound,
                             ReviewActionVO action, ReviewSourceVO source, String operatorId, String reason,
                             String reasonCode) {
        moderationRepository.saveReview(ContentReviewEntity.create(targetType, targetId, authorId, reviewRound,
                action, source, operatorId, reason, reasonCode));
    }

    @Override
    public void closeTask(ReviewTargetTypeVO targetType, String targetId) {
        moderationRepository.closeOpenTasks(targetType.name(), targetId);
    }

    @Override
    public Map<String, Long> backlogCounts() {
        Map<String, Long> counts = new HashMap<>();
        for (ReviewTargetTypeVO type : List.of(ReviewTargetTypeVO.POST, ReviewTargetTypeVO.COMMENT,
                ReviewTargetTypeVO.PRODUCT, ReviewTargetTypeVO.ERRAND)) {
            counts.put(type.name(), 0L);
        }
        counts.putAll(moderationRepository.countBacklogByType());
        return counts;
    }

    @Override
    public long backlogTotal() {
        return backlogCounts().values().stream().mapToLong(Long::longValue).sum();
    }

    @Override
    public ReviewPendingWaitStats pendingWaitStats() {
        return moderationRepository.pendingWaitStats();
    }

    @Override
    public ReviewTaskEntity claimTask(ReviewTargetTypeVO targetType, String auditorId) {
        // targetType null = 跨类型取候选（全局 FIFO）；类型只作过滤器，不做优先级插队
        return moderationRepository.claimTask(targetType == null ? null : targetType.name(), auditorId, LEASE_MINUTES);
    }

    @Override
    public void handleLeaseTimeout(String taskId, int claimSeq) {
        // 轮次守卫未命中（已裁决/已放回/已按新轮次重领）＝幂等成功零副作用，不报错（同 handlePayTimeout 口径）
        moderationRepository.recycleTaskByRound(taskId, claimSeq);
    }

    @Override
    public void releaseTask(String taskId, String auditorId, String reason) {
        if (!moderationRepository.releaseTask(taskId, auditorId)) {
            throw new BusinessException(ResponseCode.BUSINESS_ERROR, "工单已被回收或已裁决，请刷新");
        }
        if (reason != null && !reason.isBlank()) {
            log.info("[审核工单] 审核员 {} 主动放回工单 {}，原因：{}", auditorId, taskId, reason);
        }
    }

    @Override
    public ReviewTaskEntity findTask(String taskId) {
        return moderationRepository.findTaskById(taskId);
    }

    @Override
    public ReviewTaskEntity findHeldTask(ReviewTargetTypeVO targetType, String targetId, String auditorId) {
        return moderationRepository.findHeldTaskByTarget(targetType.name(), targetId, auditorId);
    }

    @Override
    public boolean closeHeldTask(String taskId, String auditorId) {
        return moderationRepository.closeTaskByHolder(taskId, auditorId);
    }

    @Override
    public List<ReviewQueueItemEntity> listReviewQueue(ReviewTargetTypeVO targetType, int page, int pageSize) {
        if (targetType == ReviewTargetTypeVO.USER) {
            throw new BusinessException(ResponseCode.PARAM_ERROR, "用户不进入审核队列");
        }
        return moderationRepository.pageReviewQueue(targetType == null ? null : targetType.name(),
                (page - 1) * pageSize, pageSize);
    }

    @Override
    public long countReviewQueue(ReviewTargetTypeVO targetType) {
        return moderationRepository.countReviewQueue(targetType == null ? null : targetType.name());
    }

    @Override
    public ReviewQueueItemEntity reviewDetail(ReviewTargetTypeVO targetType, String targetId) {
        ReviewQueueItemEntity detail = moderationRepository.findReviewDetail(targetType.name(), targetId);
        if (detail == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "审核对象不存在");
        }
        return detail;
    }

    @Override
    public ReviewTaskDetailEntity reviewTaskDetail(String taskId) {
        ReviewTaskEntity task = moderationRepository.findTaskById(taskId);
        if (task == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "审核工单不存在");
        }
        ReviewTargetDetailEntity target = moderationRepository.findTargetDetail(task.getTargetType().name(), task.getTargetId());
        if (target == null) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "审核对象不存在");
        }
        ReviewTaskDetailEntity detailEntity = new ReviewTaskDetailEntity();
        detailEntity.setTask(task);
        detailEntity.setTarget(target);
        // 历史裁决两路都只读 content_review（author_id 冗余列使按作者聚合单表可查，spec §5.1）
        detailEntity.setTargetReviews(moderationRepository.listReviewsByTarget(task.getTargetType().name(), task.getTargetId()));
        detailEntity.setAuthorReviews(moderationRepository.listReviewsByAuthor(task.getAuthorId(), AUTHOR_HISTORY_LIMIT));
        return detailEntity;
    }

    @Override
    public boolean existsUser(String userId) {
        return moderationRepository.existsUser(userId);
    }

    @Override
    public String rejectReason(ReviewTargetTypeVO targetType, String targetId) {
        return moderationRepository.findRejectReason(targetType.name(), targetId);
    }

    @Override
    public Map<String, String> rejectReasons(ReviewTargetTypeVO targetType, List<String> targetIds) {
        return moderationRepository.findRejectReasons(targetType.name(), targetIds);
    }

    @Override
    public void assignAuditor(String userId) {
        changeRole(userId, "AUDITOR");
    }

    @Override
    public void removeAuditor(String userId) {
        changeRole(userId, "USER");
    }

    private void changeRole(String userId, String role) {
        if (!moderationRepository.existsUser(userId)) {
            throw new BusinessException(ResponseCode.NOT_FOUND, "用户不存在");
        }
        moderationRepository.updateUserRole(userId, role);
    }
}
