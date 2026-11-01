package com.betterschool.infrastructure.adapter.repository.moderation;

import com.betterschool.domain.moderation.adapter.repository.IModerationRepository;
import com.betterschool.domain.moderation.model.entity.ContentReportEntity;
import com.betterschool.domain.moderation.model.entity.ContentReviewEntity;
import com.betterschool.domain.moderation.model.entity.ReviewQueueItemEntity;
import com.betterschool.domain.moderation.model.entity.ReviewTargetDetailEntity;
import com.betterschool.domain.moderation.model.entity.ReviewTaskEntity;
import com.betterschool.domain.moderation.model.entity.SensitiveWordEntity;
import com.betterschool.domain.moderation.model.valobj.ReportStatusVO;
import com.betterschool.domain.moderation.model.valobj.ReviewActionVO;
import com.betterschool.domain.moderation.model.valobj.ReviewPendingWaitStats;
import com.betterschool.domain.moderation.model.valobj.ReviewSourceVO;
import com.betterschool.domain.moderation.model.valobj.ReviewTargetTypeVO;
import com.betterschool.infrastructure.dao.moderation.IModerationDao;
import com.betterschool.infrastructure.dao.moderation.po.ContentReportPO;
import com.betterschool.infrastructure.dao.moderation.po.ContentReviewPO;
import com.betterschool.infrastructure.dao.moderation.po.ReviewQueueItemPO;
import com.betterschool.infrastructure.dao.moderation.po.ReviewTaskPO;
import com.betterschool.infrastructure.dao.moderation.po.ReviewTaskTargetPO;
import com.betterschool.infrastructure.dao.moderation.po.SensitiveWordPO;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Repository
@RequiredArgsConstructor
public class ModerationRepository implements IModerationRepository {

    private final IModerationDao moderationDao;
    private final ReviewTaskPoolStore reviewTaskPoolStore;
    private final ObjectMapper objectMapper;

    @Override
    public void saveReview(ContentReviewEntity reviewEntity) {
        moderationDao.insertIgnoreReview(toReviewPO(reviewEntity));
    }

    @Override
    public void closeOpenTasks(String targetType, String targetId) {
        moderationDao.closeOpenTasks(targetType, targetId);
    }

    @Override
    public ReviewTaskEntity claimTask(String targetType, String claimerId, int leaseMinutes) {
        return reviewTaskPoolStore.claim(targetType, claimerId, leaseMinutes)
                .map(this::toTaskEntity).orElse(null);
    }

    @Override
    public boolean recycleTaskByRound(long taskId, int claimSeq) {
        return reviewTaskPoolStore.recycleByRound(taskId, claimSeq);
    }

    @Override
    public boolean releaseTask(long taskId, String claimerId) {
        return reviewTaskPoolStore.releaseByClaimer(taskId, claimerId);
    }

    @Override
    public Map<String, Long> countBacklogByType() {
        Map<String, Long> counts = new HashMap<>();
        for (Map<String, Object> row : moderationDao.countBacklogByType()) {
            counts.put((String) row.get("targetType"), ((Number) row.get("cnt")).longValue());
        }
        return counts;
    }

    @Override
    public ReviewPendingWaitStats pendingWaitStats() {
        Map<String, Object> row = moderationDao.selectPendingWaitStats();
        if (row == null) {
            return new ReviewPendingWaitStats(0, 0, 0);
        }
        return new ReviewPendingWaitStats(
                ((Number) row.getOrDefault("pendingCnt", 0)).longValue(),
                ((Number) row.getOrDefault("avgWaitSeconds", 0)).longValue(),
                ((Number) row.getOrDefault("oldestWaitSeconds", 0)).longValue());
    }

    @Override
    public Long insertIgnoreReport(ContentReportEntity reportEntity) {
        ContentReportPO reportPO = toReportPO(reportEntity);
        return moderationDao.insertIgnoreReport(reportPO) == 1 ? reportPO.getId() : null;
    }

    @Override
    public boolean processById(Long reportId, String handlerId) {
        return moderationDao.processById(reportId, handlerId) == 1;
    }

    @Override
    public boolean ignoreById(Long reportId, String handlerId) {
        return moderationDao.ignoreById(reportId, handlerId) == 1;
    }

    @Override
    public ContentReportEntity findReportById(Long reportId) {
        return toReportEntity(moderationDao.selectReportById(reportId));
    }

    @Override
    public boolean existsProcessedCreditLog(String authorId, String targetType, String targetId) {
        return moderationDao.existsProcessedCreditLog(authorId, targetType, targetId) > 0;
    }

    @Override
    public List<ReviewQueueItemEntity> pageReviewQueue(String targetType, int offset, int limit) {
        return moderationDao.selectReviewQueue(targetType, offset, limit).stream()
                .map(this::toQueueItemEntity).toList();
    }

    @Override
    public long countReviewQueue(String targetType) {
        return moderationDao.countReviewQueue(targetType);
    }

    @Override
    public ReviewQueueItemEntity findReviewDetail(String targetType, String targetId) {
        return toQueueItemEntity(moderationDao.selectReviewDetail(targetType, targetId));
    }

    @Override
    public ReviewTaskEntity findTaskById(long taskId) {
        return toTaskEntity(moderationDao.selectTaskById(taskId));
    }

    @Override
    public ReviewTaskEntity findHeldTaskByTarget(String targetType, String targetId, String claimBy) {
        return toTaskEntity(moderationDao.selectHeldTaskByTarget(targetType, targetId, claimBy));
    }

    @Override
    public boolean closeTaskByHolder(long taskId, String claimBy) {
        return moderationDao.closeByHolder(taskId, claimBy) == 1;
    }

    @Override
    public ReviewTargetDetailEntity findTargetDetail(String targetType, String targetId) {
        return toTargetDetailEntity(moderationDao.selectTaskTargetDetail(targetType, targetId));
    }

    @Override
    public List<ContentReviewEntity> listReviewsByTarget(String targetType, String targetId) {
        return moderationDao.selectReviewsByTarget(targetType, targetId).stream()
                .map(this::toReviewEntity).toList();
    }

    @Override
    public List<ContentReviewEntity> listReviewsByAuthor(String authorId, int limit) {
        return moderationDao.selectReviewsByAuthor(authorId, limit).stream()
                .map(this::toReviewEntity).toList();
    }

    @Override
    public String findRejectReason(String targetType, String targetId) {
        return moderationDao.selectRejectReason(targetType, targetId);
    }

    @Override
    public Map<String, String> findRejectReasons(String targetType, List<String> targetIds) {
        if (targetIds.isEmpty()) {
            return Map.of();
        }
        Map<String, String> reasons = new HashMap<>();
        for (Map<String, Object> row : moderationDao.selectRejectReasons(targetType, targetIds)) {
            // target_id 是 VARCHAR（POST/COMMENT=UUID，商品/跑腿过渡期为数值串）：按字符串原样收敛
            reasons.put(String.valueOf(row.get("targetId")), (String) row.get("reason"));
        }
        return reasons;
    }

    @Override
    public List<ContentReportEntity> pageReports(String status, int offset, int limit) {
        return moderationDao.selectReports(status, offset, limit).stream()
                .map(this::toReportEntity).toList();
    }

    @Override
    public long countReports(String status) {
        return moderationDao.countReports(status);
    }

    @Override
    public boolean updateUserRole(String userId, String role) {
        return moderationDao.updateUserRole(userId, role) == 1;
    }

    @Override
    public boolean existsUser(String userId) {
        return moderationDao.existsUser(userId) > 0;
    }

    @Override
    public void saveWord(SensitiveWordEntity wordEntity) {
        moderationDao.insertIgnoreWord(toWordPO(wordEntity));
    }

    @Override
    public void deleteWord(Long wordId) {
        moderationDao.deleteWord(wordId);
    }

    @Override
    public List<String> listEnabledWords() {
        return moderationDao.selectEnabledWords();
    }

    @Override
    public List<SensitiveWordEntity> pageWords(int offset, int limit) {
        return moderationDao.selectWords(offset, limit).stream()
                .map(this::toWordEntity).toList();
    }

    @Override
    public long countWords() {
        return moderationDao.countWords();
    }

    private ContentReviewPO toReviewPO(ContentReviewEntity reviewEntity) {
        ContentReviewPO reviewPO = new ContentReviewPO();
        reviewPO.setId(reviewEntity.getId());
        reviewPO.setTargetType(reviewEntity.getTargetType().name());
        reviewPO.setTargetId(reviewEntity.getTargetId());
        reviewPO.setAuthorId(reviewEntity.getAuthorId());
        reviewPO.setReviewRound(reviewEntity.getReviewRound());
        reviewPO.setAction(reviewEntity.getAction().name());
        reviewPO.setSource(reviewEntity.getSource().name());
        reviewPO.setOperatorId(reviewEntity.getOperatorId());
        reviewPO.setReason(reviewEntity.getReason());
        reviewPO.setReasonCode(reviewEntity.getReasonCode());
        reviewPO.setCreateTime(reviewEntity.getCreatedAt());
        reviewPO.setUpdateTime(reviewEntity.getCreatedAt());
        return reviewPO;
    }

    private ContentReportPO toReportPO(ContentReportEntity reportEntity) {
        ContentReportPO reportPO = new ContentReportPO();
        reportPO.setId(reportEntity.getId());
        reportPO.setTargetType(reportEntity.getTargetType().name());
        reportPO.setTargetId(reportEntity.getTargetId());
        reportPO.setAuthorId(reportEntity.getAuthorId());
        reportPO.setReporterId(reportEntity.getReporterId());
        reportPO.setReason(reportEntity.getReason());
        reportPO.setContentSnapshot(reportEntity.getContentSnapshot());
        reportPO.setStatus(reportEntity.getStatus().name());
        reportPO.setCreateTime(reportEntity.getCreatedAt());
        reportPO.setUpdateTime(reportEntity.getCreatedAt());
        return reportPO;
    }

    private ContentReportEntity toReportEntity(ContentReportPO reportPO) {
        if (reportPO == null) {
            return null;
        }
        ContentReportEntity reportEntity = new ContentReportEntity();
        reportEntity.setId(reportPO.getId());
        reportEntity.setTargetType(ReviewTargetTypeVO.from(reportPO.getTargetType()));
        reportEntity.setTargetId(reportPO.getTargetId());
        reportEntity.setAuthorId(reportPO.getAuthorId());
        reportEntity.setReporterId(reportPO.getReporterId());
        reportEntity.setReason(reportPO.getReason());
        reportEntity.setContentSnapshot(reportPO.getContentSnapshot());
        reportEntity.setStatus(ReportStatusVO.from(reportPO.getStatus()));
        reportEntity.setHandlerId(reportPO.getHandlerId());
        reportEntity.setHandledAt(reportPO.getHandledAt());
        reportEntity.setCreatedAt(reportPO.getCreateTime());
        reportEntity.setReporterNickname(reportPO.getReporterNickname());
        reportEntity.setAuthorNickname(reportPO.getAuthorNickname());
        return reportEntity;
    }

    private ReviewQueueItemEntity toQueueItemEntity(ReviewQueueItemPO queueItemPO) {
        if (queueItemPO == null) {
            return null;
        }
        ReviewQueueItemEntity queueItemEntity = new ReviewQueueItemEntity();
        queueItemEntity.setTargetType(ReviewTargetTypeVO.from(queueItemPO.getTargetType()));
        queueItemEntity.setTargetId(queueItemPO.getTargetId());
        queueItemEntity.setReviewRound(queueItemPO.getReviewRound() == null ? 1 : queueItemPO.getReviewRound());
        queueItemEntity.setAuthorId(queueItemPO.getAuthorId());
        queueItemEntity.setAuthorNickname(queueItemPO.getAuthorNickname());
        queueItemEntity.setContent(queueItemPO.getContent());
        queueItemEntity.setReward(queueItemPO.getReward());
        queueItemEntity.setImages(toImagesList(queueItemPO.getImages()));
        queueItemEntity.setStatus(queueItemPO.getStatus());
        queueItemEntity.setCreatedAt(queueItemPO.getCreateTime());
        queueItemEntity.setEnteredAt(queueItemPO.getEnteredAt());
        return queueItemEntity;
    }

    /** 配图与 post/product/errand 同一存储约定（JSON 数组字符串），反序列化失败按空处理 */
    private List<String> toImagesList(String imagesJson) {
        if (imagesJson == null || imagesJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(imagesJson,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (Exception e) {
            log.warn("审核队列配图反序列化失败，按空处理：{}", imagesJson, e);
            return List.of();
        }
    }

    private ReviewTaskEntity toTaskEntity(ReviewTaskPO taskPO) {
        if (taskPO == null) {
            return null;
        }
        ReviewTaskEntity taskEntity = new ReviewTaskEntity();
        taskEntity.setId(taskPO.getId());
        taskEntity.setTargetType(ReviewTargetTypeVO.from(taskPO.getTargetType()));
        taskEntity.setTargetId(taskPO.getTargetId());
        taskEntity.setReviewRound(taskPO.getReviewRound() == null ? 1 : taskPO.getReviewRound());
        taskEntity.setAuthorId(taskPO.getAuthorId());
        taskEntity.setSource(taskPO.getSource());
        taskEntity.setClaimState(taskPO.getClaimState());
        taskEntity.setClaimBy(taskPO.getClaimBy());
        taskEntity.setClaimSeq(taskPO.getClaimSeq());
        taskEntity.setLeaseExpireAt(taskPO.getLeaseExpireAt());
        taskEntity.setRecycleCount(taskPO.getRecycleCount());
        taskEntity.setEscalated(taskPO.getEscalated() != null && taskPO.getEscalated());
        taskEntity.setEnteredAt(taskPO.getEnteredAt());
        taskEntity.setDecidedAt(taskPO.getDecidedAt());
        taskEntity.setCreatedAt(taskPO.getCreateTime());
        taskEntity.setUpdateTime(taskPO.getUpdateTime());
        return taskEntity;
    }

    private ReviewTargetDetailEntity toTargetDetailEntity(ReviewTaskTargetPO targetPO) {
        if (targetPO == null) {
            return null;
        }
        ReviewTargetDetailEntity detailEntity = new ReviewTargetDetailEntity();
        detailEntity.setTargetType(ReviewTargetTypeVO.from(targetPO.getTargetType()));
        detailEntity.setTargetId(targetPO.getTargetId());
        detailEntity.setAuthorId(targetPO.getAuthorId());
        detailEntity.setAuthorNickname(targetPO.getAuthorNickname());
        detailEntity.setAuthorAvatar(targetPO.getAuthorAvatar());
        detailEntity.setContent(targetPO.getContent());
        detailEntity.setImages(toImagesList(targetPO.getImages()));
        detailEntity.setReward(targetPO.getReward());
        detailEntity.setStatus(targetPO.getStatus());
        detailEntity.setContextPostId(targetPO.getContextPostId());
        detailEntity.setContextPostTitle(targetPO.getContextPostTitle());
        detailEntity.setContextParentId(targetPO.getContextParentId());
        detailEntity.setContextParentContent(targetPO.getContextParentContent());
        return detailEntity;
    }

    private ContentReviewEntity toReviewEntity(ContentReviewPO reviewPO) {
        if (reviewPO == null) {
            return null;
        }
        ContentReviewEntity reviewEntity = new ContentReviewEntity();
        reviewEntity.setId(reviewPO.getId());
        reviewEntity.setTargetType(ReviewTargetTypeVO.from(reviewPO.getTargetType()));
        reviewEntity.setTargetId(reviewPO.getTargetId());
        reviewEntity.setAuthorId(reviewPO.getAuthorId());
        reviewEntity.setReviewRound(reviewPO.getReviewRound() == null ? 1 : reviewPO.getReviewRound());
        reviewEntity.setAction(ReviewActionVO.from(reviewPO.getAction()));
        reviewEntity.setSource(ReviewSourceVO.from(reviewPO.getSource()));
        reviewEntity.setOperatorId(reviewPO.getOperatorId());
        reviewEntity.setReason(reviewPO.getReason());
        reviewEntity.setReasonCode(reviewPO.getReasonCode());
        reviewEntity.setCreatedAt(reviewPO.getCreateTime());
        return reviewEntity;
    }

    private SensitiveWordPO toWordPO(SensitiveWordEntity wordEntity) {
        SensitiveWordPO wordPO = new SensitiveWordPO();
        wordPO.setId(wordEntity.getId());
        wordPO.setWord(wordEntity.getWord());
        wordPO.setEnabled(wordEntity.isEnabled() ? 1 : 0);
        wordPO.setCreateTime(wordEntity.getCreatedAt());
        wordPO.setUpdateTime(wordEntity.getCreatedAt());
        return wordPO;
    }

    private SensitiveWordEntity toWordEntity(SensitiveWordPO wordPO) {
        SensitiveWordEntity wordEntity = new SensitiveWordEntity();
        wordEntity.setId(wordPO.getId());
        wordEntity.setWord(wordPO.getWord());
        wordEntity.setEnabled(wordPO.getEnabled() != null && wordPO.getEnabled() == 1);
        wordEntity.setCreatedAt(wordPO.getCreateTime());
        return wordEntity;
    }
}
