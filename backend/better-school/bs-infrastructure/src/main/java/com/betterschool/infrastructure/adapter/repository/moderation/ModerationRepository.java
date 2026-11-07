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
import com.betterschool.infrastructure.dao.comment.IPostCommentDao;
import com.betterschool.infrastructure.dao.comment.po.PostCommentPO;
import com.betterschool.infrastructure.dao.errand.IErrandDao;
import com.betterschool.infrastructure.dao.errand.po.ErrandOrderPO;
import com.betterschool.infrastructure.dao.moderation.IModerationDao;
import com.betterschool.infrastructure.dao.moderation.po.ContentReportPO;
import com.betterschool.infrastructure.dao.moderation.po.ContentReviewPO;
import com.betterschool.infrastructure.dao.moderation.po.ReviewQueueItemPO;
import com.betterschool.infrastructure.dao.moderation.po.ReviewTaskPO;
import com.betterschool.infrastructure.dao.moderation.po.ReviewTaskTargetPO;
import com.betterschool.infrastructure.dao.moderation.po.SensitiveWordPO;
import com.betterschool.infrastructure.dao.post.IPostDao;
import com.betterschool.infrastructure.dao.post.po.PostPO;
import com.betterschool.infrastructure.dao.product.IProductDao;
import com.betterschool.infrastructure.dao.product.po.ProductPO;
import com.betterschool.infrastructure.dao.user.IUserDao;
import com.betterschool.infrastructure.dao.user.po.UserPO;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.betterschool.types.id.IIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Slf4j
@Repository
@RequiredArgsConstructor
public class ModerationRepository implements IModerationRepository {

    private final IModerationDao moderationDao;
    private final ReviewTaskPoolStore reviewTaskPoolStore;
    private final ObjectMapper objectMapper;
    /** 业务 ID 发号器（review_id UUID，uuid-biz-id 票 06） */
    private final IIdGenerator idGenerator;
    // 素材回查 DAO：队列/详情的素材列由 review_task 单表定位后按 target_id 集回查各业务表（SQL 层零 JOIN）
    private final IPostDao postDao;
    private final IPostCommentDao commentDao;
    private final IProductDao productDao;
    private final IErrandDao errandDao;
    /** 作者/举报人昵称头像批量回查 */
    private final IUserDao userDao;

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
    public boolean recycleTaskByRound(String taskId, int claimSeq) {
        return reviewTaskPoolStore.recycleByRound(taskId, claimSeq);
    }

    @Override
    public boolean releaseTask(String taskId, String claimerId) {
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
    public String insertIgnoreReport(ContentReportEntity reportEntity) {
        ContentReportPO reportPO = toReportPO(reportEntity);
        // 业务 ID 随 insert 一次写入（uuid-biz-id 票 06）；撞 uk_reporter_target 被 IGNORE 时本行未落库，发号作废无害
        reportPO.setReportId(idGenerator.nextId());
        return moderationDao.insertIgnoreReport(reportPO) == 1 ? reportPO.getReportId() : null;
    }

    @Override
    public boolean processById(String reportId, String handlerId) {
        return moderationDao.processById(reportId, handlerId) == 1;
    }

    @Override
    public boolean ignoreById(String reportId, String handlerId) {
        return moderationDao.ignoreById(reportId, handlerId) == 1;
    }

    @Override
    public ContentReportEntity findReportById(String reportId) {
        return toReportEntity(moderationDao.selectReportById(reportId));
    }

    @Override
    public boolean existsProcessedCreditLog(String authorId, String targetType, String targetId) {
        return moderationDao.existsProcessedCreditLog(authorId, targetType, targetId) > 0;
    }

    @Override
    public List<ReviewQueueItemEntity> pageReviewQueue(String targetType, int offset, int limit) {
        List<ReviewQueueItemPO> rows = moderationDao.selectReviewQueue(targetType, offset, limit);
        if (rows.isEmpty()) {
            return List.of();
        }
        // 工单只存定位列：素材与作者昵称按 target_id 集回查业务表 + bs_user 拼装（SQL 层零 JOIN）。
        // 业务行缺失或已不在待审态 = 原 INNER JOIN + state 谓词语义，跳过（工单与业务行状态同事务收口，
        // 正常流不会漂移；真漂移交补偿，页可能缩水是可接受的降级）
        Map<String, PostPO> posts = postsById(targetIdsOf(rows, ReviewTargetTypeVO.POST));
        Map<String, PostCommentPO> comments = commentsById(targetIdsOf(rows, ReviewTargetTypeVO.COMMENT));
        Map<String, ProductPO> products = productsById(targetIdsOf(rows, ReviewTargetTypeVO.PRODUCT));
        Map<String, ErrandOrderPO> errands = errandsById(targetIdsOf(rows, ReviewTargetTypeVO.ERRAND));
        Map<String, UserPO> users = usersByIds(rows.stream().map(ReviewQueueItemPO::getAuthorId).toList());
        List<ReviewQueueItemPO> visible = new ArrayList<>(rows.size());
        for (ReviewQueueItemPO row : rows) {
            if (fillQueueMaterial(row, posts, comments, products, errands, users)) {
                visible.add(row);
            }
        }
        return visible.stream().map(this::toQueueItemEntity).toList();
    }

    @Override
    public long countReviewQueue(String targetType) {
        return moderationDao.countReviewQueue(targetType);
    }

    @Override
    public ReviewQueueItemEntity findReviewDetail(String targetType, String targetId) {
        ReviewQueueItemPO row = moderationDao.selectReviewDetail(targetType, targetId);
        if (row == null) {
            return null;
        }
        // 作者昵称（原 LEFT JOIN bs_user 列）：单行即单元素回查
        UserPO author = usersByIds(List.of(row.getAuthorId())).get(row.getAuthorId());
        row.setAuthorNickname(author == null ? null : author.getNickname());
        return toQueueItemEntity(row);
    }

    @Override
    public ReviewTaskEntity findTaskById(String taskId) {
        return toTaskEntity(moderationDao.selectTaskById(taskId));
    }

    @Override
    public ReviewTaskEntity findHeldTaskByTarget(String targetType, String targetId, String claimBy) {
        return toTaskEntity(moderationDao.selectHeldTaskByTarget(targetType, targetId, claimBy));
    }

    @Override
    public boolean closeTaskByHolder(String taskId, String claimBy) {
        return moderationDao.closeByHolder(taskId, claimBy) == 1;
    }

    @Override
    public ReviewTargetDetailEntity findTargetDetail(String targetType, String targetId) {
        ReviewTaskTargetPO targetPO = moderationDao.selectTaskTargetDetail(targetType, targetId);
        if (targetPO == null) {
            return null;
        }
        // 作者昵称头像（原 LEFT JOIN bs_user 列）
        UserPO author = usersByIds(List.of(targetPO.getAuthorId())).get(targetPO.getAuthorId());
        if (author != null) {
            targetPO.setAuthorNickname(author.getNickname());
            targetPO.setAuthorAvatar(author.getAvatar());
        }
        // 评论类型专属上下文（原 LEFT JOIN post / post_comment 两列）：来源帖标题 + 父评论文本二次回查
        if (ReviewTargetTypeVO.COMMENT == ReviewTargetTypeVO.from(targetType)) {
            if (targetPO.getContextPostId() != null) {
                PostPO post = postDao.selectById(targetPO.getContextPostId());
                targetPO.setContextPostTitle(post == null ? null : post.getTitle());
            }
            if (targetPO.getContextParentId() != null) {
                PostCommentPO parent = commentDao.selectById(targetPO.getContextParentId());
                targetPO.setContextParentContent(parent == null ? null : parent.getContent());
            }
        }
        return toTargetDetailEntity(targetPO);
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
        // 每 target 取最新一轮 REJECT 行（物理 id 最大）：单表取全部 REJECT 行后 Java 裁决
        // （原自连派生表 JOIN 的多查形态）。target_id 是 VARCHAR（POST/COMMENT=UUID，商品/跑腿为业务键）：按字符串原样收敛
        Map<String, ContentReviewPO> latestByTarget = new HashMap<>();
        for (ContentReviewPO row : moderationDao.selectRejectRowsByTargetIds(targetType, targetIds)) {
            latestByTarget.merge(row.getTargetId(), row,
                    (older, newer) -> newer.getId() != null && (older.getId() == null || newer.getId() > older.getId()) ? newer : older);
        }
        Map<String, String> reasons = new HashMap<>();
        latestByTarget.forEach((targetId, row) -> reasons.put(targetId, row.getReason()));
        return reasons;
    }

    @Override
    public List<ContentReportEntity> pageReports(String status, int offset, int limit) {
        List<ContentReportPO> rows = moderationDao.selectReports(status, offset, limit);
        if (rows.isEmpty()) {
            return List.of();
        }
        // 举报人/被举报人昵称（原两处 LEFT JOIN bs_user 列）批量回查拼装
        List<String> userIds = new ArrayList<>(rows.size() * 2);
        for (ContentReportPO row : rows) {
            userIds.add(row.getReporterId());
            userIds.add(row.getAuthorId());
        }
        Map<String, UserPO> users = usersByIds(userIds);
        for (ContentReportPO row : rows) {
            UserPO reporter = users.get(row.getReporterId());
            UserPO author = users.get(row.getAuthorId());
            row.setReporterNickname(reporter == null ? null : reporter.getNickname());
            row.setAuthorNickname(author == null ? null : author.getNickname());
        }
        return rows.stream().map(this::toReportEntity).toList();
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
        reviewPO.setReviewId(idGenerator.nextId());
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
        reportPO.setReportId(reportEntity.getReportId());
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

    // ---------- 素材拼装辅助（队列/详情/举报列表的 SQL 层零 JOIN 收口点） ----------

    /** 业务行在待审态才留在队列（原 SQL JOIN WHERE state='PENDING_REVIEW' 谓词） */
    private static final String PENDING_REVIEW = "PENDING_REVIEW";

    /** 按类型滤出本页 target_id 集 */
    private static List<String> targetIdsOf(List<ReviewQueueItemPO> rows, ReviewTargetTypeVO type) {
        String typeName = type.name();
        return rows.stream().filter(row -> typeName.equals(row.getTargetType()))
                .map(ReviewQueueItemPO::getTargetId).toList();
    }

    private Map<String, PostPO> postsById(List<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return postDao.selectByIds(ids).stream()
                .collect(HashMap::new, (m, p) -> m.put(p.getPostId(), p), HashMap::putAll);
    }

    private Map<String, PostCommentPO> commentsById(List<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return commentDao.selectByIds(ids).stream()
                .collect(HashMap::new, (m, c) -> m.put(c.getCommentId(), c), HashMap::putAll);
    }

    private Map<String, ProductPO> productsById(List<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return productDao.selectByIds(ids).stream()
                .collect(HashMap::new, (m, p) -> m.put(p.getProductId(), p), HashMap::putAll);
    }

    private Map<String, ErrandOrderPO> errandsById(List<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return errandDao.selectByIds(ids).stream()
                .collect(HashMap::new, (m, e) -> m.put(e.getErrandOrderId(), e), HashMap::putAll);
    }

    /** userId 集 → bs_user 批量回查索引（作者昵称头像/举报人昵称共用） */
    private Map<String, UserPO> usersByIds(List<String> userIds) {
        List<String> ids = userIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        return userDao.selectByIds(ids).stream()
                .collect(HashMap::new, (m, u) -> m.put(u.getUserId(), u), HashMap::putAll);
    }

    /**
     * 单条队列工单的素材拼装（原 queuePost/Comment/Product/Errand 分支的 SELECT 投影）：
     * 内容/配图/状态/创建时间取业务行，作者昵称取 rt.author_id 回查 bs_user。
     *
     * @return false = 业务行缺失或已不在待审态，调用方跳过该工单（原 INNER JOIN + state 谓词的 Java 收口）
     */
    private boolean fillQueueMaterial(ReviewQueueItemPO row, Map<String, PostPO> posts,
                                      Map<String, PostCommentPO> comments, Map<String, ProductPO> products,
                                      Map<String, ErrandOrderPO> errands, Map<String, UserPO> users) {
        String targetType = row.getTargetType();
        if (ReviewTargetTypeVO.POST.name().equals(targetType)) {
            PostPO post = posts.get(row.getTargetId());
            if (post == null || !PENDING_REVIEW.equals(post.getState())) {
                return false;
            }
            row.setContent(concatTitleWithContent(post.getTitle(), post.getContent()));
            row.setImages(post.getImages());
            row.setStatus(post.getState());
            row.setCreateTime(post.getCreateTime());
        } else if (ReviewTargetTypeVO.COMMENT.name().equals(targetType)) {
            PostCommentPO comment = comments.get(row.getTargetId());
            if (comment == null || !PENDING_REVIEW.equals(comment.getState())) {
                return false;
            }
            row.setContent(comment.getContent());
            row.setStatus(comment.getState());
            row.setCreateTime(comment.getCreateTime());
        } else if (ReviewTargetTypeVO.PRODUCT.name().equals(targetType)) {
            ProductPO product = products.get(row.getTargetId());
            if (product == null || !PENDING_REVIEW.equals(product.getState())) {
                return false;
            }
            row.setContent(concatTitleWithContent(product.getTitle(), product.getDescription()));
            row.setReward(product.getPrice());
            row.setImages(product.getImages());
            row.setStatus(product.getState());
            row.setCreateTime(product.getCreateTime());
        } else {
            ErrandOrderPO errand = errands.get(row.getTargetId());
            if (errand == null || !PENDING_REVIEW.equals(errand.getState())) {
                return false;
            }
            row.setContent(errand.getDescription());
            row.setReward(errand.getReward());
            row.setImages(errand.getImages());
            row.setStatus(errand.getState());
            row.setCreateTime(errand.getCreateTime());
        }
        UserPO author = users.get(row.getAuthorId());
        row.setAuthorNickname(author == null ? null : author.getNickname());
        return true;
    }

    /** post/product 的内容列 = 标题 + 正文（原 CONCAT(COALESCE(title, ''), x) 口径：标题 NULL 空串化，正文 NULL 整体 NULL） */
    private static String concatTitleWithContent(String title, String content) {
        if (content == null) {
            return null;
        }
        return (title == null ? "" : title) + content;
    }

    private ContentReportEntity toReportEntity(ContentReportPO reportPO) {
        if (reportPO == null) {
            return null;
        }
        ContentReportEntity reportEntity = new ContentReportEntity();
        reportEntity.setReportId(reportPO.getReportId());
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
        taskEntity.setTaskId(taskPO.getTaskId());
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
        reviewEntity.setReviewId(reviewPO.getReviewId());
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
