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
import com.betterschool.infrastructure.dao.comment.po.PostComment;
import com.betterschool.infrastructure.dao.errand.IErrandDao;
import com.betterschool.infrastructure.dao.errand.po.ErrandOrder;
import com.betterschool.infrastructure.dao.credit.IUserCreditDao;
import com.betterschool.infrastructure.dao.moderation.IContentReportDao;
import com.betterschool.infrastructure.dao.moderation.IContentReviewDao;
import com.betterschool.infrastructure.dao.moderation.IReviewTaskDao;
import com.betterschool.infrastructure.dao.moderation.ISensitiveWordDao;
import com.betterschool.infrastructure.dao.moderation.po.ContentReport;
import com.betterschool.infrastructure.dao.moderation.po.ContentReview;
import com.betterschool.infrastructure.dao.moderation.po.ReviewQueueItem;
import com.betterschool.infrastructure.dao.moderation.po.ReviewTask;
import com.betterschool.infrastructure.dao.moderation.po.ReviewTaskTarget;
import com.betterschool.infrastructure.dao.moderation.po.SensitiveWord;
import com.betterschool.infrastructure.dao.post.IPostDao;
import com.betterschool.infrastructure.dao.post.po.Post;
import com.betterschool.infrastructure.dao.product.IProductDao;
import com.betterschool.infrastructure.dao.product.po.Product;
import com.betterschool.infrastructure.dao.user.IUserDao;
import com.betterschool.infrastructure.dao.user.po.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Slf4j
@Repository
@RequiredArgsConstructor
public class ModerationRepository implements IModerationRepository {

    private final IReviewTaskDao reviewTaskDao;
    private final IContentReportDao contentReportDao;
    private final IContentReviewDao contentReviewDao;
    private final ISensitiveWordDao sensitiveWordDao;
    /** 一内容一罚判定（user_credit_detail 主表语句，主表归属 credit 域 DAO） */
    private final IUserCreditDao userCreditDao;
    private final ReviewTaskPoolStore reviewTaskPoolStore;
    private final ObjectMapper objectMapper;
    // 素材回查 DAO：队列/详情的素材列由 review_task 单表定位后按 target_id 集回查各业务表（SQL 层零 JOIN）
    private final IPostDao postDao;
    private final IPostCommentDao commentDao;
    private final IProductDao productDao;
    private final IErrandDao errandDao;
    /** 作者/举报人昵称头像批量回查 */
    private final IUserDao userDao;

    @Override
    public void saveReview(ContentReviewEntity reviewEntity) {
        contentReviewDao.insertIgnoreReview(toReviewPO(reviewEntity));
    }

    @Override
    public void closeOpenTasks(String targetType, String targetId) {
        reviewTaskDao.closeOpenTasks(targetType, targetId);
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
        for (Map<String, Object> row : reviewTaskDao.countBacklogByType()) {
            counts.put((String) row.get("targetType"), ((Number) row.get("cnt")).longValue());
        }
        return counts;
    }

    @Override
    public ReviewPendingWaitStats pendingWaitStats() {
        Map<String, Object> row = reviewTaskDao.selectPendingWaitStats();
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
        ContentReport report = toReportPO(reportEntity);
        // 业务 ID 随 insert 一次写入（uuid-biz-id 票 06）；撞 uk_reporter_target 被 IGNORE 时本行未落库，发号作废无害
        report.setReportId(UUID.randomUUID().toString());
        return contentReportDao.insertIgnoreReport(report) == 1 ? report.getReportId() : null;
    }

    @Override
    public boolean processById(String reportId, String handlerId) {
        return contentReportDao.processById(reportId, handlerId) == 1;
    }

    @Override
    public boolean ignoreById(String reportId, String handlerId) {
        return contentReportDao.ignoreById(reportId, handlerId) == 1;
    }

    @Override
    public ContentReportEntity findReportById(String reportId) {
        return toReportEntity(contentReportDao.selectReportById(reportId));
    }

    @Override
    public boolean existsProcessedCreditLog(String authorId, String targetType, String targetId) {
        return userCreditDao.existsProcessedCreditLog(authorId, targetType, targetId) > 0;
    }

    @Override
    public List<ReviewQueueItemEntity> pageReviewQueue(String targetType, int offset, int limit) {
        List<ReviewQueueItem> queueItemList = reviewTaskDao.selectReviewQueue(targetType, offset, limit);
        if (queueItemList.isEmpty()) {
            return List.of();
        }
        // 工单只存定位列：素材与作者昵称按 target_id 集回查业务表 + bs_user 拼装（SQL 层零 JOIN）。
        // 业务行缺失或已不在待审态 = 原 INNER JOIN + state 谓词语义，跳过（工单与业务行状态同事务收口，
        // 正常流不会漂移；真漂移交补偿，页可能缩水是可接受的降级）
        Map<String, Post> posts = postsById(targetIdsOf(queueItemList, ReviewTargetTypeVO.POST));
        Map<String, PostComment> comments = commentsById(targetIdsOf(queueItemList, ReviewTargetTypeVO.COMMENT));
        Map<String, Product> products = productsById(targetIdsOf(queueItemList, ReviewTargetTypeVO.PRODUCT));
        Map<String, ErrandOrder> errands = errandsById(targetIdsOf(queueItemList, ReviewTargetTypeVO.ERRAND));
        Map<String, User> users = usersByIds(queueItemList.stream().map(ReviewQueueItem::getAuthorId).toList());
        List<ReviewQueueItem> visibleQueueItemList = new ArrayList<>(queueItemList.size());
        for (ReviewQueueItem row : queueItemList) {
            if (fillQueueMaterial(row, posts, comments, products, errands, users)) {
                visibleQueueItemList.add(row);
            }
        }
        return visibleQueueItemList.stream().map(this::toQueueItemEntity).toList();
    }

    @Override
    public long countReviewQueue(String targetType) {
        return reviewTaskDao.countReviewQueue(targetType);
    }

    @Override
    public ReviewQueueItemEntity findReviewDetail(String targetType, String targetId) {
        ReviewQueueItem row = reviewTaskDao.selectReviewDetail(targetType, targetId);
        if (row == null) {
            return null;
        }
        // 作者昵称（原 LEFT JOIN bs_user 列）：单行即单元素回查
        User author = usersByIds(List.of(row.getAuthorId())).get(row.getAuthorId());
        row.setAuthorNickname(author == null ? null : author.getNickname());
        return toQueueItemEntity(row);
    }

    @Override
    public ReviewTaskEntity findTaskById(String taskId) {
        return toTaskEntity(reviewTaskDao.selectTaskById(taskId));
    }

    @Override
    public ReviewTaskEntity findHeldTaskByTarget(String targetType, String targetId, String claimBy) {
        return toTaskEntity(reviewTaskDao.selectHeldTaskByTarget(targetType, targetId, claimBy));
    }

    @Override
    public boolean closeTaskByHolder(String taskId, String claimBy) {
        return reviewTaskDao.closeByHolder(taskId, claimBy) == 1;
    }

    @Override
    public ReviewTargetDetailEntity findTargetDetail(String targetType, String targetId) {
        ReviewTaskTarget target = reviewTaskDao.selectTaskTargetDetail(targetType, targetId);
        if (target == null) {
            return null;
        }
        // 作者昵称头像（原 LEFT JOIN bs_user 列）
        User author = usersByIds(List.of(target.getAuthorId())).get(target.getAuthorId());
        if (author != null) {
            target.setAuthorNickname(author.getNickname());
            target.setAuthorAvatar(author.getAvatar());
        }
        // 评论类型专属上下文（原 LEFT JOIN post / post_comment 两列）：来源帖标题 + 父评论文本二次回查
        if (ReviewTargetTypeVO.COMMENT == ReviewTargetTypeVO.from(targetType)) {
            if (target.getContextPostId() != null) {
                Post post = postDao.selectById(target.getContextPostId());
                target.setContextPostTitle(post == null ? null : post.getTitle());
            }
            if (target.getContextParentId() != null) {
                PostComment parent = commentDao.selectById(target.getContextParentId());
                target.setContextParentContent(parent == null ? null : parent.getContent());
            }
        }
        return toTargetDetailEntity(target);
    }

    @Override
    public List<ContentReviewEntity> listReviewsByTarget(String targetType, String targetId) {
        return contentReviewDao.selectReviewsByTarget(targetType, targetId).stream()
                .map(this::toReviewEntity).toList();
    }

    @Override
    public List<ContentReviewEntity> listReviewsByAuthor(String authorId, int limit) {
        return contentReviewDao.selectReviewsByAuthor(authorId, limit).stream()
                .map(this::toReviewEntity).toList();
    }

    @Override
    public String findRejectReason(String targetType, String targetId) {
        return contentReviewDao.selectRejectReason(targetType, targetId);
    }

    @Override
    public Map<String, String> findRejectReasons(String targetType, List<String> targetIdList) {
        if (targetIdList.isEmpty()) {
            return Map.of();
        }
        // 每 target 取最新一轮 REJECT 行（物理 id 最大）：单表取全部 REJECT 行后 Java 裁决
        // （原自连派生表 JOIN 的多查形态）。target_id 是 VARCHAR（POST/COMMENT=UUID，商品/跑腿为业务键）：按字符串原样收敛
        Map<String, ContentReview> latestByTarget = new HashMap<>();
        for (ContentReview row : contentReviewDao.selectRejectRowsByTargetIds(targetType, targetIdList)) {
            latestByTarget.merge(row.getTargetId(), row,
                    (older, newer) -> newer.getId() != null && (older.getId() == null || newer.getId() > older.getId()) ? newer : older);
        }
        Map<String, String> reasons = new HashMap<>();
        latestByTarget.forEach((targetId, row) -> reasons.put(targetId, row.getReason()));
        return reasons;
    }

    @Override
    public List<ContentReportEntity> pageReports(String status, int offset, int limit) {
        List<ContentReport> reportList = contentReportDao.selectReports(status, offset, limit);
        if (reportList.isEmpty()) {
            return List.of();
        }
        // 举报人/被举报人昵称（原两处 LEFT JOIN bs_user 列）批量回查拼装
        List<String> userIdList = new ArrayList<>(reportList.size() * 2);
        for (ContentReport row : reportList) {
            userIdList.add(row.getReporterId());
            userIdList.add(row.getAuthorId());
        }
        Map<String, User> users = usersByIds(userIdList);
        for (ContentReport row : reportList) {
            User reporter = users.get(row.getReporterId());
            User author = users.get(row.getAuthorId());
            row.setReporterNickname(reporter == null ? null : reporter.getNickname());
            row.setAuthorNickname(author == null ? null : author.getNickname());
        }
        return reportList.stream().map(this::toReportEntity).toList();
    }

    @Override
    public long countReports(String status) {
        return contentReportDao.countReports(status);
    }

    @Override
    public boolean updateUserRole(String userId, String role) {
        return userDao.updateUserRole(userId, role) == 1;
    }

    @Override
    public boolean existsUser(String userId) {
        return userDao.existsUser(userId) > 0;
    }

    @Override
    public void saveWord(SensitiveWordEntity wordEntity) {
        sensitiveWordDao.insertIgnoreWord(toWordPO(wordEntity));
    }

    @Override
    public void deleteWord(Long wordId) {
        sensitiveWordDao.deleteWord(wordId);
    }

    @Override
    public List<String> listEnabledWords() {
        return sensitiveWordDao.selectEnabledWords();
    }

    @Override
    public List<SensitiveWordEntity> pageWords(int offset, int limit) {
        return sensitiveWordDao.selectWords(offset, limit).stream()
                .map(this::toWordEntity).toList();
    }

    @Override
    public long countWords() {
        return sensitiveWordDao.countWords();
    }

    private ContentReview toReviewPO(ContentReviewEntity reviewEntity) {
        ContentReview review = new ContentReview();
        review.setReviewId(UUID.randomUUID().toString());
        review.setTargetType(reviewEntity.getTargetType().name());
        review.setTargetId(reviewEntity.getTargetId());
        review.setAuthorId(reviewEntity.getAuthorId());
        review.setReviewRound(reviewEntity.getReviewRound());
        review.setAction(reviewEntity.getAction().name());
        review.setSource(reviewEntity.getSource().name());
        review.setOperatorId(reviewEntity.getOperatorId());
        review.setReason(reviewEntity.getReason());
        review.setReasonCode(reviewEntity.getReasonCode());
        return review;
    }

    private ContentReport toReportPO(ContentReportEntity reportEntity) {
        ContentReport report = new ContentReport();
        report.setReportId(reportEntity.getReportId());
        report.setTargetType(reportEntity.getTargetType().name());
        report.setTargetId(reportEntity.getTargetId());
        report.setAuthorId(reportEntity.getAuthorId());
        report.setReporterId(reportEntity.getReporterId());
        report.setReason(reportEntity.getReason());
        report.setContentSnapshot(reportEntity.getContentSnapshot());
        report.setStatus(reportEntity.getStatus().name());
        return report;
    }

    // ---------- 素材拼装辅助（队列/详情/举报列表的 SQL 层零 JOIN 收口点） ----------

    /** 业务行在待审态才留在队列（原 SQL JOIN WHERE state='PENDING_REVIEW' 谓词） */
    private static final String PENDING_REVIEW = "PENDING_REVIEW";

    /** 按类型滤出本页 target_id 集 */
    private static List<String> targetIdsOf(List<ReviewQueueItem> queueItemList, ReviewTargetTypeVO type) {
        String typeName = type.name();
        return queueItemList.stream().filter(row -> typeName.equals(row.getTargetType()))
                .map(ReviewQueueItem::getTargetId).toList();
    }

    private Map<String, Post> postsById(List<String> postIdList) {
        if (postIdList.isEmpty()) {
            return Map.of();
        }
        return postDao.selectByIds(postIdList).stream()
                .collect(HashMap::new, (m, p) -> m.put(p.getPostId(), p), HashMap::putAll);
    }

    private Map<String, PostComment> commentsById(List<String> commentIdList) {
        if (commentIdList.isEmpty()) {
            return Map.of();
        }
        return commentDao.selectByIds(commentIdList).stream()
                .collect(HashMap::new, (m, c) -> m.put(c.getCommentId(), c), HashMap::putAll);
    }

    private Map<String, Product> productsById(List<String> productIdList) {
        if (productIdList.isEmpty()) {
            return Map.of();
        }
        return productDao.selectByIds(productIdList).stream()
                .collect(HashMap::new, (m, p) -> m.put(p.getProductId(), p), HashMap::putAll);
    }

    private Map<String, ErrandOrder> errandsById(List<String> errandIdList) {
        if (errandIdList.isEmpty()) {
            return Map.of();
        }
        return errandDao.selectByIds(errandIdList).stream()
                .collect(HashMap::new, (m, e) -> m.put(e.getErrandOrderId(), e), HashMap::putAll);
    }

    /** userId 集 → bs_user 批量回查索引（作者昵称头像/举报人昵称共用） */
    private Map<String, User> usersByIds(List<String> userIdList) {
        List<String> distinctUserIdList = userIdList.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctUserIdList.isEmpty()) {
            return Map.of();
        }
        return userDao.selectByIds(distinctUserIdList).stream()
                .collect(HashMap::new, (m, u) -> m.put(u.getUserId(), u), HashMap::putAll);
    }

    /**
     * 单条队列工单的素材拼装（原 queuePost/Comment/Product/Errand 分支的 SELECT 投影）：
     * 内容/配图/状态/创建时间取业务行，作者昵称取 rt.author_id 回查 bs_user。
     *
     * @return false = 业务行缺失或已不在待审态，调用方跳过该工单（原 INNER JOIN + state 谓词的 Java 收口）
     */
    private boolean fillQueueMaterial(ReviewQueueItem row, Map<String, Post> posts,
                                      Map<String, PostComment> comments, Map<String, Product> products,
                                      Map<String, ErrandOrder> errands, Map<String, User> users) {
        String targetType = row.getTargetType();
        if (ReviewTargetTypeVO.POST.name().equals(targetType)) {
            Post post = posts.get(row.getTargetId());
            if (post == null || !PENDING_REVIEW.equals(post.getState())) {
                return false;
            }
            row.setContent(concatTitleWithContent(post.getTitle(), post.getContent()));
            row.setImages(post.getImages());
            row.setStatus(post.getState());
            row.setCreateTime(post.getCreateTime());
        } else if (ReviewTargetTypeVO.COMMENT.name().equals(targetType)) {
            PostComment comment = comments.get(row.getTargetId());
            if (comment == null || !PENDING_REVIEW.equals(comment.getState())) {
                return false;
            }
            row.setContent(comment.getContent());
            row.setStatus(comment.getState());
            row.setCreateTime(comment.getCreateTime());
        } else if (ReviewTargetTypeVO.PRODUCT.name().equals(targetType)) {
            Product product = products.get(row.getTargetId());
            if (product == null || !PENDING_REVIEW.equals(product.getState())) {
                return false;
            }
            row.setContent(concatTitleWithContent(product.getTitle(), product.getDescription()));
            row.setReward(product.getPrice());
            row.setImages(product.getImages());
            row.setStatus(product.getState());
            row.setCreateTime(product.getCreateTime());
        } else {
            ErrandOrder errand = errands.get(row.getTargetId());
            if (errand == null || !PENDING_REVIEW.equals(errand.getState())) {
                return false;
            }
            row.setContent(errand.getDescription());
            row.setReward(errand.getReward());
            row.setImages(errand.getImages());
            row.setStatus(errand.getState());
            row.setCreateTime(errand.getCreateTime());
        }
        User author = users.get(row.getAuthorId());
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

    private ContentReportEntity toReportEntity(ContentReport report) {
        if (report == null) {
            return null;
        }
        ContentReportEntity reportEntity = new ContentReportEntity();
        reportEntity.setReportId(report.getReportId());
        reportEntity.setTargetType(ReviewTargetTypeVO.from(report.getTargetType()));
        reportEntity.setTargetId(report.getTargetId());
        reportEntity.setAuthorId(report.getAuthorId());
        reportEntity.setReporterId(report.getReporterId());
        reportEntity.setReason(report.getReason());
        reportEntity.setContentSnapshot(report.getContentSnapshot());
        reportEntity.setStatus(ReportStatusVO.from(report.getStatus()));
        reportEntity.setHandlerId(report.getHandlerId());
        reportEntity.setHandledAt(report.getHandledAt());
        reportEntity.setCreatedAt(report.getCreateTime());
        reportEntity.setReporterNickname(report.getReporterNickname());
        reportEntity.setAuthorNickname(report.getAuthorNickname());
        return reportEntity;
    }

    private ReviewQueueItemEntity toQueueItemEntity(ReviewQueueItem queueItem) {
        if (queueItem == null) {
            return null;
        }
        ReviewQueueItemEntity queueItemEntity = new ReviewQueueItemEntity();
        queueItemEntity.setTargetType(ReviewTargetTypeVO.from(queueItem.getTargetType()));
        queueItemEntity.setTargetId(queueItem.getTargetId());
        queueItemEntity.setReviewRound(queueItem.getReviewRound() == null ? 1 : queueItem.getReviewRound());
        queueItemEntity.setAuthorId(queueItem.getAuthorId());
        queueItemEntity.setAuthorNickname(queueItem.getAuthorNickname());
        queueItemEntity.setContent(queueItem.getContent());
        queueItemEntity.setReward(queueItem.getReward());
        queueItemEntity.setImages(toImagesList(queueItem.getImages()));
        queueItemEntity.setStatus(queueItem.getStatus());
        queueItemEntity.setCreatedAt(queueItem.getCreateTime());
        queueItemEntity.setEnteredAt(queueItem.getEnteredAt());
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

    private ReviewTaskEntity toTaskEntity(ReviewTask task) {
        if (task == null) {
            return null;
        }
        ReviewTaskEntity taskEntity = new ReviewTaskEntity();
        taskEntity.setTaskId(task.getTaskId());
        taskEntity.setTargetType(ReviewTargetTypeVO.from(task.getTargetType()));
        taskEntity.setTargetId(task.getTargetId());
        taskEntity.setReviewRound(task.getReviewRound() == null ? 1 : task.getReviewRound());
        taskEntity.setAuthorId(task.getAuthorId());
        taskEntity.setSource(task.getSource());
        taskEntity.setClaimState(task.getClaimState());
        taskEntity.setClaimBy(task.getClaimBy());
        taskEntity.setClaimSeq(task.getClaimSeq());
        taskEntity.setLeaseExpireAt(task.getLeaseExpireAt());
        taskEntity.setRecycleCount(task.getRecycleCount());
        taskEntity.setEscalated(task.getEscalated() != null && task.getEscalated());
        taskEntity.setEnteredAt(task.getEnteredAt());
        taskEntity.setDecidedAt(task.getDecidedAt());
        taskEntity.setCreatedAt(task.getCreateTime());
        taskEntity.setUpdateTime(task.getUpdateTime());
        return taskEntity;
    }

    private ReviewTargetDetailEntity toTargetDetailEntity(ReviewTaskTarget target) {
        if (target == null) {
            return null;
        }
        ReviewTargetDetailEntity detailEntity = new ReviewTargetDetailEntity();
        detailEntity.setTargetType(ReviewTargetTypeVO.from(target.getTargetType()));
        detailEntity.setTargetId(target.getTargetId());
        detailEntity.setAuthorId(target.getAuthorId());
        detailEntity.setAuthorNickname(target.getAuthorNickname());
        detailEntity.setAuthorAvatar(target.getAuthorAvatar());
        detailEntity.setContent(target.getContent());
        detailEntity.setImages(toImagesList(target.getImages()));
        detailEntity.setReward(target.getReward());
        detailEntity.setStatus(target.getStatus());
        detailEntity.setContextPostId(target.getContextPostId());
        detailEntity.setContextPostTitle(target.getContextPostTitle());
        detailEntity.setContextParentId(target.getContextParentId());
        detailEntity.setContextParentContent(target.getContextParentContent());
        return detailEntity;
    }

    private ContentReviewEntity toReviewEntity(ContentReview review) {
        if (review == null) {
            return null;
        }
        ContentReviewEntity reviewEntity = new ContentReviewEntity();
        reviewEntity.setReviewId(review.getReviewId());
        reviewEntity.setTargetType(ReviewTargetTypeVO.from(review.getTargetType()));
        reviewEntity.setTargetId(review.getTargetId());
        reviewEntity.setAuthorId(review.getAuthorId());
        reviewEntity.setReviewRound(review.getReviewRound() == null ? 1 : review.getReviewRound());
        reviewEntity.setAction(ReviewActionVO.from(review.getAction()));
        reviewEntity.setSource(ReviewSourceVO.from(review.getSource()));
        reviewEntity.setOperatorId(review.getOperatorId());
        reviewEntity.setReason(review.getReason());
        reviewEntity.setReasonCode(review.getReasonCode());
        reviewEntity.setCreatedAt(review.getCreateTime());
        return reviewEntity;
    }

    private SensitiveWord toWordPO(SensitiveWordEntity wordEntity) {
        SensitiveWord word = new SensitiveWord();
        word.setId(wordEntity.getId());
        word.setWord(wordEntity.getWord());
        word.setEnabled(wordEntity.isEnabled() ? 1 : 0);
        return word;
    }

    private SensitiveWordEntity toWordEntity(SensitiveWord word) {
        SensitiveWordEntity wordEntity = new SensitiveWordEntity();
        wordEntity.setId(word.getId());
        wordEntity.setWord(word.getWord());
        wordEntity.setEnabled(word.getEnabled() != null && word.getEnabled() == 1);
        wordEntity.setCreatedAt(word.getCreateTime());
        return wordEntity;
    }
}
